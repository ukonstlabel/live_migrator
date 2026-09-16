package migrator.engine;

import migrator.patch.ForwardingTable;
import migrator.patch.ReflectionReferencePatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Hard, borderline-focused tests for {@link ReflectionReferencePatcher} that complement the
 * happy-path suite. These target the failure-prone corners of graph patching:
 *
 * <ul>
 *   <li><b>Stack safety</b> — a very deep object graph must be patched with the iterative
 *       work-stack, never blowing the call stack.</li>
 *   <li><b>Cycles & self-reference</b> — self-referential arrays and rings must terminate.</li>
 *   <li><b>Rehashing</b> — migrating a hash key whose replacement has a different {@code hashCode}
 *       must re-bucket correctly (the map is rebuilt, not mutated in place).</li>
 *   <li><b>Sorted containers</b> — {@code TreeSet}/{@code TreeMap} must stay ordered/consistent.</li>
 *   <li><b>Concurrent & specialised containers</b> — {@code ConcurrentHashMap}, {@code ArrayDeque},
 *       {@code LinkedList} (non-{@code RandomAccess}), and fixed-size {@code Arrays.asList}.</li>
 *   <li><b>Multi-dimensional arrays.</b></li>
 * </ul>
 */
@DisplayName("ReflectionReferencePatcher — borderline graphs & containers")
class ReflectionReferencePatcherHardTest {

    interface Identifiable { int getId(); }

    static final class OldClass implements Identifiable {
        private final int id;
        OldClass(int id) { this.id = id; }
        @Override public int getId() { return id; }
    }

    static final class NewClass implements Identifiable {
        private final int id;
        NewClass(int id) { this.id = id; }
        @Override public int getId() { return id; }
    }

    private ForwardingTable forwarding;
    private ReflectionReferencePatcher patcher;

    @BeforeEach
    void setUp() {
        forwarding = new ForwardingTable();
        patcher = new ReflectionReferencePatcher(forwarding);
    }

    // ----------------------------------------------------------------------------------------------
    // Stack safety
    // ----------------------------------------------------------------------------------------------

    static final class Node {
        Identifiable payload;
        Node next;
    }

    @Test
    @DisplayName("patches a very deep linked chain without StackOverflowError")
    void deepChainIsStackSafe() {
        int depth = 200_000;
        OldClass old = new OldClass(1);
        NewClass migrated = new NewClass(1);
        forwarding.put(old, migrated);

        Node head = new Node();
        Node cur = head;
        for (int i = 0; i < depth; i++) {
            cur.next = new Node();
            cur = cur.next;
        }
        cur.payload = old; // only the deepest node references the migrated object

        assertThatCode(() -> patcher.patchObject(head)).doesNotThrowAnyException();

        // Walk to the tail and confirm the deep reference was replaced.
        Node tail = head;
        while (tail.next != null) tail = tail.next;
        assertThat(tail.payload).isSameAs(migrated);
    }

    // ----------------------------------------------------------------------------------------------
    // Cycles / self-reference
    // ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a self-referential array terminates and still patches its migrated element")
    void selfReferentialArray() {
        OldClass old = new OldClass(1);
        NewClass migrated = new NewClass(1);
        forwarding.put(old, migrated);

        Object[] array = new Object[2];
        array[0] = array;  // points at itself
        array[1] = old;

        assertThatCode(() -> patcher.patchObject(array)).doesNotThrowAnyException();

        assertThat(array[0]).isSameAs(array); // self-reference untouched
        assertThat(array[1]).isSameAs(migrated);
    }

    @Test
    @DisplayName("a ring of nodes terminates and patches the migrated payload")
    void cyclicGraph() {
        OldClass old = new OldClass(1);
        NewClass migrated = new NewClass(1);
        forwarding.put(old, migrated);

        Node a = new Node(), b = new Node(), c = new Node();
        a.next = b; b.next = c; c.next = a; // ring
        b.payload = old;

        assertThatCode(() -> patcher.patchObject(a)).doesNotThrowAnyException();

        assertThat(b.payload).isSameAs(migrated);
    }

