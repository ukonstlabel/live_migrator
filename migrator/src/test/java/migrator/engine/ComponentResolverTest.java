package migrator.engine;

import migrator.ClassMigrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.CracController;
import migrator.phase.MigrationContext;
import migrator.phase.MigrationPhaseListener;
import migrator.plan.MigratorDescriptor;
import migrator.smoke.SmokeTest;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ComponentResolver}.
 */
@DisplayName("ComponentResolver")
class ComponentResolverTest {

    private ComponentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ComponentResolver();
    }

    // Test classes for migrator resolution
    interface TestEntity {}
    static class OldEntity implements TestEntity {}
    static class NewEntity implements TestEntity {}

    public static class ValidMigrator implements ClassMigrator<OldEntity, NewEntity> {
        @Override
        public NewEntity migrate(OldEntity old) {
            return new NewEntity();
        }
    }

    public static class InvalidMigrator {
        // Does not implement ClassMigrator
    }

    @Nested
    @DisplayName("resolveMigrator")
    class ResolveMigrator {

        @Test
        @DisplayName("should resolve valid migrator class")
        void shouldResolveValidMigratorClass() {
            Class<? extends ClassMigrator<?, ?>> result = resolver.resolveMigrator(ValidMigrator.class);

            assertThat(result).isEqualTo(ValidMigrator.class);
        }

        @Test
        @DisplayName("should throw for non-migrator class")
        void shouldThrowForNonMigratorClass() {
            assertThatThrownBy(() -> resolver.resolveMigrator(InvalidMigrator.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Migrator must implement ClassMigrator");
        }
    }

    // Test classes for phase listener resolution
    public static class ValidPhaseListener implements MigrationPhaseListener {
        @Override
        public void onBeforeCriticalPhase(MigrationContext ctx) {}

        @Override
        public void onAfterCriticalPhase(MigrationContext ctx) {}
    }

    public static class InvalidPhaseListener {
        // Does not implement MigrationPhaseListener
    }

    public static class NoArgCtorPhaseListener implements MigrationPhaseListener {
        private NoArgCtorPhaseListener(String arg) {} // Not a no-arg constructor

        @Override
        public void onBeforeCriticalPhase(MigrationContext ctx) {}

        @Override
        public void onAfterCriticalPhase(MigrationContext ctx) {}
    }

    public static class ThrowingCtorPhaseListener implements MigrationPhaseListener {
        public ThrowingCtorPhaseListener() {
            throw new RuntimeException("Constructor failed");
        }

        @Override
        public void onBeforeCriticalPhase(MigrationContext ctx) {}

        @Override
        public void onAfterCriticalPhase(MigrationContext ctx) {}
    }

    @Nested
    @DisplayName("resolvePhaseListener")
    class ResolvePhaseListener {

        @Test
        @DisplayName("should instantiate valid phase listener")
        void shouldInstantiateValidPhaseListener() {
            MigrationPhaseListener result = resolver.resolvePhaseListener(ValidPhaseListener.class);

            assertThat(result).isInstanceOf(ValidPhaseListener.class);
        }

        @Test
        @DisplayName("should throw for non-phase-listener class")
        void shouldThrowForNonPhaseListenerClass() {
            assertThatThrownBy(() -> resolver.resolvePhaseListener(InvalidPhaseListener.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@PhaseListener must implement MigrationPhaseListener");
        }

        @Test
        @DisplayName("should throw for class without no-arg constructor")
        void shouldThrowForClassWithoutNoArgConstructor() {
            assertThatThrownBy(() -> resolver.resolvePhaseListener(NoArgCtorPhaseListener.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must have a no-arg constructor");
        }

        @Test
        @DisplayName("should throw when constructor throws, surfacing the cause")
        void shouldThrowWhenConstructorThrows() {
            assertThatThrownBy(() -> resolver.resolvePhaseListener(ThrowingCtorPhaseListener.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to instantiate")
                    .hasMessageContaining("Constructor failed")
                    .cause().isInstanceOf(RuntimeException.class).hasMessage("Constructor failed");
        }

        @Test
        @DisplayName("should reject a null phase listener class")
        void shouldRejectNullClass() {
            assertThatThrownBy(() -> resolver.resolvePhaseListener(null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // Test classes for smoke test resolution
    public static class ValidSmokeTest implements SmokeTest {
        @Override
        public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
            return SmokeTestResult.ok("valid");
        }
    }

    public static class AnotherSmokeTest implements SmokeTest {
        @Override
        public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
            return SmokeTestResult.ok("another");
        }
    }

    public static class InvalidSmokeTest {
        // Does not implement SmokeTest
    }

    public static class ThrowingCtorSmokeTest implements SmokeTest {
        public ThrowingCtorSmokeTest() {
            throw new RuntimeException("Smoke test constructor failed");
        }

        @Override
        public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
            return SmokeTestResult.ok("never");
        }
    }

    public static class NoArgCtorSmokeTest implements SmokeTest {
        private NoArgCtorSmokeTest(String arg) {} // Not a no-arg constructor

        @Override
        public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
            return SmokeTestResult.ok("never");
        }
    }

    @Nested
    @DisplayName("resolveSmokeTestRunner")
    class ResolveSmokeTestRunner {

        @Test
        @DisplayName("should build runner with single smoke test")
        void shouldBuildRunnerWithSingleSmokeTest() {
            SmokeTestRunner runner = resolver.resolveSmokeTestRunner(Set.of(ValidSmokeTest.class));

            assertThat(runner).isNotNull();

            var report = runner.runAll(Map.of());
            assertThat(report.success()).isTrue();
            assertThat(report.results()).hasSize(1);
        }

        @Test
        @DisplayName("should build runner with multiple smoke tests")
        void shouldBuildRunnerWithMultipleSmokeTests() {
            SmokeTestRunner runner = resolver.resolveSmokeTestRunner(
                    Set.of(ValidSmokeTest.class, AnotherSmokeTest.class)
            );

            var report = runner.runAll(Map.of());
            assertThat(report.success()).isTrue();
            assertThat(report.results()).hasSize(2);
        }

        @Test
        @DisplayName("should build empty runner for empty set")
        void shouldBuildEmptyRunnerForEmptySet() {
            SmokeTestRunner runner = resolver.resolveSmokeTestRunner(Set.of());

            var report = runner.runAll(Map.of());
            assertThat(report.success()).isTrue();
            assertThat(report.results()).isEmpty();
        }

        @Test
        @DisplayName("should throw for non-smoke-test class")
        void shouldThrowForNonSmokeTestClass() {
            assertThatThrownBy(() -> resolver.resolveSmokeTestRunner(Set.of(InvalidSmokeTest.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@SmokeTestComponent must implement SmokeTest");
        }

        @Test
        @DisplayName("should throw when smoke test constructor throws, surfacing the cause")
        void shouldThrowWhenSmokeTestConstructorThrows() {
            assertThatThrownBy(() -> resolver.resolveSmokeTestRunner(Set.of(ThrowingCtorSmokeTest.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to instantiate")
                    .cause().hasMessage("Smoke test constructor failed");
        }

        @Test
        @DisplayName("should give the no-arg-constructor message for a smoke test missing one")
        void shouldThrowNoArgMessageForSmokeTestWithoutNoArgCtor() {
            assertThatThrownBy(() -> resolver.resolveSmokeTestRunner(Set.of(NoArgCtorSmokeTest.class)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must have a no-arg constructor");
        }

        @Test
        @DisplayName("should reject a null smoke test set")
        void shouldRejectNullSet() {
            assertThatThrownBy(() -> resolver.resolveSmokeTestRunner(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // Test classes for commit manager resolution
    public static class TestCracController implements CracController {
        @Override
        public void restoreFromCheckpoint() {}

        @Override
        public void deleteCheckpoint() {}
    }

    public static class ValidCommitManager extends CommitManager {
        public ValidCommitManager() {
            super(new TestCracController());
        }
    }

    public static class InvalidCommitManager {
        // Does not extend CommitManager
    }

    @Nested
    @DisplayName("resolveCommitManager")
    class ResolveCommitManager {

        @Test
        @DisplayName("should instantiate valid commit manager")
        void shouldInstantiateValidCommitManager() {
            CommitManager result = resolver.resolveCommitManager(ValidCommitManager.class);

            assertThat(result).isInstanceOf(ValidCommitManager.class);
        }

        @Test
        @DisplayName("should throw for non-commit-manager class")
        void shouldThrowForNonCommitManagerClass() {
            assertThatThrownBy(() -> resolver.resolveCommitManager(InvalidCommitManager.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@CommitComponent must implement CommitManager");
        }
    }

    // Test classes for rollback manager resolution
    public static class ValidRollbackManager extends RollbackManager {
        public ValidRollbackManager() {
            super(new TestCracController());
        }
    }

    public static class InvalidRollbackManager {
        // Does not extend RollbackManager
    }

    @Nested
    @DisplayName("resolveRollbackManager")
    class ResolveRollbackManager {

        @Test
        @DisplayName("should instantiate valid rollback manager")
        void shouldInstantiateValidRollbackManager() {
            RollbackManager result = resolver.resolveRollbackManager(ValidRollbackManager.class);

            assertThat(result).isInstanceOf(ValidRollbackManager.class);
        }

        @Test
        @DisplayName("should throw for non-rollback-manager class")
        void shouldThrowForNonRollbackManagerClass() {
            assertThatThrownBy(() -> resolver.resolveRollbackManager(InvalidRollbackManager.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@RollbackComponent must implement RollbackManager");
        }
    }

    @Nested
    @DisplayName("private constructor accessibility")
    class PrivateConstructorAccessibility {

        private static class PrivateCtorPhaseListener implements MigrationPhaseListener {
            private PrivateCtorPhaseListener() {}

            @Override
            public void onBeforeCriticalPhase(MigrationContext ctx) {}

            @Override
            public void onAfterCriticalPhase(MigrationContext ctx) {}
        }

        @Test
        @DisplayName("should instantiate class with private constructor")
        void shouldInstantiateClassWithPrivateConstructor() {
            MigrationPhaseListener result = resolver.resolvePhaseListener(PrivateCtorPhaseListener.class);

            assertThat(result).isInstanceOf(PrivateCtorPhaseListener.class);
        }
    }
}
