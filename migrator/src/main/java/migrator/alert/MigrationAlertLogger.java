package migrator.alert;

import migrator.config.AlertLevel;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logging for migration events.
 *
 * <p>Provides consistent log format suitable for log aggregators (ELK, Splunk, etc.).
 * Log entries use markers like MIGRATION_STARTED, MIGRATION_COMPLETED, MIGRATION_FAILED
 * with key=value pairs for easy parsing and alerting.
 *
 * <h2>Log Levels:</h2>
 * <ul>
 *   <li>INFO/DEBUG: migration started, phase transitions, successful completion</li>
 *   <li>WARN: rollback triggered (intentional recovery)</li>
 *   <li>ERROR: migration failed</li>
 * </ul>
 *
 * <h2>Alert Level Configuration:</h2>
 * <ul>
 *   <li>DEBUG: logs all events (debug, info, warn, error)</li>
 *   <li>WARNING: logs warnings and errors only</li>
 *   <li>ERROR: logs errors only</li>
 * </ul>
 *
 * <h2>Example Output:</h2>
 * <pre>
 * 12:00:00.000 INFO  migration - MIGRATION_STARTED id=42
 * 12:00:00.100 INFO  migration - PHASE_STARTED id=42 phase=FIRST_PASS
 * 12:00:00.500 INFO  migration - PHASE_COMPLETED id=42 phase=FIRST_PASS duration_ms=400
 * 12:00:01.000 INFO  migration - MIGRATION_COMPLETED id=42 duration_ms=1000 objects_migrated=500
 * </pre>
 */
public final class MigrationAlertLogger {

    private static final Logger log = LoggerFactory.getLogger("migration");

    private static volatile AlertLevel alertLevel = AlertLevel.WARNING;

    private MigrationAlertLogger() {}

    /**
     * Set the alert level for logging.
     *
     * @param level the alert level (DEBUG, WARNING, or ERROR)
     */
    public static void setAlertLevel(AlertLevel level) {
        alertLevel = level != null ? level : AlertLevel.WARNING;
    }

    /**
     * Get the current alert level.
     *
     * @return the current alert level
     */
    public static AlertLevel getAlertLevel() {
        return alertLevel;
    }

    /** @return true when INFO/DEBUG events should be logged (alert level DEBUG). */
    private static boolean shouldLogInfo() {
        return alertLevel == AlertLevel.DEBUG;
    }

    /** @return true when WARN events should be logged (alert level DEBUG or WARNING). */
    private static boolean shouldLogWarn() {
        return alertLevel == AlertLevel.DEBUG || alertLevel == AlertLevel.WARNING;
    }

    /** Maps a phase to its name, or {@code "UNKNOWN"} when null. */
    private static String name(Phase phase) {
        return phase != null ? phase.name() : "UNKNOWN";
    }

    /** Flattens free text so it can't break the single-line key="value" format (newlines, quotes). */
    private static String sanitize(String s) {
        if (s == null) return null;
        return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace('"', '\'');
    }

    /**
     * Log when a migration starts.
     *
     * @param migrationId the unique migration identifier
     */
    public static void migrationStarted(long migrationId) {
        if (shouldLogInfo()) {
            log.info("MIGRATION_STARTED id={}", migrationId);
        }
    }

    /**
     * Log when a migration phase begins.
     *
     * @param migrationId the migration identifier
     * @param phase the phase that is starting
     */
    public static void phaseStarted(long migrationId, Phase phase) {
        if (shouldLogInfo()) {
            log.info("PHASE_STARTED id={} phase={}", migrationId, name(phase));
        }
    }

    /**
     * Log when a migration phase completes.
     *
     * @param migrationId the migration identifier
     * @param phase the phase that completed
     * @param durationMs duration of the phase in milliseconds
     */
    public static void phaseCompleted(long migrationId, Phase phase, long durationMs) {
        if (shouldLogInfo()) {
            log.info("PHASE_COMPLETED id={} phase={} duration_ms={}", migrationId, name(phase), durationMs);
        }
    }

    /**
     * Log when a migration completes successfully.
     *
     * @param migrationId the migration identifier
     * @param metrics the final migration metrics
     */
    public static void migrationCompleted(long migrationId, MigrationMetrics metrics) {
        if (shouldLogInfo()) {
            if (metrics != null) {
                log.info("MIGRATION_COMPLETED id={} duration_ms={} objects_migrated={} objects_patched={} heap_delta={}",
                        migrationId,
                        metrics.totalDurationMs(),
                        metrics.objectsMigrated(),
                        metrics.objectsPatched(),
                        metrics.heapDelta());
            } else {
                log.info("MIGRATION_COMPLETED id={}", migrationId);
            }
        }
    }

    /**
     * Log when a migration fails.
     *
     * @param migrationId the migration identifier
     * @param error the error that caused the failure
     * @param currentPhase the phase during which failure occurred (may be null)
     * @param partialMetrics partial metrics if available (may be null)
     */
    public static void migrationFailed(long migrationId, Throwable error, Phase currentPhase, MigrationMetrics partialMetrics) {
        String errorMsg = sanitize(error != null ? error.getMessage() : "Unknown error");
        String phaseName = name(currentPhase);

        if (partialMetrics != null) {
            log.error("MIGRATION_FAILED id={} phase={} error=\"{}\" duration_ms={} objects_migrated={}",
                    migrationId, phaseName, errorMsg,
                    partialMetrics.totalDurationMs(),
                    partialMetrics.objectsMigrated());
        } else {
            log.error("MIGRATION_FAILED id={} phase={} error=\"{}\"",
                    migrationId, phaseName, errorMsg);
        }
    }

    /**
     * Log when rollback is triggered.
     *
     * @param migrationId the migration identifier
     * @param reason the reason for rollback (e.g., "smoke test failure", "timeout")
     */
    public static void rollbackTriggered(long migrationId, String reason) {
        if (shouldLogWarn()) {
            log.warn("ROLLBACK_TRIGGERED id={} reason=\"{}\"", migrationId, sanitize(reason));
        }
    }

    /**
     * Log when rollback completes.
     *
     * @param migrationId the migration identifier
     * @param success whether rollback was successful
     */
    public static void rollbackCompleted(long migrationId, boolean success) {
        if (success) {
            if (shouldLogWarn()) {
                log.warn("ROLLBACK_COMPLETED id={} status=SUCCESS", migrationId);
            }
        } else {
            // Always log errors
            log.error("ROLLBACK_COMPLETED id={} status=FAILED", migrationId);
        }
    }

    /**
     * Log when migration times out.
     *
     * @param migrationId the migration identifier
     * @param timeoutMs the configured timeout in milliseconds
     * @param currentPhase the phase during which timeout occurred
     */
    public static void migrationTimeout(long migrationId, long timeoutMs, Phase currentPhase) {
        // Always log errors
        log.error("MIGRATION_TIMEOUT id={} timeout_ms={} phase={}", migrationId, timeoutMs, name(currentPhase));
    }
}
