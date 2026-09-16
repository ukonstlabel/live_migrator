package migrator.patch;

import java.util.IdentityHashMap;

/**
 * Maps old objects to their migrated counterparts using identity-based comparison.
 *
 * <p>This table is used during migration to track which old objects have been
 * migrated and what their new counterparts are.
 *
 * <p>Key features:
 * <ul>
 *   <li>Identity-based comparison (not equals/hashCode)</li>
 *   <li>Fast O(1) lookups via a single {@link IdentityHashMap} (no per-lookup allocation)</li>
 * </ul>
 *
 * <p>The table is created fresh per migration and lives only for the duration of that
 * migration; the old objects it maps are kept strongly reachable by the engine while it
 * runs, so weak references / GC bookkeeping would add allocation cost without benefit.
 *
 * <p><b>Not thread-safe.</b> The contract is that all {@link #put} calls happen during the
 * migrate pass and complete-before the patch pass begins, after which the table is only read.
 * If reference patching is ever parallelized, this map must be made concurrent (or safely
 * published) accordingly.
 *
 * @see ReferencePatcher
 */
public final class ForwardingTable {

    private final IdentityHashMap<Object, Object> map = new IdentityHashMap<>();

    /**
     * Register a mapping from old object to new object.
     *
     * @param oldObj the original object
     * @param newObj the migrated object
     */
    public void put(Object oldObj, Object newObj) {
        map.put(oldObj, newObj);
    }

    /**
     * Get the migrated object for the given old object.
     *
     * @param oldObj the original object
     * @return the migrated object, or null if not found
     */
    public Object get(Object oldObj) {
        return map.get(oldObj);
    }

    /**
     * Check if the table contains a mapping for the given object.
     *
     * @param oldObj the original object
     * @return true if a mapping exists
     */
    public boolean contains(Object oldObj) {
        return map.containsKey(oldObj);
    }

    /**
     * Remove the mapping for the given object.
     *
     * @param oldObj the original object
     */
    public void remove(Object oldObj) {
        map.remove(oldObj);
    }

    /** Clear all mappings. */
    public void clear() {
        map.clear();
    }
}
