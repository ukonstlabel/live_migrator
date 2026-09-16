package migrator.metrics;

import migrator.metrics.MigrationMetrics.Phase;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Collects JVM metrics during migration execution using JMX.
 *
 * <p>This collector tracks:
 * <ul>
 *   <li>Heap memory usage (before and after)</li>
 *   <li>CPU load (before, after, and peak)</li>
 *   <li>Per-phase timing using functional-style {@link #timed(Phase, ThrowingRunnable)}</li>
 *   <li>Object counts (migrated and patched)</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>
 * MigrationMetricsCollector collector = new MigrationMetricsCollector();
 * collector.start(migrationId);
 *
 * collector.timed(Phase.FIRST_PASS, () -&gt; performFirstPass());
 * collector.objectsMigrated(count);
 *
 * MigrationMetrics metrics = collector.finish();
 * </pre>
 *
 * @see MigrationMetrics
 */
public final class MigrationMetricsCollector {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final int availableProcessors = Runtime.getRuntime().availableProcessors();

    private final Map<Phase, Long> phaseDurations = new EnumMap<>(Phase.class);
    private MigrationMetrics.Builder builder;

    private Instant startTime;
    private double cpuLoadPeak;

    /**
     * Starts metrics collection for a new migration.
     *
     * @param migrationId the unique migration identifier
     * @return this collector for method chaining
     */
    public MigrationMetricsCollector start(long migrationId) {
        this.startTime = Instant.now();
        this.phaseDurations.clear();
        this.builder = MigrationMetrics.builder();

        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        double cpuLoad = getCpuLoad();
        this.cpuLoadPeak = cpuLoad;

        builder.migrationId(migrationId)
               .startTime(startTime)
               .heapBefore(heap.getUsed(), heap.getCommitted(), heap.getMax())
               .nonHeapUsedBefore(memoryBean.getNonHeapMemoryUsage().getUsed())
               .cpuLoadBefore(cpuLoad)
               .availableProcessors(availableProcessors);

        return this;
    }

    /** @throws IllegalStateException if {@link #start(long)} has not been called yet. */
    private void requireStarted() {
        if (builder == null) {
            throw new IllegalStateException("start() must be called before collecting metrics");
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    /**
     * Time a phase and run the action (can throw checked exceptions).
     */
    public <E extends Exception> void timed(Phase phase, ThrowingRunnable<E> action) throws E {
        requireStarted();
        long start = System.nanoTime();
        try {
            action.run();
        } finally {
            phaseDurations.put(phase, Duration.ofNanos(System.nanoTime() - start).toMillis());
            sampleCpu();
        }
    }

    /**
     * Time a phase and return the result (can throw checked exceptions).
     */
    public <T, E extends Exception> T timed(Phase phase, ThrowingSupplier<T, E> action) throws E {
        requireStarted();
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            phaseDurations.put(phase, Duration.ofNanos(System.nanoTime() - start).toMillis());
            sampleCpu();
        }
    }

    /**
     * Records the number of objects migrated.
     *
     * @param count the number of migrated objects
     * @return this collector for method chaining
     */
    public MigrationMetricsCollector objectsMigrated(int count) {
        requireStarted();
        builder.objectsMigrated(count);
        return this;
    }

    /**
     * Records the number of objects patched.
     *
     * @param count the number of patched objects
     * @return this collector for method chaining
     */
    public MigrationMetricsCollector objectsPatched(int count) {
        requireStarted();
        builder.objectsPatched(count);
        return this;
    }

    /**
     * Records the number of migrators in the migration plan.
     *
     * @param count the number of migrators
     * @return this collector for method chaining
     */
    public MigrationMetricsCollector migratorCount(int count) {
        requireStarted();
        builder.migratorCount(count);
        return this;
    }

    /**
     * Finishes metrics collection and returns the final metrics.
     *
     * @return the collected migration metrics
     */
    public MigrationMetrics finish() {
        requireStarted();
        Instant endTime = Instant.now();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        double cpuLoadAfter = getCpuLoad();

        return builder
                .endTime(endTime)
                .heapAfter(heap.getUsed(), heap.getCommitted(), heap.getMax())
                .nonHeapUsedAfter(memoryBean.getNonHeapMemoryUsage().getUsed())
                .cpuLoadAfter(cpuLoadAfter)
                .cpuLoadPeak(Math.max(cpuLoadPeak, cpuLoadAfter))
                .phaseDurations(phaseDurations)
                .totalDurationMs(Duration.between(startTime, endTime).toMillis())
                .build();
    }

    /** Samples current CPU load and raises the running peak if it is higher. */
    private void sampleCpu() {
        double current = getCpuLoad();
        if (current > cpuLoadPeak) {
            cpuLoadPeak = current;
        }
    }

    /**
     * Returns current CPU load in [0.0, 1.0], preferring the JVM's process/system CPU load and
     * falling back to the normalized system load average. Returns -1 when no metric is available.
     */
    private double getCpuLoad() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double load = sunBean.getCpuLoad();
            if (load >= 0) return load;
            load = sunBean.getProcessCpuLoad();
            if (load >= 0) return load;
        }
        double loadAvg = osBean.getSystemLoadAverage();
        return loadAvg >= 0 ? Math.min(1.0, loadAvg / availableProcessors) : -1;
    }

    /**
     * Memory snapshot for on-demand inspection.
     */
    public record MemorySnapshot(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed) {
        public static MemorySnapshot capture() {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = mem.getHeapMemoryUsage();
            return new MemorySnapshot(heap.getUsed(), heap.getCommitted(), heap.getMax(),
                    mem.getNonHeapMemoryUsage().getUsed());
        }

        public String summary() {
            return String.format(Locale.ROOT, "Heap: %s/%s (max %s)",
                    formatBytes(heapUsed), formatBytes(heapCommitted), formatBytes(heapMax));
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
            return String.format(Locale.ROOT, "%.2fGB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
