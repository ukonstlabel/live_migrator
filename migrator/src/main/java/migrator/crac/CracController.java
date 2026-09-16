package migrator.crac;

import migrator.exceptions.MigrateException;

/**
 * Abstraction over CRaC (Coordinated Restore at Checkpoint) operations.
 *
 * <p>This interface provides checkpoint and restore functionality used for
 * rollback support during migration. Implementations can use:
 * <ul>
 *   <li>OpenJDK CRaC for full process checkpointing</li>
 *   <li>CRIU (Checkpoint/Restore in Userspace)</li>
 *   <li>Platform-specific checkpointing mechanisms</li>
 *   <li>{@link NoopCracController} for environments without checkpoint support</li>
 * </ul>
 *
 * <p><strong>Warning:</strong> The semantics of {@link #restoreFromCheckpoint()}
 * are platform-dependent. The method may:
 * <ul>
 *   <li>Replace the current process entirely (not returning)</li>
 *   <li>Throw an exception if restore is not supported</li>
 *   <li>Return normally if restore fails (which is then treated as an error)</li>
 * </ul>
 *
 * @see NoopCracController
 * @see migrator.commit.CommitManager
 * @see migrator.commit.RollbackManager
 */
public interface CracController {

    /**
     * Deletes or commits the prepared checkpoint.
     *
     * <p>After calling this method, rollback via checkpoint restore is no longer
     * possible. This is called by {@link migrator.commit.CommitManager} after
     * successful migration to finalize the changes.
     *
     * <p>Implementations should perform best-effort deletion. Non-critical
     * cleanup failures may be logged but should not throw exceptions.
     *
     * @throws MigrateException if a fatal error prevents checkpoint deletion
     */
    void deleteCheckpoint() throws MigrateException;

    /**
     * Restores the application state from a checkpoint.
     *
     * <p><strong>Warning:</strong> This method may not return. When checkpoint
     * restore succeeds, the current process state is replaced with the
     * checkpointed state. Execution resumes from the checkpoint, and this
     * method call never returns to the caller.
     *
     * <p>If restore is not supported by the implementation, this method
     * should throw an exception rather than silently failing.
     *
     * @throws MigrateException if restore fails or is not supported
     * @throws UnsupportedOperationException if the platform doesn't support restore
     */
    void restoreFromCheckpoint() throws MigrateException;
}
