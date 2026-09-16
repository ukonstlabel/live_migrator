package migrator.annotations;

import java.lang.annotation.*;

/**
 * Marks a class as the rollback manager component.
 *
 * <p>The annotated class must implement or extend {@link migrator.commit.RollbackManager}.
 * There must be exactly one class annotated with {@code @RollbackComponent} in the
 * classpath. The rollback manager is responsible for restoring the application
 * to its pre-migration state if migration fails.
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}RollbackComponent
 * public class AppRollbackManager extends RollbackManager {
 *     public AppRollbackManager() {
 *         super(new MyCracController());
 *     }
 * }
 * </pre>
 *
 * @see migrator.commit.RollbackManager
 * @see migrator.scanner.AnnotationScanner
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RollbackComponent {
}
