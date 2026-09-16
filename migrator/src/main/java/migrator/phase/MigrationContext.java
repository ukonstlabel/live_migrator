package migrator.phase;

import migrator.plan.MigrationPlan;

import java.util.Objects;

/**
 * Context information delivered to {@link MigrationPhaseListener} callbacks.
 *
 * <p>Provides information about the current migration including:
 * <ul>
 *   <li>The migration plan being executed</li>
 *   <li>A unique migration ID for logging/tracking</li>
 *   <li>Timing information</li>
 * </ul>
 *
 * <p>The context is immutable, but the {@link MigrationPlan} it exposes must be treated as
 * read-only by listeners — mutating it would corrupt shared migration state.
 *
 * @see MigrationPhaseListener
 * @see MigrationPlan
 */
public final class MigrationContext {

    private final MigrationPlan plan;
    private final long migrationId;
    private final long startedAtNanos;

    /**
     * Creates a new migration context.
     *
     * @param plan the migration plan being executed (must not be null)
     * @param migrationId unique identifier for this migration execution
     */
    public MigrationContext(MigrationPlan plan, long migrationId) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.migrationId = migrationId;
        this.startedAtNanos = System.nanoTime();
    }

    /** The migration plan being executed. */
    public MigrationPlan plan() {
        return plan;
    }

    /** Unique identifier for this migration execution. */
    public long migrationId() {
        return migrationId;
    }

    /**
     * The {@link System#nanoTime()} reading captured when this migration started.
     *
     * <p>This is a monotonic value with no defined epoch, meaningful only as a difference
     * (see {@link #elapsedNanos()}); it is not a wall-clock timestamp.
     */
    public long startedAtNanos() {
        return startedAtNanos;
    }

    /** Nanoseconds elapsed since this migration started. */
    public long elapsedNanos() {
        return System.nanoTime() - startedAtNanos;
    }

    @Override
    public String toString() {
        return "MigrationContext{migrationId=" + migrationId
                + ", elapsedMs=" + (elapsedNanos() / 1_000_000) + "}";
    }
}
