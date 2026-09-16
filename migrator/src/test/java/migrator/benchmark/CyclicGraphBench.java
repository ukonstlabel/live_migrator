package migrator.benchmark;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;
import migrator.commit.CommitManager;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;
import migrator.engine.MigrationEngine;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import migrator.smoke.SmokeTestResult;
import migrator.smoke.SmokeTestRunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Stress test for cyclic object graphs.
 *
 * The migrated type is the leaf Payload (OldPayload -> NewPayload). Graph nodes
 * (GraphNode) are NOT migrated; they form cycles and each carries a payload, so the
 * patcher must traverse a cyclic structure (relying on its visited set) and replace the
 * payload reference at every node.
 *
 * Three shapes:
 *   - ring   : a single ring of N nodes (deepest recursion -> stresses the call stack)
 *   - wide   : N nodes with random chords (many cycles, shallow diameter)
 *   - many   : M independent small rings (lots of separate cyclic graphs)
 *
 * Roots are held in a static field so the engine reaches them via static-field patching.
 * Run with the agent: -agentpath:agent/libagent.so
 */
public class CyclicGraphBench {

    public interface Payload { int id(); }

    public static class OldPayload implements Payload {
        final int id; final byte[] data;
        OldPayload(int id) { this.id = id; this.data = new byte[32]; }
        public int id() { return id; }
    }

    public static class NewPayload implements Payload {
        final int id; final byte[] data; final long migratedAt;
        NewPayload(int id, byte[] data) { this.id = id; this.data = data; this.migratedAt = System.nanoTime(); }
        public int id() { return id; }
    }

    @Migrator
    public static class PayloadMigrator implements ClassMigrator<OldPayload, NewPayload> {
        @Override public NewPayload migrate(OldPayload o) { return new NewPayload(o.id, o.data); }
    }

    public static class GraphNode {
        final int id;
        Payload payload;                        // migrated reference (Old -> New)
        final List<GraphNode> neighbors = new ArrayList<>();
        GraphNode(int id, Payload payload) { this.id = id; this.payload = payload; }
    }

    /** Static root so the engine patches the graph via static-field traversal. */
    public static List<GraphNode> ROOTS = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("================================================================");
        System.out.println("  Live Migrator -- Cyclic Graph Stress Test");
        System.out.println("================================================================");
        Runtime rt = Runtime.getRuntime();
        System.out.printf("Heap max %,d MB | CPUs %d | maxStackHint(-Xss default)%n%n",
                rt.maxMemory() / (1024 * 1024), rt.availableProcessors());

        // ring: increasing depth until it either completes or blows the stack
        run("ring-1K",    () -> buildRing(1_000));
        run("ring-10K",   () -> buildRing(10_000));
        run("ring-50K",   () -> buildRing(50_000));
        run("ring-100K",  () -> buildRing(100_000));

        // wide cyclic graph: many cycles, shallow diameter
        run("wide-100K-x6", () -> buildWide(100_000, 6));
        run("wide-500K-x6", () -> buildWide(500_000, 6));

        // many independent small rings
        run("many-50Kx10",  () -> buildManyRings(50_000, 10));
        run("many-10Kx100", () -> buildManyRings(10_000, 100));
    }

    interface GraphBuilder { int build(); } // returns total node count

    static void run(String label, GraphBuilder builder) {
        ROOTS = new ArrayList<>();
        int nodes = builder.build();
        System.out.printf("---- %-14s nodes=%,d ----%n", label, nodes);

        try {
            SmokeTestRunner smoke = new SmokeTestRunner.Builder()
                    .addSmokeTest(created -> SmokeTestResult.ok("noop")).build();
            MigrationEngine engine = new MigrationEngine(
                    PayloadMigrator.class, null, smoke,
                    new CommitManager(NoopCracController.INSTANCE),
                    new RollbackManager(NoopCracController.INSTANCE));
            engine.setFullHeapWalk(false);
            engine.setAllTimeoutsSeconds(0);

            long t0 = System.nanoTime();
            engine.migrate(Set.of(CyclicGraphBench.class), null, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            MigrationMetrics m = MigrationEngine.getLastMetrics();
            Verify v = verify();
            System.out.printf("  migrate %,d ms  (1st %,d, crit %,d, 2nd %,d, reg %,d)  migrated=%,d%n",
                    ms,
                    m.phaseDuration(Phase.FIRST_PASS), m.phaseDuration(Phase.CRITICAL_PHASE),
                    m.phaseDuration(Phase.SECOND_PASS), m.phaseDuration(Phase.REGISTRY_UPDATE),
                    m.objectsMigrated());
            System.out.printf("  verify: reachable=%,d  newPayload=%,d  oldPayload=%,d  -> %s%n%n",
                    v.reachable, v.newCount, v.oldCount,
                    (v.oldCount == 0 && v.newCount == v.reachable) ? "OK (100% patched, no cycle hang)" : "MISMATCH");
        } catch (StackOverflowError soe) {
            System.out.printf("  *** StackOverflowError — recursive patch blew the stack on this depth ***%n%n");
        } catch (Throwable t) {
            System.out.printf("  FAILED: %s%n", t);
            t.printStackTrace(System.err);
            System.out.println();
        } finally {
            ROOTS = new ArrayList<>();
            System.gc();
        }
    }

    static class Verify { int reachable, newCount, oldCount; }

    /** BFS the whole graph and tally payload types. */
    static Verify verify() {
        Verify v = new Verify();
        Set<GraphNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<GraphNode> q = new ArrayDeque<>(ROOTS);
        for (GraphNode r : ROOTS) seen.add(r);
        while (!q.isEmpty()) {
            GraphNode n = q.poll();
            v.reachable++;
            if (n.payload instanceof NewPayload) v.newCount++;
            else if (n.payload instanceof OldPayload) v.oldCount++;
            for (GraphNode nb : n.neighbors) if (seen.add(nb)) q.add(nb);
        }
        return v;
    }

    // ── Builders ────────────────────────────────────────────────────

    static int buildRing(int n) {
        GraphNode[] nodes = new GraphNode[n];
        for (int i = 0; i < n; i++) nodes[i] = new GraphNode(i, new OldPayload(i));
        for (int i = 0; i < n; i++) nodes[i].neighbors.add(nodes[(i + 1) % n]);
        ROOTS.add(nodes[0]);
        return n;
    }

    static int buildWide(int n, int edges) {
        GraphNode[] nodes = new GraphNode[n];
        for (int i = 0; i < n; i++) nodes[i] = new GraphNode(i, new OldPayload(i));
        Random rnd = new Random(42);
        for (int i = 0; i < n; i++) {
            nodes[i].neighbors.add(nodes[(i + 1) % n]); // ensure connected + cyclic
            for (int e = 0; e < edges - 1; e++) nodes[i].neighbors.add(nodes[rnd.nextInt(n)]);
        }
        ROOTS.add(nodes[0]);
        return n;
    }

    static int buildManyRings(int ringSize, int count) {
        for (int g = 0; g < count; g++) {
            GraphNode[] nodes = new GraphNode[ringSize];
            int base = g * ringSize;
            for (int i = 0; i < ringSize; i++) nodes[i] = new GraphNode(base + i, new OldPayload(base + i));
            for (int i = 0; i < ringSize; i++) nodes[i].neighbors.add(nodes[(i + 1) % ringSize]);
            ROOTS.add(nodes[0]);
        }
        return ringSize * count;
    }
}
