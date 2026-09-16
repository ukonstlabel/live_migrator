package migrator.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Reflection-based {@link ReferencePatcher} implementation.
 *
 * <p>Features:
 * <ul>
 *   <li>Module-aware access handling (skips JDK internals)</li>
 *   <li>Identity-based visited set to avoid infinite recursion on cycles</li>
 *   <li>Safe access setup using trySetAccessible / setAccessible fallback</li>
 *   <li>Handles collections, arrays, Optional, Reference, ThreadLocal, etc.</li>
 *   <li>Creates replacement containers for immutable collections</li>
 * </ul>
 *
 * @see ForwardingTable
 */
public final class ReflectionReferencePatcher implements ReferencePatcher {

    private static final Logger log = LoggerFactory.getLogger(ReflectionReferencePatcher.class);

    private final ForwardingTable forwarding;

    /**
     * Caches the reflected field arrays per class. A full heap walk patches many objects of the
     * same class, and field enumeration (walking the superclass chain + {@code getDeclaredFields})
     * is expensive, so computing it once per class avoids repeating that reflection per object.
     */
    private final Map<Class<?>, Field[]> instanceFieldCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Field[]> staticFieldCache = new ConcurrentHashMap<>();

    public ReflectionReferencePatcher(ForwardingTable forwarding) {
        this.forwarding = Objects.requireNonNull(forwarding);
    }

    @Override
    public void patchObject(Object obj) {
        if (obj == null) return;
        // Single-root entry point: a fresh per-call visited set. For one root this is the
        // cheapest option (the set stays as small as the root's reachable subgraph).
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> work = new ArrayDeque<>();
        enqueue(obj, visited, work);
        drain(visited, work);
    }

