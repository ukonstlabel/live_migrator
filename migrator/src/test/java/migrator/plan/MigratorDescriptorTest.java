package migrator.plan;

import migrator.ClassMigrator;
import migrator.exceptions.MigrateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MigratorDescriptor}.
 */
@DisplayName("MigratorDescriptor")
class MigratorDescriptorTest {

    // Test interfaces and classes
    interface User {
        int getId();
        String getName();
    }

    interface Entity {
        int getId();
    }

    static class OldUser implements User {
        private final int id;
        private final String name;

        OldUser(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public int getId() { return id; }

        @Override
        public String getName() { return name; }
    }

    static class NewUser implements User {
        private final int id;
        private final String name;

        NewUser(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public int getId() { return id; }

        @Override
        public String getName() { return name; }
    }

    static class OldEntity implements Entity {
        private final int id;

        OldEntity(int id) {
            this.id = id;
        }

        @Override
        public int getId() { return id; }
    }

    static class NewEntity implements Entity {
        private final int id;

        NewEntity(int id) {
            this.id = id;
        }

        @Override
        public int getId() { return id; }
    }

    // Classes without common interface
    static class Standalone1 {
        int value;
    }

    static class Standalone2 {
        int value;
    }

    // Test migrators
    public static class UserMigrator implements ClassMigrator<OldUser, NewUser> {
        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId(), old.getName());
        }
    }

    public static class EntityMigrator implements ClassMigrator<OldEntity, NewEntity> {
        @Override
        public NewEntity migrate(OldEntity old) {
            return new NewEntity(old.getId());
        }
    }

    public static class InvalidMigrator implements ClassMigrator<Standalone1, Standalone2> {
        @Override
        public Standalone2 migrate(Standalone1 old) {
            Standalone2 result = new Standalone2();
            result.value = old.value;
            return result;
        }
    }

    static class PrivateConstructorMigrator implements ClassMigrator<OldUser, NewUser> {
        private PrivateConstructorMigrator() {}

        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId(), old.getName());
        }
    }

    /** Has only a parameterized constructor — no no-arg constructor of any visibility. */
    static class NoNoArgConstructorMigrator implements ClassMigrator<OldUser, NewUser> {
        @SuppressWarnings("unused")
        NoNoArgConstructorMigrator(int unused) {}

        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId(), old.getName());
        }
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("should extract from and to types from generic parameters")
        void shouldExtractFromAndToTypes() {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);

            assertThat(descriptor.from()).isEqualTo(OldUser.class);
            assertThat(descriptor.to()).isEqualTo(NewUser.class);
        }

        @Test
        @DisplayName("should instantiate migrator")
        void shouldInstantiateMigrator() {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);

            assertThat(descriptor.migrator()).isInstanceOf(UserMigrator.class);
        }

        @Test
        @DisplayName("should find common interface")
        void shouldFindCommonInterface() {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);

            assertThat(descriptor.commonInterface()).isEqualTo(User.class);
        }

        @Test
        @DisplayName("should find Entity interface")
        void shouldFindEntityInterface() {
            MigratorDescriptor descriptor = new MigratorDescriptor(EntityMigrator.class);

            assertThat(descriptor.commonInterface()).isEqualTo(Entity.class);
        }

        @Test
        @DisplayName("should throw when no common interface exists")
        void shouldThrowWhenNoCommonInterface() {
            assertThatThrownBy(() -> new MigratorDescriptor(InvalidMigrator.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot determine common interface");
        }

        @Test
        @DisplayName("should throw when migrator has no no-arg constructor")
        void shouldThrowWhenNoNoArgConstructor() {
            assertThatThrownBy(() -> new MigratorDescriptor(NoNoArgConstructorMigrator.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot instantiate migrator");
        }

        @Test
        @DisplayName("should instantiate a migrator with a private no-arg constructor")
        void shouldInstantiateMigratorWithPrivateConstructor() {
            // The descriptor makes the no-arg constructor accessible regardless of visibility,
            // consistent with how ComponentResolver instantiates other components.
            MigratorDescriptor descriptor = new MigratorDescriptor(PrivateConstructorMigrator.class);

            assertThat(descriptor.migrator()).isInstanceOf(PrivateConstructorMigrator.class);
            assertThat(descriptor.from()).isEqualTo(OldUser.class);
            assertThat(descriptor.to()).isEqualTo(NewUser.class);
        }
    }

    @Nested
    @DisplayName("common interface detection")
    class CommonInterfaceDetection {

        // Test hierarchy: ChildUser extends OldUser which implements User
        static class ChildOldUser extends OldUser {
            ChildOldUser(int id, String name) {
                super(id, name);
            }
        }

        static class ChildNewUser extends NewUser {
            ChildNewUser(int id, String name) {
                super(id, name);
            }
        }

        public static class ChildUserMigrator implements ClassMigrator<ChildOldUser, ChildNewUser> {
            @Override
            public ChildNewUser migrate(ChildOldUser old) {
                return new ChildNewUser(old.getId(), old.getName());
            }
        }

        @Test
        @DisplayName("should find interface inherited from superclass")
        void shouldFindInterfaceInheritedFromSuperclass() {
            MigratorDescriptor descriptor = new MigratorDescriptor(ChildUserMigrator.class);

            // Should find User interface from superclass
            assertThat(descriptor.commonInterface()).isEqualTo(User.class);
        }

        // Test superinterface
        interface BaseEntity {
            int getId();
        }

        interface ExtendedEntity extends BaseEntity {
            String getType();
        }

        static class OldExtended implements ExtendedEntity {
            @Override
            public int getId() { return 1; }
            @Override
            public String getType() { return "old"; }
        }

        static class NewExtended implements ExtendedEntity {
            @Override
            public int getId() { return 1; }
            @Override
            public String getType() { return "new"; }
        }

        public static class ExtendedMigrator implements ClassMigrator<OldExtended, NewExtended> {
            @Override
            public NewExtended migrate(OldExtended old) {
                return new NewExtended();
            }
        }

        @Test
        @DisplayName("should find interface from superinterface hierarchy")
        void shouldFindInterfaceFromSuperinterfaceHierarchy() {
            MigratorDescriptor descriptor = new MigratorDescriptor(ExtendedMigrator.class);

            // Should find ExtendedEntity (or BaseEntity)
            assertThat(descriptor.commonInterface()).isIn(ExtendedEntity.class, BaseEntity.class);
        }
    }

    @Nested
    @DisplayName("migrator execution")
    class MigratorExecution {

        @Test
        @DisplayName("should successfully migrate object using extracted migrator")
        void shouldSuccessfullyMigrateObject() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);

            OldUser old = new OldUser(42, "Alice");

            @SuppressWarnings("unchecked")
            ClassMigrator<OldUser, NewUser> migrator =
                    (ClassMigrator<OldUser, NewUser>) descriptor.migrator();
            NewUser result = migrator.migrate(old);

            assertThat(result.getId()).isEqualTo(42);
            assertThat(result.getName()).isEqualTo("Alice");
        }
    }
}
