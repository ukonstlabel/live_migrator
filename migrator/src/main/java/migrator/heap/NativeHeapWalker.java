package migrator.heap;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import migrator.exceptions.MigrateException;

/**
 * Native implementation of {@link HeapWalker} using JNI.
 *
 * <p>This implementation uses native methods to efficiently walk the JVM heap
 * and locate objects of specific types. It supports:
 * <ul>
 *   <li>Taking snapshots of objects by class type</li>
 *   <li>Bulk resolution of all matched objects in a single native call</li>
 *   <li>Full heap walks returning all live objects</li>
 *   <li>Filtered heap walks for specific classes only</li>
 *   <li>Epoch advancement for tracking migration generations</li>
 * </ul>
 *
 * <p><strong>Note:</strong> Requires the native migrator library to be loaded.
 *
 * @see HeapWalker
 */
public final class NativeHeapWalker implements HeapWalker {

    private static native Object[] nativeSnapshotObjects(Class<?> targetClass);
    private native Object[] nativeWalkHeap();
    private native Object[] nativeWalkHeapFiltered(Class<?>[] targetClasses);
    private static native void nativeAdvanceEpoch();

    @Override
    public Object[] snapshotObjects(Class<?> targetClass) {
        Object[] result = nativeSnapshotObjects(targetClass);
        return result != null ? result : new Object[0];
    }

    @Override
    public Set<Object> walkHeap() {
        Object[] objects = nativeWalkHeap();
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        if (objects != null) {
            Collections.addAll(set, objects);
        }
        return set;
    }

    @Override
    public Set<Object> walkHeap(Collection<Class<?>> classes) throws MigrateException {
        if (classes == null || classes.isEmpty()) return Collections.emptySet();
        Class<?>[] targets = classes.stream()
                                .filter(Objects::nonNull)
                                .distinct()
                                .toArray(Class<?>[]::new);
        Object[] objs = nativeWalkHeapFiltered(targets);
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        if (objs != null) Collections.addAll(set, objs);
        return set;
    }
    
    /**
     * Advances the migration epoch counter.
     *
     * <p>This method should be called after a successful migration to mark
     * a new generation of objects. It helps the native layer distinguish
     * between pre-migration and post-migration objects.
     */
    public static void advanceEpoch() {
        nativeAdvanceEpoch();
    }
}
