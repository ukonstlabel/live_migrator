package migrator.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the migratable state of {@code M} {@link OldPayload} instances in two realistic
 * holders — a {@code List} and a {@code Map} — in static fields, so it is a reachable GC
 * root. Live Migrator (S0) patches the references in these holders, finding them by
 * scanning the static fields of the supplied {@code StateHolder.class} and walking the heap.
 *
 * <p>The fields are typed as {@link Payload} because after migration the elements become
 * {@link NewPayload} (statically typing them {@code OldPayload} would only get in the way of
 * the reflective replacement at the readability level — at runtime everything is erased).
 */
public final class StateHolder {

    /** Same seed → identical state across runs (reproducibility). */
    public static volatile List<Payload> list;
    public static volatile Map<Integer, Payload> map;

    private StateHolder() {}

    /** Builds fresh state of {@code m} objects with {@code payloadSize} bytes of data each. */
    public static void build(int m, int payloadSize) {
        List<Payload> l = new ArrayList<>(m);
        Map<Integer, Payload> mp = new ConcurrentHashMap<>(Math.max(16, m * 4 / 3), 0.75f, 4);
        for (int i = 0; i < m; i++) {
            byte[] data = new byte[payloadSize];
            data[0] = (byte) (i & 0xFF);
            if (payloadSize > 1) data[1] = (byte) ((i >> 8) & 0xFF);
            OldPayload obj = new OldPayload(i, "item-" + i, data);
            l.add(obj);
            mp.put(i, obj);
        }
        list = l;
        map = mp;
    }

    public static void reset() {
        list = null;
        map = null;
    }
}
