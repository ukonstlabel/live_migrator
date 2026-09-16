package migrator.engine;

import migrator.ClassMigrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;
import migrator.heap.HeapWalker;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the straggler rescan under quiescence: instances of a source class created <em>after</em>
 * the first-pass snapshot but before the application is quiesced must still be migrated, and the
 * per-migrator "created" lists must accumulate across the first pass and the rescan (not be
 * overwritten).
 *
 * <p>The engine's heap walker is replaced with a fake that returns one extra ("straggler") instance
 * on its second snapshot, simulating an object created in the snapshot-to-quiesce window.
 */
@DisplayName("MigrationEngine — straggler rescan under quiescence")
class StragglerRescanTest {

    interface Account {}
    static final class OldAccount implements Account { final int id; OldAccount(int id) { this.id = id; } }
    static final class NewAccount implements Account { final int id; NewAccount(int id) { this.id = id; } }

    static final AtomicInteger migrateCalls = new AtomicInteger();

    /** Public + no-arg so MigratorDescriptor can instantiate it; counts invocations via a static. */
    public static final class AccountMigrator implements ClassMigrator<OldAccount, NewAccount> {
        @Override public NewAccount migrate(OldAccount old) {
            migrateCalls.incrementAndGet();
            return new NewAccount(old.id);
        }
    }

    /** Returns [a1, a2] on the first snapshot and [a1, a2, straggler] on the rescan. */
    static final class StragglerHeapWalker implements HeapWalker {
        final OldAccount a1 = new OldAccount(1);
        final OldAccount a2 = new OldAccount(2);
        final OldAccount straggler = new OldAccount(3);
        int snapshotCalls = 0;

        @Override public Object[] snapshotObjects(Class<?> targetClass) {
            if (targetClass != OldAccount.class) return new Object[0];
            snapshotCalls++;
            return snapshotCalls == 1
                    ? new Object[]{a1, a2}
                    : new Object[]{a1, a2, straggler};
        }

        @Override public Set<Object> walkHeap() { return Collections.emptySet(); }
        @Override public Set<Object> walkHeap(Collection<Class<?>> classes) { return Collections.emptySet(); }
    }

    @BeforeEach
    void reset() {
        migrateCalls.set(0);
        MigrationState.getInstance().reset();
    }

    @AfterEach
    void cleanup() {
        MigrationState.getInstance().reset();
    }

    @Test
    @DisplayName("migrates an instance created after the first-pass snapshot and accumulates created objects")
    void migratesStragglerAndAccumulates() throws Exception {
        AtomicInteger capturedCreatedTotal = new AtomicInteger(-1);

        SmokeTestRunner smoke = new SmokeTestRunner.Builder()
                .addSmokeTest(created -> {
                    capturedCreatedTotal.set(created.values().stream().mapToInt(List::size).sum());
                    return SmokeTestResult.ok("capture");
                })
                .build();

        MigrationEngine engine = new MigrationEngine(
                AccountMigrator.class,
                NoopPhaseListener.INSTANCE,
                smoke,
                new CommitManager(NoopCracController.INSTANCE),
                new RollbackManager(NoopCracController.INSTANCE));

        StragglerHeapWalker fake = new StragglerHeapWalker();
        injectHeapWalker(engine, fake);

        engine.migrate(Set.<Class<?>>of(), null, null);

        // The walker was snapshotted twice: the first pass and the post-quiescence rescan.
        assertThat(fake.snapshotCalls).isEqualTo(2);
        // a1 and a2 are migrated in the first pass; the rescan skips them (forwarding.contains) and
        // migrates only the straggler — three migrate() calls in total.
        assertThat(migrateCalls.get()).isEqualTo(3);
        // The created list accumulated all three new objects (the rescan appended, did not overwrite).
        assertThat(capturedCreatedTotal.get()).isEqualTo(3);
        // Migration completed successfully.
        assertThat(MigrationState.getInstance().getStatus()).isEqualTo(MigrationState.Status.SUCCESS);
    }

    private static void injectHeapWalker(MigrationEngine engine, HeapWalker walker) throws Exception {
        Field f = MigrationEngine.class.getDeclaredField("heapWalker");
        f.setAccessible(true);
        f.set(engine, walker);
    }
}
