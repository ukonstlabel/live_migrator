package migrator.annotations;

import java.lang.annotation.*;

/**
 * Marks a class as the migration phase listener.
 *
 * <p>The annotated class must implement {@link migrator.phase.MigrationPhaseListener}.
 * There must be exactly one class annotated with {@code @PhaseListener} in the
 * classpath. The listener receives callbacks before and after the critical
 * migration phase, allowing the application to quiesce and resume operations.
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}PhaseListener
 * public class AppPhaseListener implements MigrationPhaseListener {
 *     public void onBeforeCriticalPhase(MigrationContext ctx) {
 *         // Pause request processing, drain queues
 *     }
 *     public void onAfterCriticalPhase(MigrationContext ctx) {
 *         // Resume normal operation
 *     }
 * }
 * </pre>
 *
 * @see migrator.phase.MigrationPhaseListener
 * @see migrator.scanner.AnnotationScanner
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PhaseListener {
}
