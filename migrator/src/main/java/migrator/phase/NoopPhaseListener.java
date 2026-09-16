package migrator.phase;

/**
 * Default no-op implementation used when the user doesn't supply a listener.
 *
 * <p>This implementation does nothing in either phase callback, allowing
 * migrations to proceed without application-level coordination.
 */
public enum NoopPhaseListener implements MigrationPhaseListener {
    /** The singleton instance. */
    INSTANCE;

    @Override
    public void onBeforeCriticalPhase(MigrationContext ctx) { /* no-op */ }

    @Override
    public void onAfterCriticalPhase(MigrationContext ctx) { /* no-op */ }
}
