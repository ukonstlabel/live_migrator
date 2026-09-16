package migrator.crac;

/**
 * Default no-op implementation of {@link CracController}.
 *
 * <p>Use this implementation when:
 * <ul>
 *   <li>CRaC/CRIU is not available on the platform</li>
 *   <li>Checkpoint/restore functionality is not needed</li>
 *   <li>Testing migration without rollback support</li>
 * </ul>
 *
 * <p>Implemented as an enum singleton to ensure:
 * <ul>
 *   <li>Stateless operation</li>
 *   <li>Thread safety</li>
 *   <li>Protection against reflection and serialization attacks</li>
 * </ul>
 *
 * @see CracController
 * @see migrator.commit.CommitManager
 * @see migrator.commit.RollbackManager
 */
public enum NoopCracController implements CracController {
    /** The singleton instance. */
    INSTANCE;

    /**
     * No-op implementation that does nothing.
     *
     * <p>Since no checkpoint was created, there is nothing to delete.
     */
    @Override
    public void deleteCheckpoint() { /* no-op */ }

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * <p>This no-op implementation does not support checkpoint restore.
     * If rollback capability is required, use a real CRaC implementation.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void restoreFromCheckpoint() {
        throw new UnsupportedOperationException("CRaC restore not supported in NoopCracController");
    }
}
