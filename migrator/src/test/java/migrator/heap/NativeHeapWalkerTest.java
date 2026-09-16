package migrator.heap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import migrator.exceptions.MigrateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hard, behavioural tests for the JNI/JVMTI native methods backing {@link NativeHeapWalker}:
 * {@code nativeSnapshotObjects}, {@code nativeWalkHeap}, {@code nativeWalkHeapFiltered} and
 * {@code nativeAdvanceEpoch} (see {@code agent/agent.c}).
 *
 * <p>These run against the real native agent self-attached into the test JVM
 * (see {@link NativeAgentSupport}). They focus on borderline and bad inputs:
 * <ul>
 *   <li>null / empty / null-only inputs that must not crash the VM,</li>
 *   <li>exact-class vs. subclass matching semantics of the JVMTI {@code klass} filter,</li>
 *   <li>interfaces and array classes as targets,</li>
 *   <li>deduplication of repeated classes and identity (not {@code equals}) set semantics,</li>
 *   <li>per-walk epoch/tag isolation so one walk never leaks results into the next,</li>
 *   <li>large object counts to confirm every live instance is returned exactly once.</li>
 * </ul>
 *
 * <p>Each test that asserts an exact instance count uses its <em>own</em> fixture class so the
 * heap holds no stray instances of that type from other tests (the default per-method lifecycle
 * also gives each test a fresh {@code keepAlive} list). Strong references in {@code keepAlive}
 * stop the GC from collecting fixtures mid-walk.
 *
 * <p>If the native agent cannot be loaded (no C toolchain / headers, attach disabled, unsupported
 * platform) every test skips via assumptions rather than failing.
 */
@DisplayName("NativeHeapWalker — native heap-walk methods")
class NativeHeapWalkerTest {

    private final NativeHeapWalker walker = new NativeHeapWalker();

    // Strong references so the GC cannot collect the fixtures mid-walk (fresh per test method).
    private final List<Object> keepAlive = new ArrayList<>();

    @BeforeAll
    static void loadAgent() {
        // Attempt once; individual tests assume the result so they skip (not fail) when unavailable.
        NativeAgentSupport.ensureLoaded();
    }

    @BeforeEach
    void requireAgent() {
        assumeTrue(NativeAgentSupport.ensureLoaded(),
                () -> "native agent not loaded, skipping: " + NativeAgentSupport.skipReason());
    }

    // ----------------------------------------------------------------------------------------------
    // snapshotObjects — happy path & identity
    // ----------------------------------------------------------------------------------------------

    static final class LiveById { int a; LiveById(int a) { this.a = a; } }

    @Test
    @DisplayName("snapshotObjects returns exactly the live instances, by identity")
    void snapshotReturnsLiveInstancesByIdentity() {
        LiveById o1 = new LiveById(1), o2 = new LiveById(2), o3 = new LiveById(3);
        keep(o1, o2, o3);

        Object[] snap = walker.snapshotObjects(LiveById.class);

        Set<Object> ids = identitySet(snap);
        assertThat(ids).contains(o1, o2, o3);
        assertThat(snap).allMatch(o -> o.getClass() == LiveById.class);
        assertThat(snap).hasSize(3);
    }

    static final class Deduped { int v; Deduped(int v) { this.v = v; } }

    @Test
    @DisplayName("snapshotObjects never returns null and never contains duplicates")
    void snapshotIsNonNullAndDeduplicated() {
        for (int i = 0; i < 25; i++) keep(new Deduped(i));

        Object[] snap = walker.snapshotObjects(Deduped.class);

        assertThat(snap).isNotNull();
        assertThat(snap).doesNotContainNull();
        assertThat(identitySet(snap)).hasSameSizeAs(Arrays.asList(snap)); // no identity duplicates
        assertThat(snap).hasSize(25);
    }

    // ----------------------------------------------------------------------------------------------
    // snapshotObjects — borderline / bad inputs
    // ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("snapshotObjects(null) returns an empty array, does not throw or crash the VM")
    void snapshotNullClassReturnsEmpty() {
        Object[] snap = walker.snapshotObjects(null);
        assertThat(snap).isNotNull().isEmpty();
    }

    static final class NeverInstantiated { private NeverInstantiated() {} }

    @Test
    @DisplayName("snapshotObjects of a class with zero live instances returns empty (not null)")
    void snapshotZeroInstancesReturnsEmpty() {
        Object[] snap = walker.snapshotObjects(NeverInstantiated.class);
        assertThat(snap).isNotNull().isEmpty();
    }

    static class ExactBase { int a; ExactBase(int a) { this.a = a; } }
    static final class ExactDerived extends ExactBase { int b; ExactDerived(int a, int b) { super(a); this.b = b; } }

