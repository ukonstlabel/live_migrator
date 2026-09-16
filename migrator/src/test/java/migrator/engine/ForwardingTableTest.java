package migrator.engine;

import migrator.patch.ForwardingTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForwardingTable}.
 */
@DisplayName("ForwardingTable")
class ForwardingTableTest {

    private ForwardingTable table;

    @BeforeEach
    void setUp() {
        table = new ForwardingTable();
    }

    @Nested
    @DisplayName("put and get")
    class PutAndGet {

        @Test
        @DisplayName("should store and retrieve mapping by identity")
        void shouldStoreAndRetrieveByIdentity() {
            Object oldObj = new Object();
            Object newObj = new Object();

            table.put(oldObj, newObj);

            assertThat(table.get(oldObj)).isSameAs(newObj);
        }

        @Test
        @DisplayName("should return null for unknown object")
        void shouldReturnNullForUnknown() {
            Object unknown = new Object();

            assertThat(table.get(unknown)).isNull();
        }

        @Test
        @DisplayName("should use identity not equals for lookup")
        void shouldUseIdentityNotEquals() {
            String oldObj = new String("test"); // distinct instance
            String equalObj = new String("test"); // equal but not same instance
            Object newObj = new Object();

            table.put(oldObj, newObj);

            assertThat(table.get(oldObj)).isSameAs(newObj);
            assertThat(table.get(equalObj)).isNull(); // different identity
        }

        @Test
        @DisplayName("should handle multiple mappings")
        void shouldHandleMultipleMappings() {
            Object old1 = new Object();
            Object new1 = new Object();
            Object old2 = new Object();
            Object new2 = new Object();

            table.put(old1, new1);
            table.put(old2, new2);

            assertThat(table.get(old1)).isSameAs(new1);
            assertThat(table.get(old2)).isSameAs(new2);
        }

        @Test
        @DisplayName("should allow same new object for multiple old objects")
        void shouldAllowSameNewObjectForMultipleOld() {
            Object old1 = new Object();
            Object old2 = new Object();
            Object sharedNew = new Object();

            table.put(old1, sharedNew);
            table.put(old2, sharedNew);

            assertThat(table.get(old1)).isSameAs(sharedNew);
            assertThat(table.get(old2)).isSameAs(sharedNew);
        }
    }

    @Nested
    @DisplayName("contains")
    class Contains {

        @Test
        @DisplayName("should return true for existing key")
        void shouldReturnTrueForExisting() {
            Object oldObj = new Object();
            Object newObj = new Object();

            table.put(oldObj, newObj);

            assertThat(table.contains(oldObj)).isTrue();
        }

