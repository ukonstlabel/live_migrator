package migrator.bench;

import migrator.bench.NodeGraph.GraphHolder;
import migrator.bench.NodeGraph.NodeMigrator;
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

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Multi-axis scalability study of the S0 (Live Migrator) in-process pause (RQ1 / P1 #7).
 *
 * <p>Measures the cost of one live migration over a configurable object graph
 * ({@link NodeGraph}) while a single axis is varied at a time (pin the others via the JMH
 * {@code -p} flag). The axes map directly to the asymptotic cost model:
 * <ul>
 *   <li><b>m</b> (vertices V) and <b>fanout</b> (edges E) → the O(V+E) reference patch;</li>
 *   <li><b>payloadSize</b> (bytes/node) → the O(heap) filtered walk, V/E held constant.</li>
 * </ul>
 * The <b>GC axis</b> is driven externally by appending {@code -XX:+UseZGC} /
 * {@code -XX:+UseShenandoahGC} to the fork (see {@code tests/benchmarks/scripts/scalability.sh}); the
 * fork below leaves the collector at its JDK default (G1).
 *
 * <p>Uses the filtered (SPEC) heap walk — S0's intended low-pause path — and forces a collection
 * in per-invocation setup so the walk never re-scans uncollected garbage from previous ops (see
 * memory: heap-walk-sees-uncollected-garbage).
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
@Fork(value = 1, jvmArgsAppend = {
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading",
        "-Xms3g", "-Xmx3g"
})
public class ScalabilityBench {

    @Param({"50000"})
    public int m;

    @Param({"2"})
    public int fanout;

    @Param({"64"})
    public int payloadSize;

    // Axis N: number of live background objects (a class the migrator never touches) — grows total
    // heap N at fixed m, isolating the walk's dependence on total heap vs. migrated-instance count.
    @Param({"0"})
    public int bgObjects;

    @Param({"64"})
    public int bgSize;

    // Axis L: edge layout at fixed m/E — "ring" (default), "chain" (depth = m), "star" (depth = 1).
    @Param({"ring"})
    public String layout;

    private boolean nativeReady;

    @Setup(Level.Trial)
    public void perTrial() {
        try {
            BenchAgent.ensureNativeAgentLoaded();
            nativeReady = true;
        } catch (Throwable t) {
            nativeReady = false;
        }
    }

    @Setup(Level.Invocation)
    public void perInvocation() {
        // Drop the previous graph and force GC so the filtered walk sees exactly this invocation's
        // m nodes, not uncollected garbage (outside the timed region in SingleShotTime).
        GraphHolder.reset();
        System.gc();
        GraphHolder.build(m, fanout, payloadSize, layout, bgObjects, bgSize);
    }

    @Benchmark
    public Object liveMigrate() throws Exception {
        if (!nativeReady) {
            throw new IllegalStateException(
                    "Native JVMTI agent not loaded — S0 is unavailable on this machine");
        }
        SmokeTestRunner smoke = new SmokeTestRunner.Builder()
                .addSmokeTest(created -> SmokeTestResult.ok("scal-noop"))
                .build();
        MigrationEngine engine = new MigrationEngine(
                NodeMigrator.class,
                null,
                smoke,
                new CommitManager(NoopCracController.INSTANCE),
                new RollbackManager(NoopCracController.INSTANCE));
        engine.setFullHeapWalk(false);   // filtered (SPEC) walk — S0's intended fast path
        engine.setAllTimeoutsSeconds(0);
        engine.migrate(Set.of(GraphHolder.class), null, null);
        return GraphHolder.nodes;
    }
}