    @Test
    @DisplayName("snapshotObjects uses exact-class matching: subclass instances are NOT included")
    void snapshotMatchesExactClassNotSubclasses() {
        ExactBase base = new ExactBase(10);
        ExactDerived d1 = new ExactDerived(1, 1), d2 = new ExactDerived(2, 2);
        keep(base, d1, d2);

        Object[] snapBase = walker.snapshotObjects(ExactBase.class);
        Object[] snapDerived = walker.snapshotObjects(ExactDerived.class);

        // ExactBase.class matches only the one true ExactBase; the subclasses are a different exact class.
        assertThat(identitySet(snapBase)).contains(base).doesNotContain(d1, d2);
        assertThat(snapBase).allMatch(o -> o.getClass() == ExactBase.class);
        assertThat(snapBase).hasSize(1);

        // ExactDerived.class matches exactly the two subclass instances.
        assertThat(identitySet(snapDerived)).contains(d1, d2).doesNotContain(base);
        assertThat(snapDerived).hasSize(2);
    }

    interface Marker {}
    static final class MarkerImpl implements Marker { int v; MarkerImpl(int v) { this.v = v; } }

    @Test
    @DisplayName("snapshotObjects of an interface returns empty — interfaces have no direct instances")
    void snapshotInterfaceReturnsEmpty() {
        keep(new MarkerImpl(99));
        Object[] snap = walker.snapshotObjects(Marker.class);
        assertThat(snap).isEmpty();
    }

    @Test
    @DisplayName("snapshotObjects works for array classes and matches the exact array type")
    void snapshotArrayClass() {
        long[][] tag = new long[3][]; // a distinctively-typed array we can find by identity
        keep((Object) tag); // cast so the long[][] is one element, not spread as the varargs array

        Object[] snap = walker.snapshotObjects(long[][].class);

        assertThat(identitySet(snap)).contains((Object) tag);
        assertThat(snap).allMatch(o -> o.getClass() == long[][].class);
    }

    // ----------------------------------------------------------------------------------------------
    // walkHeap(Collection) — filtered
    // ----------------------------------------------------------------------------------------------

    static final class FA { int x; FA(int x) { this.x = x; } }
    static class FB { int x; FB(int x) { this.x = x; } }
    static final class FC extends FB { FC(int x) { super(x); } }

    @Test
    @DisplayName("filtered walk returns instances of all requested classes, identity-deduplicated")
    void filteredWalkReturnsRequestedClasses() throws MigrateException {
        FA a = new FA(1);
        FC c = new FC(2);
        MarkerImpl m = new MarkerImpl(3);
        keep(a, c, m);

        Set<Object> result = walker.walkHeap(List.of(FA.class, FC.class, MarkerImpl.class));

        assertThat(result).contains(a, c, m);
    }

    static final class DedupClass { int x; DedupClass(int x) { this.x = x; } }

    @Test
    @DisplayName("filtered walk de-duplicates repeated classes and skips null elements")
    void filteredWalkDedupesAndSkipsNulls() throws MigrateException {
        DedupClass o1 = new DedupClass(1), o2 = new DedupClass(2);
        keep(o1, o2);

        // Duplicate the class twice and include a null element — must behave like a single class entry.
        Collection<Class<?>> classes = Arrays.asList(DedupClass.class, DedupClass.class, null, DedupClass.class);
        Set<Object> result = walker.walkHeap(classes);

        Set<Object> single = identitySet(walker.snapshotObjects(DedupClass.class));
        assertThat(result).contains(o1, o2);
        // The duplicate/null entries must not inflate or corrupt the result set.
        assertThat(result).hasSameSizeAs(single);
    }

    @Test
    @DisplayName("filtered walk: null collection returns an empty set")
    void filteredWalkNullCollection() throws MigrateException {
        assertThat(walker.walkHeap((Collection<Class<?>>) null)).isEmpty();
    }

    @Test
    @DisplayName("filtered walk: empty collection returns an empty set")
    void filteredWalkEmptyCollection() throws MigrateException {
        assertThat(walker.walkHeap(Collections.emptyList())).isEmpty();
    }

    static final class OnlyNullFixture { int x; OnlyNullFixture(int x) { this.x = x; } }

    @Test
    @DisplayName("filtered walk: a collection containing only null returns an empty set")
    void filteredWalkOnlyNull() throws MigrateException {
        keep(new OnlyNullFixture(1)); // ensure an instance exists; it must NOT be returned
        Collection<Class<?>> onlyNull = new ArrayList<>();
        onlyNull.add(null);
        assertThat(walker.walkHeap(onlyNull)).isEmpty();
    }

    /** Two instances are {@code equals} when their key matches, but remain distinct identities. */
    static final class ValueEquals {
        final int key;
        ValueEquals(int key) { this.key = key; }
        @Override public boolean equals(Object o) { return o instanceof ValueEquals v && v.key == key; }
        @Override public int hashCode() { return key; }
    }

