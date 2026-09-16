package migrator.config;

/**
 * Exception thrown when migration configuration cannot be loaded or is invalid.
 *
 * <p>This exception is thrown when:
 * <ul>
 *   <li>No configuration file is found on the classpath</li>
 *   <li>Configuration file cannot be parsed (invalid YAML/properties syntax)</li>
 *   <li>Configuration values are invalid (e.g., negative timeouts)</li>
 *   <li>Required configuration properties are missing</li>
 * </ul>
 *
 * <p>This is an unchecked exception to allow configuration loading to be
 * integrated into initialization code without forced exception handling.
 *
 * @see MigrationConfigLoader
 * @see MigrationConfig
 */
public class MigrationConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new configuration exception with the specified message.
     *
     * @param message a description of the configuration problem
     */
    public MigrationConfigException(String message) {
        super(message);
    }

    /**
     * Creates a new configuration exception with the specified message and cause.
     *
     * @param message a description of the configuration problem
     * @param cause the underlying cause (e.g., IOException, YAML parse error)
     */
    public MigrationConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
