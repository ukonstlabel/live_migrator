package migrator.benchmark;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;
import migrator.engine.MigrationEngine;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import migrator.phase.MigrationContext;
import migrator.phase.MigrationPhaseListener;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heap stress test: progressively scales object count to find practical limits.
 * Uses SPEC (filtered) heap walk for the reference-patching phase.
 *
 * Each OldPayload is ~1 KB (1024-byte data array + header/fields/String).
 * Scale: 1K -> 10K -> 50K -> 100K -> 500K -> 1M -> 2M objects.
 * At 1M objects x ~1 KB = ~1 GB total.
 */
public class HeapStressTest {

    // ─── Model classes ──────────────────────────────────────────────

    public interface Payload {
        int getId();
        String getName();
        byte[] getData();
    }

    public static class OldPayload implements Payload {
        public final int id;
        public final String name;
        public final byte[] data;

        public OldPayload(int id, String name, byte[] data) {
            this.id = id;
            this.name = name;
            this.data = data;
        }

        @Override public int getId() { return id; }
        @Override public String getName() { return name; }
        @Override public byte[] getData() { return data; }
    }

    public static class NewPayload implements Payload {
        public final int id;
        public final String label;
        public final byte[] data;
        public final long migratedAt;

        public NewPayload(int id, String label, byte[] data, long migratedAt) {
            this.id = id;
            this.label = label;
            this.data = data;
            this.migratedAt = migratedAt;
        }

        @Override public int getId() { return id; }
        @Override public String getName() { return label; }
        @Override public byte[] getData() { return data; }
    }

    @Migrator
    public static class PayloadMigrator implements ClassMigrator<OldPayload, NewPayload> {
        @Override
        public NewPayload migrate(OldPayload old) {
            return new NewPayload(old.id, old.name, old.data, System.nanoTime());
        }
    }

    static class BenchPhaseListener implements MigrationPhaseListener {
        long quiesceStart;
        long quiesceDurationMs;

        @Override
        public void onBeforeCriticalPhase(MigrationContext ctx) {
            quiesceStart = System.nanoTime();
        }

        @Override
        public void onAfterCriticalPhase(MigrationContext ctx) {
            quiesceDurationMs = (System.nanoTime() - quiesceStart) / 1_000_000;
        }
    }

    // ─── Configuration ──────────────────────────────────────────────

    static final int PAYLOAD_SIZE = 64; // small payload, focus on object count

    static final int[] OBJECT_COUNTS = {
        1_000, 10_000, 100_000, 500_000
    };

    static List<OldPayload> objectStore;
    static Map<Integer, OldPayload> mapStore;

    // ─── Main ───────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println("  Live Migrator -- Heap Stress Test (SPEC mode, 1KB objects)");
        System.out.println("================================================================");
        System.out.println();
        printJvmInfo();

        List<String[]> resultTable = new ArrayList<>();
        resultTable.add(new String[]{
            "Count", "Data MB", "Alloc ms", "Total ms",
            "1st Pass", "Critical", "2nd Pass", "Registry",
            "Smoke", "Migrated", "Patched",
            "Heap +MB", "Quiesce ms", "List%", "Map%"
        });

        for (int count : OBJECT_COUNTS) {
            double dataMb = (double) count * PAYLOAD_SIZE / (1024.0 * 1024);
            System.out.println("----------------------------------------------------------------");
            System.out.printf("Test: %,d objects x %d B = %.0f MB%n", count, PAYLOAD_SIZE, dataMb);
            System.out.println("----------------------------------------------------------------");

            String[] row = runSingleTest(count);
            if (row != null) resultTable.add(row);

            objectStore = null;
            mapStore = null;
            System.gc();
            sleep(2000);
            System.gc();
            sleep(1000);
            System.out.println();
        }

