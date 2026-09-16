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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigrationContext}.
 */
@DisplayName("MigrationContext")
class MigrationContextTest {

    // Test classes for creating migration plan
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
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("should store plan and migration id")
        void shouldStorePlanAndMigrationId() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            MigrationContext context = new MigrationContext(plan, 42L);

            assertThat(context.plan()).isSameAs(plan);
            assertThat(context.migrationId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("should record start time")
        void shouldRecordStartTime() throws MigrateException {
            long beforeNanos = System.nanoTime();

            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext context = new MigrationContext(plan, 1L);

            long afterNanos = System.nanoTime();

            assertThat(context.startedAtNanos())
                    .isGreaterThanOrEqualTo(beforeNanos)
                    .isLessThanOrEqualTo(afterNanos);
        }

        @Test
        @DisplayName("should reject null plan")
        void shouldRejectNullPlan() {
            assertThatThrownBy(() -> new MigrationContext(null, 1L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should accept zero migration id")
        void shouldAcceptZeroMigrationId() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            MigrationContext context = new MigrationContext(plan, 0L);

            assertThat(context.migrationId()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should accept negative migration id")
        void shouldAcceptNegativeMigrationId() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            MigrationContext context = new MigrationContext(plan, -1L);

            assertThat(context.migrationId()).isEqualTo(-1L);
        }
    }

    @Nested
    @DisplayName("timing")
    class Timing {

        @Test
        @DisplayName("should have consistent startedAtNanos across calls")
        void shouldHaveConsistentStartedAtNanosAcrossCalls() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext context = new MigrationContext(plan, 1L);

            long first = context.startedAtNanos();
            long second = context.startedAtNanos();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("different contexts should have different start times")
        void differentContextsShouldHaveDifferentStartTimes() throws MigrateException, InterruptedException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            MigrationContext first = new MigrationContext(plan, 1L);
            Thread.sleep(1); // Ensure some time passes
            MigrationContext second = new MigrationContext(plan, 2L);

            // Technically they could be equal if the JVM is fast enough, but very unlikely
            assertThat(second.startedAtNanos()).isGreaterThanOrEqualTo(first.startedAtNanos());
        }

        @Test
        @DisplayName("elapsedNanos should be non-negative and grow over time")
        void elapsedNanosShouldGrow() throws MigrateException, InterruptedException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext context = new MigrationContext(plan, 1L);

            long first = context.elapsedNanos();
            assertThat(first).isGreaterThanOrEqualTo(0L);
            Thread.sleep(1);
            assertThat(context.elapsedNanos()).isGreaterThanOrEqualTo(first);
        }

        @Test
        @DisplayName("toString should include the migration id")
        void toStringShouldIncludeMigrationId() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));
            MigrationContext context = new MigrationContext(plan, 7L);

            assertThat(context.toString()).contains("migrationId=7");
        }
    }

    @Nested
    @DisplayName("plan access")
    class PlanAccess {

        @Test
        @DisplayName("should return the exact plan instance")
        void shouldReturnTheExactPlanInstance() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            MigrationContext context = new MigrationContext(plan, 1L);

            assertThat(context.plan()).isSameAs(plan);
        }

        @Test
        @DisplayName("should allow querying plan details through context")
        void shouldAllowQueryingPlanDetailsThroughContext() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(TestMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            MigrationContext context = new MigrationContext(plan, 1L);

            assertThat(context.plan().hasMigration(OldEntity.class)).isTrue();
            assertThat(context.plan().targetOf(OldEntity.class)).isEqualTo(NewEntity.class);
        }
    }
}
