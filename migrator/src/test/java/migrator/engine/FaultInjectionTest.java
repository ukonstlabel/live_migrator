package migrator.engine;

import migrator.ClassMigrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.CracController;
import migrator.exceptions.MigrateException;
import migrator.heap.HeapWalker;
import migrator.phase.MigrationContext;
import migrator.phase.MigrationPhaseListener;
import migrator.phase.NoopPhaseListener;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;
import migrator.state.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fault-injection / rollback-fidelity tests (P2 #9).
 *
 * <p>Drives the full {@link MigrationEngine#migrate} pipeline deterministically (a fake
 * {@link HeapWalker} injected by reflection — no native agent needed) and injects a fault at a
 * specific phase, then measures the resulting state of a holder reference. The point is to locate
 * the recoverability boundary, which is the in-place <b>second-pass patch</b>:
 * <ul>
 *   <li>faults <b>before</b> the patch (e.g. {@code onBeforeCriticalPhase}) abort cleanly — the
 *       holder still points at the old object;</li>
 *   <li>faults <b>at/after</b> the patch (smoke-test failure, smoke-test timeout) leave the holder
 *       pointing at the migrated object: the reflective patch is not transactional and the only
 *       undo is a process-replacing CRaC restore, which {@link CracController} here cannot do.</li>
 * </ul>
 * It also checks the finalization guarantees: a committed run deletes the checkpoint exactly once
 * and never restores; a failed run never commits.
 */
@DisplayName("Fault injection — rollback fidelity & finalization boundary")
class FaultInjectionTest {

    interface Entity { int id(); }
    static final class OldEntity implements Entity { final int id; OldEntity(int id) { this.id = id; } public int id() { return id; } }
    static final class NewEntity implements Entity { final int id; NewEntity(int id) { this.id = id; } public int id() { return id; } }

    /** Public + no-arg so the engine can instantiate it from the plan. */
    public static final class EntityMigrator implements ClassMigrator<OldEntity, NewEntity> {
        @Override public NewEntity migrate(OldEntity old) { return new NewEntity(old.id); }
    }

    /** Application holder whose reference the engine should patch old → new. */
    static final class Box { Entity ref; Box(Entity e) { this.ref = e; } }

    /** Returns the old instance on snapshot and {box, old} as the patch working set. */
    static final class FakeWalker implements HeapWalker {
        final OldEntity old;
        final Box box;
        FakeWalker(OldEntity old, Box box) { this.old = old; this.box = box; }
        @Override public Object[] snapshotObjects(Class<?> targetClass) {
            return targetClass == OldEntity.class ? new Object[]{old} : new Object[0];
        }
        @Override public Set<Object> walkHeap() { return Collections.emptySet(); }
        @Override public Set<Object> walkHeap(Collection<Class<?>> classes) { return Set.of(box, old); }
    }

    /** Records checkpoint operations; restore throws (no real process restore in a unit test). */
    static final class RecordingCrac implements CracController {
        int deletes = 0, restores = 0;
        @Override public void deleteCheckpoint() { deletes++; }
        @Override public void restoreFromCheckpoint() throws MigrateException {
            restores++;
            throw new MigrateException("no real CRaC restore in test");
        }
    }

    /** Phase listener that can be made to fail before or after the critical phase. */
    static final class FaultyListener implements MigrationPhaseListener {
        boolean failBefore, failAfter;
        @Override public void onBeforeCriticalPhase(MigrationContext ctx) throws MigrateException {
            if (failBefore) throw new MigrateException("injected onBeforeCriticalPhase failure");
        }
        @Override public void onAfterCriticalPhase(MigrationContext ctx) throws MigrateException {
            if (failAfter) throw new MigrateException("injected onAfterCriticalPhase failure");
        }
    }

    private RecordingCrac crac;

    @BeforeEach
    void reset() {
        MigrationState.getInstance().reset();
        crac = new RecordingCrac();
    }

    @AfterEach
    void cleanup() {
        MigrationState.getInstance().reset();
    }

    private MigrationEngine engine(MigrationPhaseListener listener, SmokeTestRunner smoke, Box box, OldEntity old)
            throws Exception {
        MigrationEngine engine = new MigrationEngine(
                EntityMigrator.class,
                listener,
                smoke,
                new CommitManager(crac),
                new RollbackManager(crac));
        Field f = MigrationEngine.class.getDeclaredField("heapWalker");
        f.setAccessible(true);
        f.set(engine, new FakeWalker(old, box));
        return engine;
    }

    private static SmokeTestRunner smokeOk() {
        return new SmokeTestRunner.Builder().addSmokeTest(c -> SmokeTestResult.ok("ok")).build();
    }

    // ── 1. Happy path: commit exactly once, no rollback, state migrated ──────────────────
    @Test
    @DisplayName("success: holder migrated, checkpoint deleted once, never restored")
    void success_commitsOnce() throws Exception {
        OldEntity old = new OldEntity(1);
        Box box = new Box(old);
        engine(NoopPhaseListener.INSTANCE, smokeOk(), box, old).migrate(Set.<Class<?>>of(), null, null);

        assertThat(box.ref).isInstanceOf(NewEntity.class);     // reference patched
        assertThat(crac.deletes).isEqualTo(1);                 // committed exactly once
        assertThat(crac.restores).isZero();                    // never rolled back
        assertThat(MigrationState.getInstance().getStatus()).isEqualTo(MigrationState.Status.SUCCESS);
    }

    // ── 2. Fault BEFORE the patch: clean abort, state untouched ──────────────────────────
    @Test
    @DisplayName("onBeforeCriticalPhase failure: holder UNTOUCHED (old), never committed")
    void faultBeforePatch_leavesStateIntact() throws Exception {
        OldEntity old = new OldEntity(2);
        Box box = new Box(old);
        FaultyListener listener = new FaultyListener();
        listener.failBefore = true;

        assertThatThrownBy(() ->
                engine(listener, smokeOk(), box, old).migrate(Set.<Class<?>>of(), null, null))
                .isInstanceOf(MigrateException.class);

        assertThat(box.ref).isInstanceOf(OldEntity.class);     // patch never ran → state intact
        assertThat(crac.deletes).isZero();                     // not committed
        assertThat(MigrationState.getInstance().getStatus()).isEqualTo(MigrationState.Status.FAILED);
    }

    // ── 3. Smoke-test FAILURE (after patch): state migrated, rollback attempted, no commit ──
    @Test
    @DisplayName("smoke failure: holder MIGRATED (not undone), rollback attempted, never committed")
    void smokeFailure_afterPatch_stateMigrated() throws Exception {
        OldEntity old = new OldEntity(3);
        Box box = new Box(old);
        SmokeTestRunner failing = new SmokeTestRunner.Builder()
                .addSmokeTest(c -> SmokeTestResult.fail("probe", "injected smoke failure", null))
                .build();

        assertThatThrownBy(() ->
                engine(NoopPhaseListener.INSTANCE, failing, box, old).migrate(Set.<Class<?>>of(), null, null))
                .isInstanceOf(MigrateException.class);

        assertThat(box.ref).isInstanceOf(NewEntity.class);     // patched before smoke; NOT restored
        assertThat(crac.restores).isGreaterThanOrEqualTo(1);   // rollback was attempted (CRaC failed)
        assertThat(crac.deletes).isZero();                     // never committed
        assertThat(MigrationState.getInstance().getStatus()).isEqualTo(MigrationState.Status.FAILED);
    }

    // ── 4. Smoke-test TIMEOUT (after patch): state migrated, never committed ─────────────
    @Test
    @DisplayName("smoke timeout: holder MIGRATED, never committed")
    void smokeTimeout_afterPatch_stateMigrated() throws Exception {
        OldEntity old = new OldEntity(4);
        Box box = new Box(old);
        SmokeTestRunner slow = new SmokeTestRunner.Builder()
                .addSmokeTest(c -> {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return SmokeTestResult.ok("slow");
                })
                .build();
        MigrationEngine engine = engine(NoopPhaseListener.INSTANCE, slow, box, old);
        engine.setAllTimeoutsSeconds(1);   // smoke (3s) exceeds the 1s timeout → fault

        assertThatThrownBy(() -> engine.migrate(Set.<Class<?>>of(), null, null))
                .isInstanceOf(Exception.class);

        assertThat(box.ref).isInstanceOf(NewEntity.class);     // patched before smoke timed out
        assertThat(crac.deletes).isZero();                     // never committed
        assertThat(MigrationState.getInstance().getStatus()).isEqualTo(MigrationState.Status.FAILED);
    }
}
