package migrator.smoke;

import java.util.List;
import java.util.Map;

import migrator.exceptions.ValidationException;
import migrator.plan.MigratorDescriptor;

/**
 * Functional interface for implementing post-migration smoke tests.
 *
 * <p>Smoke tests validate that migrated objects are functioning correctly
 * after the migration completes. They receive a map of all newly created
 * objects grouped by their migrator, allowing tests to verify the migration
 * results.
 *
 * <p>Implementations should be:
 * <ul>
 *   <li><b>Fast</b> - avoid expensive operations that block migration completion</li>
 *   <li><b>Deterministic</b> - produce consistent results for the same input</li>
 *   <li><b>Side-effect free</b> - avoid modifying external state</li>
 * </ul>
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}SmokeTestComponent
 * public class UserValidationTest implements SmokeTest {
 *     {@literal @}Override
 *     public SmokeTestResult run(Map&lt;MigratorDescriptor, List&lt;Object&gt;&gt; created) {
 *         for (var entry : created.entrySet()) {
 *             for (Object obj : entry.getValue()) {
 *                 if (obj instanceof User user &amp;&amp; user.getEmail() == null) {
 *                     return SmokeTestResult.fail("user-validation", "User has null email", null);
 *                 }
 *             }
 *         }
 *         return SmokeTestResult.ok("user-validation");
 *     }
 * }
 * </pre>
 *
 * @see SmokeTestResult
 * @see SmokeTestRunner
 * @see migrator.annotations.SmokeTestComponent
 */
@FunctionalInterface
public interface SmokeTest {

    /**
     * Execute the smoke test against the migrated objects.
     *
     * @param createdPerMigrator mapping from migrator descriptor to the list of
     *                           newly created objects produced by that migrator
     * @return a result indicating pass or fail with optional details
     * @throws ValidationException if an unrecoverable error occurs during validation
     */
    SmokeTestResult run(Map<MigratorDescriptor, List<Object>> createdPerMigrator) throws ValidationException;
}
