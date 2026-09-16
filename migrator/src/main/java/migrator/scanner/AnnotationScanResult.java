package migrator.scanner;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable result of scanning the classpath for migration-related annotations.
 *
 * <p>Contains references to the discovered annotated classes:
 * <ul>
 *   <li>{@link #migrator()} - the class annotated with {@link migrator.annotations.Migrator}</li>
 *   <li>{@link #phaseListener()} - the class annotated with {@link migrator.annotations.PhaseListener}</li>
 *   <li>{@link #commitManager()} - the class annotated with {@link migrator.annotations.CommitComponent}</li>
 *   <li>{@link #rollbackManager()} - the class annotated with {@link migrator.annotations.RollbackComponent}</li>
 *   <li>{@link #smokeTests()} - all classes annotated with {@link migrator.annotations.SmokeTestComponent}</li>
 * </ul>
 *
 * <p>This class is typically created by {@link AnnotationScanner} and passed to the
 * migration engine for component instantiation.
 *
 * @see AnnotationScanner
 * @see migrator.engine.ComponentResolver
 */
public final class AnnotationScanResult {

    private final Class<?> migrator;
    private final Class<?> phaseListener;
    private final Class<?> commitManager;
    private final Class<?> rollbackManager;
    private final Set<Class<?>> smokeTests;

    /**
     * Creates a new annotation scan result.
     *
     * @param migrator the class annotated with @Migrator
     * @param phaseListener the class annotated with @PhaseListener
     * @param commitManager the class annotated with @CommitComponent
     * @param rollbackManager the class annotated with @RollbackComponent
     * @param smokeTests all classes annotated with @SmokeTestComponent
     */
    public AnnotationScanResult(
            Class<?> migrator,
            Class<?> phaseListener,
            Class<?> commitManager,
            Class<?> rollbackManager,
            Set<Class<?>> smokeTests
    ) {
        this.migrator = Objects.requireNonNull(migrator, "migrator");
        this.phaseListener = Objects.requireNonNull(phaseListener, "phaseListener");
        this.commitManager = Objects.requireNonNull(commitManager, "commitManager");
        this.rollbackManager = Objects.requireNonNull(rollbackManager, "rollbackManager");
        this.smokeTests = Set.copyOf(Objects.requireNonNull(smokeTests, "smokeTests"));
    }

    /** Returns the class annotated with {@code @Migrator}. */
    public Class<?> migrator() { return migrator; }

    /** Returns the class annotated with {@code @PhaseListener}. */
    public Class<?> phaseListener() { return phaseListener; }

    /** Returns the class annotated with {@code @CommitComponent}. */
    public Class<?> commitManager() { return commitManager; }

    /** Returns the class annotated with {@code @RollbackComponent}. */
    public Class<?> rollbackManager() { return rollbackManager; }

    /** Returns all classes annotated with {@code @SmokeTestComponent}. */
    public Set<Class<?>> smokeTests() { return smokeTests; }
}
