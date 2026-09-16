package migrator.patch;

/**
 * Interface for patching object references during migration.
 *
 * <p>Implementations traverse object graphs and replace references to old
 * objects with references to their migrated counterparts using a
 * {@link ForwardingTable}. This is the core mechanism that ensures all
 * references in the application point to the new migrated objects.
 *
 * <p>Reference patching handles:
 * <ul>
 *   <li>Instance fields (including private and inherited fields)</li>
 *   <li>Static fields (for registries and caches)</li>
 *   <li>Array elements</li>
 *   <li>Collection elements (List, Set, Map)</li>
 *   <li>Immutable containers (Optional, WeakReference, etc.)</li>
 * </ul>
 *
 * @see ReflectionReferencePatcher
 * @see ForwardingTable
 */
public interface ReferencePatcher {

    /**
     * Patch all references within the given object graph.
     *
     * <p>Recursively traverses fields, arrays, and collections starting from
     * the root object. Uses identity comparison to detect cycles and prevent
     * infinite recursion. JDK internal classes are skipped for safety.
     *
     * @param obj the root object to patch (null is safely ignored)
     */
    void patchObject(Object obj);

    /**
     * Patch all references within a batch of object graphs.
     *
     * <p>Equivalent to calling {@link #patchObject(Object)} on each element. Implementations may,
     * but are not required to, share cycle-detection state across the batch; the default
     * implementation uses independent per-root state.
     *
     * @param objects the root objects to patch (null is safely ignored)
     */
    default void patchObjects(Iterable<?> objects) {
        if (objects == null) return;
        for (Object obj : objects) {
            patchObject(obj);
        }
    }

    /**
     * Patch static fields of the given class.
     *
     * <p>Iterates through all static non-primitive fields of the class and
     * its superclasses, replacing references to old objects with their
     * migrated counterparts. This is essential for updating static caches
     * and registries.
     *
     * @param clazz the class whose static fields should be patched (null is safely ignored)
     */
    void patchStaticFields(Class<?> clazz);
}