    // ----------------------------------------------------------------------------------------------
    // Rehashing: a migrated hash key whose replacement has a different hashCode
    // ----------------------------------------------------------------------------------------------

    /** Value-based equals/hashCode so a migrated key lands in a different bucket. */
    static final class HashKey implements Identifiable {
        private final int id;
        HashKey(int id) { this.id = id; }
        @Override public int getId() { return id; }
        @Override public boolean equals(Object o) { return o instanceof HashKey k && k.id == id; }
        @Override public int hashCode() { return id; }
    }

    static final class MapHolder {
        Map<Object, Object> map;
        MapHolder(Map<Object, Object> map) { this.map = map; }
    }

    @Test
    @DisplayName("migrating a hash key with a different hashCode re-buckets correctly")
    void hashKeyRehash() {
        HashKey oldKey = new HashKey(1);
        HashKey newKey = new HashKey(999); // different bucket from oldKey
        forwarding.put(oldKey, newKey);
        assertThat(oldKey.hashCode()).isNotEqualTo(newKey.hashCode());

        Map<Object, Object> map = new java.util.HashMap<>();
        map.put(oldKey, "payload");
        MapHolder holder = new MapHolder(map);

        patcher.patchObject(holder);

        assertThat(map).containsKey(newKey);
        assertThat(map).doesNotContainKey(oldKey);
        assertThat(map.get(newKey)).isEqualTo("payload");
        assertThat(map).hasSize(1);
    }

    // ----------------------------------------------------------------------------------------------
    // Sorted containers
    // ----------------------------------------------------------------------------------------------

    /** Comparable holder so it can live in TreeSet/TreeMap under natural ordering. */
    static final class Sortable implements Comparable<Sortable> {
        final int id;
        Sortable(int id) { this.id = id; }
        @Override public int compareTo(Sortable o) { return Integer.compare(id, o.id); }
        @Override public boolean equals(Object o) { return o instanceof Sortable s && s.id == id; }
        @Override public int hashCode() { return id; }
        @Override public String toString() { return "S" + id; }
    }

    static final class CollectionHolder {
        java.util.Collection<Object> collection;
        CollectionHolder(java.util.Collection<Object> c) { this.collection = c; }
    }

    @Test
    @DisplayName("a TreeSet element migration keeps the set sorted and consistent")
    void treeSetElementMigration() {
        Sortable old = new Sortable(5);
        Sortable migrated = new Sortable(2);
        forwarding.put(old, migrated);

        TreeSet<Object> set = new TreeSet<>();
        set.add(new Sortable(1));
        set.add(old);
        set.add(new Sortable(9));
        CollectionHolder holder = new CollectionHolder(set);

        patcher.patchObject(holder);

        assertThat(set).contains(migrated).doesNotContain(old);
        // Still a valid, sorted TreeSet: ids in ascending order 1, 2, 9.
        assertThat(set.stream().map(o -> ((Sortable) o).id)).containsExactly(1, 2, 9);
    }

    @Test
    @DisplayName("a TreeMap key migration keeps the map sorted and consistent")
    void treeMapKeyMigration() {
        Sortable oldKey = new Sortable(5);
        Sortable newKey = new Sortable(2);
        forwarding.put(oldKey, newKey);

        TreeMap<Object, Object> map = new TreeMap<>();
        map.put(new Sortable(1), "a");
        map.put(oldKey, "b");
        map.put(new Sortable(9), "c");
        MapHolder holder = new MapHolder(map);

        patcher.patchObject(holder);

        assertThat(map).containsKey(newKey).doesNotContainKey(oldKey);
        assertThat(map.get(newKey)).isEqualTo("b");
        assertThat(map.keySet().stream().map(o -> ((Sortable) o).id)).containsExactly(1, 2, 9);
    }

    /** Not Comparable — adding it to a natural-ordering TreeSet throws ClassCastException. */
    static final class NotComparable {}

