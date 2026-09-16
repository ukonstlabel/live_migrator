package migrator.bench;

import migrator.ClassMigrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;
import migrator.engine.MigrationEngine;
import migrator.metrics.MigrationMetrics;
import migrator.phase.NoopPhaseListener;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Measures the transient peak-memory overhead of a live migration (the claimed ×2 from old+new
 * coexisting until commit). Standalone {@code main} — run with the native agent self-attach flag,
 * exactly like the JMH benches:
 *
 * <pre>
 * java -Xms3g -Xmx3g -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading \
 *      -cp tests/benchmarks/target/benchmarks.jar migrator.bench.PeakMemoryProbe
 * </pre>
 *
 * <p>The engine samples {@code memoryAfter} at {@code finish()} — after commit but BEFORE the
 * forwarding table is cleared, so the old objects are still strongly held alongside the new ones.
 * That sample is therefore the peak (old+new); {@code memoryBefore} is the pre-migration baseline
 * (old only). The ratio after/before is the measured memory multiplier.
 *
 * <p>Two migrators isolate the cause: {@code copy} allocates a fresh {@code byte[]} per object
 * (state is duplicated → expect ≈×2), {@code share} reuses the old payload array (expect ≈×1).
 * This shows the ×2 is the cost of a <em>copying</em> transform, not of live migration per se.
 */
public final class PeakMemoryProbe {

    /** Migrator that DUPLICATES the payload (fresh byte[]): old+new coexist → ≈×2 peak. */
    public static final class CopyingMigrator implements ClassMigrator<OldPayload, NewPayload> {
        @Override public NewPayload migrate(OldPayload o) {
            return new NewPayload(o.id, o.name, o.data.clone(), System.nanoTime());
        }
    }

    /** Migrator that SHARES the payload array: new object adds only a wrapper → ≈×1 peak. */
    public static final class SharingMigrator implements ClassMigrator<OldPayload, NewPayload> {
        @Override public NewPayload migrate(OldPayload o) {
            return new NewPayload(o.id, o.name, o.data, System.nanoTime());
        }
    }

    /** Static root the engine finds by scanning {@code Holder.class}'s fields. */
    public static final class Holder {
        public static volatile List<Payload> payloads;
        public static void build(int m, int payloadSize) {
            List<Payload> ps = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                byte[] data = new byte[payloadSize];
                data[0] = (byte) i;
                ps.add(new OldPayload(i, "u" + i, data));
            }
            payloads = ps;
        }
        public static void reset() { payloads = null; }
    }

    private static final double MB = 1024.0 * 1024.0;

    public static void main(String[] args) throws Exception {
        BenchAgent.ensureNativeAgentLoaded();

        int payloadSize = 2048;
        int[] ms = {10_000, 50_000, 100_000};

        System.out.printf("%-7s %8s %10s %10s %10s %9s %7s%n",
                "mode", "m", "payloadMB", "beforeMB", "peakMB", "deltaMB", "x");
        for (String mode : new String[]{"share", "copy"}) {
            for (int m : ms) {
                runOnce(mode, m, payloadSize);
            }
        }
    }

    private static void runOnce(String mode, int m, int payloadSize) throws Exception {
        Holder.reset();
        System.gc();
        Holder.build(m, payloadSize);
        System.gc();

        Class<? extends ClassMigrator<?, ?>> migrator =
                "copy".equals(mode) ? CopyingMigrator.class : SharingMigrator.class;

        SmokeTestRunner smoke = new SmokeTestRunner.Builder()
                .addSmokeTest(created -> SmokeTestResult.ok("peakmem-noop"))
                .build();
        MigrationEngine engine = new MigrationEngine(
                migrator,
                NoopPhaseListener.INSTANCE,
                smoke,
                new CommitManager(NoopCracController.INSTANCE),
                new RollbackManager(NoopCracController.INSTANCE));
        engine.setFullHeapWalk(false);
        engine.setAllTimeoutsSeconds(0);
        engine.migrate(Set.of(Holder.class), null, null);

        MigrationMetrics mm = MigrationEngine.getLastMetrics();
        double before = mm.memoryBefore().heapUsed() / MB;
        double peak = mm.memoryAfter().heapUsed() / MB;   // sampled while old+new both held
        double delta = mm.heapDelta() / MB;
        double payloadMB = (double) m * payloadSize / MB;
        System.out.printf("%-7s %8d %10.1f %10.1f %10.1f %9.1f %7.2f%n",
                mode, m, payloadMB, before, peak, delta, peak / before);
    }
}