    /**
     * Batch entry point (used by the heap-walk patch phase, which passes every migrated-class
     * instance as a root). Uses <b>one shared visited set + work queue</b> for the whole batch, so
     * each reachable object is processed exactly once and the total work is {@code O(V+E)}
     * regardless of how densely the migrated objects reference one another. In-place replacement is
     * idempotent (guarded by {@code forwarding}), so objects reachable from several roots are
     * deduplicated without changing the result.
     */
    @Override
    public void patchObjects(Iterable<?> objects) {
        if (objects == null) return;
        int sizeHint = (objects instanceof Collection<?> c) ? c.size() : 64;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>(Math.max(64, sizeHint * 2)));
        Deque<Object> work = new ArrayDeque<>();
        for (Object o : objects) enqueue(o, visited, work);
        drain(visited, work);
    }

    @Override
    public void patchStaticFields(Class<?> clazz) {
        if (clazz == null) return;
        // skip JDK classes
        Module module = clazz.getModule();
        String moduleName = module != null ? module.getName() : null;
        if (moduleName != null && (moduleName.startsWith("java") || moduleName.startsWith("jdk"))) {
            return;
        }
        // visited set for deep patching static field contents
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> work = new ArrayDeque<>();
        for (Field field : staticFields(clazz)) {
            patchStaticField(field, visited, work);
        }
        drain(visited, work);
    }

    // ── Internal iterative implementation ───────────────────────────────────────────────
    //
    // The object graph is traversed with an explicit work-stack rather than recursion:
    // a deep or large connected graph (linked list, tree, ring) can have a traversal path
    // O(N) long, which would overflow the call stack. enqueue() schedules each not-yet-seen
    // object once; in-place replacements happen when the *holder* is processed.

    /** Schedule {@code o} for processing if it hasn't been seen. */
    private static void enqueue(Object o, Set<Object> visited, Deque<Object> work) {
        if (o != null && visited.add(o)) {
            work.push(o);
        }
    }

    /** Process the work-stack until empty. */
    private void drain(Set<Object> visited, Deque<Object> work) {
        Object obj;
        while ((obj = work.poll()) != null) {
            processOne(obj, visited, work);
        }
    }

    /** Processes one dequeued object: patches array elements, JDK-container contents, or instance fields. */
    private void processOne(Object obj, Set<Object> visited, Deque<Object> work) {
        Class<?> cls = obj.getClass();

        if (cls.isArray()) {
            Class<?> componentType = cls.getComponentType();
            if (componentType.isPrimitive()) {
                return; // nothing to patch
            }
            patchArray(obj, visited, work);
            return;
        }

        // Handle JDK container types specially - traverse their contents
        // but don't modify their internal fields
        Module module = cls.getModule();
        String moduleName = module != null ? module.getName() : null;
        boolean isJdkClass = moduleName != null &&
                (moduleName.startsWith("java") || moduleName.startsWith("jdk"));

        if (isJdkClass) {
            patchJdkContainer(obj, visited, work);
            return;
        }

        for (Field field : instanceFields(cls)) {
            patchField(obj, field, visited, work);
        }
    }

    /**
     * Handle JDK container types by recursing into their contents.
     * We don't modify JDK internal fields, but we do patch/recurse their contained objects.
     */
    private void patchJdkContainer(Object obj, Set<Object> visited, Deque<Object> work) {
        if (obj instanceof List<?> list) {
            patchList(list, visited, work);
        } else if (obj instanceof Map<?, ?> map) {
            patchMap(map, visited, work);
        } else if (obj instanceof Collection<?> collection) {
            patchCollection(collection, visited, work);
        } else if (obj instanceof Optional<?> optional) {
            patchOptional(optional, visited, work);
        } else if (obj instanceof Reference<?> ref) {
            patchReference(ref, visited, work);
        }
        // Other JDK types (ThreadLocal, etc.) - skip, they're handled via field patching
    }

    /** Replaces migrated elements of a List in place; non-migrated elements are scheduled for traversal. */
    @SuppressWarnings("unchecked")
    private void patchList(List<?> list, Set<Object> visited, Deque<Object> work) {
        List<Object> mutableList = (List<Object>) list;

        // RandomAccess lists (ArrayList, CopyOnWriteArrayList) are cheapest by index; for
        // sequential lists (LinkedList) a ListIterator keeps the traversal O(n) instead of O(n²).
        if (list instanceof RandomAccess) {
            for (int i = 0; i < mutableList.size(); i++) {
                Object val = mutableList.get(i);
                if (val == null) continue;

                Object replacement = resolveReplacement(val);
                if (replacement != null && replacement != val) {
                    try {
                        mutableList.set(i, replacement);
                    } catch (Exception e) {
                        // best-effort: immutable list or other issue
                        log.debug("Failed to replace list element at index {}: {}", i, e.getMessage());
                    }
                } else {
                    enqueue(val, visited, work);
                }
            }
        } else {
            ListIterator<Object> it = mutableList.listIterator();
            while (it.hasNext()) {
                Object val = it.next();
                if (val == null) continue;

                Object replacement = resolveReplacement(val);
                if (replacement != null && replacement != val) {
                    try {
                        it.set(replacement);
                    } catch (Exception e) {
                        log.debug("Failed to replace list element: {}", e.getMessage());
                    }
                } else {
                    enqueue(val, visited, work);
                }
            }
        }
    }

    /** Replaces migrated elements of a non-List collection via bulk remove/add; recurses into the rest. */
    @SuppressWarnings("unchecked")
    private void patchCollection(Collection<?> collection, Set<Object> visited, Deque<Object> work) {
        // Rebuild the collection in iteration order rather than removeAll()/addAll():
        //  - ordered collections (queues, deques, LinkedHashSet) keep their element order
        //  - matching stays identity-based, not the equals/hashCode semantics of removeAll(),
        //    which could remove distinct-but-equal elements.
        Collection<Object> mutableCollection = (Collection<Object>) collection;
        List<Object> newContents = new ArrayList<>(mutableCollection.size());
        boolean changed = false;

        for (Object val : mutableCollection) {
            if (val == null) {
                newContents.add(null);
                continue;
            }

            Object replacement = resolveReplacement(val);
            if (replacement != null && replacement != val) {
                newContents.add(replacement);
                changed = true;
            } else {
                newContents.add(val);
                enqueue(val, visited, work);
            }
        }

        if (changed) {
            replaceCollectionContents(mutableCollection, newContents);
        }
    }

    /** Replaces migrated keys/values of a Map (re-inserting changed entries); recurses into unchanged ones. */
    @SuppressWarnings("unchecked")
    private void patchMap(Map<?, ?> map, Set<Object> visited, Deque<Object> work) {
        Map<Object, Object> mutableMap = (Map<Object, Object>) map;

        // Rebuild in iteration order so replaced entries keep their position; unchanged keys and
        // values are scheduled for deeper traversal. Building a snapshot first also avoids
        // ConcurrentModificationException from mutating the map while iterating it.
        Map<Object, Object> newContents = new LinkedHashMap<>();
        boolean changed = false;

        for (Map.Entry<Object, Object> entry : mutableMap.entrySet()) {
            Object key = entry.getKey();
            Object val = entry.getValue();

            Object newKey = key != null ? resolveReplacement(key) : null;
            Object effKey;
            if (newKey != null && newKey != key) {
                effKey = newKey;
                changed = true;
            } else {
                effKey = key;
                if (key != null) enqueue(key, visited, work);
            }

            Object newVal = val != null ? resolveReplacement(val) : null;
            Object effVal;
            if (newVal != null && newVal != val) {
                effVal = newVal;
                changed = true;
            } else {
                effVal = val;
                if (val != null) enqueue(val, visited, work);
            }

            newContents.put(effKey, effVal);
        }

        if (changed) {
            replaceMapContents(mutableMap, newContents);
        }
    }

    /**
     * Replaces the contents of {@code target} with {@code newContents} as an in-place rebuild.
     *
     * <p>The rebuild is {@code clear()} then {@code addAll()}. If {@code addAll} fails after the
     * {@code clear} — e.g. a sorted collection whose migrated element breaks the comparator, or a
     * bounded/checked collection that rejects an element — the original contents are restored, so a
     * failed rebuild never leaves the collection empty or half-populated.
     */
    private static void replaceCollectionContents(Collection<Object> target, List<Object> newContents) {
        List<Object> original = new ArrayList<>(target);
        try {
            target.clear();
            target.addAll(newContents);
        } catch (RuntimeException e) {
            log.warn("Failed to rebuild collection ({}); restoring original contents", e.toString());
            try {
                target.clear();
                target.addAll(original);
            } catch (RuntimeException restoreEx) {
                log.error("Failed to restore collection after a failed rebuild; it may be left partial", restoreEx);
            }
        }
    }

    /** Map counterpart of {@link #replaceCollectionContents}: clear()+putAll(), restoring on failure. */
    private static void replaceMapContents(Map<Object, Object> target, Map<Object, Object> newContents) {
        Map<Object, Object> original = new LinkedHashMap<>(target);
        try {
            target.clear();
            target.putAll(newContents);
        } catch (RuntimeException e) {
            log.warn("Failed to rebuild map ({}); restoring original contents", e.toString());
            try {
                target.clear();
                target.putAll(original);
            } catch (RuntimeException restoreEx) {
                log.error("Failed to restore map after a failed rebuild; it may be left partial", restoreEx);
            }
        }
    }

    /** Recurses into an Optional's value; the immutable Optional itself is replaced at its holding field. */
    private void patchOptional(Optional<?> optional, Set<Object> visited, Deque<Object> work) {
        if (optional.isEmpty()) return;

        Object val = optional.get();
        Object replacement = forwarding.get(val);

        if (replacement != null) {
            // Can't replace value in existing Optional, but we can recurse
            // The field holding this Optional should be replaced with a new Optional
            log.debug("Optional contains old object but Optional itself is immutable");
        } else {
            enqueue(val, visited, work);
        }
    }

    /** Recurses into a Reference's referent; the immutable Reference itself is replaced at its holding field. */
    private void patchReference(Reference<?> ref, Set<Object> visited, Deque<Object> work) {
        Object val = ref.get();
        if (val == null) return;

        Object replacement = forwarding.get(val);
        if (replacement != null) {
            // WeakReference/SoftReference don't support changing the referent
            // The field holding this Reference should be replaced
            log.debug("Reference contains old object but Reference itself is immutable");
        } else {
            enqueue(val, visited, work);
        }
    }

    /**
     * Resolves what a referenced value should be replaced with: its migrated counterpart from the
     * forwarding table, or — failing that — a rebuilt immutable container (Optional, record,
     * immutable List/Set/Map, Weak/SoftReference) whose contents reference migrated objects.
     *
     * <p>Returns {@code null} when the value needs no in-place replacement, in which case the caller
     * should schedule it for traversal. Note that some mutable containers (AtomicReference) are
     * mutated in place inside {@link #tryCreateReplacementContainer} and still return {@code null}.
     */
    private Object resolveReplacement(Object val) {
        Object replacement = forwarding.get(val);
        if (replacement != null) {
            return replacement;
        }
        return tryCreateReplacementContainer(val);
    }

    /**
     * Try to create a replacement for immutable containers (Optional, Reference, records,
     * immutable collections) that contain old objects. Mutable containers like AtomicReference
     * are mutated in place and return null.
     *
     * <p>Note: callbacks such as Supplier and per-thread state such as ThreadLocal are intentionally
     * not handled here. Invoking a Supplier would execute arbitrary application code mid-migration,
     * and a ThreadLocal can only be read/written for the patching thread, never the application
     * threads that actually hold the value.
     *
     * @param val the container value
     * @return a new container with the replacement object, or null if not applicable
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object tryCreateReplacementContainer(Object val) {
        if (val.getClass().isRecord()) {
            return tryCreateReplacementRecord(val);
        }
        if (val instanceof Optional<?> optional) {
            if (optional.isPresent()) {
                Object innerVal = optional.get();
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    return Optional.of(replacement);
                }
            }
        } else if (val instanceof java.lang.ref.WeakReference<?> weakRef) {
            Object innerVal = weakRef.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    // A Reference's ReferenceQueue is not exposed via the public API, so the rebuilt
                    // reference cannot be re-registered with the original queue; GC-notification
                    // behaviour for this referent is lost.
                    return new java.lang.ref.WeakReference<>(replacement);
                }
            }
        } else if (val instanceof java.lang.ref.SoftReference<?> softRef) {
            Object innerVal = softRef.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    // See WeakReference above: the original ReferenceQueue cannot be preserved.
                    return new java.lang.ref.SoftReference<>(replacement);
                }
            }
        } else if (val instanceof AtomicReference<?> atomicRef) {
            Object innerVal = atomicRef.get();
            if (innerVal != null) {
                Object replacement = forwarding.get(innerVal);
                if (replacement != null) {
                    // AtomicReference is mutable, update in place
                    ((AtomicReference) atomicRef).set(replacement);
                }
            }
            return null; // mutated in place
        } else if (val instanceof CompletableFuture<?> future) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    Object innerVal = future.join();
                    if (innerVal != null) {
                        Object replacement = forwarding.get(innerVal);
                        if (replacement != null) {
                            return CompletableFuture.completedFuture(replacement);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to get CompletableFuture value: {}", e.getMessage());
                }
            }
        } else if (val instanceof Map<?, ?> map) {
            // Only handle immutable maps - mutable ones are patched in place
            if (isImmutableCollection(map)) {
                return tryCreateReplacementMap(map);
            }
        } else if (val instanceof List<?> list) {
            // Only handle immutable lists - mutable ones are patched in place
            if (isImmutableCollection(list)) {
                return tryCreateReplacementList(list);
            }
        } else if (val instanceof Set<?> set) {
            // Only handle immutable sets - mutable ones are patched in place
            if (isImmutableCollection(set)) {
                return tryCreateReplacementSet(set);
            }
        }
        return null;
    }

    /**
     * Rebuilds an immutable record, substituting any component that maps to a migrated object
     * (or that is itself a replaceable container). Returns null if no component changed or the
     * record cannot be reconstructed via its canonical constructor.
     */
    private Object tryCreateReplacementRecord(Object record) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        boolean changed = false;

        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            Object current;
            try {
                java.lang.reflect.Method accessor = components[i].getAccessor();
                accessor.setAccessible(true);
                current = accessor.invoke(record);
            } catch (Exception e) {
                log.debug("Failed to read record component {}: {}", components[i].getName(), e.getMessage());
                return null;
            }

            Object replacement = current != null ? forwarding.get(current) : null;
            if (replacement == null && current != null) {
                replacement = tryCreateReplacementContainer(current);
            }

            if (replacement != null) {
                args[i] = replacement;
                changed = true;
            } else {
                args[i] = current;
            }
        }

        if (!changed) {
            return null;
        }

        try {
            Constructor<?> canonical = record.getClass().getDeclaredConstructor(paramTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        } catch (Exception e) {
            log.debug("Failed to rebuild record {}: {}", record.getClass().getName(), e.getMessage());
            return null;
        }
    }

    /** Heuristic: true if the collection/map is a known JDK immutable/unmodifiable type (by class name). */
    private boolean isImmutableCollection(Object collection) {
        String className = collection.getClass().getName();
        return className.contains("ImmutableCollections")
            || className.contains("Unmodifiable")
            || className.contains("Singleton")
            || className.contains("Empty");
    }

    /** Builds an immutable copy of a map with migrated keys/values substituted, or null if nothing changed. */
    private Object tryCreateReplacementMap(Map<?, ?> map) {
        boolean needsReplacement = false;
        Map<Object, Object> newMap = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object val = entry.getValue();

            Object newKey = key != null ? forwarding.get(key) : null;
            Object newVal = val != null ? forwarding.get(val) : null;

            // Recursively handle nested containers
            if (newVal == null && val != null) {
                Object nestedReplacement = tryCreateReplacementContainer(val);
                if (nestedReplacement != null) {
                    newVal = nestedReplacement;
                }
            }

            if (newKey != null || newVal != null) {
                needsReplacement = true;
                newMap.put(newKey != null ? newKey : key, newVal != null ? newVal : val);
            } else {
                newMap.put(key, val);
            }
        }

        if (needsReplacement) {
            // Collections.unmodifiableMap (over a private copy) rather than Map.copyOf: the source
            // may legitimately hold null keys/values (e.g. Collections.singletonMap), which copyOf
            // rejects with NPE.
            return Collections.unmodifiableMap(newMap);
        }
        return null;
    }

    /** Builds an immutable copy of a list with migrated elements substituted, or null if nothing changed. */
    private Object tryCreateReplacementList(List<?> list) {
        boolean needsReplacement = false;
        List<Object> newList = new ArrayList<>();

        for (Object val : list) {
            Object replacement = val != null ? forwarding.get(val) : null;

            // Recursively handle nested containers
            if (replacement == null && val != null) {
                Object nestedReplacement = tryCreateReplacementContainer(val);
                if (nestedReplacement != null) {
                    replacement = nestedReplacement;
                }
            }

            if (replacement != null) {
                needsReplacement = true;
                newList.add(replacement);
            } else {
                newList.add(val);
            }
        }

        if (needsReplacement) {
            // unmodifiableList over a private copy (see tryCreateReplacementMap): null-safe.
            return Collections.unmodifiableList(newList);
        }
        return null;
    }

    /** Builds an immutable copy of a set with migrated elements substituted, or null if nothing changed. */
    private Object tryCreateReplacementSet(Set<?> set) {
        boolean needsReplacement = false;
        Set<Object> newSet = new LinkedHashSet<>();

        for (Object val : set) {
            Object replacement = val != null ? forwarding.get(val) : null;

            // Recursively handle nested containers
            if (replacement == null && val != null) {
                Object nestedReplacement = tryCreateReplacementContainer(val);
                if (nestedReplacement != null) {
                    replacement = nestedReplacement;
                }
            }

            if (replacement != null) {
                needsReplacement = true;
                newSet.add(replacement);
            } else {
                newSet.add(val);
            }
        }

        if (needsReplacement) {
            // unmodifiableSet over a private copy (see tryCreateReplacementMap): null-safe and
            // preserves iteration order via LinkedHashSet.
            return Collections.unmodifiableSet(newSet);
        }
        return null;
    }

    /** Replaces migrated elements of an object array in place; non-migrated elements are scheduled for traversal. */
    private void patchArray(Object array, Set<Object> visited, Deque<Object> work) {
        int len = Array.getLength(array);
        for (int i = 0; i < len; i++) {
            Object val = Array.get(array, i);
            if (val == null) continue;

            Object replacement = resolveReplacement(val);
            if (replacement != null && replacement != val) {
                try {
                    Array.set(array, i, replacement);
                } catch (IllegalArgumentException | ArrayStoreException e) {
                    // replacement not assignable to the array's component type; leave as-is
                    log.debug("Failed to set array element at index {}: {}", i, e.getMessage());
                }
            } else {
                enqueue(val, visited, work);
            }
        }
    }

    // Fields are pre-filtered (no JDK-declared fields) and made accessible at cache time,
    // so the per-object path here is just get -> forwarding lookup -> set.
    private void patchField(Object obj, Field field, Set<Object> visited, Deque<Object> work) {
        try {
            Object val = field.get(obj);
            if (val == null) return;

            // forwarding replacement first, then immutable-container rebuild (records, Optional,
            // immutable collections, references); otherwise schedule for traversal.
            Object replacement = resolveReplacement(val);
            if (replacement != null && replacement != val) {
                field.set(obj, replacement);
            } else {
                enqueue(val, visited, work);
            }
        } catch (IllegalAccessException | IllegalArgumentException e) {
            // best-effort: log and continue. IllegalArgumentException occurs when the
            // replacement is not assignable to the field's declared type (e.g. a field
            // typed to the concrete old class); isolate it so the traversal continues.
            log.debug("Failed to patch field {}: {}", field, e.getMessage());
        }
    }

    /** Patches a single static field: replaces a migrated value or container, otherwise schedules its value. */
    private void patchStaticField(Field field, Set<Object> visited, Deque<Object> work) {
        try {
            Object val = field.get(null);
            if (val == null) return;

            // Same resolution order as instance fields (forwarding first, then container rebuild)
            // so a value that is itself a migrated record/container is handled consistently.
            Object replacement = resolveReplacement(val);
            if (replacement != null && replacement != val) {
                if (Modifier.isFinal(field.getModifiers())) {
                    // static final fields cannot be reassigned via reflection (and the JIT may have
                    // inlined the constant). Surface this instead of swallowing it at debug level.
                    log.warn("Cannot patch static final field {}; it still references a migrated object", field);
                    return;
                }
                field.set(null, replacement);
            } else {
                enqueue(val, visited, work);
            }
        } catch (IllegalAccessException | IllegalArgumentException e) {
            // best-effort: log and continue (see patchField for the IllegalArgumentException case).
            log.debug("Failed to patch static field {}: {}", field, e.getMessage());
        }
    }

    // ── Field enumeration helpers ───────────────────────────────────────────────

    /** Returns the cached non-static, non-primitive, non-JDK, accessible instance fields of a class. */
    private Field[] instanceFields(Class<?> cls) {
        return instanceFieldCache.computeIfAbsent(cls, c -> getAllFields(c)
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.getType().isPrimitive())
                .filter(this::isPatchableField)
                .filter(this::makeAccessible)
                .toArray(Field[]::new));
    }

    /** Returns the cached static, non-primitive, non-JDK, accessible fields of a class. */
    private Field[] staticFields(Class<?> cls) {
        return staticFieldCache.computeIfAbsent(cls, c -> getAllFields(c)
                .filter(f -> Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.getType().isPrimitive())
                .filter(this::isPatchableField)
                .filter(this::makeAccessible)
                .toArray(Field[]::new));
    }

    /** Fields declared in JDK modules are never patched; exclude them at cache time. */
    private boolean isPatchableField(Field field) {
        Module module = field.getDeclaringClass().getModule();
        String name = module != null ? module.getName() : null;
        return name == null || !(name.startsWith("java") || name.startsWith("jdk"));
    }

    /**
     * Make the field accessible once at cache time. Fields that cannot be opened (strong
     * encapsulation) are dropped from the cache so the per-object path never re-checks access.
     */
    private boolean makeAccessible(Field field) {
        try {
            if (field.trySetAccessible()) return true;
        } catch (Exception ignore) {
            // fall through to legacy attempt
        }
        try {
            field.setAccessible(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Streams the declared fields of {@code startClass} and all its superclasses. */
    private Stream<Field> getAllFields(Class<?> startClass) {
        return Stream.iterate(startClass, Objects::nonNull, (Class<?> clazz) -> clazz.getSuperclass())
                    .flatMap(c -> Arrays.stream(c.getDeclaredFields()));
    }
}
