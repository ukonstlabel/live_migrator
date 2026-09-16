package migrator.heap;

import java.util.Collection;
import java.util.Set;

import migrator.exceptions.MigrateException;

/**
 * Interface for walking the JVM heap to discover and resolve object references.
 *
 * <p>Implementations of this interface provide mechanisms to:
 * <ul>
 *   <li>Take snapshots of all objects of a given class type</li>
 *   <li>Walk the entire heap or a filtered subset</li>
 * </ul>
 *
 * <p>The primary implementation is {@link NativeHeapWalker}, which uses JNI
 * for efficient heap traversal.
 *
 * <p><strong>Error handling:</strong> native-backed implementations require the native
 * library to be loaded. If it is missing, calls may fail with an unchecked error (e.g.
 * {@link UnsatisfiedLinkError}) rather than {@link MigrateException}. {@link #snapshotObjects}
 * has no checked-exception channel and signals failure only via unchecked exceptions.
 *
 * @see NativeHeapWalker
 */
public interface HeapWalker {

    /**
     * Takes a snapshot of all objects of a given class type and returns them directly.
     *
     * <p>This is the fast path — it resolves all objects in a single bulk JVMTI call
     * instead of returning tags that must be resolved individually.
     *
     * @param targetClass the class type to snapshot
     * @return array of all live instances (never null, may be empty)
     */
    Object[] snapshotObjects(Class<?> targetClass);

    /**
     * Walk the entire heap and return all live objects.
     *
     * <p>This method performs a full heap traversal, which can be expensive
     * for large heaps. Use {@link #walkHeap(Collection)} when you know
     * which classes to target for better performance.
     *
     * @return an identity-based set of all live objects on the heap
     * @throws MigrateException if the heap walk fails (e.g., native library not loaded)
     */
    Set<Object> walkHeap() throws MigrateException;

    /**
     * Walk the heap and return objects of the specified classes only.
     *
     * <p>This method is more efficient than {@link #walkHeap()} when you know
     * which classes hold references to objects being migrated. Only objects
     * whose class is in the provided collection will be returned.
     *
     * @param classes the classes to filter for (null or empty returns empty set)
     * @return an identity-based set of objects matching the specified classes
     * @throws MigrateException if the heap walk fails (e.g., native library not loaded)
     */
    Set<Object> walkHeap(Collection<Class<?>> classes) throws MigrateException;
}
