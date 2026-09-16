package migrator.annotations;

import java.lang.annotation.*;

/**
 * Marks a class as a smoke test component for migration validation.
 *
 * <p>The annotated class must implement {@link migrator.smoke.SmokeTest}.
 * Unlike other migration annotations, multiple classes can be annotated
 * with {@code @SmokeTestComponent}. All discovered smoke tests will be
 * executed after migration to validate the migrated objects.
 *
 * <p>At least one smoke test component must be present in the classpath.
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}SmokeTestComponent
 * public class UserMigrationSmokeTest implements SmokeTest {
 *     public SmokeTestResult run(Map&lt;MigratorDescriptor, List&lt;Object&gt;&gt; created) {
 *         // Validate migrated users
 *         return SmokeTestResult.ok("user-validation");
 *     }
 * }
 * </pre>
 *
 * @see migrator.smoke.SmokeTest
 * @see migrator.scanner.AnnotationScanner
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SmokeTestComponent {
}
