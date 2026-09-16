package migrator.engine;

import migrator.ClassMigrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.phase.MigrationPhaseListener;
import migrator.smoke.SmokeTestRunner;
import migrator.smoke.SmokeTest;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves and instantiates migration components from scanned annotation results.
 *
 * <p>This class is responsible for:
 * <ul>
 *   <li>Validating that annotated classes implement the correct interfaces</li>
 *   <li>Instantiating components using reflection (requires no-arg constructors)</li>
 *   <li>Building the {@link SmokeTestRunner} from discovered smoke test classes</li>
 * </ul>
 *
 * <p>Used internally by {@link MigrationEngine} during initialization.
 *
 * @see MigrationEngine
 * @see migrator.scanner.AnnotationScanner
 */
public final class ComponentResolver {

    /**
     * Validates and casts a class annotated with {@code @Migrator} to the proper type.
     *
     * @param type the class annotated with {@code @Migrator}
     * @return the class cast to {@code ClassMigrator<?, ?>}
     * @throws IllegalStateException if the class does not implement ClassMigrator
     */
    public Class<? extends ClassMigrator<?, ?>> resolveMigrator(Class<?> type) {
        if (type == null) {
            throw new IllegalStateException("@Migrator class must not be null");
        }
        if (!ClassMigrator.class.isAssignableFrom(type)) {
            throw new IllegalStateException(
                    "@Migrator must implement ClassMigrator<?, ?>: " + type.getName()
            );
        }
        @SuppressWarnings("unchecked")
        Class<? extends ClassMigrator<?, ?>> result =
                (Class<? extends ClassMigrator<?, ?>>) type;
        return result;
    }

    /**
     * Instantiates a phase listener from an annotated class.
     *
     * @param type the class annotated with {@code @PhaseListener}
     * @return an instance of the phase listener
     * @throws IllegalStateException if instantiation fails or class is invalid
     */
    public MigrationPhaseListener resolvePhaseListener(Class<?> type) {
        return instantiate(type, MigrationPhaseListener.class, "@PhaseListener");
    }

    /**
     * Builds a {@link SmokeTestRunner} from a set of smoke test classes.
     *
     * @param smokeTestClasses classes annotated with {@code @SmokeTestComponent}
     * @return a configured smoke test runner
     * @throws IllegalStateException if any class cannot be instantiated
     */
    public SmokeTestRunner resolveSmokeTestRunner(Set<Class<?>> smokeTestClasses) {
        Objects.requireNonNull(smokeTestClasses, "smokeTestClasses");
        SmokeTestRunner.Builder builder = new SmokeTestRunner.Builder();

        for (Class<?> testClass : smokeTestClasses) {
            SmokeTest test = instantiate(testClass, SmokeTest.class, "@SmokeTestComponent");
            builder.addSmokeTest(test);
        }

        return builder.build();
    }

    /**
     * Instantiates a commit manager from an annotated class.
     *
     * @param type the class annotated with {@code @CommitComponent}
     * @return an instance of the commit manager
     * @throws IllegalStateException if instantiation fails or class is invalid
     */
    public CommitManager resolveCommitManager(Class<?> type) {
        return instantiate(type, CommitManager.class, "@CommitComponent");
    }

    /**
     * Instantiates a rollback manager from an annotated class.
     *
     * @param type the class annotated with {@code @RollbackComponent}
     * @return an instance of the rollback manager
     * @throws IllegalStateException if instantiation fails or class is invalid
     */
    public RollbackManager resolveRollbackManager(Class<?> type) {
        return instantiate(type, RollbackManager.class, "@RollbackComponent");
    }

    /**
     * Validates that {@code type} implements {@code expectedInterface} and instantiates it via its
     * (possibly non-public) no-arg constructor.
     *
     * @throws IllegalStateException if the type is incompatible or has no no-arg constructor
     */
    private <T> T instantiate(
            Class<?> type,
            Class<T> expectedInterface,
            String annotationName
    ) {
        if (type == null) {
            throw new IllegalStateException(annotationName + " class must not be null");
        }
        if (!expectedInterface.isAssignableFrom(type)) {
            throw new IllegalStateException(
                    annotationName + " must implement " + expectedInterface.getSimpleName()
                            + ": " + type.getName()
            );
        }

        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return expectedInterface.cast(ctor.newInstance());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    type.getName() + " must have a no-arg constructor", e
            );
        } catch (InvocationTargetException e) {
            // The component's own constructor threw; surface its cause rather than burying it.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(
                    "Failed to instantiate " + type.getName() + ": " + cause, cause
            );
        } catch (Exception e) {
            // ReflectiveOperationException (abstract class, inaccessible ctor, etc.) — the
            // resolvers document IllegalStateException for instantiation failures.
            throw new IllegalStateException(
                    "Failed to instantiate " + type.getName() + ": " + e, e
            );
        }
    }

}
