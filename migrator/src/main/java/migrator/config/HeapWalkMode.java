package migrator.config;

/**
 * Defines the heap walking strategy used during migration.
 *
 * <p>The heap walk mode determines how the migration engine discovers
 * objects that need to be migrated. This can be configured via the
 * {@code migration.heap.walk.mode} property in the configuration file.
 *
 * @see MigrationConfig#heapWalkMode()
 * @see migrator.heap.HeapWalker
 */
public enum HeapWalkMode {
    /**
     * Walk the entire JVM heap to discover all objects.
     *
     * <p>This mode provides comprehensive coverage but has higher
     * performance overhead. Use this mode when:
     * <ul>
     *   <li>You need to migrate all instances of a type</li>
     *   <li>Objects may be held in unexpected locations</li>
     *   <li>Performance is less critical than completeness</li>
     * </ul>
     */
    FULL,

    /**
     * Walk only objects of explicitly specified classes.
     *
     * <p>This mode is more efficient but requires knowing which
     * classes hold references to objects being migrated. Use this
     * mode when:
     * <ul>
     *   <li>You know exactly where migrated objects are stored</li>
     *   <li>Performance is critical</li>
     *   <li>The object graph is well-understood</li>
     * </ul>
     */
    SPEC
}
