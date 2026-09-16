package migrator.exceptions;

/**
 * Exception thrown when a required migration annotation is not found during classpath scanning.
 *
 * <p>The migration framework requires certain annotated classes to be present:
 * <ul>
 *   <li>{@link migrator.annotations.Migrator} - exactly one required</li>
 *   <li>{@link migrator.annotations.PhaseListener} - exactly one required</li>
 *   <li>{@link migrator.annotations.CommitComponent} - exactly one required</li>
 *   <li>{@link migrator.annotations.RollbackComponent} - exactly one required</li>
 *   <li>{@link migrator.annotations.SmokeTestComponent} - at least one required</li>
 * </ul>
 *
 * <p>This exception is thrown when the {@link migrator.scanner.AnnotationScanner} cannot
 * locate a required annotated class. (When <em>multiple</em> classes are found for a
 * single-instance annotation, the scanner throws {@link IllegalStateException} instead.)
 *
 * <p>This is an unchecked exception because missing annotations indicate a
 * configuration/deployment problem that cannot be recovered at runtime.
 *
 * @see migrator.scanner.AnnotationScanner
 */
public class AnnotationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the specified message.
     *
     * @param message description of which annotation was not found or had multiple matches
     */
    public AnnotationNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the specified message and cause.
     *
     * @param message description of which annotation was not found or had multiple matches
     * @param cause the underlying cause (e.g. a classpath scanning failure)
     */
    public AnnotationNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
