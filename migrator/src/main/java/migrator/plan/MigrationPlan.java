package migrator.plan;

import java.util.*;

import migrator.exceptions.MigrateException;

/**
 * Immutable migration plan containing ordered migrator descriptors.
 *
 * <p>A MigrationPlan defines which classes will be migrated and in what order.
 * It provides:
 * <ul>
 *   <li>Lookup of migrators by source class</li>
 *   <li>Mapping from source classes to target classes</li>
 *   <li>Topologically ordered list of migrators (dependencies first)</li>
 * </ul>
 *
 * <p>Plans are built using {@link #build(List)}, which validates that:
 * <ul>
 *   <li>Each source class has exactly one migrator</li>
 *   <li>Each target class is targeted by exactly one migrator</li>
 *   <li>Source and target classes share a common interface</li>
 *   <li>There are no cycles in the migration graph</li>
 * </ul>
 *
 * @see MigratorDescriptor
 * @see migrator.engine.MigrationEngine
 */
public final class MigrationPlan {

    private final Map<Class<?>, MigratorDescriptor> bySource;
    private final Map<Class<?>, Class<?>> targetBySource;
    private final List<MigratorDescriptor> ordered;

    private MigrationPlan(
            Map<Class<?>, MigratorDescriptor> bySource,
            Map<Class<?>, Class<?>> targetBySource,
            List<MigratorDescriptor> ordered
    ) {
        this.bySource = bySource;
        this.targetBySource = targetBySource;
        this.ordered = ordered;
    }

    // ===== public API =====

    /**
     * Checks if a migration exists for the given source class.
     *
     * @param cls the source class to check
     * @return true if a migrator is registered for this class
     */
    public boolean hasMigration(Class<?> cls) {
        return bySource.containsKey(cls);
    }

    /**
     * Gets the migrator descriptor for a given source class.
     *
     * @param cls the source class
     * @return the migrator descriptor, or null if no migration exists for this class
     */
    public MigratorDescriptor migratorFor(Class<?> cls) {
        return bySource.get(cls);
    }

    /**
     * Gets the target class for a given source class.
     *
     * @param cls the source class
     * @return the target class, or null if no migration exists for this class
     */
    public Class<?> targetOf(Class<?> cls) {
        return targetBySource.get(cls);
    }

    /**
     * Gets the list of migrators in topological order (dependencies first).
     *
     * @return an immutable list of migrator descriptors in execution order
     */
    public List<MigratorDescriptor> orderedMigrators() {
        return ordered;
    }

    // ===== factory =====

    /**
     * Builds a migration plan from a list of migrator descriptors.
     *
     * <p>This method validates the descriptors and computes the execution order:
     * <ul>
     *   <li>Checks for duplicate source classes</li>
     *   <li>Checks for duplicate target classes</li>
     *   <li>Validates that source and target share a common interface</li>
     *   <li>Detects cycles in the migration graph</li>
     *   <li>Computes topological order for execution</li>
     * </ul>
     *
     * @param descriptors the migrator descriptors to include in the plan
     * @return an immutable migration plan
     * @throws MigrateException if validation fails
     */
    public static MigrationPlan build(List<MigratorDescriptor> descriptors)
            throws MigrateException {

        Objects.requireNonNull(descriptors, "descriptors");

        Map<Class<?>, MigratorDescriptor> bySource = new HashMap<>();
        Map<Class<?>, Class<?>> targetBySource = new HashMap<>();
        Map<Class<?>, Class<?>> sourceByTarget = new HashMap<>();

        for (MigratorDescriptor d : descriptors) {
            Objects.requireNonNull(d, "descriptor");
            Class<?> from = d.from();
            Class<?> to = d.to();

            if (bySource.containsKey(from)) {
                throw new MigrateException(
                        "Duplicate migrator for source class: " + from.getName()
                );
            }

            if (sourceByTarget.containsKey(to)) {
                throw new MigrateException(
                        "Multiple migrators target the same class: " + to.getName()
                );
            }

            validateCompatibility(from, to, d.commonInterface());

            bySource.put(from, d);
            targetBySource.put(from, to);
            sourceByTarget.put(to, from);
        }

        detectCycles(targetBySource);
        List<MigratorDescriptor> ordered = topologicalOrder(bySource, targetBySource);

        return new MigrationPlan(
                Map.copyOf(bySource),
                Map.copyOf(targetBySource),
                List.copyOf(ordered)
        );
    }

    // ===== validation =====

    /** Verifies that both the source and target classes implement the inferred common interface. */
    private static void validateCompatibility(
            Class<?> from,
            Class<?> to,
            Class<?> commonInterface
    ) throws MigrateException {

        if (!commonInterface.isAssignableFrom(from)) {
            throw new MigrateException(
                    "Source does not implement common interface: " +
                    from.getName()
            );
        }

        if (!commonInterface.isAssignableFrom(to)) {
            throw new MigrateException(
                    "Target does not implement common interface: " +
                    to.getName()
            );
        }
    }

    /** Throws if the source&rarr;target edges form a cycle (e.g. A&rarr;B&rarr;A). */
    private static void detectCycles(Map<Class<?>, Class<?>> edges)
            throws MigrateException {

        Set<Class<?>> visited = new HashSet<>();
        Set<Class<?>> stack = new HashSet<>();

        for (Class<?> node : edges.keySet()) {
            if (dfsCycle(node, edges, visited, stack)) {
                throw new MigrateException(
                        "Migration cycle detected starting at " + node.getName()
                );
            }
        }
    }

    /** Depth-first helper for {@link #detectCycles}; returns true if {@code node} lies on a cycle. */
    private static boolean dfsCycle(
            Class<?> node,
            Map<Class<?>, Class<?>> edges,
            Set<Class<?>> visited,
            Set<Class<?>> stack
    ) {
        if (stack.contains(node)) return true;
        if (!visited.add(node)) return false;

        stack.add(node);
        Class<?> next = edges.get(node);
        if (next != null && dfsCycle(next, edges, visited, stack)) {
            return true;
        }
        stack.remove(node);
        return false;
    }

    // ===== ordering =====

    /**
     * Guarantees that:
     * A -> B -> C
     * order = C, B, A
     */
    private static List<MigratorDescriptor> topologicalOrder(
            Map<Class<?>, MigratorDescriptor> bySource,
            Map<Class<?>, Class<?>> edges
    ) {
        List<MigratorDescriptor> result = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();

        for (Class<?> node : bySource.keySet()) {
            topoDfs(node, bySource, edges, visited, result);
        }

        return result;
    }

    /** Post-order DFS helper for {@link #topologicalOrder}: appends a node after its target. */
    private static void topoDfs(
            Class<?> node,
            Map<Class<?>, MigratorDescriptor> bySource,
            Map<Class<?>, Class<?>> edges,
            Set<Class<?>> visited,
            List<MigratorDescriptor> result
    ) {
        if (!visited.add(node)) return;

        Class<?> next = edges.get(node);
        if (next != null && bySource.containsKey(next)) {
            topoDfs(next, bySource, edges, visited, result);
        }

        result.add(bySource.get(node));
    }
}
