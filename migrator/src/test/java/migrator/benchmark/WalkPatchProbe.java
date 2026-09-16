package migrator.benchmark;

import migrator.heap.NativeHeapWalker;
import migrator.patch.ForwardingTable;
import migrator.patch.ReflectionReferencePatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Isolates the two halves of the second pass so we can read each one's scaling
 * exponent independently:
 *
 *   walkMs   = native filtered heap walk (IterateThroughHeap + GetObjectsWithTags)
 *   patchMs  = ReflectionReferencePatcher over the walked objects (shared visited set)
 *
 * Sizes double each step, so an O(N) phase should roughly double in time and an
 * O(N^2) phase should roughly 4x. Run with the agent on -agentpath.
 */
public class WalkPatchProbe {

    public static class Old {
        public final int id;
        public final String name;
        public final byte[] data;
        public Old(int id, String name, byte[] data) { this.id = id; this.name = name; this.data = data; }
    }

    public static class New {
        public final int id;
        public final String name;
        public final byte[] data;
        public New(int id, String name, byte[] data) { this.id = id; this.name = name; this.data = data; }
    }

    static final int[] SIZES = {10_000, 20_000, 40_000, 80_000, 160_000};

    // Keep strong refs so nothing is GC'd mid-measurement.
    static List<Old> olds;
    static List<New> news;

    public static void main(String[] args) throws Exception {
        NativeHeapWalker walker = new NativeHeapWalker();

        System.out.printf("%-10s %-10s %-12s %-14s %-14s %-12s%n",
                "N", "walked", "walkMs", "patchShared", "patchFresh", "patch/obj µs");
        System.out.println("-".repeat(78));

        Double prevWalk = null, prevPatch = null;
        Integer prevN = null;

        for (int n : SIZES) {
            olds = new ArrayList<>(n);
            news = new ArrayList<>(n);
            ForwardingTable forwarding = new ForwardingTable();
            for (int i = 0; i < n; i++) {
                byte[] d = new byte[64];
                Old o = new Old(i, "item-" + i, d);
                New nw = new New(i, "item-" + i, d);
                olds.add(o);
                news.add(nw);
                forwarding.put(o, nw);
            }

            ReflectionReferencePatcher patcher = new ReflectionReferencePatcher(forwarding);

            // ── measure native walk ──
            long w0 = System.nanoTime();
            Set<Object> walked = walker.walkHeap(Set.of(Old.class, New.class));
            long w1 = System.nanoTime();
            double walkMs = (w1 - w0) / 1e6;

            // ── measure patch (shared visited) ──
            long p0 = System.nanoTime();
            patcher.patchObjects(walked);
            long p1 = System.nanoTime();
            double patchSharedMs = (p1 - p0) / 1e6;

            // ── measure patch (fresh visited per object) on a fresh patcher ──
            ReflectionReferencePatcher patcher2 = new ReflectionReferencePatcher(forwarding);
            long f0 = System.nanoTime();
            for (Object o : walked) patcher2.patchObject(o);
            long f1 = System.nanoTime();
            double patchFreshMs = (f1 - f0) / 1e6;

            double perObj = walked.isEmpty() ? 0 : patchSharedMs * 1000.0 / walked.size();
            System.out.printf("%-10d %-10d %-12.1f %-14.1f %-14.1f %-12.3f%n",
                    n, walked.size(), walkMs, patchSharedMs, patchFreshMs, perObj);

            if (prevN != null) {
                double f = (double) n / prevN; // size factor (≈2)
                System.out.printf("   -> growth x%.1f size:  walk x%.2f  patchShared x%.2f%n",
                        f, walkMs / prevWalk, patchSharedMs / prevPatch);
            }
            prevWalk = walkMs; prevPatch = patchSharedMs; prevN = n;

            olds = null; news = null; walked = null;
            System.gc();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            System.gc();
        }
    }
}
