package migrator.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable metrics collected during a migration execution.
 *
 * <p>Captures comprehensive performance and resource data including:
 * <ul>
 *   <li>Timing information (total duration, per-phase durations)</li>
 *   <li>Memory metrics (heap usage before/after)</li>
 *   <li>CPU metrics (load before/after/peak)</li>
 *   <li>Object counts (migrated, patched)</li>
 * </ul>
 *
 * <p>Use {@link #summary()} for a human-readable summary, or {@link #toMap()}
 * for JSON serialization.
 *
 * @see MigrationMetricsCollector
 * @see migrator.engine.MigrationEngine#getLastMetrics()
 */
public record MigrationMetrics(
        long migrationId,
        Instant startTime,
        Instant endTime,
        MemoryMetrics memoryBefore,
        MemoryMetrics memoryAfter,
        CpuMetrics cpu,
        Map<Phase, Long> phaseDurations,
        long totalDurationMs,
        int objectsMigrated,
        int objectsPatched,
        int migratorCount
) {
    /** Defensively wraps the mutable phase-duration map so the record stays truly immutable. */
    public MigrationMetrics {
        phaseDurations = (phaseDurations == null || phaseDurations.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(phaseDurations));
    }

    /**
     * Migration execution phases for timing breakdown.
     */
    public enum Phase {
        /** Initial pass: object allocation and migration */
        FIRST_PASS,
        /** Critical phase: reference patching and registry updates */
        CRITICAL_PHASE,
        /** Second pass: patching remaining references */
        SECOND_PASS,
        /** Registry update phase */
        REGISTRY_UPDATE,
        /** Smoke test execution phase */
        SMOKE_TEST
    }

    /**
     * Memory usage snapshot.
     *
     * @param heapUsed bytes of heap memory in use
     * @param heapCommitted bytes of heap memory committed
     * @param heapMax maximum heap size in bytes
     * @param nonHeapUsed bytes of non-heap memory in use
     */
    public record MemoryMetrics(long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed) {
        /** @return a human-readable "used / committed (max …)" heap summary. */
        public String heapSummary() {
            return String.format(Locale.ROOT, "%s / %s (max %s)",
                    formatBytes(heapUsed), formatBytes(heapCommitted), formatBytes(heapMax));
        }
    }

    /**
     * CPU usage metrics.
     *
     * @param before CPU load before migration (0.0-1.0)
     * @param after CPU load after migration (0.0-1.0)
     * @param peak peak CPU load during migration (0.0-1.0)
     * @param processors number of available processors
     */
    public record CpuMetrics(double before, double after, double peak, int processors) {
        /** @return a human-readable "before% -> after% (peak: …%)" CPU summary. */
        public String summary() {
            return String.format(Locale.ROOT, "%.1f%% -> %.1f%% (peak: %.1f%%)",
                    Math.max(0, before) * 100, Math.max(0, after) * 100, Math.max(0, peak) * 100);
        }
    }

    /**
     * Returns the change in heap memory usage during migration.
     *
     * @return heap delta in bytes (positive means increase)
     */
    public long heapDelta() {
        return memoryAfter.heapUsed - memoryBefore.heapUsed;
    }

    /**
     * Returns the total migration duration as a Duration object.
     *
     * @return the total duration
     */
    public Duration totalDuration() {
        return Duration.ofMillis(totalDurationMs);
    }

    /**
     * Returns the duration of a specific phase.
     *
     * @param phase the phase to query
     * @return duration in milliseconds, or 0 if phase not recorded
     */
    public long phaseDuration(Phase phase) {
        return phaseDurations.getOrDefault(phase, 0L);
    }

    /**
     * Returns a human-readable summary of the migration metrics.
     *
     * @return a formatted summary string
     */
    public String summary() {
        return String.format(Locale.ROOT,
                "Migration #%d in %dms | Heap: %s (delta: %s) | CPU: %s | Objects: %d migrated, %d patched",
                migrationId, totalDurationMs, memoryAfter.heapSummary(), formatBytes(heapDelta()),
                cpu.summary(), objectsMigrated, objectsPatched);
    }

    /**
     * Converts the metrics to a Map for JSON serialization.
     *
     * @return a map containing all metric values
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("migrationId", migrationId);
        // Null-guard every component: although the engine always supplies fully-populated metrics
        // via the collector, the canonical/record constructor is public, so a directly-built metric
        // may have null timestamps or memory/cpu sub-records. Serialize null rather than NPE.
        map.put("startTime", startTime != null ? startTime.toString() : null);
        map.put("endTime", endTime != null ? endTime.toString() : null);
        map.put("totalDurationMs", totalDurationMs);
        map.put("heapUsedBefore", memoryBefore != null ? memoryBefore.heapUsed : null);
        map.put("heapUsedAfter", memoryAfter != null ? memoryAfter.heapUsed : null);
        map.put("heapDelta", (memoryBefore != null && memoryAfter != null) ? heapDelta() : null);
        map.put("cpuLoadBefore", cpu != null ? cpu.before : null);
        map.put("cpuLoadAfter", cpu != null ? cpu.after : null);
        map.put("cpuLoadPeak", cpu != null ? cpu.peak : null);
        map.put("objectsMigrated", objectsMigrated);
        map.put("objectsPatched", objectsPatched);
        phaseDurations.forEach((phase, duration) ->
                map.put(phase.name().toLowerCase() + "DurationMs", duration));
        return map;
    }

    /** Formats a byte count as B/KB/MB/GB with one or two decimal places. */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2fGB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Creates a new builder for constructing MigrationMetrics.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing {@link MigrationMetrics} instances.
     */
    public static final class Builder {
        private long migrationId;
        private Instant startTime;
        private Instant endTime;
        private long heapUsedBefore, heapCommittedBefore, heapMaxBefore, nonHeapUsedBefore;
        private long heapUsedAfter, heapCommittedAfter, heapMaxAfter, nonHeapUsedAfter;
        private double cpuBefore, cpuAfter, cpuPeak;
        private int processors;
        private final Map<Phase, Long> phaseDurations = new EnumMap<>(Phase.class);
        private long totalDurationMs;
        private int objectsMigrated, objectsPatched, migratorCount;

        public Builder migrationId(long id) { this.migrationId = id; return this; }
        public Builder startTime(Instant t) { this.startTime = t; return this; }
        public Builder endTime(Instant t) { this.endTime = t; return this; }

        public Builder heapBefore(long used, long committed, long max) {
            this.heapUsedBefore = used;
            this.heapCommittedBefore = committed;
            this.heapMaxBefore = max;
            return this;
        }

        public Builder heapAfter(long used, long committed, long max) {
            this.heapUsedAfter = used;
            this.heapCommittedAfter = committed;
            this.heapMaxAfter = max;
            return this;
        }

        public Builder nonHeapUsedBefore(long v) { this.nonHeapUsedBefore = v; return this; }
        public Builder nonHeapUsedAfter(long v) { this.nonHeapUsedAfter = v; return this; }
        public Builder cpuLoadBefore(double v) { this.cpuBefore = v; return this; }
        public Builder cpuLoadAfter(double v) { this.cpuAfter = v; return this; }
        public Builder cpuLoadPeak(double v) { this.cpuPeak = v; return this; }
        public Builder availableProcessors(int v) { this.processors = v; return this; }

        public Builder phaseDurations(Map<Phase, Long> durations) {
            this.phaseDurations.putAll(durations);
            return this;
        }

        public Builder totalDurationMs(long v) { this.totalDurationMs = v; return this; }
        public Builder objectsMigrated(int v) { this.objectsMigrated = v; return this; }
        public Builder objectsPatched(int v) { this.objectsPatched = v; return this; }
        public Builder migratorCount(int v) { this.migratorCount = v; return this; }

        public MigrationMetrics build() {
            return new MigrationMetrics(
                    migrationId, startTime, endTime,
                    new MemoryMetrics(heapUsedBefore, heapCommittedBefore, heapMaxBefore, nonHeapUsedBefore),
                    new MemoryMetrics(heapUsedAfter, heapCommittedAfter, heapMaxAfter, nonHeapUsedAfter),
                    new CpuMetrics(cpuBefore, cpuAfter, cpuPeak, processors),
                    new EnumMap<>(phaseDurations),
                    totalDurationMs, objectsMigrated, objectsPatched, migratorCount
            );
        }
    }
}