    @Test
    @DisplayName("filtered walk returns an identity set: equal-but-distinct instances are both present")
    void filteredWalkIsIdentityBased() throws MigrateException {
        ValueEquals v1 = new ValueEquals(7);
        ValueEquals v2 = new ValueEquals(7); // equals(v1) but a different object
        keep(v1, v2);
        assertThat(v1).isEqualTo(v2);

        Set<Object> result = walker.walkHeap(List.of(ValueEquals.class));

        // A value-based Set would collapse these to one; the identity set must keep both.
        assertThat(result).contains(v1, v2);
        long distinct = result.stream().filter(o -> o.getClass() == ValueEquals.class).count();
        assertThat(distinct).isEqualTo(2);
    }

    // ----------------------------------------------------------------------------------------------
    // walkHeap() — full
    // ----------------------------------------------------------------------------------------------

    static final class FullWalkFixture { int x; FullWalkFixture(int x) { this.x = x; } }

    @Test
    @DisplayName("full heap walk returns a large identity set containing our live objects")
    void fullWalkContainsLiveObjects() throws MigrateException {
        FullWalkFixture f1 = new FullWalkFixture(1);
        FullWalkFixture f2 = new FullWalkFixture(2);
        keep(f1, f2);

        Set<Object> all = walker.walkHeap();

        assertThat(all).isNotNull();
        // Membership via the identity set itself — never call equals() on arbitrary heap objects.
        assertThat(all.contains(f1)).isTrue();
        assertThat(all.contains(f2)).isTrue();
        // The whole heap is far larger than our fixtures (strings, classes, JDK internals, ...).
        assertThat(all.size()).isGreaterThan(100);
    }

    // ----------------------------------------------------------------------------------------------
    // epoch / per-walk tag isolation
    // ----------------------------------------------------------------------------------------------

    static final class EpochBase { int x; EpochBase(int x) { this.x = x; } }
    static final class EpochMarker { int x; EpochMarker(int x) { this.x = x; } }

    @Test
    @DisplayName("each walk uses a fresh tag: one class's walk never leaks into another's")
    void walksAreIsolatedByEpoch() throws MigrateException {
        EpochBase base = new EpochBase(1);
        EpochMarker marker = new EpochMarker(1);
        keep(base, marker);

        int baseCount = walker.snapshotObjects(EpochBase.class).length;
        int markerCount = walker.snapshotObjects(EpochMarker.class).length;
        assertThat(baseCount).isEqualTo(1);
        assertThat(markerCount).isEqualTo(1);

        // Interleave walks of different classes; counts must stay stable (no stale tags carried over).
        for (int i = 0; i < 5; i++) {
            assertThat(walker.snapshotObjects(EpochBase.class)).hasSize(baseCount);
            assertThat(walker.snapshotObjects(EpochMarker.class)).hasSize(markerCount);
            Set<Object> mixed = walker.walkHeap(List.of(EpochBase.class, EpochMarker.class));
            assertThat(mixed).contains(base, marker);
            assertThat(mixed).hasSize(2);
        }
    }

    static final class EpochAdvance { int x; EpochAdvance(int x) { this.x = x; } }

    @Test
    @DisplayName("advanceEpoch is idempotent and does not break subsequent walks")
    void advanceEpochDoesNotBreakWalks() {
        EpochAdvance o = new EpochAdvance(1);
        keep(o);
        int before = walker.snapshotObjects(EpochAdvance.class).length;
        assertThat(before).isEqualTo(1);

        assertThatCode(() -> {
            NativeHeapWalker.advanceEpoch();
            NativeHeapWalker.advanceEpoch();
            NativeHeapWalker.advanceEpoch();
        }).doesNotThrowAnyException();

        Object[] after = walker.snapshotObjects(EpochAdvance.class);
        assertThat(after).hasSize(before);
        assertThat(identitySet(after)).contains(o);
    }

    // ----------------------------------------------------------------------------------------------
    // scale
    // ----------------------------------------------------------------------------------------------

    static final class ScaleType { final int i; ScaleType(int i) { this.i = i; } }

    @Test
    @DisplayName("snapshot returns every live instance exactly once at scale (10k objects)")
    void snapshotAtScale() {
        int n = 10_000;
        List<ScaleType> objs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) objs.add(new ScaleType(i));
        keep(objs);

        Object[] snap = walker.snapshotObjects(ScaleType.class);

        assertThat(snap).hasSize(n);
        assertThat(identitySet(snap)).hasSize(n); // no duplicates, no drops
    }

    // ----------------------------------------------------------------------------------------------

    private void keep(Object... objs) {
        Collections.addAll(keepAlive, objs);
    }

    private static Set<Object> identitySet(Object[] objects) {
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        if (objects != null) Collections.addAll(set, objects);
        return set;
    }
}
