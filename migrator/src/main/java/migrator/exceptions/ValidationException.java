package migrator.exceptions;

/**
 * Exception thrown when validation fails during migration.
 *
 * <p>This exception indicates that post-migration validation detected a problem
 * with the migrated objects or application state. It is used by:
 * <ul>
 *   <li>{@link migrator.smoke.SmokeTest} - validates migrated object correctness</li>
 *   <li>{@link migrator.smoke.HealthCheck} - validates system health after migration</li>
 * </ul>
 *
 * <p>When this exception is thrown during smoke testing, the migration engine
 * will typically trigger a rollback to restore the pre-migration state.
 *
 * @see migrator.smoke.SmokeTest
 * @see migrator.smoke.HealthCheck
 * @see migrator.smoke.SmokeTestRunner
 */
public class ValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Creates a new validation exception with no message. */
    public ValidationException() { super(); }

    /**
     * Creates a new validation exception with the specified message.
     *
     * @param message a description of the validation failure
     */
    public ValidationException(String message) { super(message); }

    /**
     * Creates a new validation exception with the specified message and cause.
     *
     * @param message a description of the validation failure
     * @param cause the underlying exception that caused the validation to fail
     */
    public ValidationException(String message, Throwable cause) { super(message, cause); }

    /**
     * Creates a new validation exception with the specified cause.
     *
     * @param cause the underlying exception that caused the validation to fail
     */
    public ValidationException(Throwable cause) { super(cause); }
}
