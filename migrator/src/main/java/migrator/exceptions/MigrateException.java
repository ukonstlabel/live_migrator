package migrator.exceptions;

/**
 * Exception thrown when a migration operation fails.
 *
 * <p>This exception provides rich diagnostic context including:
 * <ul>
 *   <li>A human-readable error message</li>
 *   <li>The object that caused the failure (serialization-safe representation)</li>
 *   <li>Source and target class types</li>
 *   <li>The migration stage where failure occurred</li>
 * </ul>
 *
 * <p>The offending object is captured as a safe string ({@link #getObjectIdStr()})
 * rather than by reference, so a problematic object is never retained. Note that
 * {@code from}/{@code to} are held as {@link Class} references; retaining such an
 * exception long-term can pin the classloader that defined those classes.
 *
 * @see migrator.engine.MigrationEngine
 * @see migrator.ClassMigrator
 */
public class MigrateException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Maximum length of an embedded object {@code toString()} in diagnostics. */
    private static final int MAX_DESC_LEN = 512;

    /** Safe diagnostic representation of object involved in failure */
    private final String objectIdStr;

    /** identityHashCode of object (if available) */
    private final Integer objectIdentityHash;

    private final Class<?> from;
    private final Class<?> to;
    private final String stage;

    // ---------------- constructors ----------------

    /**
     * Creates a new migration exception with a message.
     *
     * @param message the error message
     */
    public MigrateException(String message) {
        super(message);
        this.objectIdStr = null;
        this.objectIdentityHash = null;
        this.from = null;
        this.to = null;
        this.stage = null;
    }

    /**
     * Creates a new migration exception with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public MigrateException(String message, Throwable cause) {
        super(message, cause);
        this.objectIdStr = null;
        this.objectIdentityHash = null;
        this.from = null;
        this.to = null;
        this.stage = null;
    }

    /**
     * Creates a new migration exception with full diagnostic context.
     *
     * @param message the error message
     * @param object the object that caused the failure (will be safely described)
     * @param from the source class type
     * @param to the target class type
     * @param stage the migration stage where failure occurred
     */
    public MigrateException(String message,
                            Object object,
                            Class<?> from,
                            Class<?> to,
                            String stage) {
        super(message);
        this.objectIdStr = safeDescribe(object);
        this.objectIdentityHash = object != null ? System.identityHashCode(object) : null;
        this.from = from;
        this.to = to;
        this.stage = stage;
    }

    /**
     * Creates a new migration exception with full diagnostic context and cause.
     *
     * @param message the error message
     * @param object the object that caused the failure (will be safely described)
     * @param from the source class type
     * @param to the target class type
     * @param stage the migration stage where failure occurred
     * @param cause the underlying cause
     */
    public MigrateException(String message,
                            Object object,
                            Class<?> from,
                            Class<?> to,
                            String stage,
                            Throwable cause) {
        super(message, cause);
        this.objectIdStr = safeDescribe(object);
        this.objectIdentityHash = object != null ? System.identityHashCode(object) : null;
        this.from = from;
        this.to = to;
        this.stage = stage;
    }

    // ---------------- helpers ----------------

    /**
     * Builds a serialization-safe description of an object: its class name and identity
     * hash, plus its {@code toString()} when available. Guards against {@code toString()}
     * returning null or throwing, so it never fails.
     *
     * @param o the object to describe (may be null)
     * @return a non-null diagnostic string
     */
    private static String safeDescribe(Object o) {
        if (o == null) return "null";
        try {
            String s = o.toString();
            if (s == null) {
                return o.getClass().getName() + "@" + System.identityHashCode(o);
            }
            return o.getClass().getName() + "@" + System.identityHashCode(o) + " [" + truncate(s) + "]";
        } catch (Exception e) {
            return o.getClass().getName() + "@" + System.identityHashCode(o) + " [toString failed]";
        }
    }

    /** Caps an embedded {@code toString()} so a large object can't bloat the diagnostic message. */
    private static String truncate(String s) {
        return s.length() <= MAX_DESC_LEN ? s : s.substring(0, MAX_DESC_LEN) + "…(truncated)";
    }

    // ---------------- getters ----------------

    /**
     * Returns a safe string representation of the object that caused the failure.
     *
     * @return the object description, or null if not set
     */
    public String getObjectIdStr() {
        return objectIdStr;
    }

    /**
     * Returns the identity hash code of the object that caused the failure.
     *
     * @return the identity hash code, or null if not set
     */
    public Integer getObjectIdentityHash() {
        return objectIdentityHash;
    }

    /**
     * Returns the source class type being migrated from.
     *
     * @return the source class, or null if not set
     */
    public Class<?> getFrom() {
        return from;
    }

    /**
     * Returns the target class type being migrated to.
     *
     * @return the target class, or null if not set
     */
    public Class<?> getTo() {
        return to;
    }

    /**
     * Returns the migration stage where the failure occurred.
     *
     * @return the stage name, or null if not set
     */
    public String getStage() {
        return stage;
    }

    // ---------------- diagnostics ----------------

    /**
     * Returns the base message augmented with whatever diagnostic context is available
     * (stage, source/target types, and a safe description of the offending object).
     *
     * @return the message with appended diagnostic details
     */
    @Override
    public String getMessage() {
        String base = super.getMessage();
        StringBuilder sb = new StringBuilder(base != null ? base : "");

        if (stage != null) sb.append(" [stage=").append(stage).append("]");
        if (from != null) sb.append(" [from=").append(from.getName()).append("]");
        if (to != null) sb.append(" [to=").append(to.getName()).append("]");
        if (objectIdStr != null) sb.append(" [object=").append(objectIdStr).append("]");

        return sb.toString();
    }
}
