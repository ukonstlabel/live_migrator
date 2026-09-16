package migrator.state;

import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe singleton tracking migration state across the JVM.
 *
 * <p>This class maintains global state for migrations including:
 * <ul>
 *   <li>Current migration status ({@link Status})</li>
 *   <li>Active phase during in-progress migrations</li>
 *   <li>Metrics from the last completed migration</li>
 *   <li>A bounded history of recent migrations</li>
 *   <li>Error information if the last migration failed</li>
 * </ul>
 *
 * <p>The state is updated by {@link migrator.engine.MigrationEngine} during
 * migration execution and can be queried by monitoring tools or health checks.
 *
 * <h2>Usage:</h2>
 * <pre>
 * MigrationState state = MigrationState.getInstance();
 * if (state.getStatus() == Status.IN_PROGRESS) {
 *     System.out.println("Migration in phase: " + state.getCurrentPhase());
 * }
 * </pre>
 *
 * @see MigrationHistoryEntry
 * @see migrator.metrics.MigrationMetrics
 */
public final class MigrationState {

    /**
     * Migration execution status.
     */
    public enum Status {
        /** No migration in progress and none has run yet (or since reset) */
        IDLE,
        /** Migration is currently executing */
        IN_PROGRESS,
        /** Last migration completed successfully */
        SUCCESS,
        /** Last migration failed */
        FAILED
    }

    private static final MigrationState INSTANCE = new MigrationState();
    private static final int DEFAULT_HISTORY_SIZE = 10;

    private volatile int maxHistorySize = DEFAULT_HISTORY_SIZE;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile Status status = Status.IDLE;
    private volatile Phase currentPhase;
    private volatile long currentMigrationId;
    private volatile Instant startTime;
    private volatile MigrationMetrics lastMetrics;
    private volatile String lastError;
    private final List<MigrationHistoryEntry> history = new ArrayList<>();

    private MigrationState() {}

    /**
     * Get the singleton instance.
     */
    public static MigrationState getInstance() {
        return INSTANCE;
    }

    /**
     * Mark migration as started.
     *
     * @param migrationId the unique migration identifier
     */
    public void migrationStarted(long migrationId) {
        lock.writeLock().lock();
        try {
            this.status = Status.IN_PROGRESS;
            this.currentMigrationId = migrationId;
            this.startTime = Instant.now();
            this.currentPhase = null;
            this.lastError = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Update the current phase being executed.
     *
     * @param phase the current phase, or null if between phases
     */
    public void setCurrentPhase(Phase phase) {
        // Under the write lock like every other mutator, so it can't race with the lifecycle
        // transitions (started/completed/failed/reset) that also clear currentPhase.
        lock.writeLock().lock();
        try {
            this.currentPhase = phase;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark migration as completed successfully.
     *
     * @param migrationId the id of the migration that completed
     * @param metrics the final migration metrics
     */
    public void migrationCompleted(long migrationId, MigrationMetrics metrics) {
        lock.writeLock().lock();
        try {
            this.status = Status.SUCCESS;
            this.currentMigrationId = migrationId;
            this.lastMetrics = metrics;
            this.currentPhase = null;
            this.lastError = null;
            addToHistory(MigrationHistoryEntry.success(migrationId, metrics));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark migration as failed.
     *
     * @param migrationId the id of the migration that failed
     * @param error the error that caused the failure
     * @param partialMetrics partial metrics collected before failure (may be null)
     */
    public void migrationFailed(long migrationId, Throwable error, MigrationMetrics partialMetrics) {
        lock.writeLock().lock();
        try {
            this.status = Status.FAILED;
            this.currentMigrationId = migrationId;
            this.lastError = error != null ? error.getMessage() : "Unknown error";
            this.lastMetrics = partialMetrics;
            this.currentPhase = null;
            addToHistory(MigrationHistoryEntry.failure(migrationId, lastError, partialMetrics));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Prepends an entry (most-recent-first) and trims the list to {@code maxHistorySize}. Caller holds the write lock. */
    private void addToHistory(MigrationHistoryEntry entry) {
        history.add(0, entry);
        while (history.size() > maxHistorySize) {
            history.remove(history.size() - 1);
        }
    }

    /**
     * Set the maximum number of history entries to keep.
     *
     * @param size the maximum history size, must be positive
     * @throws IllegalArgumentException if size is not positive
     */
    public void setMaxHistorySize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("maxHistorySize must be positive: " + size);
        }
        lock.writeLock().lock();
        try {
            this.maxHistorySize = size;
            // Trim history if needed
            while (history.size() > maxHistorySize) {
                history.remove(history.size() - 1);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the maximum number of history entries.
     *
     * @return the maximum history size
     */
    public int getMaxHistorySize() {
        return maxHistorySize;
    }

    /**
     * Get the current migration status.
     *
     * <p>This and the other single-field getters are lock-free, so reading several of them in
     * sequence may observe a torn view across a concurrent transition. Use {@link #toMap()} or
     * {@link #getHistory()} when a consistent snapshot is required.
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Get the current phase being executed, or null if not in a migration.
     */
    public Phase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Get the current migration ID, or 0 if no migration has run.
     */
    public long getCurrentMigrationId() {
        return currentMigrationId;
    }

    /**
     * Get when the current/last migration started.
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Get the metrics from the last migration. After a successful run these are the final metrics;
     * after a failure they are the partial metrics collected before the failure (possibly null).
     */
    public MigrationMetrics getLastMetrics() {
        return lastMetrics;
    }

    /**
     * Get the error message from the last failed migration.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Get an unmodifiable view of migration history (most recent first).
     */
    public List<MigrationHistoryEntry> getHistory() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(history));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Convert the current state to a Map for JSON serialization.
     */
    public Map<String, Object> toMap() {
        lock.readLock().lock();
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status.name());
            map.put("currentPhase", currentPhase != null ? currentPhase.name() : null);
            map.put("currentMigrationId", currentMigrationId);
            map.put("startTime", startTime != null ? startTime.toString() : null);
            map.put("lastError", lastError);

            if (lastMetrics != null) {
                map.put("lastMigration", lastMetrics.toMap());
            }

            List<Map<String, Object>> historyList = new ArrayList<>(history.size());
            for (MigrationHistoryEntry entry : history) {
                Map<String, Object> entryMap = new LinkedHashMap<>();
                entryMap.put("migrationId", entry.migrationId());
                entryMap.put("status", entry.status().name());
                entryMap.put("timestamp", entry.timestamp() != null ? entry.timestamp().toString() : null);
                entryMap.put("errorMessage", entry.errorMessage());
                historyList.add(entryMap);
            }
            map.put("history", historyList);

            return map;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reset state to IDLE, including restoring the default history size. Primarily for testing,
     * so each test starts from a clean slate.
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            this.status = Status.IDLE;
            this.currentPhase = null;
            this.currentMigrationId = 0;
            this.startTime = null;
            this.lastMetrics = null;
            this.lastError = null;
            this.maxHistorySize = DEFAULT_HISTORY_SIZE;
            this.history.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
