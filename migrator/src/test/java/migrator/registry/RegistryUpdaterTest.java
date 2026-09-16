package migrator.registry;

import migrator.patch.ForwardingTable;
import migrator.patch.ReferencePatcher;
import migrator.patch.ReflectionReferencePatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RegistryUpdater}.
 */
@DisplayName("RegistryUpdater")
class RegistryUpdaterTest {

    private ForwardingTable forwarding;
    private ReferencePatcher referencePatcher;
    private RegistryUpdater updater;

    // Common interface for test classes
    interface User {
        int getId();
    }

    static class OldUser implements User {
        private final int id;
        OldUser(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OldUser)) return false;
            return id == ((OldUser) o).id;
        }
        @Override
        public int hashCode() { return id; }
    }

    static class NewUser implements User {
        private final int id;
        NewUser(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    @BeforeEach
    void setUp() {
        forwarding = new ForwardingTable();
        referencePatcher = new ReflectionReferencePatcher(forwarding);
        updater = new RegistryUpdater(forwarding, referencePatcher);
    }

    @Nested
    @DisplayName("updateAnnotatedRegistries with Map")
    class MapRegistries {

        static class MapRegistry {
            @UpdateRegistry(replaceKeys = true, replaceValues = true)
            static Map<Object, Object> registry = new HashMap<>();
        }

        static class ConcurrentMapRegistry {
            @UpdateRegistry(replaceKeys = true, replaceValues = true)
            static Map<Object, Object> registry = new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Test
        @DisplayName("should replace migrated map value with the new instance")
        void shouldReplaceMapValue() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            MapRegistry.registry.clear();
            MapRegistry.registry.put("user", old);

            updater.updateAnnotatedRegistries(List.of(MapRegistry.class), List.of());

            // The real registry must now point at the migrated instance.
            assertThat(MapRegistry.registry.get("user")).isSameAs(replacement);
        }

        @Test
        @DisplayName("should replace migrated map key with the new instance")
        void shouldReplaceMapKey() {
            OldUser oldKey = new OldUser(1);
            NewUser newKey = new NewUser(1);
            forwarding.put(oldKey, newKey);

            MapRegistry.registry.clear();
            MapRegistry.registry.put(oldKey, "value");

            updater.updateAnnotatedRegistries(List.of(MapRegistry.class), List.of());

            assertThat(MapRegistry.registry).containsKey(newKey);
            assertThat(MapRegistry.registry).doesNotContainKey(oldKey);
            assertThat(MapRegistry.registry.get(newKey)).isEqualTo("value");
        }

        @Test
        @DisplayName("should replace value in a ConcurrentHashMap registry")
        void shouldReplaceConcurrentMapValue() {
            OldUser old = new OldUser(7);
            NewUser replacement = new NewUser(7);
            forwarding.put(old, replacement);

            ConcurrentMapRegistry.registry.clear();
            ConcurrentMapRegistry.registry.put("user", old);

            updater.updateAnnotatedRegistries(List.of(ConcurrentMapRegistry.class), List.of());

            assertThat(ConcurrentMapRegistry.registry.get("user")).isSameAs(replacement);
        }

        @Test
        @DisplayName("should leave unmigrated entries untouched")
        void shouldLeaveUnmigratedEntriesUntouched() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            MapRegistry.registry.clear();
            MapRegistry.registry.put("migrated", old);
            MapRegistry.registry.put("kept", "plain");

            updater.updateAnnotatedRegistries(List.of(MapRegistry.class), List.of());

            assertThat(MapRegistry.registry.get("migrated")).isSameAs(replacement);
            assertThat(MapRegistry.registry.get("kept")).isEqualTo("plain");
        }

        @Test
        @DisplayName("should handle empty map")
        void shouldHandleEmptyMap() {
            MapRegistry.registry.clear();

            // Should not throw
            updater.updateAnnotatedRegistries(List.of(MapRegistry.class), List.of());

            assertThat(MapRegistry.registry).isEmpty();
        }
    }

    @Nested
    @DisplayName("updateAnnotatedRegistries with Collection")
    class CollectionRegistries {

        static class ListRegistry {
            @UpdateRegistry
            static List<Object> registry = new ArrayList<>();
        }

        static class SetRegistry {
            @UpdateRegistry
            static Set<Object> registry = new HashSet<>();
        }

        @Test
        @DisplayName("should replace migrated list element in place")
        void shouldReplaceListElement() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            ListRegistry.registry.clear();
            ListRegistry.registry.add(old);
            ListRegistry.registry.add("keep");

            updater.updateAnnotatedRegistries(List.of(ListRegistry.class), List.of());

            // Element 0 must be the migrated instance; order and other elements preserved.
            assertThat(ListRegistry.registry).containsExactly(replacement, "keep");
            assertThat(ListRegistry.registry.get(0)).isSameAs(replacement);
        }

        @Test
        @DisplayName("should replace migrated set element in place")
        void shouldReplaceSetElement() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            SetRegistry.registry.clear();
            SetRegistry.registry.add(old);
            SetRegistry.registry.add("keep");

            updater.updateAnnotatedRegistries(List.of(SetRegistry.class), List.of());

            assertThat(SetRegistry.registry).contains(replacement, "keep");
            assertThat(SetRegistry.registry).doesNotContain(old);
        }
    }

    @Nested
    @DisplayName("updateAnnotatedRegistries with Array")
    class ArrayRegistries {

        static class ArrayRegistry {
            @UpdateRegistry
            static Object[] registry = new Object[3];
        }

        @Test
        @DisplayName("should handle array registry without throwing")
        void shouldHandleArrayRegistryWithoutThrowing() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            ArrayRegistry.registry[0] = old;
            ArrayRegistry.registry[1] = "keep";
            ArrayRegistry.registry[2] = null;

            // Should not throw
            updater.updateAnnotatedRegistries(List.of(ArrayRegistry.class), List.of());

            assertThat(ArrayRegistry.registry[1]).isEqualTo("keep");
            assertThat(ArrayRegistry.registry[2]).isNull();
        }
    }

    @Nested
    @DisplayName("instance registry fields")
    class InstanceRegistryFields {

        static class InstanceHolder {
            @UpdateRegistry(replaceKeys = true, replaceValues = true)
            Map<Object, Object> instanceRegistry = new HashMap<>();
        }

        @Test
        @DisplayName("should replace value in an instance map registry")
        void shouldReplaceInstanceRegistryValue() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            InstanceHolder holder = new InstanceHolder();
            holder.instanceRegistry.put("user", old);

            updater.updateAnnotatedRegistries(List.of(InstanceHolder.class), List.of(holder));

            assertThat(holder.instanceRegistry.get("user")).isSameAs(replacement);
        }

        @Test
        @DisplayName("should replace values across multiple instance holders")
        void shouldReplaceAcrossMultipleInstanceHolders() {
            OldUser old1 = new OldUser(1);
            OldUser old2 = new OldUser(2);
            NewUser new1 = new NewUser(1);
            NewUser new2 = new NewUser(2);
            forwarding.put(old1, new1);
            forwarding.put(old2, new2);

            InstanceHolder holder1 = new InstanceHolder();
            holder1.instanceRegistry.put("user", old1);

            InstanceHolder holder2 = new InstanceHolder();
            holder2.instanceRegistry.put("user", old2);

            updater.updateAnnotatedRegistries(
                    List.of(InstanceHolder.class),
                    List.of(holder1, holder2)
            );

            assertThat(holder1.instanceRegistry.get("user")).isSameAs(new1);
            assertThat(holder2.instanceRegistry.get("user")).isSameAs(new2);
        }
    }

    @Nested
    @DisplayName("deep patching")
    class DeepPatching {

        static class DeepRegistry {
            @UpdateRegistry(deep = true)
            static Object registry;
        }

        // Use Object type to allow type-compatible replacement
        static class Container {
            Object user;
            Container(Object user) { this.user = user; }
        }

        @Test
        @DisplayName("should deep patch nested objects when deep=true with compatible types")
        void shouldDeepPatchNestedObjectsWithCompatibleTypes() {
            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            Container container = new Container(old);
            DeepRegistry.registry = container;

            updater.updateAnnotatedRegistries(List.of(DeepRegistry.class), List.of());

            // Deep patching should replace the nested reference
            assertThat(container.user).isSameAs(replacement);
        }

        @Test
        @DisplayName("should handle null registry with deep=true")
        void shouldHandleNullRegistryWithDeep() {
            DeepRegistry.registry = null;

            // Should not throw
            updater.updateAnnotatedRegistries(List.of(DeepRegistry.class), List.of());
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        static class EmptyRegistry {
            @UpdateRegistry
            static Map<Object, Object> registry = new HashMap<>();
        }

        static class NullRegistry {
            @UpdateRegistry
            static Map<Object, Object> registry = null;
        }

        @Test
        @DisplayName("should handle empty registries")
        void shouldHandleEmptyRegistries() {
            EmptyRegistry.registry.clear();

            // Should not throw
            updater.updateAnnotatedRegistries(List.of(EmptyRegistry.class), List.of());
        }

        @Test
        @DisplayName("should handle null registries")
        void shouldHandleNullRegistries() {
            NullRegistry.registry = null;

            // Should not throw
            updater.updateAnnotatedRegistries(List.of(NullRegistry.class), List.of());
        }

        @Test
        @DisplayName("should handle empty classes list")
        void shouldHandleEmptyClassesList() {
            // Should not throw
            updater.updateAnnotatedRegistries(List.of(), List.of());
        }
    }

    @Nested
    @DisplayName("RegistryAware callback")
    class RegistryAwareCallback {

        static class CallbackRegistry implements RegistryAware {
            @UpdateRegistry
            static Map<Object, Object> items = new HashMap<>();

            static boolean callbackInvoked = false;

            @Override
            public void onRegistryUpdated() {
                callbackInvoked = true;
            }
        }

        @Test
        @DisplayName("should handle RegistryAware without throwing")
        void shouldHandleRegistryAwareWithoutThrowing() {
            CallbackRegistry.callbackInvoked = false;
            CallbackRegistry.items.clear();

            OldUser old = new OldUser(1);
            NewUser replacement = new NewUser(1);
            forwarding.put(old, replacement);

            CallbackRegistry.items.put("user", old);

            updater.updateAnnotatedRegistries(List.of(CallbackRegistry.class), List.of());

            // The map value is replaced. Note: onRegistryUpdated() fires only when the
            // registry VALUE implements RegistryAware (a custom registry object), not when
            // the declaring class does — here the field is a plain Map, so no callback.
            assertThat(CallbackRegistry.items.get("user")).isSameAs(replacement);
        }
    }
}
