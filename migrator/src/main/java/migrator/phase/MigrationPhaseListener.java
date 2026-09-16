package migrator.phase;

import migrator.exceptions.MigrateException;

/**
 * Listener that receives signals about migration phases that require
 * application-level actions (for example: stop accepting requests,
 * drain threadpools, flush caches, etc).
 *
 * <p>Note: these methods are <em>signals</em>, not commands. The engine itself
 * does not perform pause/resume. Implementations are responsible for
 * performing the required actions synchronously and reliably.
 *
 * <h2>Usage:</h2>
 * <pre>
 * {@literal @}PhaseListener
 * public class MyPhaseListener implements MigrationPhaseListener {
 *     public void onBeforeCriticalPhase(MigrationContext ctx) {
 *         // Stop accepting requests, drain work queues
 *     }
 *     public void onAfterCriticalPhase(MigrationContext ctx) {
 *         // Resume normal operation
 *     }
 * }
 * </pre>
 *
 * @see MigrationContext
 * @see migrator.annotations.PhaseListener
 */
public interface MigrationPhaseListener {

    /**
     * Called before entering the critical phase when references will be patched
     * and registries updated. The application SHOULD be in a safe state (mutations
     * quiesced) when this method returns.
     *
     * <p><b>Completeness contract.</b> The first-pass snapshot of source-class instances is taken
     * <em>before</em> this callback, while the application is still live, so any instance created
     * between the snapshot and quiescence would otherwise be missed. To guarantee that
     * <em>all</em> instances are migrated, this method MUST, before it returns, stop the application
     * from creating new instances of any source class (e.g. close the relevant factories / request
     * intake). The engine then performs a <em>straggler rescan</em> under quiescence to migrate any
     * instances created during that window. If the application keeps producing source-class
     * instances after this returns, those instances will not be migrated.
     *
     * <p>Implementations may throw an exception to refuse the migration.
     *
     * @param ctx migration context
     * @throws MigrateException if the migration should be aborted
     */
    void onBeforeCriticalPhase(MigrationContext ctx) throws MigrateException;

    /**
     * Called after the critical phase to resume normal operation. Invoked whenever
     * {@link #onBeforeCriticalPhase} was entered — including when it threw partway through, and
     * when the migration failed — so that anything quiesced in the "before" callback is always
     * released.
     *
     * <p><b>At-least-once / idempotency:</b> on the failure path this may be invoked more than
     * once (e.g. once during normal teardown and again from the engine's safety-net finally if the
     * first attempt threw). Implementations MUST be idempotent: resuming an already-resumed
     * application must be a harmless no-op.
     *
     * <p>Implementations should resume normal operation when this returns.
     *
     * @param ctx migration context
     * @throws MigrateException if resuming fails
     */
    void onAfterCriticalPhase(MigrationContext ctx) throws MigrateException;
}
