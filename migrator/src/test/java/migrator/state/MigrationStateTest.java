package migrator.state;

import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Hard, borderline-focused tests for the {@link MigrationState} singleton: the global migration
 * state machine, its bounded most-recent-first history, and its thread-safety contract.
 *
 * <p>Basic happy-path transitions are exercised by the engine integration test; this class targets
 * the edge cases and potential bugs: null / null-message failures, history trimming exactly at the
 * limit, {@link MigrationState#setMaxHistorySize} bounds and immediate shrink, snapshot vs. live
 * semantics of {@link MigrationState#getHistory()}, {@code toMap()} null-safety, and concurrent
 * mutation.
 *
 * <p>Because the state is a process-wide singleton, every test {@link MigrationState#reset() resets}
 * it before and after so runs are independent and leave no residue for other suites.
 */
@DisplayName("MigrationState — state machine, history & concurrency")
class MigrationStateTest {

    private final MigrationState state = MigrationState.getInstance();

    @BeforeEach
    void resetBefore() {
        state.reset();
    }

    @AfterEach
    void resetAfter() {
        state.reset();
    }

    private static MigrationMetrics metrics(long id) {
        return MigrationMetrics.builder()
                .migrationId(id)
                .startTime(Instant.now())
                .endTime(Instant.now())
                .heapBefore(1_000_000, 2_000_000, 4_000_000)
                .heapAfter(1_100_000, 2_000_000, 4_000_000)
                .objectsMigrated(10)
                .objectsPatched(20)
                .migratorCount(1)
                .build();
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("singleton & initial state")
    class SingletonAndInitial {

        @Test
        @DisplayName("getInstance always returns the same instance")
        void singleton() {
            assertThat(MigrationState.getInstance()).isSameAs(MigrationState.getInstance());
        }

        @Test
        @DisplayName("a freshly reset state is fully IDLE/empty with the default history size")
        void freshState() {
            assertThat(state.getStatus()).isEqualTo(MigrationState.Status.IDLE);
            assertThat(state.getCurrentPhase()).isNull();
            assertThat(state.getCurrentMigrationId()).isZero();
            assertThat(state.getStartTime()).isNull();
            assertThat(state.getLastMetrics()).isNull();
            assertThat(state.getLastError()).isNull();
            assertThat(state.getHistory()).isEmpty();
            assertThat(state.getMaxHistorySize()).isEqualTo(10);
        }
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("lifecycle transitions")
    class Lifecycle {

        @Test
        @DisplayName("migrationStarted clears phase, error and metrics-era from a prior run")
        void startedClearsPriorState() {
            // Leave residue from an earlier failed run.
            state.migrationStarted(1L);
            state.setCurrentPhase(Phase.SECOND_PASS);
            state.migrationFailed(1L, new RuntimeException("boom"), metrics(1L));
            assertThat(state.getLastError()).isEqualTo("boom");

            state.migrationStarted(2L);

            assertThat(state.getStatus()).isEqualTo(MigrationState.Status.IN_PROGRESS);
            assertThat(state.getCurrentMigrationId()).isEqualTo(2L);
            assertThat(state.getStartTime()).isNotNull();
            assertThat(state.getCurrentPhase()).isNull();
            assertThat(state.getLastError()).isNull();
        }

        @Test
        @DisplayName("setCurrentPhase is observable and migrationCompleted clears it")
        void phaseSetAndCleared() {
            state.migrationStarted(1L);
            state.setCurrentPhase(Phase.CRITICAL_PHASE);
            assertThat(state.getCurrentPhase()).isEqualTo(Phase.CRITICAL_PHASE);

            state.migrationCompleted(1L, metrics(1L));

            assertThat(state.getStatus()).isEqualTo(MigrationState.Status.SUCCESS);
            assertThat(state.getCurrentPhase()).isNull();
            assertThat(state.getLastError()).isNull();
            assertThat(state.getLastMetrics()).isNotNull();
        }

        @Test
        @DisplayName("migrationFailed records the throwable's message and clears the phase")
        void failedRecordsMessage() {
            state.migrationStarted(1L);
            state.setCurrentPhase(Phase.SMOKE_TEST);

            state.migrationFailed(1L, new IllegalStateException("smoke failed"), null);

            assertThat(state.getStatus()).isEqualTo(MigrationState.Status.FAILED);
            assertThat(state.getLastError()).isEqualTo("smoke failed");
            assertThat(state.getCurrentPhase()).isNull();
            assertThat(state.getLastMetrics()).isNull();
        }

        @Test
        @DisplayName("migrationFailed with a null throwable records \"Unknown error\"")
        void failedWithNullThrowable() {
            state.migrationStarted(1L);

            state.migrationFailed(1L, null, null);

            assertThat(state.getLastError()).isEqualTo("Unknown error");
            assertThat(state.getHistory().get(0).errorMessage()).isEqualTo("Unknown error");
        }

        @Test
        @DisplayName("migrationFailed with a null-message throwable records a null error (no NPE)")
        void failedWithNullMessageThrowable() {
            state.migrationStarted(1L);

            // new RuntimeException() has getMessage() == null — must not NPE, lastError stays null.
            state.migrationFailed(1L, new RuntimeException(), null);

            assertThat(state.getLastError()).isNull();
            List<MigrationHistoryEntry> history = state.getHistory();
            assertThat(history).hasSize(1);
            assertThat(history.get(0).status()).isEqualTo(MigrationState.Status.FAILED);
            assertThat(history.get(0).errorMessage()).isNull();
        }
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("history")
    class History {

        @Test
        @DisplayName("entries are stored most-recent-first across mixed outcomes")
        void mostRecentFirst() {
            state.migrationCompleted(1L, metrics(1L));
            state.migrationFailed(2L, new RuntimeException("x"), null);
            state.migrationCompleted(3L, metrics(3L));

            List<MigrationHistoryEntry> history = state.getHistory();
            assertThat(history).hasSize(3);
            assertThat(history.get(0).migrationId()).isEqualTo(3L);
            assertThat(history.get(1).migrationId()).isEqualTo(2L);
            assertThat(history.get(1).status()).isEqualTo(MigrationState.Status.FAILED);
            assertThat(history.get(2).migrationId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("history is trimmed to the default cap of 10, keeping the most recent")
        void trimmedToDefaultCap() {
            for (long i = 1; i <= 15; i++) {
                state.migrationCompleted(i, metrics(i));
            }

            List<MigrationHistoryEntry> history = state.getHistory();
            assertThat(history).hasSize(10);
            // Newest (15) first, oldest retained is 6; 1..5 dropped.
            assertThat(history.get(0).migrationId()).isEqualTo(15L);
            assertThat(history.get(9).migrationId()).isEqualTo(6L);
            assertThat(history).noneMatch(e -> e.migrationId() <= 5L);
        }

        @Test
        @DisplayName("getHistory returns an unmodifiable snapshot decoupled from later mutations")
        void unmodifiableSnapshot() {
            state.migrationCompleted(1L, metrics(1L));
            List<MigrationHistoryEntry> snapshot = state.getHistory();

            // Unmodifiable…
            assertThatThrownBy(() -> snapshot.add(MigrationHistoryEntry.success(99L, metrics(99L))))
                    .isInstanceOf(UnsupportedOperationException.class);

            // …and a point-in-time snapshot: further state changes don't mutate the returned list.
            state.migrationCompleted(2L, metrics(2L));
            assertThat(snapshot).hasSize(1);
            assertThat(state.getHistory()).hasSize(2);
        }
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("setMaxHistorySize")
    class MaxHistorySize {

        @Test
        @DisplayName("rejects zero and negative sizes")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> state.setMaxHistorySize(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> state.setMaxHistorySize(-5))
                    .isInstanceOf(IllegalArgumentException.class);
            // unchanged on rejection
            assertThat(state.getMaxHistorySize()).isEqualTo(10);
        }

        @Test
        @DisplayName("shrinking trims existing history immediately, keeping the most recent")
        void shrinkTrimsImmediately() {
            for (long i = 1; i <= 8; i++) state.migrationCompleted(i, metrics(i));

            state.setMaxHistorySize(3);

            List<MigrationHistoryEntry> history = state.getHistory();
            assertThat(history).hasSize(3);
            assertThat(history.get(0).migrationId()).isEqualTo(8L);
            assertThat(history.get(2).migrationId()).isEqualTo(6L);
        }

        @Test
        @DisplayName("a size of 1 keeps only the newest entry")
        void sizeOfOne() {
            state.setMaxHistorySize(1);
            state.migrationCompleted(1L, metrics(1L));
            state.migrationCompleted(2L, metrics(2L));
            state.migrationCompleted(3L, metrics(3L));

            assertThat(state.getHistory()).hasSize(1);
            assertThat(state.getHistory().get(0).migrationId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("growing the cap lets more entries accumulate")
        void growCap() {
            state.setMaxHistorySize(20);
            for (long i = 1; i <= 15; i++) state.migrationCompleted(i, metrics(i));

            assertThat(state.getHistory()).hasSize(15);
        }
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("toMap")
    class ToMap {

        @Test
        @DisplayName("serializes a null phase / null startTime / no-metrics state without NPE")
        void nullSafe() {
            Map<String, Object> map = state.toMap();

            assertThat(map.get("status")).isEqualTo("IDLE");
            assertThat(map).containsKey("currentPhase");
            assertThat(map.get("currentPhase")).isNull();
            assertThat(map.get("currentMigrationId")).isEqualTo(0L);
            assertThat(map.get("startTime")).isNull();
            assertThat(map.get("lastError")).isNull();
            assertThat(map).doesNotContainKey("lastMigration"); // omitted when metrics are null
            assertThat(map.get("history")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        }

        @Test
        @DisplayName("includes phase name, metrics map and history entries when populated")
        void populated() {
            state.migrationStarted(7L);
            state.setCurrentPhase(Phase.FIRST_PASS);
            state.migrationCompleted(7L, metrics(7L));

            Map<String, Object> map = state.toMap();

            assertThat(map.get("status")).isEqualTo("SUCCESS");
            assertThat(map.get("currentMigrationId")).isEqualTo(7L);
            assertThat(map.get("startTime")).isNotNull();
            assertThat(map).containsKey("lastMigration");
            assertThat(map.get("lastMigration")).isInstanceOf(Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) map.get("history");
            assertThat(history).hasSize(1);
            assertThat(history.get(0).get("migrationId")).isEqualTo(7L);
            assertThat(history.get(0).get("status")).isEqualTo("SUCCESS");
            assertThat(history.get(0).get("timestamp")).isNotNull();
            assertThat(history.get(0).get("errorMessage")).isNull();
        }

        @Test
        @DisplayName("currentPhase is serialized by name while a migration is in progress")
        void phaseNameWhileInProgress() {
            state.migrationStarted(1L);
            state.setCurrentPhase(Phase.CRITICAL_PHASE);

            assertThat(state.toMap().get("currentPhase")).isEqualTo("CRITICAL_PHASE");
        }
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("reset clears everything and restores the default history size")
        void resetRestoresDefaults() {
            state.setMaxHistorySize(3);
            state.migrationStarted(5L);
            state.setCurrentPhase(Phase.SECOND_PASS);
            state.migrationCompleted(5L, metrics(5L));

            state.reset();

            assertThat(state.getStatus()).isEqualTo(MigrationState.Status.IDLE);
            assertThat(state.getCurrentPhase()).isNull();
            assertThat(state.getCurrentMigrationId()).isZero();
            assertThat(state.getStartTime()).isNull();
            assertThat(state.getLastMetrics()).isNull();
            assertThat(state.getLastError()).isNull();
            assertThat(state.getHistory()).isEmpty();
            assertThat(state.getMaxHistorySize()).isEqualTo(10); // default restored, not the 3 we set
        }
    }

    // ----------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent completions never corrupt history or exceed the cap")
        void concurrentCompletions() throws InterruptedException {
            int threads = 16;
            int perThread = 200;
            state.setMaxHistorySize(10);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();

            try {
                for (int t = 0; t < threads; t++) {
                    final int base = t * perThread;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                long id = base + i + 1;
                                state.migrationStarted(id);
                                state.setCurrentPhase(Phase.FIRST_PASS);
                                if (id % 2 == 0) {
                                    state.migrationCompleted(id, metrics(id));
                                } else {
                                    state.migrationFailed(id, new RuntimeException("e" + id), null);
                                }
                                // Concurrent readers must never throw despite ongoing writes.
                                state.getHistory();
                                state.toMap();
                            }
                        } catch (Throwable e) {
                            failures.incrementAndGet();
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(failures.get()).isZero();
            // Invariant: the bounded history is never exceeded, and readers always get a clean list.
            assertThat(state.getHistory()).hasSizeLessThanOrEqualTo(10);
            assertThat(catchThrowable(state::toMap)).isNull();
        }
    }
}
