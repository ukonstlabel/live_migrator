package migrator.integration;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.CracController;
import migrator.engine.MigrationTimeoutConfig;
import migrator.engine.TimeoutExecutor;
import migrator.exceptions.MigrateException;
import migrator.exceptions.MigrationTimeoutException;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetricsCollector;
import migrator.phase.MigrationContext;
import migrator.phase.MigrationPhaseListener;
import migrator.plan.MigratorDescriptor;
import migrator.plan.MigrationPlan;
import migrator.smoke.SmokeTestReport;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;
import migrator.state.MigrationState;
import migrator.state.MigrationHistoryEntry;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for migration framework components.
 *
 * <p>These tests verify component interactions without requiring the native JVMTI agent.
 * For full end-to-end tests with actual heap walking, run with:
 * {@code -Dmigration.integration.full=true} and the native agent loaded.
 *
 * <p>Test categories:
 * <ul>
 *   <li>Rollback scenarios (CommitManager, RollbackManager, CracController)</li>
 *   <li>Timeout behavior (TimeoutExecutor, MigrationTimeoutConfig)</li>
 *   <li>Smoke test execution (SmokeTestRunner, SmokeTest, HealthCheck)</li>
 *   <li>Migration state tracking (MigrationState, MigrationHistoryEntry)</li>
 *   <li>Phase listener interactions</li>
 *   <li>Metrics collection</li>
 * </ul>
 */
@DisplayName("Migration Component Integration Tests")
class MigrationEngineIntegrationTest {

    @BeforeEach
    void setUp() {
        MigrationState.getInstance().reset();
    }

    // ==================== Test Model Classes ====================

    /**
     * Common interface for test users (required by MigratorDescriptor).
     */
    public interface TestUser {
        int getId();
        String getName();
    }

    public static class OldTestUser implements TestUser {
        public final int id;
        public final String name;

        public OldTestUser(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public int getId() { return id; }

        @Override
        public String getName() { return name; }
    }

    public static class NewTestUser implements TestUser {
        public final int id;
        public final String name;
        public final String email;

        public NewTestUser(int id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        @Override
        public int getId() { return id; }

        @Override
        public String getName() { return name; }
    }

    // ==================== Test Migrators ====================

    @Migrator
    public static class TestUserMigrator implements ClassMigrator<OldTestUser, NewTestUser> {
        @Override
        public NewTestUser migrate(OldTestUser old) throws MigrateException {
            return new NewTestUser(old.id, old.name, old.name.toLowerCase() + "@test.com");
        }
    }

    // ==================== Mock Components ====================

    static class MockCracController implements CracController {
        private final AtomicBoolean checkpointDeleted = new AtomicBoolean(false);
        private final AtomicBoolean restoreCalled = new AtomicBoolean(false);
        private boolean failOnDelete = false;
        private boolean failOnRestore = false;
        private boolean simulateSuccessfulRestore = false;

        @Override
        public void deleteCheckpoint() throws MigrateException {
            if (failOnDelete) {
                throw new MigrateException("Simulated checkpoint delete failure");
            }
            checkpointDeleted.set(true);
        }

        @Override
        public void restoreFromCheckpoint() throws MigrateException {
            restoreCalled.set(true);
            if (failOnRestore) {
                throw new MigrateException("Simulated restore failure");
            }
            if (!simulateSuccessfulRestore) {
                throw new MigrateException("Mock restore completed (simulated)");
            }
            // If simulateSuccessfulRestore is true, we return normally (which is treated as error by RollbackManager)
        }

        public boolean wasCheckpointDeleted() { return checkpointDeleted.get(); }
        public boolean wasRestoreCalled() { return restoreCalled.get(); }
        public void setFailOnDelete(boolean fail) { this.failOnDelete = fail; }
        public void setFailOnRestore(boolean fail) { this.failOnRestore = fail; }
        public void setSimulateSuccessfulRestore(boolean simulate) { this.simulateSuccessfulRestore = simulate; }

        public void reset() {
            checkpointDeleted.set(false);
            restoreCalled.set(false);
            failOnDelete = false;
            failOnRestore = false;
            simulateSuccessfulRestore = false;
        }
    }

