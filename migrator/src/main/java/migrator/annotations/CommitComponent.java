package migrator.annotations;

import java.lang.annotation.*;

/**
 * Marks a class as the commit manager component.
 *
 * <p>The annotated class must implement or extend {@link migrator.commit.CommitManager}.
 * There must be exactly one class annotated with {@code @CommitComponent} in the
 * classpath. The commit manager is responsible for finalizing the migration
 * by deleting the checkpoint (making rollback impossible).
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}CommitComponent
 * public class AppCommitManager extends CommitManager {
 *     public AppCommitManager() {
 *         super(new MyCracController());
 *     }
 * }
 * </pre>
 *
 * @see migrator.commit.CommitManager
 * @see migrator.scanner.AnnotationScanner
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CommitComponent {
}
