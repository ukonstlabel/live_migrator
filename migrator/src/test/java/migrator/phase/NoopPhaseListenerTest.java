package migrator.phase;

import migrator.ClassMigrator;
import migrator.exceptions.MigrateException;
import migrator.plan.MigrationPlan;
import migrator.plan.MigratorDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link NoopPhaseListener}.
 */
@DisplayName("NoopPhaseListener")
class NoopPhaseListenerTest {

    // Test classes
    interface TestEntity {}
    static class OldEntity implements TestEntity {}
    static class NewEntity implements TestEntity {}

    public static class TestMigrator implements ClassMigrator<OldEntity, NewEntity> {
        @Override
        public NewEntity migrate(OldEntity old) {
            return new NewEntity();
        }
    }

    @Nested
    @DisplayName("singleton pattern")
    class SingletonPattern {

        @Test
        @DisplayName("should be an enum singleton")
        void shouldBeAnEnumSingleton() {
            NoopPhaseListener instance1 = NoopPhaseListener.INSTANCE;
            NoopPhaseListener instance2 = NoopPhaseListener.INSTANCE;

            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("should implement MigrationPhaseListener")
        void shouldImplementMigrationPhaseListener() {
            assertThat(NoopPhaseListener.INSTANCE).isInstanceOf(MigrationPhaseListener.class);
        }
    }

    @Nested
    @DisplayName("onBeforeCriticalPhase")
    class OnBeforeCriticalPhase {

        @Test
        @DisplayName("should not throw")
        void shouldNotThrow() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext ctx = new MigrationContext(plan, 1L);

            assertThatCode(() -> NoopPhaseListener.INSTANCE.onBeforeCriticalPhase(ctx))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle null context")
        void shouldHandleNullContext() {
            assertThatCode(() -> NoopPhaseListener.INSTANCE.onBeforeCriticalPhase(null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("onAfterCriticalPhase")
    class OnAfterCriticalPhase {

        @Test
        @DisplayName("should not throw")
        void shouldNotThrow() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext ctx = new MigrationContext(plan, 1L);

            assertThatCode(() -> NoopPhaseListener.INSTANCE.onAfterCriticalPhase(ctx))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle null context")
        void shouldHandleNullContext() {
            assertThatCode(() -> NoopPhaseListener.INSTANCE.onAfterCriticalPhase(null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("usage in migration")
    class UsageInMigration {

        @Test
        @DisplayName("should be usable as default listener")
        void shouldBeUsableAsDefaultListener() throws MigrateException {
            MigrationPhaseListener listener = NoopPhaseListener.INSTANCE;

            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext ctx = new MigrationContext(plan, 1L);

            // Simulate migration lifecycle calls
            listener.onBeforeCriticalPhase(ctx);
            // ... migration happens ...
            listener.onAfterCriticalPhase(ctx);

            // No assertions needed - just verifying no exceptions
        }

        @Test
        @DisplayName("should be callable multiple times")
        void shouldBeCallableMultipleTimes() throws MigrateException {
            MigrationPhaseListener listener = NoopPhaseListener.INSTANCE;

            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            for (int i = 0; i < 100; i++) {
                MigrationContext ctx = new MigrationContext(plan, i);
                listener.onBeforeCriticalPhase(ctx);
                listener.onAfterCriticalPhase(ctx);
            }
        }
    }
}
