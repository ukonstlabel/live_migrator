package migrator.bench;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Configurable object graph for the scalability study (RQ1 / P1 #7). Lets each independent
 * axis be varied on its own:
 * <ul>
 *   <li><b>m</b> — number of migratable nodes (vertices V);</li>
 *   <li><b>fanout</b> — references per node (edges E = m·fanout) — exercises the O(V+E) patch;</li>
 *   <li><b>payloadSize</b> — bytes per node — grows the heap without changing V/E, isolating the
 *       O(heap) cost of the walk.</li>
 * </ul>
 *
 * <p>{@code OldNode → NewNode} is migrated by {@link NodeMigrator}; the engine then patches every
 * reference: the {@link GraphHolder#nodes} root list (m edges) and each node's {@code refs} array
 * (m·fanout edges).
 */
public final class NodeGraph {

    private NodeGraph() {}

    /** Common domain interface so the engine has a meaningful migrated type. */
    public interface Node {
        int getId();
    }

    /** Source node: id, opaque payload, and {@code fanout} edges to other nodes. */
    public static final class OldNode implements Node {
        public final int id;
        public final byte[] data;
        public final Object[] refs;

        public OldNode(int id, byte[] data, int fanout) {
            this.id = id;
            this.data = data;
            this.refs = new Object[fanout];
        }

        @Override public int getId() { return id; }
    }

    /** Target node (changes shape: adds {@code migratedAt}). */
    public static final class NewNode implements Node {
        public final int id;
        public final byte[] data;
        public final Object[] refs;
        public final long migratedAt;

        public NewNode(int id, byte[] data, Object[] refs, long migratedAt) {
            this.id = id;
            this.data = data;
            this.refs = refs;
            this.migratedAt = migratedAt;
        }

        @Override public int getId() { return id; }
    }

    /** Transformation reused by the engine; the {@code refs} array is shared and patched afterward. */
    @Migrator
    public static final class NodeMigrator implements ClassMigrator<OldNode, NewNode> {
        @Override
        public NewNode migrate(OldNode old) {
            return new NewNode(old.id, old.data, old.refs, System.nanoTime());
        }
    }

    /**
     * Background heap filler: live objects of a class the migrator does NOT touch. Grows the total
     * live heap {@code N} at a fixed migrated-set size {@code m}, isolating the heap-walk's
     * dependence on total heap from its dependence on the number of migrated instances (axis N).
     */
    public static final class Filler {
        public final byte[] pad;
        public Filler(int size) { pad = new byte[Math.max(0, size)]; }
    }

    /**
     * Holds the graph root in a static field so it is a reachable GC root the engine finds by
     * scanning {@code GraphHolder.class}'s static fields.
     */
    public static final class GraphHolder {
        public static volatile List<Node> nodes;
        /** Background filler held alive so it counts toward total heap N during the walk (axis N). */
        public static volatile List<Object> bg;

        private GraphHolder() {}

        /** Builds {@code m} nodes, each with {@code fanout} deterministic edges to other nodes (ring). */
        public static void build(int m, int fanout, int payloadSize) {
            build(m, fanout, payloadSize, "ring", 0, 0);
        }

        /**
         * Builds the migratable graph plus optional background heap.
         *
         * @param layout    edge wiring at fixed {@code m}: {@code "ring"} (each node → {@code fanout}
         *                  successors, default), {@code "chain"} (one deep linear chain, depth = m,
         *                  E = m−1), or {@code "star"} (one root → all others, depth = 1, E = m−1).
         *                  {@code chain}/{@code star} hold V and E ≈ fixed and vary only depth (axis L).
         * @param bgObjects number of background {@link Filler} objects to keep live (axis N)
         * @param bgSize    bytes per background object
         */
        public static void build(int m, int fanout, int payloadSize,
                                 String layout, int bgObjects, int bgSize) {
            List<Node> ns = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                byte[] data = new byte[payloadSize];
                if (payloadSize > 0) data[0] = (byte) (i & 0xFF);
                int slots = switch (layout) {
                    case "chain" -> (i < m - 1) ? 1 : 0;          // forward edge except last
                    case "star"  -> (i == 0) ? Math.max(0, m - 1) : 0; // root holds all edges
                    default      -> fanout;                        // ring
                };
                ns.add(new OldNode(i, data, slots));
            }
            // Wire edges deterministically (reproducible across runs); skip self-references.
            switch (layout) {
                case "chain" -> {
                    for (int i = 0; i < m - 1; i++) ((OldNode) ns.get(i)).refs[0] = ns.get(i + 1);
                }
                case "star" -> {
                    OldNode root = (OldNode) ns.get(0);
                    for (int j = 1; j < m; j++) root.refs[j - 1] = ns.get(j);
                }
                default -> {
                    for (int i = 0; i < m; i++) {
                        OldNode node = (OldNode) ns.get(i);
                        for (int k = 0; k < fanout; k++) node.refs[k] = ns.get((i + 1 + k) % m);
                    }
                }
            }
            nodes = ns;

            if (bgObjects > 0) {
                List<Object> filler = new ArrayList<>(bgObjects);
                for (int i = 0; i < bgObjects; i++) filler.add(new Filler(bgSize));
                bg = filler;
            } else {
                bg = null;
            }
        }

        public static void reset() {
            nodes = null;
            bg = null;
        }
    }
}
