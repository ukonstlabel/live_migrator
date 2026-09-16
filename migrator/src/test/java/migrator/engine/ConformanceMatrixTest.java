package migrator.engine;

import migrator.patch.ForwardingTable;
import migrator.patch.ReflectionReferencePatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformance matrix of reference-kind coverage (RQ2 / P1 #5).
 *
 * <p>This is a <b>characterization</b> test: for each kind of reference that can hold a migrated
 * object, it sets up a holder pointing at an {@code Old} instance, runs the
 * {@link ReflectionReferencePatcher} with a forwarding entry {@code old -> new}, then inspects the
 * holder and records the measured outcome:
 * <ul>
 *   <li><b>PASS</b> — the reference now points at the migrated {@code New} instance;</li>
 *   <li><b>PARTIAL</b> — the value is migrated but a semantic is lost (e.g. a rebuilt
 *       Weak/SoftReference can no longer be re-registered with its original ReferenceQueue);</li>
 *   <li><b>FAIL</b> — the reference still points at the stale {@code Old} instance.</li>
 * </ul>
 *
 * <p>The measured rows are printed and written to {@code target/conformance-matrix.{md,csv}} so the
 * citable table is generated from real behaviour rather
 * than asserted by hand. A handful of always-safe kinds are asserted PASS as a regression guard;
 * the patcher must also never throw on any kind (robustness).
 *
 * <p>Two rows (off-heap, JNI global) are <i>by construction</i>, not live probes: a reference
 * encoded as a primitive off-heap address, or held as a JNI global outside the Java heap graph, is
 * unreachable by a reflective heap patcher in principle. They are marked accordingly.
 */
@DisplayName("Reference-coverage conformance matrix")
class ConformanceMatrixTest {

    interface Id { int id(); }
    static final class OldImpl implements Id {
        final int id; OldImpl(int id) { this.id = id; }
        public int id() { return id; }
    }
    static final class NewImpl implements Id {
        final int id; NewImpl(int id) { this.id = id; }
        public int id() { return id; }
    }

    private static final String PASS = "PASS", PARTIAL = "PARTIAL", FAIL = "FAIL";

    record Row(String category, String kind, String outcome, boolean measured, String note) {}

    private final List<Row> rows = new ArrayList<>();

    /** Fresh patcher with a single forwarding entry old -> new. */
    private static ReflectionReferencePatcher patcherFor(Object oldObj, Object newObj) {
        ForwardingTable ft = new ForwardingTable();
        ft.put(oldObj, newObj);
        return new ReflectionReferencePatcher(ft);
    }

    /** PASS if current==expectedNew, FAIL if current==old, else the given fallback. */
    private static String classify(Object current, Object old, Object expectedNew, String fallback) {
        if (current == expectedNew) return PASS;
        if (current == old) return FAIL;
        return fallback;
    }

    private void record(String cat, String kind, String outcome, String note) {
        rows.add(new Row(cat, kind, outcome, true, note));
    }
    private void recordByConstruction(String cat, String kind, String outcome, String note) {
        rows.add(new Row(cat, kind, outcome, false, note));
    }

    // ── holders ──────────────────────────────────────────────────────────────
    static final class IfaceField { Id ref; }
    static final class ObjectField { Object ref; }
    static final class ConcreteOldField { OldImpl ref; }           // field typed as the concrete Old class
    static final class Box { Object ref; }                          // generic holder for container kinds
    static class StaticHolder { static Id ref; }
    static final class StaticFinalHolder { static final Id REF = new OldImpl(900); }

    @Test
    @DisplayName("measures PASS/PARTIAL/FAIL for every reference kind and emits the table")
    void conformanceMatrix() {
        // ── Fields ────────────────────────────────────────────────────────────
        {   // interface-typed instance field
            OldImpl o = new OldImpl(1); NewImpl n = new NewImpl(1);
            IfaceField h = new IfaceField(); h.ref = o;
            patcherFor(o, n).patchObject(h);
            record("Fields", "instance field (interface-typed)", classify(h.ref, o, n, FAIL),
                    "direct reflective field replacement");
        }
        {   // Object-typed instance field
            OldImpl o = new OldImpl(2); NewImpl n = new NewImpl(2);
            ObjectField h = new ObjectField(); h.ref = o;
            patcherFor(o, n).patchObject(h);
            record("Fields", "instance field (Object-typed)", classify(h.ref, o, n, FAIL),
                    "direct reflective field replacement");
        }
        {   // field typed as the concrete Old class — New is not assignable
            OldImpl o = new OldImpl(3); NewImpl n = new NewImpl(3);
            ConcreteOldField h = new ConcreteOldField(); h.ref = o;
            patcherFor(o, n).patchObject(h);
            record("Fields", "instance field (concrete Old-typed)", classify(h.ref, o, n, FAIL),
                    "New not assignable to OldImpl-typed field; left in place to avoid type corruption");
        }
        {   // mutable static field
            OldImpl o = new OldImpl(4); NewImpl n = new NewImpl(4);
            StaticHolder.ref = o;
            patcherFor(o, n).patchStaticFields(StaticHolder.class);
            record("Fields", "static field", classify(StaticHolder.ref, o, n, FAIL),
                    "patchStaticFields reflective replacement");
            StaticHolder.ref = null;
        }
        {   // static final field
            Id o = StaticFinalHolder.REF; NewImpl n = new NewImpl(900);
            patcherFor(o, n).patchStaticFields(StaticFinalHolder.class);
            record("Fields", "static final field", classify(StaticFinalHolder.REF, o, n, FAIL),
                    "final field not reassigned; constant may also be JIT-inlined at call sites");
        }

        // ── Arrays & mutable collections ───────────────────────────────────────
        {   // Object[] element
            OldImpl o = new OldImpl(5); NewImpl n = new NewImpl(5);
            Object[] arr = { o };
            patcherFor(o, n).patchObject(arr);
            record("Arrays/mutable collections", "array (Object[])", classify(arr[0], o, n, FAIL),
                    "in-place element replacement");
        }
        {   // ArrayList element
            OldImpl o = new OldImpl(6); NewImpl n = new NewImpl(6);
            List<Id> list = new ArrayList<>(); list.add(o);
            patcherFor(o, n).patchObject(list);
            record("Arrays/mutable collections", "List (ArrayList) element", classify(list.get(0), o, n, FAIL),
                    "in-place set()");
        }
        {   // HashMap value
            OldImpl o = new OldImpl(7); NewImpl n = new NewImpl(7);
            Map<String, Id> map = new HashMap<>(); map.put("k", o);
            patcherFor(o, n).patchObject(map);
            record("Arrays/mutable collections", "Map (HashMap) value", classify(map.get("k"), o, n, FAIL),
                    "in-place put()");
        }
        {   // HashMap key (requires rehash)
            OldImpl o = new OldImpl(8); NewImpl n = new NewImpl(8);
            Map<Id, String> map = new HashMap<>(); map.put(o, "v");
            patcherFor(o, n).patchObject(map);
            boolean keyMigrated = map.containsKey(n) && !map.containsKey(o);
            record("Arrays/mutable collections", "Map (HashMap) key", keyMigrated ? PASS : FAIL,
                    "key removed and re-inserted (rebucket)");
        }
        {   // HashSet element
            OldImpl o = new OldImpl(9); NewImpl n = new NewImpl(9);
            Set<Id> set = new HashSet<>(); set.add(o);
            patcherFor(o, n).patchObject(set);
            boolean migrated = set.contains(n) && !set.contains(o);
            record("Arrays/mutable collections", "Set (HashSet) element", migrated ? PASS : FAIL,
                    "element removed and re-added");
        }

        // ── Immutable containers (rebuilt) ─────────────────────────────────────
        {   // immutable List.of
            OldImpl o = new OldImpl(10); NewImpl n = new NewImpl(10);
            Box h = new Box(); h.ref = List.of(o);
            patcherFor(o, n).patchObject(h);
            Object got = ((List<?>) h.ref).get(0);
            record("Immutable containers", "immutable List (List.of)", classify(got, o, n, FAIL),
                    "field reassigned to a rebuilt immutable list");
        }
        {   // immutable Set.of
            OldImpl o = new OldImpl(11); NewImpl n = new NewImpl(11);
            Box h = new Box(); h.ref = Set.of(o);
            patcherFor(o, n).patchObject(h);
            boolean migrated = ((Set<?>) h.ref).contains(n) && !((Set<?>) h.ref).contains(o);
            record("Immutable containers", "immutable Set (Set.of)", migrated ? PASS : FAIL,
                    "field reassigned to a rebuilt immutable set");
        }
        {   // record holding a migrated reference
            OldImpl o = new OldImpl(12); NewImpl n = new NewImpl(12);
            Box h = new Box(); h.ref = new Holder(o);
            patcherFor(o, n).patchObject(h);
            Object got = ((Holder) h.ref).id();
            record("Immutable containers", "record component", classify(got, o, n, FAIL),
                    "field reassigned to a rebuilt record (canonical ctor)");
        }
        {   // Optional
            OldImpl o = new OldImpl(13); NewImpl n = new NewImpl(13);
            Box h = new Box(); h.ref = Optional.of(o);
            patcherFor(o, n).patchObject(h);
            Object got = ((Optional<?>) h.ref).orElse(null);
            record("Immutable containers", "Optional", classify(got, o, n, FAIL),
                    "field reassigned to Optional.of(new)");
        }

        // ── References ─────────────────────────────────────────────────────────
        {   // WeakReference held in a field
            OldImpl o = new OldImpl(14); NewImpl n = new NewImpl(14);
            Box h = new Box(); h.ref = new WeakReference<>(o);
            patcherFor(o, n).patchObject(h);
            Object got = ((WeakReference<?>) h.ref).get();
            String outcome = got == n ? PARTIAL : (got == o ? FAIL : PARTIAL);
            record("References", "WeakReference", outcome,
                    "referent migrated by rebuilding the Reference; original ReferenceQueue association lost");
        }
        {   // SoftReference held in a field
            OldImpl o = new OldImpl(15); NewImpl n = new NewImpl(15);
            Box h = new Box(); h.ref = new SoftReference<>(o);
            patcherFor(o, n).patchObject(h);
            Object got = ((SoftReference<?>) h.ref).get();
            String outcome = got == n ? PARTIAL : (got == o ? FAIL : PARTIAL);
            record("References", "SoftReference", outcome,
                    "referent migrated by rebuilding; ReferenceQueue association lost");
        }

        // ── Concurrency / async holders ────────────────────────────────────────
        {   // AtomicReference held in an application field (the realistic case)
            OldImpl o = new OldImpl(16); NewImpl n = new NewImpl(16);
            AtomicReference<Id> ar = new AtomicReference<>(o);
            Box h = new Box(); h.ref = ar;
            patcherFor(o, n).patchObject(h);
            record("Concurrency / async", "AtomicReference (in field)", classify(ar.get(), o, n, FAIL),
                    "mutated in place via set() when reached as a field value");
        }
        {   // CompletableFuture, completed with the old object, held in an application field
            OldImpl o = new OldImpl(17); NewImpl n = new NewImpl(17);
            CompletableFuture<Id> cf = CompletableFuture.completedFuture(o);
            Box h = new Box(); h.ref = cf;
            patcherFor(o, n).patchObject(h);
            Object got = cf.getNow(null);
            record("Concurrency / async", "CompletableFuture (completed)", classify(got, o, n, FAIL),
                    "JDK class; internal result field not traversed or rebuilt");
        }

        // ── Out of reflective reach ─────────────────────────────────────────────
        {   // ThreadLocal value (lives in the Thread's ThreadLocalMap, not in the ThreadLocal object)
            OldImpl o = new OldImpl(18); NewImpl n = new NewImpl(18);
            ThreadLocal<Id> tl = new ThreadLocal<>(); tl.set(o);
            Box h = new Box(); h.ref = tl;
            patcherFor(o, n).patchObject(h);
            record("Out of reflective reach", "ThreadLocal value", classify(tl.get(), o, n, FAIL),
                    "value stored per-thread; not reachable from the object graph, and only the patching thread is visible");
            tl.remove();
        }
        {   // lambda / Supplier capturing the old object
            OldImpl o = new OldImpl(19); NewImpl n = new NewImpl(19);
            final Id captured = o;
            Supplier<Id> s = () -> captured;
            Box h = new Box(); h.ref = s;
            patcherFor(o, n).patchObject(h);
            Object got = s.get();
            record("Out of reflective reach", "Supplier / lambda capture", classify(got, o, n, FAIL),
                    "captured value held in a synthetic final field of a hidden class; the patcher does not invoke or rebuild functional values");
        }
        recordByConstruction("Out of reflective reach", "off-heap (Unsafe address)", FAIL,
                "a reference encoded as a primitive off-heap address is not a Java reference; invisible to a reflective patcher");
        recordByConstruction("Out of reflective reach", "JNI global reference", FAIL,
                "held in native code outside the Java heap graph; cannot be enumerated or rewritten from Java");

        // ── emit ────────────────────────────────────────────────────────────────
        emit(rows);

        // robustness: no probe produced a malformed outcome
        assertThat(rows).allSatisfy(r ->
                assertThat(r.outcome()).isIn(PASS, PARTIAL, FAIL));
        // regression guard on always-safe kinds
        assertThat(outcomeOf("instance field (interface-typed)")).isEqualTo(PASS);
        assertThat(outcomeOf("List (ArrayList) element")).isEqualTo(PASS);
        assertThat(outcomeOf("Map (HashMap) value")).isEqualTo(PASS);
        assertThat(outcomeOf("static field")).isEqualTo(PASS);
    }

    record Holder(Id id) {}

    private String outcomeOf(String kind) {
        return rows.stream().filter(r -> r.kind().equals(kind)).findFirst().orElseThrow().outcome();
    }

    private static void emit(List<Row> rows) {
        long pass = rows.stream().filter(r -> r.outcome().equals(PASS)).count();
        long partial = rows.stream().filter(r -> r.outcome().equals(PARTIAL)).count();
        long fail = rows.stream().filter(r -> r.outcome().equals(FAIL)).count();

        StringBuilder console = new StringBuilder("\n=== Reference-coverage conformance matrix ===\n");
        StringBuilder md = new StringBuilder(
                "| Категория | Вид ссылки | Результат | Замер | Примечание |\n"
                        + "|---|---|:---:|:---:|---|\n");
        StringBuilder csv = new StringBuilder("category,kind,outcome,measured,note\n");
        String prevCat = null;
        for (Row r : rows) {
            String mark = switch (r.outcome()) { case PASS -> "✅"; case PARTIAL -> "⚠️"; default -> "❌"; };
            String cat = r.category().equals(prevCat) ? "" : r.category();
            prevCat = r.category();
            md.append(String.format("| %s | %s | %s %s | %s | %s |%n",
                    cat, r.kind(), mark, r.outcome(), r.measured() ? "да" : "по констр.", r.note()));
            console.append(String.format(Locale.ROOT, "  %-8s %-34s %-28s %s%n",
                    r.outcome(), r.kind(), "(" + r.category() + ")", r.measured() ? "" : "[by construction]"));
            csv.append(String.format("%s,%s,%s,%s,\"%s\"%n",
                    r.category(), r.kind(), r.outcome(), r.measured(), r.note()));
        }
        String totals = String.format(Locale.ROOT,
                "%nИтог: %d PASS, %d PARTIAL, %d FAIL из %d видов.%n", pass, partial, fail, rows.size());
        console.append(totals);
        md.append(totals.replace("\n", "\n\n"));
        System.out.print(console);

        try {
            Path dir = Path.of("target");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("conformance-matrix.md"), md.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("conformance-matrix.csv"), csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
