package migrator.commit;

import migrator.crac.CracController;
import migrator.exceptions.MigrateException;

import java.util.Objects;

/**
 * Manages rollback by restoring from checkpoint via {@link CracController}.
 *
 * <p>The rollback manager is invoked when migration fails (e.g., smoke test failure,
 * timeout, or exception during migration). It attempts to restore the application
 * to its pre-migration state using CRaC checkpoint restore.
 *
 * <p><strong>Warning:</strong> The {@link #rollback()} method may not return
 * if the checkpoint restore replaces the current process. When restore succeeds,
 * execution resumes from the checkpoint, and the caller's stack is discarded.
 *
 * <p>Annotate your implementation with {@link migrator.annotations.RollbackComponent}
 * for automatic discovery during classpath scanning.
 *
 * <p><strong>Note:</strong> rollback is only as capable as the injected
 * {@link CracController}. Backed by {@link migrator.crac.NoopCracController} (no checkpoint
 * support), {@link #rollback()} always fails — there is nothing to restore.
 *
 * @see CommitManager
 * @see CracController
 * @see migrator.annotations.RollbackComponent
 */
public class RollbackManager {

    private final CracController cracController;

    /**
     * Creates a new rollback manager.
     *
     * @param cracController the CRaC controller for checkpoint operations (must not be null)
     */
    public RollbackManager(CracController cracController) {
        this.cracController = Objects.requireNonNull(cracController);
    }

    /**
     * Attempt to restore from checkpoint.
     *
     * <p><strong>Warning:</strong> This method may not return if the restore
     * replaces the current process. If it does return, it throws an exception.
     *
     * @throws MigrateException if rollback fails
     */
    public void rollback() throws MigrateException {
        try {
            cracController.restoreFromCheckpoint();
            // If restoreFromCheckpoint returns normally, that means the implementation didn't
            // actually restore the process (rare). We consider that an error in this context.
            throw new MigrateException("CRaC restore did not occur (restoreFromCheckpoint returned)");
        } catch (MigrateException me) {
            throw me;
        } catch (Exception e) {
            throw new MigrateException("Rollback failed: " + e, e);
        }
    }
}