        System.out.println();
        System.out.println("================================================================");
        System.out.println("                       SUMMARY TABLE");
        System.out.println("================================================================");
        printTable(resultTable);
    }

    static String[] runSingleTest(int objectCount) {
        Runtime rt = Runtime.getRuntime();

        // ── Allocate ──
        System.out.print("  [1/4] Allocating...");
        long allocStart = System.nanoTime();

        objectStore = new ArrayList<>(objectCount);
        mapStore = new ConcurrentHashMap<>(objectCount, 0.75f, 4);

        for (int i = 0; i < objectCount; i++) {
            byte[] data = new byte[PAYLOAD_SIZE];
            data[0] = (byte) (i & 0xFF);
            data[1] = (byte) ((i >> 8) & 0xFF);
            OldPayload obj = new OldPayload(i, "item-" + i, data);
            objectStore.add(obj);
            mapStore.put(i, obj);
        }

        long allocMs = (System.nanoTime() - allocStart) / 1_000_000;
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.out.printf(" %,d ms (heap %,d MB)%n", allocMs, heapMb);

        // ── Migrate ──
        System.out.print("  [2/4] Migrating (SPEC)...");
        BenchPhaseListener phaseListener = new BenchPhaseListener();

        try {
            SmokeTestRunner smokeRunner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok("bench-noop"))
                    .build();

            MigrationEngine engine = new MigrationEngine(
                    PayloadMigrator.class,
                    phaseListener,
                    smokeRunner,
                    new CommitManager(NoopCracController.INSTANCE),
                    new RollbackManager(NoopCracController.INSTANCE)
            );
            engine.setFullHeapWalk(false); // SPEC mode
            engine.setAllTimeoutsSeconds(0); // no timeout

            long t0 = System.nanoTime();
            engine.migrate(Set.of(HeapStressTest.class), null, null);
            long migrateMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf(" %,d ms%n", migrateMs);

            // ── Metrics ──
            MigrationMetrics m = MigrationEngine.getLastMetrics();
            if (m != null) {
                System.out.printf("    Total      : %,6d ms%n", m.totalDurationMs());
                System.out.printf("    1st pass   : %,6d ms%n", m.phaseDuration(Phase.FIRST_PASS));
                System.out.printf("    Critical   : %,6d ms  (quiesce %,d ms)%n",
                        m.phaseDuration(Phase.CRITICAL_PHASE), phaseListener.quiesceDurationMs);
                System.out.printf("    2nd pass   : %,6d ms%n", m.phaseDuration(Phase.SECOND_PASS));
                System.out.printf("    Registry   : %,6d ms%n", m.phaseDuration(Phase.REGISTRY_UPDATE));
                System.out.printf("    Smoke      : %,6d ms%n", m.phaseDuration(Phase.SMOKE_TEST));
                System.out.printf("    Migrated=%,d  Patched=%,d  Heap=%+.1fMB%n",
                        m.objectsMigrated(), m.objectsPatched(), m.heapDelta() / (1024.0 * 1024));
            }

            // ── Verify ──
            System.out.print("  [4/4] Verify...");
            int newInList = 0, newInMap = 0;
            for (Object obj : objectStore)
                if (obj instanceof NewPayload) newInList++;
            for (Object obj : mapStore.values())
                if (obj instanceof NewPayload) newInMap++;

            double lp = objectCount > 0 ? 100.0 * newInList / objectCount : 0;
            double mp = objectCount > 0 ? 100.0 * newInMap / objectCount : 0;
            System.out.printf(" List=%.1f%% Map=%.1f%%%n", lp, mp);

            return new String[]{
                String.format("%,d", objectCount),
                String.format("%.0f", (double) objectCount * PAYLOAD_SIZE / (1024.0 * 1024)),
                String.format("%,d", allocMs),
                m != null ? String.format("%,d", m.totalDurationMs()) : "-",
                m != null ? String.format("%,d", m.phaseDuration(Phase.FIRST_PASS)) : "-",
                m != null ? String.format("%,d", m.phaseDuration(Phase.CRITICAL_PHASE)) : "-",
                m != null ? String.format("%,d", m.phaseDuration(Phase.SECOND_PASS)) : "-",
                m != null ? String.format("%,d", m.phaseDuration(Phase.REGISTRY_UPDATE)) : "-",
                m != null ? String.format("%,d", m.phaseDuration(Phase.SMOKE_TEST)) : "-",
                m != null ? String.format("%,d", m.objectsMigrated()) : "-",
                m != null ? String.format("%,d", m.objectsPatched()) : "-",
                m != null ? String.format("%+.1f", m.heapDelta() / (1024.0 * 1024)) : "-",
                String.format("%,d", phaseListener.quiesceDurationMs),
                String.format("%.1f", lp),
                String.format("%.1f", mp)
            };

        } catch (Throwable t) {
            System.out.printf(" FAILED: %s%n", t.getMessage());
            t.printStackTrace(System.err);
            return new String[]{
                String.format("%,d", objectCount),
                String.format("%.0f", (double) objectCount * PAYLOAD_SIZE / (1024.0 * 1024)),
                String.format("%,d", allocMs),
                "FAIL", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-"
            };
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    static void printJvmInfo() {
        Runtime rt = Runtime.getRuntime();
        System.out.printf("JVM  : %s %s%n",
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"));
        System.out.printf("Heap : max %,d MB | CPUs: %d%n",
                rt.maxMemory() / (1024 * 1024), rt.availableProcessors());
        System.out.println();
    }

    static void printTable(List<String[]> rows) {
        if (rows.isEmpty()) return;
        int cols = rows.get(0).length;
        int[] w = new int[cols];
        for (String[] r : rows)
            for (int c = 0; c < cols && c < r.length; c++)
                w[c] = Math.max(w[c], r[c].length());

        StringBuilder sep = new StringBuilder("+");
        for (int v : w) sep.append("-".repeat(v + 2)).append("+");

        System.out.println(sep);
        for (int r = 0; r < rows.size(); r++) {
            StringBuilder sb = new StringBuilder("|");
            for (int c = 0; c < cols; c++) {
                String val = c < rows.get(r).length ? rows.get(r)[c] : "";
                sb.append(String.format(" %" + w[c] + "s |", val));
            }
            System.out.println(sb);
            if (r == 0) System.out.println(sep);
        }
        System.out.println(sep);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