    static class TrackingPhaseListener implements MigrationPhaseListener {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private boolean failOnBeforeCritical = false;
        private boolean failOnAfterCritical = false;
        private long beforeCriticalDelayMs = 0;
        private long afterCriticalDelayMs = 0;

        @Override
        public void onBeforeCriticalPhase(MigrationContext ctx) throws MigrateException {
            events.add("BEFORE_CRITICAL:" + ctx.migrationId());
            if (beforeCriticalDelayMs > 0) {
                try {
                    Thread.sleep(beforeCriticalDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failOnBeforeCritical) {
                throw new MigrateException("Application refused to enter critical phase");
            }
        }

        @Override
        public void onAfterCriticalPhase(MigrationContext ctx) throws MigrateException {
            events.add("AFTER_CRITICAL:" + ctx.migrationId());
            if (afterCriticalDelayMs > 0) {
                try {
                    Thread.sleep(afterCriticalDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failOnAfterCritical) {
                throw new MigrateException("Application failed to resume after critical phase");
            }
        }

        public List<String> getEvents() { return new ArrayList<>(events); }
        public void setFailOnBeforeCritical(boolean fail) { this.failOnBeforeCritical = fail; }
        public void setFailOnAfterCritical(boolean fail) { this.failOnAfterCritical = fail; }
        public void setBeforeCriticalDelayMs(long ms) { this.beforeCriticalDelayMs = ms; }
        public void setAfterCriticalDelayMs(long ms) { this.afterCriticalDelayMs = ms; }
    }

    // ==================== Rollback Scenario Tests ====================

    @Nested
    @DisplayName("Rollback Scenarios")
    class RollbackScenarioTests {

        @Test
        @DisplayName("RollbackManager should call CracController.restoreFromCheckpoint")
        void rollbackManagerShouldCallRestore() {
            MockCracController cracController = new MockCracController();
            RollbackManager rollbackManager = new RollbackManager(cracController);

            assertThatThrownBy(rollbackManager::rollback)
                    .isInstanceOf(MigrateException.class);

            assertThat(cracController.wasRestoreCalled()).isTrue();
        }

        @Test
        @DisplayName("RollbackManager should throw if restore returns normally")
        void rollbackManagerShouldThrowIfRestoreReturnsNormally() {
            MockCracController cracController = new MockCracController();
            cracController.setSimulateSuccessfulRestore(true);
            RollbackManager rollbackManager = new RollbackManager(cracController);

            assertThatThrownBy(rollbackManager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("CRaC restore did not occur");
        }

        @Test
        @DisplayName("RollbackManager should wrap restore failures")
        void rollbackManagerShouldWrapRestoreFailures() {
            MockCracController cracController = new MockCracController();
            cracController.setFailOnRestore(true);
            RollbackManager rollbackManager = new RollbackManager(cracController);

            assertThatThrownBy(rollbackManager::rollback)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Simulated restore failure");
        }

        @Test
        @DisplayName("CommitManager should delete checkpoint on commit")
        void commitManagerShouldDeleteCheckpoint() throws MigrateException {
            MockCracController cracController = new MockCracController();
            CommitManager commitManager = new CommitManager(cracController);

            commitManager.commit();

            assertThat(cracController.wasCheckpointDeleted()).isTrue();
        }

        @Test
        @DisplayName("CommitManager should invoke listener after commit")
        void commitManagerShouldInvokeListener() throws MigrateException {
            MockCracController cracController = new MockCracController();
            AtomicBoolean listenerCalled = new AtomicBoolean(false);

            CommitManager commitManager = new CommitManager(cracController, () -> listenerCalled.set(true));
            commitManager.commit();

            assertThat(listenerCalled.get()).isTrue();
        }

        @Test
        @DisplayName("CommitManager should ignore listener failure")
        void commitManagerShouldIgnoreListenerFailure() throws MigrateException {
            MockCracController cracController = new MockCracController();
            CommitManager commitManager = new CommitManager(cracController, () -> {
                throw new RuntimeException("Listener failed");
            });

            // Should not throw
            assertThatCode(() -> commitManager.commit()).doesNotThrowAnyException();
            assertThat(cracController.wasCheckpointDeleted()).isTrue();
        }

        @Test
        @DisplayName("CommitManager should propagate checkpoint delete failure")
        void commitManagerShouldPropagateDeleteFailure() {
            MockCracController cracController = new MockCracController();
            cracController.setFailOnDelete(true);
            CommitManager commitManager = new CommitManager(cracController);

            assertThatThrownBy(commitManager::commit)
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("checkpoint delete failure");
        }

        @Test
        @DisplayName("Commit and rollback should be mutually exclusive")
        void commitAndRollbackMutuallyExclusive() throws MigrateException {
            MockCracController cracController = new MockCracController();
            CommitManager commitManager = new CommitManager(cracController);
            RollbackManager rollbackManager = new RollbackManager(cracController);

            // Commit first
            commitManager.commit();
            assertThat(cracController.wasCheckpointDeleted()).isTrue();
            assertThat(cracController.wasRestoreCalled()).isFalse();

            // Reset and try rollback
            cracController.reset();
            assertThatThrownBy(rollbackManager::rollback).isInstanceOf(MigrateException.class);
            assertThat(cracController.wasRestoreCalled()).isTrue();
            assertThat(cracController.wasCheckpointDeleted()).isFalse();
        }
    }

    // ==================== Timeout Behavior Tests ====================

    @Nested
    @DisplayName("Timeout Behavior")
    class TimeoutBehaviorTests {

        @Test
        @DisplayName("TimeoutExecutor should return result within timeout")
        void shouldReturnResultWithinTimeout() {
            String result = TimeoutExecutor.executeWithTimeout(
                    "test",
                    Duration.ofSeconds(5),
                    () -> "success"
            );

            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("TimeoutExecutor should throw when timeout exceeded")
        void shouldThrowWhenTimeoutExceeded() {
            assertThatThrownBy(() ->
                    TimeoutExecutor.executeWithTimeout(
                            "slowOp",
                            Duration.ofMillis(50),
                            () -> {
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return "never";
                            }
                    ))
                    .isInstanceOf(MigrationTimeoutException.class)
                    .hasMessageContaining("slowOp")
                    .hasMessageContaining("50 ms");
        }

        @Test
        @DisplayName("TimeoutExecutor should preserve exception info")
        void shouldPreserveExceptionInfo() {
            try {
                TimeoutExecutor.executeWithTimeout(
                        "myOperation",
                        Duration.ofMillis(10),
                        () -> {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        }
                );
                fail("Should have thrown");
            } catch (MigrationTimeoutException e) {
                assertThat(e.getOperation()).isEqualTo("myOperation");
                assertThat(e.getTimeout()).isEqualTo(Duration.ofMillis(10));
            }
        }

        @Test
        @DisplayName("TimeoutExecutor should skip timeout when disabled")
        void shouldSkipTimeoutWhenDisabled() {
            String result = TimeoutExecutor.executeWithTimeout(
                    "test",
                    MigrationTimeoutConfig.NO_TIMEOUT,
                    () -> "noTimeout"
            );

            assertThat(result).isEqualTo("noTimeout");
        }

        @Test
        @DisplayName("MigrationTimeoutConfig builder should set all timeouts")
        void configBuilderShouldSetAllTimeouts() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .allTimeoutsSeconds(30)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.heapSnapshotTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.criticalPhaseTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.smokeTestTimeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("MigrationTimeoutConfig should support individual timeouts")
        void configShouldSupportIndividualTimeouts() {
            MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
                    .heapWalkTimeoutSeconds(60)
                    .heapSnapshotTimeoutSeconds(30)
                    .criticalPhaseTimeoutSeconds(20)
                    .smokeTestTimeoutSeconds(10)
                    .build();

            assertThat(config.heapWalkTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.heapSnapshotTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.criticalPhaseTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(config.smokeTestTimeout()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("TimeoutExecutor should handle concurrent executions")
        void shouldHandleConcurrentExecutions() throws InterruptedException {
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger timeoutCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        TimeoutExecutor.executeWithTimeout(
                                "concurrent-" + index,
                                Duration.ofMillis(100),
                                () -> {
                                    try {
                                        Thread.sleep(index < 5 ? 10 : 500);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "done";
                                }
                        );
                        successCount.incrementAndGet();
                    } catch (MigrationTimeoutException e) {
                        timeoutCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await(5, TimeUnit.SECONDS);
            assertThat(successCount.get()).isEqualTo(5);
            assertThat(timeoutCount.get()).isEqualTo(5);
        }
    }

    // ==================== Smoke Test Execution Tests ====================

    @Nested
    @DisplayName("Smoke Test Execution")
    class SmokeTestExecutionTests {

        @Test
        @DisplayName("SmokeTestRunner should run all tests")
        void shouldRunAllTests() {
            AtomicInteger testCount = new AtomicInteger(0);

            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> {
                        testCount.incrementAndGet();
                        return SmokeTestResult.ok("test1");
                    })
                    .addSmokeTest(created -> {
                        testCount.incrementAndGet();
                        return SmokeTestResult.ok("test2");
                    })
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isTrue();
            assertThat(testCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("SmokeTestRunner should report failure when any test fails")
        void shouldReportFailureWhenAnyTestFails() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok("passing"))
                    .addSmokeTest(created -> SmokeTestResult.fail("failing", "Something went wrong", null))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
        }

        @Test
        @DisplayName("SmokeTestRunner should run health checks before smoke tests")
        void shouldRunHealthChecksFirst() {
            List<String> order = Collections.synchronizedList(new ArrayList<>());

            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> {
                        order.add("health1");
                        return true;
                    })
                    .addHealthCheck(() -> {
                        order.add("health2");
                        return true;
                    })
                    .addSmokeTest(created -> {
                        order.add("smoke1");
                        return SmokeTestResult.ok("smoke1");
                    })
                    .build();

            runner.runAll(Map.of());

            assertThat(order).containsExactly("health1", "health2", "smoke1");
        }

        @Test
        @DisplayName("SmokeTestRunner should handle health check failure")
        void shouldHandleHealthCheckFailure() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addHealthCheck(() -> false)
                    .addSmokeTest(created -> SmokeTestResult.ok("smoke"))
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
        }

        @Test
        @DisplayName("SmokeTestRunner should handle test exception")
        void shouldHandleTestException() {
            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> {
                        throw new RuntimeException("Test exploded");
                    })
                    .build();

            SmokeTestReport report = runner.runAll(Map.of());

            assertThat(report.success()).isFalse();
            assertThat(report.results()).hasSize(1);
            assertThat(report.results().get(0).message()).contains("Test exploded");
        }

        @Test
        @DisplayName("SmokeTestRunner should receive migrated objects")
        void shouldReceiveMigratedObjects() throws MigrateException {
            List<Object> receivedObjects = new ArrayList<>();

            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);
            NewTestUser user1 = new NewTestUser(1, "Alice", "alice@test.com");
            NewTestUser user2 = new NewTestUser(2, "Bob", "bob@test.com");

            Map<MigratorDescriptor, List<Object>> created = Map.of(
                    descriptor, List.of(user1, user2)
            );

            SmokeTestRunner runner = new SmokeTestRunner.Builder()
                    .addSmokeTest(createdPerMigrator -> {
                        createdPerMigrator.values().forEach(receivedObjects::addAll);
                        return SmokeTestResult.ok("validator");
                    })
                    .build();

            runner.runAll(created);

            assertThat(receivedObjects).containsExactly(user1, user2);
        }

        @Test
        @DisplayName("SmokeTestResult should track failure details")
        void smokeTestResultShouldTrackFailureDetails() {
            RuntimeException cause = new RuntimeException("Root cause");
            SmokeTestResult result = SmokeTestResult.fail("validation", "Data integrity check failed", cause);

            assertThat(result.name()).isEqualTo("validation");
            assertThat(result.isOk()).isFalse();
            assertThat(result.message()).isEqualTo("Data integrity check failed");
            assertThat(result.error()).isSameAs(cause);
        }
    }

    // ==================== Migration State Tests ====================

    @Nested
    @DisplayName("Migration State Tracking")
    class MigrationStateTests {

        @Test
        @DisplayName("should start in IDLE state")
        void shouldStartInIdleState() {
            assertThat(MigrationState.getInstance().getStatus())
                    .isEqualTo(MigrationState.Status.IDLE);
        }

        @Test
        @DisplayName("should transition to IN_PROGRESS when started")
        void shouldTransitionToInProgress() {
            MigrationState.getInstance().migrationStarted(1L);

            assertThat(MigrationState.getInstance().getStatus())
                    .isEqualTo(MigrationState.Status.IN_PROGRESS);
            assertThat(MigrationState.getInstance().getCurrentMigrationId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should transition to SUCCESS when completed")
        void shouldTransitionToSuccess() {
            MigrationState.getInstance().migrationStarted(1L);

            MigrationMetrics metrics = createTestMetrics(1L);
            MigrationState.getInstance().migrationCompleted(1L, metrics);

            assertThat(MigrationState.getInstance().getStatus())
                    .isEqualTo(MigrationState.Status.SUCCESS);
            assertThat(MigrationState.getInstance().getLastMetrics()).isEqualTo(metrics);
        }

        @Test
        @DisplayName("should transition to FAILED when error occurs")
        void shouldTransitionToFailed() {
            MigrationState.getInstance().migrationStarted(1L);

            MigrationState.getInstance().migrationFailed(
                    1L, new RuntimeException("Test error"), null);

            assertThat(MigrationState.getInstance().getStatus())
                    .isEqualTo(MigrationState.Status.FAILED);
            assertThat(MigrationState.getInstance().getLastError())
                    .isEqualTo("Test error");
        }

        @Test
        @DisplayName("should track migration history")
        void shouldTrackMigrationHistory() {
            // Run 3 migrations
            for (int i = 1; i <= 3; i++) {
                MigrationState.getInstance().migrationStarted(i);
                MigrationState.getInstance().migrationCompleted(i, createTestMetrics(i));
            }

            List<MigrationHistoryEntry> history = MigrationState.getInstance().getHistory();
            assertThat(history).hasSize(3);
            // Most recent first
            assertThat(history.get(0).migrationId()).isEqualTo(3L);
            assertThat(history.get(2).migrationId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("should limit history size")
        void shouldLimitHistorySize() {
            MigrationState.getInstance().setMaxHistorySize(2);

            for (int i = 1; i <= 5; i++) {
                MigrationState.getInstance().migrationStarted(i);
                MigrationState.getInstance().migrationCompleted(i, createTestMetrics(i));
            }

            List<MigrationHistoryEntry> history = MigrationState.getInstance().getHistory();
            assertThat(history).hasSize(2);
            // Only the last 2
            assertThat(history.get(0).migrationId()).isEqualTo(5L);
            assertThat(history.get(1).migrationId()).isEqualTo(4L);
        }

        @Test
        @DisplayName("should track current phase")
        void shouldTrackCurrentPhase() {
            MigrationState.getInstance().migrationStarted(1L);
            MigrationState.getInstance().setCurrentPhase(MigrationMetrics.Phase.FIRST_PASS);

            assertThat(MigrationState.getInstance().getCurrentPhase())
                    .isEqualTo(MigrationMetrics.Phase.FIRST_PASS);

            MigrationState.getInstance().setCurrentPhase(MigrationMetrics.Phase.CRITICAL_PHASE);
            assertThat(MigrationState.getInstance().getCurrentPhase())
                    .isEqualTo(MigrationMetrics.Phase.CRITICAL_PHASE);
        }

        @Test
        @DisplayName("should clear phase on completion")
        void shouldClearPhaseOnCompletion() {
            MigrationState.getInstance().migrationStarted(1L);
            MigrationState.getInstance().setCurrentPhase(MigrationMetrics.Phase.SMOKE_TEST);
            MigrationState.getInstance().migrationCompleted(1L, createTestMetrics(1L));

            assertThat(MigrationState.getInstance().getCurrentPhase()).isNull();
        }

        @Test
        @DisplayName("toMap should include all relevant fields")
        void toMapShouldIncludeAllFields() {
            MigrationState.getInstance().migrationStarted(42L);
            MigrationState.getInstance().setCurrentPhase(MigrationMetrics.Phase.CRITICAL_PHASE);

            Map<String, Object> map = MigrationState.getInstance().toMap();

            assertThat(map).containsKey("status");
            assertThat(map).containsKey("currentPhase");
            assertThat(map).containsKey("currentMigrationId");
            assertThat(map.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(map.get("currentPhase")).isEqualTo("CRITICAL_PHASE");
            assertThat(map.get("currentMigrationId")).isEqualTo(42L);
        }

        private MigrationMetrics createTestMetrics(long id) {
            return MigrationMetrics.builder()
                    .migrationId(id)
                    .startTime(java.time.Instant.now())
                    .endTime(java.time.Instant.now())
                    .heapBefore(1000000, 2000000, 4000000)
                    .heapAfter(1100000, 2000000, 4000000)
                    .objectsMigrated(100)
                    .objectsPatched(500)
                    .migratorCount(1)
                    .build();
        }
    }

    // ==================== Phase Listener Tests ====================

    @Nested
    @DisplayName("Phase Listener Interactions")
    class PhaseListenerTests {

        @Test
        @DisplayName("Phase listener should receive migration context")
        void phaseListenerShouldReceiveContext() throws MigrateException {
            AtomicBoolean contextReceived = new AtomicBoolean(false);
            long[] receivedId = {0};

            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext ctx = new MigrationContext(plan, 42L);

            MigrationPhaseListener listener = new MigrationPhaseListener() {
                @Override
                public void onBeforeCriticalPhase(MigrationContext context) {
                    contextReceived.set(true);
                    receivedId[0] = context.migrationId();
                }

                @Override
                public void onAfterCriticalPhase(MigrationContext context) {}
            };

            listener.onBeforeCriticalPhase(ctx);

            assertThat(contextReceived.get()).isTrue();
            assertThat(receivedId[0]).isEqualTo(42L);
        }

        @Test
        @DisplayName("TrackingPhaseListener should record phase order")
        void trackingListenerShouldRecordPhaseOrder() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext ctx = new MigrationContext(plan, 1L);

            TrackingPhaseListener listener = new TrackingPhaseListener();

            listener.onBeforeCriticalPhase(ctx);
            listener.onAfterCriticalPhase(ctx);

            assertThat(listener.getEvents())
                    .containsExactly("BEFORE_CRITICAL:1", "AFTER_CRITICAL:1");
        }

        @Test
        @DisplayName("Phase listener timeout should trigger exception")
        void phaseListenerTimeoutShouldTriggerException() throws MigrateException {
            TrackingPhaseListener listener = new TrackingPhaseListener();
            listener.setBeforeCriticalDelayMs(200);

            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext ctx = new MigrationContext(plan, 1L);

            assertThatThrownBy(() ->
                    TimeoutExecutor.executeWithTimeoutChecked(
                            "onBeforeCriticalPhase",
                            Duration.ofMillis(50),
                            () -> listener.onBeforeCriticalPhase(ctx)
                    ))
                    .isInstanceOf(MigrationTimeoutException.class)
                    .hasMessageContaining("onBeforeCriticalPhase");
        }
    }

    // ==================== Metrics Collection Tests ====================

    @Nested
    @DisplayName("Metrics Collection")
    class MetricsCollectionTests {

        @Test
        @DisplayName("MigrationMetricsCollector should track timing")
        void collectorShouldTrackTiming() throws InterruptedException {
            MigrationMetricsCollector collector = new MigrationMetricsCollector();

            collector.start(1L);
            collector.migratorCount(1);
            collector.timed(MigrationMetrics.Phase.FIRST_PASS, () -> {
                Thread.sleep(50);
                return null;
            });

            MigrationMetrics metrics = collector.finish();

            assertThat(metrics.migrationId()).isEqualTo(1L);
            assertThat(metrics.totalDurationMs()).isGreaterThanOrEqualTo(50);
            assertThat(metrics.phaseDuration(MigrationMetrics.Phase.FIRST_PASS))
                    .isGreaterThanOrEqualTo(50);
        }

        @Test
        @DisplayName("MigrationMetrics should calculate heap delta")
        void metricsShouldCalculateHeapDelta() {
            MigrationMetrics metrics = MigrationMetrics.builder()
                    .migrationId(1L)
                    .startTime(java.time.Instant.now())
                    .endTime(java.time.Instant.now())
                    .heapBefore(1000000, 2000000, 4000000)
                    .heapAfter(1100000, 2000000, 4000000)
                    .objectsMigrated(100)
                    .objectsPatched(500)
                    .migratorCount(1)
                    .build();

            // heapDelta should be calculated from memory before/after
            long heapDelta = metrics.heapDelta();
            assertThat(heapDelta).isEqualTo(100000); // 1100000 - 1000000
        }

        @Test
        @DisplayName("MigrationMetrics should generate summary")
        void metricsShouldGenerateSummary() {
            MigrationMetrics metrics = MigrationMetrics.builder()
                    .migrationId(42L)
                    .startTime(java.time.Instant.now())
                    .endTime(java.time.Instant.now())
                    .heapBefore(1000000, 2000000, 4000000)
                    .heapAfter(1000000, 2000000, 4000000)
                    .objectsMigrated(1000)
                    .objectsPatched(5000)
                    .migratorCount(2)
                    .build();

            String summary = metrics.summary();

            assertThat(summary).contains("42");
            assertThat(summary).contains("1000");
        }

        @Test
        @DisplayName("MigrationMetrics toMap should be serializable")
        void metricsToMapShouldBeSerializable() {
            MigrationMetrics metrics = MigrationMetrics.builder()
                    .migrationId(1L)
                    .startTime(java.time.Instant.now())
                    .endTime(java.time.Instant.now())
                    .heapBefore(1000000, 2000000, 4000000)
                    .heapAfter(1000000, 2000000, 4000000)
                    .objectsMigrated(100)
                    .objectsPatched(500)
                    .migratorCount(1)
                    .build();

            Map<String, Object> map = metrics.toMap();

            assertThat(map).containsKey("migrationId");
            assertThat(map).containsKey("objectsMigrated");
            assertThat(map).containsKey("objectsPatched");
            assertThat(map.get("migrationId")).isEqualTo(1L);
        }
    }

    // ==================== MigratorDescriptor Tests ====================

    @Nested
    @DisplayName("Migrator Descriptor")
    class MigratorDescriptorTests {

        @Test
        @DisplayName("should extract type parameters from migrator")
        void shouldExtractTypeParameters() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);

            assertThat(descriptor.from()).isEqualTo(OldTestUser.class);
            assertThat(descriptor.to()).isEqualTo(NewTestUser.class);
            assertThat(descriptor.commonInterface()).isEqualTo(TestUser.class);
        }

        @Test
        @DisplayName("should instantiate migrator")
        void shouldInstantiateMigrator() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);

            assertThat(descriptor.migrator()).isNotNull();
            assertThat(descriptor.migrator()).isInstanceOf(TestUserMigrator.class);
        }

        @Test
        @DisplayName("migrator should perform migration correctly")
        void migratorShouldPerformMigration() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);
            @SuppressWarnings("unchecked")
            ClassMigrator<OldTestUser, NewTestUser> migrator =
                    (ClassMigrator<OldTestUser, NewTestUser>) descriptor.migrator();

            OldTestUser old = new OldTestUser(1, "Alice");
            NewTestUser newUser = migrator.migrate(old);

            assertThat(newUser.getId()).isEqualTo(1);
            assertThat(newUser.getName()).isEqualTo("Alice");
            assertThat(newUser.email).isEqualTo("alice@test.com");
        }
    }

    // ==================== Migration Plan Tests ====================

    @Nested
    @DisplayName("Migration Plan")
    class MigrationPlanTests {

        @Test
        @DisplayName("should build plan from migrator descriptors")
        void shouldBuildPlanFromDescriptors() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestUserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            assertThat(plan.orderedMigrators()).hasSize(1);
            assertThat(plan.orderedMigrators().get(0)).isEqualTo(descriptor);
        }

        @Test
        @DisplayName("should create empty plan from empty list")
        void shouldCreateEmptyPlanFromEmptyList() throws MigrateException {
            MigrationPlan plan = MigrationPlan.build(List.of());

            assertThat(plan.orderedMigrators()).isEmpty();
        }
    }
}
