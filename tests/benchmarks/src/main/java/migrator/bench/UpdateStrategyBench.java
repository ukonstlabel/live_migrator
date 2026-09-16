package migrator.bench;

import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;
import migrator.engine.MigrationEngine;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.instrument.ClassDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Baseline comparison of the <b>in-process</b> cost of state-update strategies
 * {@code OldPayload → NewPayload} over the same graph of {@code M} objects.
 *
 * <p><b>What is and isn't measured.</b> JMH measures the cost of an operation inside a
 * single warmed-up JVM, so only the <i>in-process</i> work of each strategy is measured
 * here. Multi-process downtime (process restart, load-balancer switchover, re-class-load +
 * JIT warmup of a new JVM) is <b>outside JMH</b> and is measured by a separate end-to-end
 * harness under load. Thus:
 * <ul>
 *   <li><b>S0 liveMigrator</b> — real migration by the engine (live replacement + reference patching);</li>
 *   <li><b>S1/S2 serializeReload</b> — state transfer: serialize → deserialize → transform
 *       into the new version (the in-process part of serialize+restart and rolling restart;
 *       they are equivalent in-process, the difference is operational);</li>
 *   <li><b>S3 blueGreen</b> — same, but into a fresh "green" graph while "blue" stays live
 *       (models ×2 state of a parallel environment);</li>
 *   <li><b>S4 classRedefine</b> — {@code Instrumentation.redefineClasses} of the payload class;
 *       measures the cost of the redefinition machinery itself. The instances are
 *       <i>not</i> transformed — that is exactly the gap S0 closes.</li>
 * </ul>
 *
 * Mode {@link Mode#SingleShotTime}: each measurement is a single update event over fresh
 * state (rebuilt in {@link #perInvocation()}).
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(value = 1, jvmArgsAppend = {
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading",
        "-Xms2g", "-Xmx2g"
})
public class UpdateStrategyBench {

    @Param({"1000", "10000", "100000"})
    public int m;

    /** Size of the data array per object (bytes); fixed to isolate the M axis. */
    public static final int PAYLOAD_SIZE = 64;

    private boolean nativeReady;
    private byte[] oldPayloadClassBytes;

    // ─── Lifecycle ──────────────────────────────────────────────────

    @Setup(Level.Trial)
    public void perTrial() {
        // The native agent is only needed by S0; a load failure must not break the other
        // benchmarks — record a flag and throw only inside liveMigrator.
        try {
            BenchAgent.ensureNativeAgentLoaded();
            nativeReady = true;
        } catch (Throwable t) {
            nativeReady = false;
        }
        // Class bytecode for S4 (redefine "into itself" — we measure the cost of the operation).
        try {
            oldPayloadClassBytes = OldPayload.class.getResourceAsStream(
                    "/migrator/bench/OldPayload.class").readAllBytes();
        } catch (Exception e) {
            oldPayloadClassBytes = null;
        }
    }

    @Setup(Level.Invocation)
    public void perInvocation() {
        // Fresh "old" state before each measurement (S0 mutates the heap in place).
        //
        // CRITICAL for S0 correctness: the engine's filtered heap walk uses JVMTI
        // IterateThroughHeap by class, which sees ALL OldPayload instances in the heap,
        // including not-yet-collected garbage from previous invocations. Without an explicit
        // collection the migrated-object count grows from run to run (1000→2000→3000…), and
        // each measured op does more and more work — making the S0 results meaningless.
        // Dropping references + System.gc() here (outside the timed region in SingleShotTime)
        // guarantees exactly m objects per invocation, regardless of the JMH -gc flag.
        StateHolder.reset();
        System.gc();
        StateHolder.build(m, PAYLOAD_SIZE);
    }

    // ─── S0: Live Migrator ───────────────────────────────────────────

    @Benchmark
    public Object liveMigrator() throws Exception {
        if (!nativeReady) {
            throw new IllegalStateException(
                    "Native JVMTI agent not loaded — S0 is unavailable on this machine");
        }
        SmokeTestRunner smoke = new SmokeTestRunner.Builder()
                .addSmokeTest(created -> SmokeTestResult.ok("bench-noop"))
                .build();
        MigrationEngine engine = new MigrationEngine(
                PayloadMigrator.class,
                null,
                smoke,
                new CommitManager(NoopCracController.INSTANCE),
                new RollbackManager(NoopCracController.INSTANCE));
        engine.setFullHeapWalk(false);   // filtered (SPEC) heap walk
        engine.setAllTimeoutsSeconds(0); // no timeout
        engine.migrate(Set.of(StateHolder.class), null, null);
        return StateHolder.list;
    }

    // ─── S1/S2: serialize + restart / rolling restart (in-process part) ──

    @Benchmark
    public Object serializeReload() throws Exception {
        byte[] bytes = serializeState(StateHolder.list, StateHolder.map);
        Reloaded r = deserializeAndTransform(bytes);
        return r;
    }

    // ─── S3: blue-green (in-process part) ─────────────────────────────

    @Benchmark
    public Object blueGreen() throws Exception {
        // "blue" (current state) stays live: green is built in parallel.
        List<Payload> blueList = StateHolder.list;
        Map<Integer, Payload> blueMap = StateHolder.map;
        byte[] bytes = serializeState(blueList, blueMap);
        Reloaded green = deserializeAndTransform(bytes);
        // hold blue to model ×2 state (two environments at once)
        if (blueList.isEmpty()) return null;
        return green;
    }

    // ─── S4: class redefinition (DCEVM/HotSwap analogue) ──────────────

    @Benchmark
    public Object classRedefine() throws Exception {
        if (oldPayloadClassBytes == null) {
            throw new IllegalStateException("Could not read OldPayload bytecode");
        }
        BenchAgent.instrumentation().redefineClasses(
                new ClassDefinition(OldPayload.class, oldPayloadClassBytes));
        return OldPayload.class;
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static byte[] serializeState(List<Payload> list, Map<Integer, Payload> map)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1 << 20);
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(new ArrayList<>(list));
            oos.writeObject(new ConcurrentHashMap<>(map));
        }
        return baos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Reloaded deserializeAndTransform(byte[] bytes) throws Exception {
        List<Payload> oldList;
        Map<Integer, Payload> oldMap;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            oldList = (List<Payload>) ois.readObject();
            oldMap = (Map<Integer, Payload>) ois.readObject();
        }
        // "Load in the new version": transform into NewPayload with the same logic as the engine.
        List<Payload> newList = new ArrayList<>(oldList.size());
        for (Payload p : oldList) {
            newList.add(PayloadMigrator.transform((OldPayload) p));
        }
        Map<Integer, Payload> newMap = new ConcurrentHashMap<>(Math.max(16, oldMap.size() * 4 / 3));
        for (Map.Entry<Integer, Payload> e : oldMap.entrySet()) {
            newMap.put(e.getKey(), PayloadMigrator.transform((OldPayload) e.getValue()));
        }
        return new Reloaded(newList, newMap);
    }

    /** Result container so JMH treats the work as "consumed" (anti-DCE). */
    public record Reloaded(List<Payload> list, Map<Integer, Payload> map) {}
}
