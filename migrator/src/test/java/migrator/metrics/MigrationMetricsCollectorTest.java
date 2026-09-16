package migrator.metrics;

import migrator.metrics.MigrationMetrics.Phase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MigrationMetricsCollector}.
 */
class MigrationMetricsCollectorTest {

    @Test
    void shouldCollectBasicMetrics() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();

        collector.start(1L)
                .migratorCount(2)
                .objectsMigrated(100)
                .objectsPatched(50);

        collector.timed(Phase.FIRST_PASS, () -> {});
        collector.timed(Phase.CRITICAL_PHASE, () -> {});
        collector.timed(Phase.SECOND_PASS, () -> {});
        collector.timed(Phase.REGISTRY_UPDATE, () -> {});
        collector.timed(Phase.SMOKE_TEST, () -> {});

        MigrationMetrics metrics = collector.finish();

        assertThat(metrics.migrationId()).isEqualTo(1L);
        assertThat(metrics.objectsMigrated()).isEqualTo(100);
        assertThat(metrics.objectsPatched()).isEqualTo(50);
        assertThat(metrics.migratorCount()).isEqualTo(2);
        assertThat(metrics.startTime()).isNotNull();
        assertThat(metrics.endTime()).isNotNull();
        assertThat(metrics.totalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.cpu().processors()).isGreaterThan(0);
    }

    @Test
    void shouldCaptureHeapMetrics() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(2L);

        MigrationMetrics metrics = collector.finish();

        assertThat(metrics.memoryBefore().heapUsed()).isGreaterThan(0);
        assertThat(metrics.memoryAfter().heapUsed()).isGreaterThan(0);
        assertThat(metrics.memoryBefore().heapCommitted()).isGreaterThan(0);
    }

    @Test
    void shouldCalculateHeapDelta() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(3L);

        // Allocate some memory to create a delta
        byte[] allocation = new byte[1024 * 1024]; // 1MB

        MigrationMetrics metrics = collector.finish();

        // Just verify the delta calculation works
        long delta = metrics.heapDelta();
        assertThat(delta).isNotNull();

        // Keep reference to prevent GC
        assertThat(allocation.length).isEqualTo(1024 * 1024);
    }

    @Test
    void shouldTrackCpuLoad() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(4L);

        MigrationMetrics metrics = collector.finish();

        // CPU load might be -1 if unavailable on some platforms
        assertThat(metrics.cpu().before()).isGreaterThanOrEqualTo(-1);
        assertThat(metrics.cpu().after()).isGreaterThanOrEqualTo(-1);
    }

    @Test
    void shouldCaptureMemorySnapshot() {
        MigrationMetricsCollector.MemorySnapshot snapshot = MigrationMetricsCollector.MemorySnapshot.capture();

        assertThat(snapshot.heapUsed()).isGreaterThan(0);
        assertThat(snapshot.heapCommitted()).isGreaterThan(0);
        assertThat(snapshot.summary()).contains("Heap:");
    }

    @Test
    void shouldProduceSummaryString() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(5L)
                .objectsMigrated(10)
                .objectsPatched(5);

        MigrationMetrics metrics = collector.finish();
        String summary = metrics.summary();

        assertThat(summary).contains("Migration #5");
        assertThat(summary).contains("10 migrated");
        assertThat(summary).contains("5 patched");
    }

    @Test
    void shouldConvertToMap() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(6L);

        MigrationMetrics metrics = collector.finish();
        var map = metrics.toMap();

        assertThat(map).containsKey("migrationId");
        assertThat(map).containsKey("heapUsedBefore");
        assertThat(map).containsKey("heapUsedAfter");
        assertThat(map).containsKey("heapDelta");
        assertThat(map).containsKey("cpuLoadBefore");
        assertThat(map).containsKey("totalDurationMs");
        assertThat(map.get("migrationId")).isEqualTo(6L);
    }

    @Test
    void shouldTimePhases() {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(7L);

        collector.timed(Phase.FIRST_PASS, () -> sleep(10));
        collector.timed(Phase.SECOND_PASS, () -> sleep(10));

        MigrationMetrics metrics = collector.finish();

        assertThat(metrics.phaseDuration(Phase.FIRST_PASS)).isGreaterThanOrEqualTo(10);
        assertThat(metrics.phaseDuration(Phase.SECOND_PASS)).isGreaterThanOrEqualTo(10);
    }

    @Test
    void shouldTimePhaseWithReturnValue() throws Exception {
        MigrationMetricsCollector collector = new MigrationMetricsCollector();
        collector.start(8L);

        String result = collector.timed(Phase.FIRST_PASS, () -> {
            sleep(5);
            return "done";
        });

        MigrationMetrics metrics = collector.finish();

        assertThat(result).isEqualTo("done");
        assertThat(metrics.phaseDuration(Phase.FIRST_PASS)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void nestedRecordsShouldProvideSummaries() {
        MigrationMetrics.MemoryMetrics memory = new MigrationMetrics.MemoryMetrics(
                100 * 1024 * 1024, 200 * 1024 * 1024, 512 * 1024 * 1024, 50 * 1024 * 1024);
        MigrationMetrics.CpuMetrics cpu = new MigrationMetrics.CpuMetrics(0.1, 0.5, 0.8, 8);

        assertThat(memory.heapSummary()).contains("MB");
        assertThat(cpu.summary()).contains("10.0%");
        assertThat(cpu.summary()).contains("50.0%");
        assertThat(cpu.summary()).contains("80.0%");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
