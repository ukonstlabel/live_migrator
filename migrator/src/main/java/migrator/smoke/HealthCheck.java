package migrator.smoke;

import migrator.exceptions.ValidationException;

/**
 * Functional interface for simple health checks.
 *
 * <p>Health checks verify basic system health after migration, such as:
 * <ul>
 *   <li>Database connectivity</li>
 *   <li>External service availability</li>
 *   <li>Cache integrity</li>
 *   <li>Configuration validity</li>
 * </ul>
 *
 * <p>Unlike {@link SmokeTest}, health checks do not receive migrated objects
 * and are intended for general system verification.
 *
 * <h2>Example:</h2>
 * <pre>
 * HealthCheck dbCheck = () -&gt; {
 *     try (Connection conn = dataSource.getConnection()) {
 *         return conn.isValid(5);
 *     }
 * };
 * </pre>
 *
 * @see SmokeTestRunner
 * @see SmokeTest
 */
@FunctionalInterface
public interface HealthCheck {

    /**
     * Performs a health check.
     *
     * @return true if the system is healthy, false otherwise
     * @throws ValidationException if an unrecoverable error occurs during the check
     */
    boolean check() throws ValidationException;
}
