package migrator.benchmark;

import migrator.patch.ForwardingTable;
import migrator.patch.ReferencePatcher;
import migrator.patch.ReflectionReferencePatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-process throughput benchmark of {@link ReflectionReferencePatcher}.
 *
 * <p>No native agent is needed — the patcher operates on plain objects. Each timed iteration
 * patches a freshly-built graph (patching mutates in place), so build cost is excluded from the
 * measurement, and a fresh forwarding table is used per iteration.
 *
 * <p>Two workloads exercise the distinct cost paths:
 * <ul>
 *   <li><b>graph-only</b> — nodes + cyclic neighbor lists; overwhelmingly NON-migrated element
 *       walks, which dominate the per-element {@code tryCreateReplacementContainer} cost.</li>
 *   <li><b>mixed</b> — additionally a large migrated Set/Map/array and a list of {@code Optional}
 *       elements, exercising the collection-rebuild and immutable-container paths.</li>
 * </ul>
 *
 * Run: java -cp &lt;test-classes:classes:deps&gt; migrator.benchmark.PatcherABBench
 */
public class PatcherABBench {

    public interface Payload { int id(); }
    public static class OldPayload implements Payload { final int id; final byte[] d; OldPayload(int id){this.id=id;this.d=new byte[16];} public int id(){return id;} }
    public static class NewPayload implements Payload { final int id; NewPayload(int id){this.id=id;} public int id(){return id;} }

    public static class GraphNode {
        final int id;
        Payload payload;
        final List<GraphNode> neighbors = new ArrayList<>();
        GraphNode(int id, Payload p) { this.id = id; this.payload = p; }
    }

    /** Root holder reachable from one patchObject() call. */
    public static class Root {
        List<GraphNode> nodes;          // big list of non-migrated nodes (each holds a migrated payload)
        Map<Object, Object> map;        // String -> migrated payload
        Object[] array;                 // migrated payloads
        Set<Object> set;                // migrated payloads
        List<Object> optionals;         // Optional.of(migrated payload) elements
    }

    /**
     * Builds an equivalent fresh graph and populates {@code forwarding} for it.
     *
     * @param mixed if true, also attaches a large migrated Set/Map/array/Optional-list (exercises
     *              the collection-rebuild paths); if false, a "graph-only" workload of nodes +
     *              cyclic neighbor lists — overwhelmingly NON-migrated element walks, which isolates
     *              the per-element {@code tryCreateReplacementContainer} cost the new version adds.
     */
    static Root build(int n, ForwardingTable forwarding, boolean mixed) {
        Root root = new Root();
        GraphNode[] nodes = new GraphNode[n];
        for (int i = 0; i < n; i++) {
            OldPayload old = new OldPayload(i);
            forwarding.put(old, new NewPayload(i));
            nodes[i] = new GraphNode(i, old);
        }
        // cyclic neighbor links: each node points to next two (lots of NON-migrated elements to walk)
        for (int i = 0; i < n; i++) {
            nodes[i].neighbors.add(nodes[(i + 1) % n]);
            nodes[i].neighbors.add(nodes[(i + 7) % n]);
        }
        root.nodes = new ArrayList<>(Arrays.asList(nodes));

        if (mixed) {
            int m = Math.max(1, n / 10);
            root.map = new HashMap<>();
            root.set = new HashSet<>();
            root.array = new Object[m];
            root.optionals = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                root.map.put("k" + i, nodes[i].payload);
                root.set.add(nodes[i].payload);
                root.array[i] = nodes[i].payload;
                root.optionals.add(Optional.of(nodes[i].payload));
            }
        }
        return root;
    }

    static long median(long[] xs) {
        long[] c = xs.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    /** Times {@code iters} patch runs (each on a fresh graph), returns per-run nanos. */
    static long[] measure(int n, int iters, boolean mixed) {
        long[] times = new long[iters];
        for (int it = 0; it < iters; it++) {
            ForwardingTable fwd = new ForwardingTable();
            Root root = build(n, fwd, mixed);
            ReferencePatcher patcher = new ReflectionReferencePatcher(fwd);
            long t0 = System.nanoTime();
            patcher.patchObject(root);
            times[it] = System.nanoTime() - t0;
            // sanity: a migrated payload must now be the new type
            if (!(root.nodes.get(0).payload instanceof NewPayload)) {
                throw new IllegalStateException("patcher did not patch");
            }
        }
        return times;
    }

    public static void main(String[] args) {
        int[] sizes = { 50_000, 200_000 };
        int warmup = 8, measured = 15;

        System.out.println("ReflectionReferencePatcher throughput");
        System.out.println("(median of " + measured + " runs after " + warmup + " warmup; lower = faster)\n");

        System.out.printf("%-12s %-10s %-12s %-14s%n", "workload", "N", "median ms", "µs/object");
        System.out.println("-".repeat(50));

        for (boolean mixed : new boolean[] { false, true }) {
            String label = mixed ? "mixed" : "graph-only";
            for (int n : sizes) {
                measure(n, warmup, mixed); // steady state
                long med = median(measure(n, measured, mixed));
                double ms = med / 1_000_000.0;
                double usPerObj = (med / 1_000.0) / n;
                System.out.printf("%-12s %-10d %-12.2f %-14.3f%n", label, n, ms, usPerObj);
            }
        }
    }
}
