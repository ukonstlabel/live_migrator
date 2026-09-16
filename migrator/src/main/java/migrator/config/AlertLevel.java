package migrator.config;

/**
 * Alert level for migration logging.
 *
 * <p>Controls the minimum severity of events that get logged by
 * {@link migrator.alert.MigrationAlertLogger}. This can be configured
 * via the {@code migration.alert.level} property.
 *
 * <p>Log output at each level:
 * <ul>
 *   <li>{@link #DEBUG} - All events: started, phase transitions, completed, warnings, errors</li>
 *   <li>{@link #WARNING} - Warnings (rollback triggered) and errors only</li>
 *   <li>{@link #ERROR} - Errors only (migration failed, rollback failed)</li>
 * </ul>
 *
 * @see MigrationConfig#alertLevel()
 * @see migrator.alert.MigrationAlertLogger
 */
public enum AlertLevel {
    /**
     * Log all events including debug information.
     *
     * <p>Includes: migration started, phase started/completed, migration completed,
     * warnings, and errors. Use for development and troubleshooting.
     */
    DEBUG,

    /**
     * Log warnings and errors only.
     *
     * <p>Includes: rollback triggered, migration failed, rollback failed.
     * This is the default level, suitable for production monitoring.
     */
    WARNING,

    /**
     * Log errors only.
     *
     * <p>Includes: migration failed, rollback failed. Use when you only
     * want alerts for critical failures.
     */
    ERROR
}