    @Test
    @DisplayName("a failed collection rebuild restores the original contents (no data loss)")
    void failedRebuildRestoresOriginalContents() {
        // Migrate a TreeSet element to a replacement that the set's natural ordering rejects:
        // clear()+addAll() will throw ClassCastException partway. The original elements must be
        // restored rather than left empty/partial.
        Sortable old = new Sortable(5);
        NotComparable bad = new NotComparable();
        forwarding.put(old, bad);

        Sortable keep1 = new Sortable(1), keep2 = new Sortable(9);
        TreeSet<Object> set = new TreeSet<>();
        set.add(keep1);
        set.add(old);
        set.add(keep2);
        CollectionHolder holder = new CollectionHolder(set);

        assertThatCode(() -> patcher.patchObject(holder)).doesNotThrowAnyException();

        // Rebuild failed and was rolled back: the set still holds exactly its original 3 elements.
        assertThat(set).containsExactlyInAnyOrder(keep1, old, keep2);
        assertThat(set).doesNotContain(bad);
    }

    // ----------------------------------------------------------------------------------------------
    // Concurrent & specialised containers
    // ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ConcurrentHashMap values are migrated in place")
    void concurrentHashMapValue() {
        OldClass old = new OldClass(1);
        NewClass migrated = new NewClass(1);
        forwarding.put(old, migrated);

        ConcurrentHashMap<Object, Object> map = new ConcurrentHashMap<>();
        map.put("k", old);
        MapHolder holder = new MapHolder(map);

        patcher.patchObject(holder);

        assertThat(map.get("k")).isSameAs(migrated);
    }

    @Test
    @DisplayName("ArrayDeque element migration preserves iteration order")
    void arrayDequeOrderPreserved() {
        OldClass old = new OldClass(2);
        NewClass migrated = new NewClass(2);
        forwarding.put(old, migrated);

        Deque<Object> deque = new ArrayDeque<>();
        deque.addLast("first");
        deque.addLast(old);
        deque.addLast("third");
        CollectionHolder holder = new CollectionHolder(deque);

        patcher.patchObject(holder);

        assertThat(new ArrayList<>(deque)).containsExactly("first", migrated, "third");
    }

    static final class ListHolder {
        List<Object> list;
        ListHolder(List<Object> list) { this.list = list; }
    }

    @Test
    @DisplayName("LinkedList (non-RandomAccess) elements are migrated via the ListIterator path")
    void linkedListElementMigration() {
        OldClass old = new OldClass(1);
        NewClass migrated = new NewClass(1);
        forwarding.put(old, migrated);

        List<Object> list = new LinkedList<>(Arrays.asList("a", old, "b"));
        ListHolder holder = new ListHolder(list);

        patcher.patchObject(holder);

        assertThat(list).containsExactly("a", migrated, "b");
    }

    @Test
    @DisplayName("fixed-size Arrays.asList element is replaced in place via set()")
    void fixedSizeAsListElement() {
        OldClass old = new OldClass(1);
        NewClass migrated = new NewClass(1);
        forwarding.put(old, migrated);

        // Arrays.asList is fixed-size (add/remove throw) but supports set(), and is RandomAccess.
        List<Object> list = Arrays.asList("x", old, "y");
        ListHolder holder = new ListHolder(list);

        patcher.patchObject(holder);

        assertThat(list).containsExactly("x", migrated, "y");
    }

    // ----------------------------------------------------------------------------------------------
    // Multi-dimensional arrays
    // ----------------------------------------------------------------------------------------------

    static final class MatrixHolder {
        Object[][] matrix;
        MatrixHolder(Object[][] matrix) { this.matrix = matrix; }
    }

    @Test
    @DisplayName("multi-dimensional Object[][] elements are migrated")
    void multiDimensionalArray() {
        OldClass old1 = new OldClass(1), old2 = new OldClass(2);
        NewClass new1 = new NewClass(1), new2 = new NewClass(2);
        forwarding.put(old1, new1);
        forwarding.put(old2, new2);

        Object[][] matrix = {
                { old1, "a" },
                { "b", old2 },
        };
        MatrixHolder holder = new MatrixHolder(matrix);

        patcher.patchObject(holder);

        assertThat(matrix[0][0]).isSameAs(new1);
        assertThat(matrix[1][1]).isSameAs(new2);
        assertThat(matrix[0][1]).isEqualTo("a");
    }
}
