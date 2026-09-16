package migration;

import migrator.annotations.PhaseListener;
import migrator.phase.MigrationContext;
import migrator.phase.MigrationPhaseListener;
import service.ServiceMain;

/**
 * Quiesces the demo service around the critical phase.
 *
 * <p>Per the {@link MigrationPhaseListener} completeness contract, {@code onBeforeCriticalPhase}
 * must stop the application from creating new source-class instances before it returns. Here that
 * means closing the user-intake gate: while it is closed, {@code POST /users} is rejected, so no new
 * {@code OldUser} can appear during the engine's straggler rescan. The gate is reopened afterwards.
 */
@PhaseListener
public class MigrationPhaseListenerImpl implements MigrationPhaseListener {

    @Override
    public void onBeforeCriticalPhase(MigrationContext ctx) {
        // Close intake first, then let any in-flight creation settle.
        ServiceMain.acceptingUsers.set(false);
        System.out.println("[MIGRATION] Entering critical phase (user intake paused), id=" + ctx.migrationId());
    }

    @Override
    public void onAfterCriticalPhase(MigrationContext ctx) {
        // Idempotent: resuming an already-resumed service is a harmless no-op.
        ServiceMain.acceptingUsers.set(true);
        System.out.println("[MIGRATION] Exiting critical phase (user intake resumed), id=" + ctx.migrationId());
    }
}
