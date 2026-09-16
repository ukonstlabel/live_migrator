package migrator.annotations;

import java.lang.annotation.*;

/**
 * Marks a class as the primary migrator for a live migration.
 *
 * <p>The annotated class must implement {@link migrator.ClassMigrator}.
 * There must be exactly one class annotated with {@code @Migrator} in the
 * classpath; multiple or missing annotated classes will cause an error
 * during annotation scanning.
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}Migrator
 * public class UserV1ToV2Migrator implements ClassMigrator&lt;UserV1, UserV2&gt; {
 *     public UserV2 migrate(UserV1 old) {
 *         return new UserV2(old.getName(), old.getEmail());
 *     }
 * }
 * </pre>
 *
 * @see migrator.ClassMigrator
 * @see migrator.scanner.AnnotationScanner
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Migrator {
}
