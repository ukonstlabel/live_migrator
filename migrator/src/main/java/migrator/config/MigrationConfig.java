package migrator.config;

import java.time.Duration;

/**
 * Central configuration for migration operations.
 *
 * <p>This class encapsulates all configurable parameters for the migration
 * engine, including:
 * <ul>
 *   <li>Heap walk mode (full vs. filtered)</li>
 *   <li>Timeout settings for various phases</li>
 *   <li>Heap size constraints</li>
 *   <li>History size and alert level</li>
 * </ul>
 *
 * <p>Configuration can be loaded from {@code migration.properties} or
 * {@code migration.yml} using {@link MigrationConfigLoader}.
 *
 * @see MigrationConfigLoader
 * @see migrator.engine.MigrationEngine#applyConfig(MigrationConfig)
 */
public final class MigrationConfig {

    /** Configuration with all defaults: filtered (SPEC) heap walk, no timeouts, WARNING alerts, history size 10. */
    public static final MigrationConfig DEFAULTS = builder().build();

    private final HeapWalkMode heapWalkMode;
    private final Duration heapWalkTimeout;
    private final Duration heapSnapshotTimeout;
    private final Duration criticalPhaseTimeout;
    private final Duration smokeTestTimeout;
    private final long minHeapSizeMb;
    private final long maxHeapSizeMb;
    private final int historySize;
    private final AlertLevel alertLevel;

    private MigrationConfig(Builder b) {
        this.heapWalkMode = b.heapWalkMode;
        this.heapWalkTimeout = b.heapWalkTimeout;
        this.heapSnapshotTimeout = b.heapSnapshotTimeout;
        this.criticalPhaseTimeout = b.criticalPhaseTimeout;
        this.smokeTestTimeout = b.smokeTestTimeout;
        this.minHeapSizeMb = b.minHeapSizeMb;
        this.maxHeapSizeMb = b.maxHeapSizeMb;
        this.historySize = b.historySize;
        this.alertLevel = b.alertLevel;
    }

    /**
     * Creates a new configuration builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the heap walk mode (FULL or SPEC). */
    public HeapWalkMode heapWalkMode() { return heapWalkMode; }

    /** Returns true if full heap walk is enabled. */
    public boolean isFullHeapWalk() { return heapWalkMode == HeapWalkMode.FULL; }

    /** Returns the timeout for heap walk operations. */
    public Duration heapWalkTimeout() { return heapWalkTimeout; }

    /** Returns the timeout for heap snapshot creation. */
    public Duration heapSnapshotTimeout() { return heapSnapshotTimeout; }

    /** Returns the timeout for critical phase callbacks. */
    public Duration criticalPhaseTimeout() { return criticalPhaseTimeout; }

    /** Returns the timeout for smoke test execution. */
    public Duration smokeTestTimeout() { return smokeTestTimeout; }

    /** Returns the minimum required heap size in MB, or 0 if not set. */
    public long minHeapSizeMb() { return minHeapSizeMb; }

    /** Returns the maximum allowed heap size in MB, or 0 if not set. */
    public long maxHeapSizeMb() { return maxHeapSizeMb; }

    /** Returns the maximum number of migration history entries to keep. */
    public int historySize() { return historySize; }

    /** Returns the alert level for logging. */
    public AlertLevel alertLevel() { return alertLevel; }

    @Override
    public String toString() {
        return "MigrationConfig{" +
                "heapWalkMode=" + heapWalkMode +
                ", heapWalkTimeout=" + heapWalkTimeout.toSeconds() + "s" +
                ", heapSnapshotTimeout=" + heapSnapshotTimeout.toSeconds() + "s" +
                ", criticalPhaseTimeout=" + criticalPhaseTimeout.toSeconds() + "s" +
                ", smokeTestTimeout=" + smokeTestTimeout.toSeconds() + "s" +
                ", minHeapSizeMb=" + minHeapSizeMb +
                ", maxHeapSizeMb=" + maxHeapSizeMb +
                ", historySize=" + historySize +
                ", alertLevel=" + alertLevel +
                '}';
    }

    /**
     * Builder for constructing {@link MigrationConfig} instances.
     */
    public static final class Builder {
        private HeapWalkMode heapWalkMode = HeapWalkMode.SPEC;
        private Duration heapWalkTimeout = Duration.ZERO;
        private Duration heapSnapshotTimeout = Duration.ZERO;
        private Duration criticalPhaseTimeout = Duration.ZERO;
        private Duration smokeTestTimeout = Duration.ZERO;
        private long minHeapSizeMb = 0;
        private long maxHeapSizeMb = 0;
        private int historySize = 10;
        private AlertLevel alertLevel = AlertLevel.WARNING;

        public Builder heapWalkMode(HeapWalkMode mode) {
            this.heapWalkMode = mode != null ? mode : HeapWalkMode.SPEC;
            return this;
        }

        public Builder heapWalkTimeout(Duration timeout) {
            this.heapWalkTimeout = timeout != null ? timeout : Duration.ZERO;
            return this;
        }

        public Builder heapWalkTimeoutSeconds(long seconds) {
            return heapWalkTimeout(seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO);
        }

        public Builder heapSnapshotTimeout(Duration timeout) {
            this.heapSnapshotTimeout = timeout != null ? timeout : Duration.ZERO;
            return this;
        }

        public Builder heapSnapshotTimeoutSeconds(long seconds) {
            return heapSnapshotTimeout(seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO);
        }

        public Builder criticalPhaseTimeout(Duration timeout) {
            this.criticalPhaseTimeout = timeout != null ? timeout : Duration.ZERO;
            return this;
        }

        public Builder criticalPhaseTimeoutSeconds(long seconds) {
            return criticalPhaseTimeout(seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO);
        }

        public Builder smokeTestTimeout(Duration timeout) {
            this.smokeTestTimeout = timeout != null ? timeout : Duration.ZERO;
            return this;
        }

        public Builder smokeTestTimeoutSeconds(long seconds) {
            return smokeTestTimeout(seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO);
        }

        public Builder allTimeoutsSeconds(long seconds) {
            Duration timeout = seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO;
            this.heapWalkTimeout = timeout;
            this.heapSnapshotTimeout = timeout;
            this.criticalPhaseTimeout = timeout;
            this.smokeTestTimeout = timeout;
            return this;
        }

        public Builder minHeapSizeMb(long mb) {
            this.minHeapSizeMb = mb;
            return this;
        }

        public Builder maxHeapSizeMb(long mb) {
            this.maxHeapSizeMb = mb;
            return this;
        }

        public Builder historySize(int size) {
            if (size <= 0) throw new IllegalArgumentException("historySize must be positive");
            this.historySize = size;
            return this;
        }

        public Builder alertLevel(AlertLevel level) {
            this.alertLevel = level != null ? level : AlertLevel.WARNING;
            return this;
        }

        public MigrationConfig build() {
            return new MigrationConfig(this);
        }
    }
}