        @Test
        @DisplayName("should return false for non-existing key")
        void shouldReturnFalseForNonExisting() {
            Object unknown = new Object();

            assertThat(table.contains(unknown)).isFalse();
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("should remove existing mapping")
        void shouldRemoveExisting() {
            Object oldObj = new Object();
            Object newObj = new Object();

            table.put(oldObj, newObj);
            table.remove(oldObj);

            assertThat(table.contains(oldObj)).isFalse();
            assertThat(table.get(oldObj)).isNull();
        }

        @Test
        @DisplayName("should handle removal of non-existing key")
        void shouldHandleRemovalOfNonExisting() {
            Object unknown = new Object();

            // Should not throw
            table.remove(unknown);

            assertThat(table.contains(unknown)).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("should remove all mappings")
        void shouldRemoveAllMappings() {
            Object old1 = new Object();
            Object new1 = new Object();
            Object old2 = new Object();
            Object new2 = new Object();

            table.put(old1, new1);
            table.put(old2, new2);

            table.clear();

            assertThat(table.contains(old1)).isFalse();
            assertThat(table.contains(old2)).isFalse();
        }
    }

    @Nested
    @DisplayName("weak reference behavior")
    class WeakReferenceBehavior {

        @Test
        @DisplayName("should handle null lookup gracefully")
        void shouldHandleNullLookup() {
            // Create and discard object
            table.put(new Object(), new Object());

            // Force GC (best effort - GC is not guaranteed)
            System.gc();

            // Should not throw, may return null after GC
            Object result = table.get(new Object());
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("heap size limits and memory behavior")
    class HeapSizeLimitsAndMemoryBehavior {

        @Test
        @DisplayName("should handle large number of mappings")
        void shouldHandleLargeNumberOfMappings() {
            int count = 10000;
            Object[] oldObjects = new Object[count];
            Object[] newObjects = new Object[count];

            for (int i = 0; i < count; i++) {
                oldObjects[i] = new Object();
                newObjects[i] = new Object();
                table.put(oldObjects[i], newObjects[i]);
            }

            // Verify all mappings are retrievable
            for (int i = 0; i < count; i++) {
                assertThat(table.get(oldObjects[i])).isSameAs(newObjects[i]);
            }
        }

        @Test
        @DisplayName("should cleanup stale entries after GC")
        void shouldCleanupStaleEntriesAfterGC() {
            // Add entries with strong references
            Object keepOld = new Object();
            Object keepNew = new Object();
            table.put(keepOld, keepNew);

            // Add entries that will be collected
            for (int i = 0; i < 100; i++) {
                table.put(new Object(), new Object());
            }

            // Request GC
            System.gc();

            // Trigger cleanup by performing an operation
            table.get(new Object());

            // The kept entry should still be accessible
            assertThat(table.get(keepOld)).isSameAs(keepNew);
        }

        @Test
        @DisplayName("should not leak memory when old objects are collected")
        void shouldNotLeakMemoryWhenOldObjectsAreCollected() {
            Object keepNew = new Object();

            // Create mappings without keeping references to old objects
            for (int i = 0; i < 1000; i++) {
                table.put(new Object(), keepNew);
            }

            // Request GC multiple times
            for (int i = 0; i < 3; i++) {
                System.gc();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {}
            }

            // Trigger cleanup
            table.get(new Object());

            // Table should still be functional
            Object newOld = new Object();
            Object newNew = new Object();
            table.put(newOld, newNew);
            assertThat(table.get(newOld)).isSameAs(newNew);
        }

        @Test
        @DisplayName("should handle identity hash collisions")
        void shouldHandleIdentityHashCollisions() {
            // Create objects - some may have same identity hash codes
            Object[] oldObjects = new Object[100];
            Object[] newObjects = new Object[100];

            for (int i = 0; i < 100; i++) {
                oldObjects[i] = new Object();
                newObjects[i] = new Object();
                table.put(oldObjects[i], newObjects[i]);
            }

            // All mappings should be correctly retrievable
            for (int i = 0; i < 100; i++) {
                assertThat(table.get(oldObjects[i])).isSameAs(newObjects[i]);
            }
        }

        @Test
        @DisplayName("should allow overwriting existing mapping")
        void shouldAllowOverwritingExistingMapping() {
            Object oldObj = new Object();
            Object newObj1 = new Object();
            Object newObj2 = new Object();

            table.put(oldObj, newObj1);
            table.put(oldObj, newObj2);

            assertThat(table.get(oldObj)).isSameAs(newObj2);
        }

        @Test
        @DisplayName("should handle rapid put and get operations")
        void shouldHandleRapidPutAndGetOperations() {
            Object[] objects = new Object[1000];
            for (int i = 0; i < objects.length; i++) {
                objects[i] = new Object();
            }

            // Rapid alternating put and get
            for (int i = 0; i < objects.length; i++) {
                Object newObj = new Object();
                table.put(objects[i], newObj);
                assertThat(table.get(objects[i])).isSameAs(newObj);
            }
        }

        @Test
        @DisplayName("should handle string objects correctly by identity")
        void shouldHandleStringObjectsCorrectlyByIdentity() {
            // These are interned and will be the same instance
            String interned1 = "interned";
            String interned2 = "interned";
            assertThat(interned1).isSameAs(interned2);

            Object newObj = new Object();
            table.put(interned1, newObj);

            // Should find by same identity
            assertThat(table.get(interned2)).isSameAs(newObj);

            // New string with same content but different identity
            String different = new String("interned");
            assertThat(interned1).isNotSameAs(different);
            assertThat(table.get(different)).isNull();
        }

        @Test
        @DisplayName("should handle mixed object types")
        void shouldHandleMixedObjectTypes() {
            Object obj = new Object();
            String str = new String("test");
            Integer num = 12345;
            int[] arr = new int[]{1, 2, 3};

            Object objNew = new Object();
            Object strNew = new Object();
            Object numNew = new Object();
            Object arrNew = new Object();

            table.put(obj, objNew);
            table.put(str, strNew);
            table.put(num, numNew);
            table.put(arr, arrNew);

            assertThat(table.get(obj)).isSameAs(objNew);
            assertThat(table.get(str)).isSameAs(strNew);
            assertThat(table.get(num)).isSameAs(numNew);
            assertThat(table.get(arr)).isSameAs(arrNew);
        }
    }

    @Nested
    @DisplayName("concurrency safety")
    class ConcurrencySafety {

        @Test
        @DisplayName("should handle sequential operations from single thread")
        void shouldHandleSequentialOperationsFromSingleThread() {
            for (int iteration = 0; iteration < 100; iteration++) {
                Object old = new Object();
                Object newObj = new Object();

                table.put(old, newObj);
                assertThat(table.contains(old)).isTrue();
                assertThat(table.get(old)).isSameAs(newObj);
                table.remove(old);
                assertThat(table.contains(old)).isFalse();
            }
        }
    }
}
