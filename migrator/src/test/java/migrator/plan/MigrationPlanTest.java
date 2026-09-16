package migrator.plan;

import migrator.ClassMigrator;
import migrator.exceptions.MigrateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigrationPlan}.
 */
@DisplayName("MigrationPlan")
class MigrationPlanTest {

    // Test interfaces and classes
    interface User {
        int getId();
    }

    interface Entity {
        int getId();
    }

    static class OldUser implements User {
        private final int id;
        OldUser(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class NewUser implements User {
        private final int id;
        NewUser(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class OldEntity implements Entity {
        private final int id;
        OldEntity(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class NewEntity implements Entity {
        private final int id;
        NewEntity(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    // Migrators
    public static class UserMigrator implements ClassMigrator<OldUser, NewUser> {
        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId());
        }
    }

    public static class EntityMigrator implements ClassMigrator<OldEntity, NewEntity> {
        @Override
        public NewEntity migrate(OldEntity old) {
            return new NewEntity(old.getId());
        }
    }

    // Duplicate migrator for same source
    public static class AnotherUserMigrator implements ClassMigrator<OldUser, NewUser> {
        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId() * 2);
        }
    }

    // ----- fixtures for duplicate-target: two distinct sources migrate to the SAME target -----
    interface Shared { }
    static class SrcA implements Shared { }
    static class SrcB implements Shared { }
    static class SharedTarget implements Shared { }

    public static class SrcAToTarget implements ClassMigrator<SrcA, SharedTarget> {
        @Override public SharedTarget migrate(SrcA old) { return new SharedTarget(); }
    }

    public static class SrcBToTarget implements ClassMigrator<SrcB, SharedTarget> {
        @Override public SharedTarget migrate(SrcB old) { return new SharedTarget(); }
    }

    @Nested
    @DisplayName("build")
    class Build {

        @Test
        @DisplayName("should create plan with single migrator")
        void shouldCreatePlanWithSingleMigrator() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            assertThat(plan.hasMigration(OldUser.class)).isTrue();
            assertThat(plan.migratorFor(OldUser.class)).isEqualTo(descriptor);
            assertThat(plan.targetOf(OldUser.class)).isEqualTo(NewUser.class);
            assertThat(plan.orderedMigrators()).hasSize(1);
        }

        @Test
        @DisplayName("should create plan with multiple migrators")
        void shouldCreatePlanWithMultipleMigrators() throws MigrateException {
            MigratorDescriptor userDesc = new MigratorDescriptor(UserMigrator.class);
            MigratorDescriptor entityDesc = new MigratorDescriptor(EntityMigrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(userDesc, entityDesc));

            assertThat(plan.hasMigration(OldUser.class)).isTrue();
            assertThat(plan.hasMigration(OldEntity.class)).isTrue();
            assertThat(plan.orderedMigrators()).hasSize(2);
        }

        @Test
        @DisplayName("should create empty plan")
        void shouldCreateEmptyPlan() throws MigrateException {
            MigrationPlan plan = MigrationPlan.build(List.of());

            assertThat(plan.orderedMigrators()).isEmpty();
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("should reject duplicate migrators for same source")
        void shouldRejectDuplicateMigratorsForSameSource() {
            MigratorDescriptor desc1 = new MigratorDescriptor(UserMigrator.class);
            MigratorDescriptor desc2 = new MigratorDescriptor(AnotherUserMigrator.class);

            assertThatThrownBy(() -> MigrationPlan.build(List.of(desc1, desc2)))
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Duplicate migrator for source class");
        }

        @Test
        @DisplayName("should reject null descriptors list")
        void shouldRejectNullDescriptorsList() {
            assertThatThrownBy(() -> MigrationPlan.build(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject a null descriptor element in the list")
        void shouldRejectNullDescriptorElement() {
            List<MigratorDescriptor> withNull = new java.util.ArrayList<>();
            withNull.add(new MigratorDescriptor(UserMigrator.class));
            withNull.add(null);

            assertThatThrownBy(() -> MigrationPlan.build(withNull))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject two migrators that target the same class")
        void shouldRejectDuplicateTargetClass() {
            MigratorDescriptor a = new MigratorDescriptor(SrcAToTarget.class);
            MigratorDescriptor b = new MigratorDescriptor(SrcBToTarget.class);

            assertThatThrownBy(() -> MigrationPlan.build(List.of(a, b)))
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Multiple migrators target the same class");
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        @Test
        @DisplayName("should return false for unknown class")
        void shouldReturnFalseForUnknownClass() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            assertThat(plan.hasMigration(String.class)).isFalse();
            assertThat(plan.migratorFor(String.class)).isNull();
            assertThat(plan.targetOf(String.class)).isNull();
        }
    }

    @Nested
    @DisplayName("cycle detection")
    class CycleDetection {

        // For cycle testing we need classes that form a chain
        interface ChainA { }
        interface ChainB { }
        interface ChainC { }

        static class A1 implements ChainA { }
        static class A2 implements ChainA { }
        static class B1 implements ChainB { }
        static class B2 implements ChainB { }

        // A1 -> A2 (simple, no cycle)
        public static class A1ToA2Migrator implements ClassMigrator<A1, A2> {
            @Override
            public A2 migrate(A1 old) { return new A2(); }
        }

        // Direct 2-cycle: A1 -> B1 and B1 -> A1 (both implement Cyclic).
        interface Cyclic { }
        static class CycX implements Cyclic { }
        static class CycY implements Cyclic { }

        public static class XToYMigrator implements ClassMigrator<CycX, CycY> {
            @Override public CycY migrate(CycX old) { return new CycY(); }
        }

        public static class YToXMigrator implements ClassMigrator<CycY, CycX> {
            @Override public CycX migrate(CycY old) { return new CycX(); }
        }

        // Self-cycle: a class migrated to itself (X -> X).
        public static class XToXMigrator implements ClassMigrator<CycX, CycX> {
            @Override public CycX migrate(CycX old) { return new CycX(); }
        }

        @Test
        @DisplayName("should accept non-cyclic migrations")
        void shouldAcceptNonCyclicMigrations() throws MigrateException {
            MigratorDescriptor desc = new MigratorDescriptor(A1ToA2Migrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(desc));

            assertThat(plan.hasMigration(A1.class)).isTrue();
        }

        @Test
        @DisplayName("should reject a direct two-node cycle (X -> Y -> X)")
        void shouldRejectTwoNodeCycle() {
            MigratorDescriptor xy = new MigratorDescriptor(XToYMigrator.class);
            MigratorDescriptor yx = new MigratorDescriptor(YToXMigrator.class);

            assertThatThrownBy(() -> MigrationPlan.build(List.of(xy, yx)))
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Migration cycle detected");
        }

        @Test
        @DisplayName("should reject a self-cycle (X -> X)")
        void shouldRejectSelfCycle() {
            MigratorDescriptor xx = new MigratorDescriptor(XToXMigrator.class);

            assertThatThrownBy(() -> MigrationPlan.build(List.of(xx)))
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Migration cycle detected");
        }
    }

    @Nested
    @DisplayName("topological ordering")
    class TopologicalOrdering {

        // Chain fixtures: CA -> CB -> CC (all share Chain), exercising dependency ordering.
        interface Chain { }
        static class CA implements Chain { }
        static class CB implements Chain { }
        static class CC implements Chain { }

        public static class AToBMigrator implements ClassMigrator<CA, CB> {
            @Override public CB migrate(CA old) { return new CB(); }
        }

        public static class BToCMigrator implements ClassMigrator<CB, CC> {
            @Override public CC migrate(CB old) { return new CC(); }
        }

        @Test
        @DisplayName("should order independent migrators")
        void shouldOrderIndependentMigrators() throws MigrateException {
            MigratorDescriptor userDesc = new MigratorDescriptor(UserMigrator.class);
            MigratorDescriptor entityDesc = new MigratorDescriptor(EntityMigrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(userDesc, entityDesc));

            // Both should be present, order doesn't matter for independent ones
            assertThat(plan.orderedMigrators()).containsExactlyInAnyOrder(userDesc, entityDesc);
        }

        @Test
        @DisplayName("orders a dependency chain downstream-first (B->C before A->B)")
        void shouldOrderChainDependenciesFirst() throws MigrateException {
            MigratorDescriptor ab = new MigratorDescriptor(AToBMigrator.class);
            MigratorDescriptor bc = new MigratorDescriptor(BToCMigrator.class);

            // Pass them in the "wrong" order to prove ordering is computed, not preserved.
            MigrationPlan plan = MigrationPlan.build(List.of(ab, bc));

            // CA -> CB -> CC: the B->C migrator must run before the A->B migrator.
            assertThat(plan.orderedMigrators()).containsExactly(bc, ab);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("orderedMigrators should return immutable list")
        void orderedMigratorsShouldReturnImmutableList() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            List<MigratorDescriptor> migrators = plan.orderedMigrators();

            assertThatThrownBy(() -> migrators.add(descriptor))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
