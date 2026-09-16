package migrator.benchmark;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;
import migrator.engine.MigrationEngine;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stress test for FEW objects that are individually LARGE (500 KB .. 100 MB each).
 *
 * The payload's bulk is a primitive byte[]. The migrator SHARES that array with the new
 * object (no copy), so a correct migration must not duplicate the bytes. The patcher
 * should also be size-independent: a primitive array is skipped, not scanned, so walk and
 * patch time should track object COUNT, not total bytes.
 *
 * heapDelta near zero (vs. the data size) confirms the array was shared, not copied.
 * Run with the agent: -agentpath:agent/libagent.so
 */
public class LargeObjectBench {

    public interface Payload { int id(); byte[] data(); }

    public static class OldPayload implements Payload {
        final int id; final byte[] data;
        OldPayload(int id, byte[] data) { this.id = id; this.data = data; }
        public int id() { return id; }
        public byte[] data() { return data; }
    }

    public static class NewPayload implements Payload {
        final int id; final byte[] data; final long migratedAt;
        NewPayload(int id, byte[] data) { this.id = id; this.data = data; this.migratedAt = System.nanoTime(); }
        public int id() { return id; }
        public byte[] data() { return data; }
    }

    @Migrator
    public static class PayloadMigrator implements ClassMigrator<OldPayload, NewPayload> {
        @Override public NewPayload migrate(OldPayload o) {
            return new NewPayload(o.id, o.data); // share the array — no copy
        }
    }

    static List<OldPayload> listStore;
    static Map<Integer, OldPayload> mapStore;

    // {bytesPerObject, count}
    static final long[][] CONFIGS = {
        {512L * 1024,        2_000}, // 500 KB x 2000  = ~1.0 GB
        {1L * 1024 * 1024,   1_000}, // 1 MB   x 1000  = ~1.0 GB
        {1L * 1024 * 1024,   2_000}, // 1 MB   x 2000  = ~2.0 GB
        {100L * 1024 * 1024, 10},    // 100 MB x 10    = ~1.0 GB
        {100L * 1024 * 1024, 25},    // 100 MB x 25    = ~2.5 GB
    };

    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        System.out.println("================================================================");
        System.out.println("  Live Migrator -- Large Object Stress Test");
        System.out.println("================================================================");
        System.out.printf("Heap max %,d MB | CPUs %d%n%n", rt.maxMemory() / (1024 * 1024), rt.availableProcessors());

        for (long[] cfg : CONFIGS) {
            int bytes = (int) cfg[0];
            int count = (int) cfg[1];
            runOne(bytes, count);
            listStore = null; mapStore = null;
            System.gc(); sleep(800); System.gc(); sleep(400);
        }
    }

    static void runOne(int bytes, int count) {
        Runtime rt = Runtime.getRuntime();
        double dataMb = (double) bytes * count / (1024.0 * 1024);
        System.out.printf("---- %s x %,d  = %.0f MB total ----%n", human(bytes), count, dataMb);

        long a0 = System.nanoTime();
        listStore = new ArrayList<>(count);
        mapStore = new ConcurrentHashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] d = new byte[bytes];
            d[0] = 7; d[bytes - 1] = 42;            // sentinels to verify array survives intact
            OldPayload o = new OldPayload(i, d);
            listStore.add(o);
            mapStore.put(i, o);
        }
        long allocMs = (System.nanoTime() - a0) / 1_000_000;
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        try {
            SmokeTestRunner smoke = new SmokeTestRunner.Builder()
                    .addSmokeTest(c -> SmokeTestResult.ok("noop")).build();
            MigrationEngine engine = new MigrationEngine(
                    PayloadMigrator.class, null, smoke,
                    new CommitManager(NoopCracController.INSTANCE),
                    new RollbackManager(NoopCracController.INSTANCE));
            engine.setFullHeapWalk(false);
            engine.setAllTimeoutsSeconds(0);

            long t0 = System.nanoTime();
            engine.migrate(Set.of(LargeObjectBench.class), null, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long heapAfter = rt.totalMemory() - rt.freeMemory();

            MigrationMetrics m = MigrationEngine.getLastMetrics();

            // verify: all NewPayload, arrays shared & intact
            int newList = 0, intact = 0; boolean shared = true;
            for (Object o : listStore) {
                if (o instanceof NewPayload np) {
                    newList++;
                    if (np.data.length == bytes && np.data[0] == 7 && np.data[bytes - 1] == 42) intact++;
                }
            }
            // confirm the same array instance is referenced from both List and Map (no copy)
            for (int i = 0; i < Math.min(count, 100); i++) {
                Object li = listStore.get(i), mi = mapStore.get(i);
                if (li instanceof NewPayload a && mi instanceof NewPayload b && a.data != b.data) { shared = false; break; }
            }

            System.out.printf("  alloc %,d ms | migrate %,d ms (1st %,d, crit %,d, 2nd %,d)%n",
                    allocMs, ms, m.phaseDuration(Phase.FIRST_PASS),
                    m.phaseDuration(Phase.CRITICAL_PHASE), m.phaseDuration(Phase.SECOND_PASS));
            System.out.printf("  migrated=%,d patched=%,d | heap before=%,dMB after=%,dMB delta=%+.1fMB (data=%.0fMB)%n",
                    m.objectsMigrated(), m.objectsPatched(),
                    heapBefore / (1024 * 1024), heapAfter / (1024 * 1024),
                    (heapAfter - heapBefore) / (1024.0 * 1024), dataMb);
            System.out.printf("  verify: newPayload=%,d/%,d  arraysIntact=%,d  arraysShared(no-copy)=%s -> %s%n%n",
                    newList, count, intact, shared,
                    (newList == count && intact == count && shared) ? "OK" : "MISMATCH");
        } catch (Throwable t) {
            System.out.printf("  FAILED: %s%n%n", t);
            t.printStackTrace(System.err);
        }
    }

    static String human(long bytes) {
        if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)) + "MB";
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return bytes + "B";
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
