package migrator.state;

import migrator.metrics.MigrationMetrics;

import java.time.Instant;

/**
 * Immutable record representing a single migration execution in history.
 *
 * <p>History entries are stored in {@link MigrationState} and can be queried
 * via {@link MigrationState#getHistory()}. The number of entries retained
 * is controlled by the {@code migration.history.size} configuration property.
 *
 * <p>Use the factory methods {@link #success(long, MigrationMetrics)} and
 * {@link #failure(long, String, MigrationMetrics)} to create entries.
 *
 * @param migrationId unique identifier for the migration
 * @param timestamp when the migration completed (or failed)
 * @param status final status of the migration (SUCCESS or FAILED)
 * @param metrics metrics from the migration (null if failed before completion)
 * @param errorMessage error message if migration failed (null on success)
 * @see MigrationState
 * @see MigrationMetrics
 */
public record MigrationHistoryEntry(
        long migrationId,
        Instant timestamp,
        MigrationState.Status status,
        MigrationMetrics metrics,
        String errorMessage
) {
    /**
     * Creates a successful migration history entry.
     *
     * @param migrationId the unique migration identifier
     * @param metrics the final migration metrics
     * @return a new history entry with SUCCESS status
     */
    public static MigrationHistoryEntry success(long migrationId, MigrationMetrics metrics) {
        return new MigrationHistoryEntry(
                migrationId,
                Instant.now(),
                MigrationState.Status.SUCCESS,
                metrics,
                null
        );
    }

    /**
     * Creates a failed migration history entry.
     *
     * @param migrationId the unique migration identifier
     * @param errorMessage description of what caused the failure
     * @param partialMetrics partial metrics collected before failure (may be null)
     * @return a new history entry with FAILED status
     */
    public static MigrationHistoryEntry failure(long migrationId, String errorMessage, MigrationMetrics partialMetrics) {
        return new MigrationHistoryEntry(
                migrationId,
                Instant.now(),
                MigrationState.Status.FAILED,
                partialMetrics,
                errorMessage
        );
    }
}
