package migrator.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import migrator.patch.ForwardingTable;
import migrator.patch.ReferencePatcher;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * Updates fields annotated with {@link UpdateRegistry} during migration.
 *
 * <p>This class is responsible for finding and updating "registry" or "cache"
 * fields that hold references to migrated objects. It supports:
 * <ul>
 *   <li>{@link java.util.Map} - keys and/or values can be replaced</li>
 *   <li>{@link java.util.Collection} - elements can be replaced</li>
 *   <li>Object arrays</li>
 *   <li>Custom registry types (via reflection and {@link RegistryAware})</li>
 *   <li>Generic containers with type parameters</li>
 * </ul>
 *
 * <p>The updater uses a {@link ForwardingTable} to look up migrated objects
 * and a {@link ReferencePatcher} for deep patching of nested structures.
 *
 * <h2>Usage:</h2>
 * <pre>
 * RegistryUpdater updater = new RegistryUpdater(forwarding, referencePatcher);
 * updater.updateAnnotatedRegistries(classesToScan, heapObjects);
 * </pre>
 *
 * @see UpdateRegistry
 * @see RegistryAware
 * @see ForwardingTable
 */
public final class RegistryUpdater {

    private static final Logger log = LoggerFactory.getLogger(RegistryUpdater.class);

    private final ForwardingTable forwarding;
    private final ReferencePatcher referencePatcher;

    /**
     * Creates a new registry updater.
     *
     * @param forwarding the forwarding table mapping old objects to new
     * @param referencePatcher the reference patcher for deep patching
     */
    public RegistryUpdater(ForwardingTable forwarding, ReferencePatcher referencePatcher) {
        this.forwarding = Objects.requireNonNull(forwarding);
        this.referencePatcher = Objects.requireNonNull(referencePatcher);
    }

    /**
     * Scans the specified classes for fields with @UpdateRegistry and patches them.
     * Typically, pass the new version classes (target classes) here.
     */
    public void updateAnnotatedRegistries(
        Collection<Class<?>> classesToScan,
        Collection<Object> heapObjects) {

        // Static fields are patched as found; instance fields are collected and applied in a single
        // pass over the heap (rather than re-scanning the whole heap per field). A field may be
        // reached through several scanned subclasses of the same superclass, so dedupe by Field.
        Set<Field> seen = new HashSet<>();
        List<InstanceRegistrySpec> instanceFields = new ArrayList<>();

        for (Class<?> cls : classesToScan) {
            for (Field f : allDeclaredFields(cls)) {
                UpdateRegistry ann = f.getAnnotation(UpdateRegistry.class);
                if (ann == null || !seen.add(f)) continue;

                if (Modifier.isStatic(f.getModifiers())) {
                    patchStaticRegistryField(cls, f, ann);
                } else {
                    f.setAccessible(true);
                    instanceFields.add(new InstanceRegistrySpec(cls, f, ann));
                }
            }
        }

        if (!instanceFields.isEmpty() && heapObjects != null) {
            for (Object obj : heapObjects) {
                if (obj == null) continue;
                for (InstanceRegistrySpec spec : instanceFields) {
                    if (spec.declaringClass().isInstance(obj)) {
                        applyInstanceRegistryField(spec, obj);
                    }
                }
            }
        }
    }

    /** Returns the declared fields of {@code cls} and all its superclasses (excluding {@code Object}). */
    private List<Field> allDeclaredFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }

    private record InstanceRegistrySpec(Class<?> declaringClass, Field field, UpdateRegistry ann) {}

    private record InstanceGenericSpec(Class<?> declaringClass, Field field, Class<?> interfaceType) {}

    /**
     * Updates a generic container that holds elements implementing a common interface.
     *
     * <p>This method inspects the container and replaces all elements that are instances
     * of the specified interface type with their migrated versions from the forwarding table.
     *
     * <p>Supported container types:
     * <ul>
     *   <li>{@link Collection} - List, Set, Queue, etc.</li>
     *   <li>{@link Map} - updates values that match the interface type</li>
     *   <li>Arrays</li>
     *   <li>Custom containers with iterable fields</li>
     * </ul>
     *
     * <h2>Example:</h2>
     * <pre>
     * // Container with generic type parameter
     * List&lt;User&gt; users = ...;
     * registryUpdater.updateGenericContainer(users, User.class);
     * </pre>
     *
     * @param container the generic container holding elements
     * @param interfaceType the common interface type of elements to update
     */
    public void updateGenericContainer(Object container, Class<?> interfaceType) {
        if (container == null || interfaceType == null) {
            return;
        }

        log.debug("Updating generic container {} with interface type {}",
                  container.getClass().getName(), interfaceType.getName());

        if (container instanceof Map<?, ?> map) {
            updateGenericMap(map, interfaceType);
        } else if (container instanceof Collection<?> collection) {
            updateGenericCollection(collection, interfaceType);
        } else if (container.getClass().isArray()) {
            updateGenericArray(container, interfaceType);
        } else {
            // Custom container - try to find and update iterable/collection fields
            updateCustomGenericContainer(container, interfaceType);
        }
    }

    /**
     * Updates multiple generic containers with the same interface type.
     *
     * @param containers the containers to update
     * @param interfaceType the common interface type of elements to update
     */
    public void updateGenericContainers(Collection<?> containers, Class<?> interfaceType) {
        if (containers == null || interfaceType == null) {
            return;
        }

        for (Object container : containers) {
            updateGenericContainer(container, interfaceType);
        }
    }

    /**
     * Scans classes for fields that are generic types with a common interface type parameter
     * and updates them.
     *
     * <p>This method inspects all fields of the specified classes, looking for generic types
     * like {@code List<User>}, {@code Map<String, User>}, or {@code CustomContainer<User>}
     * where the type parameter matches one of the specified interface types.
     *
     * <h2>Example:</h2>
     * <pre>
     * class UserService {
     *     private List&lt;User&gt; users;           // Will be updated
     *     private Map&lt;String, User&gt; cache;    // Will be updated
     *     private CustomRegistry&lt;User&gt; reg;   // Will be updated
     * }
     *
     * registryUpdater.updateGenericFieldsInClasses(
     *     Set.of(UserService.class),
     *     heapObjects,
     *     Set.of(User.class)
     * );
     * </pre>
     *
     * @param classesToScan classes to scan for generic fields
     * @param heapObjects heap objects to search for instances of the scanned classes
     * @param interfaceTypes the interface types to look for in generic type parameters
     */
    public void updateGenericFieldsInClasses(
            Collection<Class<?>> classesToScan,
            Collection<Object> heapObjects,
            Set<Class<?>> interfaceTypes) {

        if (classesToScan == null || heapObjects == null || interfaceTypes == null || interfaceTypes.isEmpty()) {
            return;
        }

        // Same shape as updateAnnotatedRegistries: statics handled inline, instance fields applied
        // in a single heap pass, fields deduped across subclasses sharing a superclass field.
        Set<Field> seen = new HashSet<>();
        List<InstanceGenericSpec> instanceFields = new ArrayList<>();

        for (Class<?> cls : classesToScan) {
            for (Field field : allDeclaredFields(cls)) {
                if (!seen.add(field)) continue;

                Class<?> matchedInterface = extractMatchingInterfaceType(field.getGenericType(), interfaceTypes);
                if (matchedInterface == null) {
                    continue;
                }

                log.debug("Found generic field {}.{} with interface type {}",
                          cls.getSimpleName(), field.getName(), matchedInterface.getSimpleName());

                if (Modifier.isStatic(field.getModifiers())) {
                    updateStaticGenericField(cls, field, matchedInterface);
                } else {
                    field.setAccessible(true);
                    instanceFields.add(new InstanceGenericSpec(cls, field, matchedInterface));
                }
            }
        }

        if (!instanceFields.isEmpty()) {
            for (Object obj : heapObjects) {
                if (obj == null) continue;
                for (InstanceGenericSpec spec : instanceFields) {
                    if (spec.declaringClass().isInstance(obj)) {
                        applyInstanceGenericField(spec, obj);
                    }
                }
            }
        }
    }

    /**
     * Extracts a matching interface type from a generic type's type parameters.
     *
     * @param genericType the generic type to inspect
     * @param interfaceTypes the set of interface types to match against
     * @return the matched interface type, or null if no match found
     */
    private Class<?> extractMatchingInterfaceType(Type genericType, Set<Class<?>> interfaceTypes) {
        if (!(genericType instanceof ParameterizedType paramType)) {
            return null;
        }

        Type[] typeArgs = paramType.getActualTypeArguments();
        for (Type typeArg : typeArgs) {
            Class<?> typeArgClass = resolveTypeArgToClass(typeArg);
            if (typeArgClass != null) {
                // Check if this type arg matches any of the interface types. The reverse
                // (type arg is a supertype of the interface, e.g. List<AbstractUser> for User)
                // is allowed too, but Object is excluded so List<Object> doesn't match everything.
                for (Class<?> interfaceType : interfaceTypes) {
                    if (interfaceType.isAssignableFrom(typeArgClass)
                            || (typeArgClass != Object.class && typeArgClass.isAssignableFrom(interfaceType))) {
                        return interfaceType;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Resolves a Type to its Class representation.
     */
    private Class<?> resolveTypeArgToClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType paramType) {
            Type rawType = paramType.getRawType();
            if (rawType instanceof Class<?> cls) {
                return cls;
            }
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class<?> cls) {
                return cls;
            }
        }
        if (type instanceof TypeVariable<?> typeVar) {
            Type[] bounds = typeVar.getBounds();
            if (bounds.length > 0 && bounds[0] instanceof Class<?> cls) {
                return cls;
            }
        }
        return null;
    }

    /**
     * Updates a static generic field.
     */
    private void updateStaticGenericField(Class<?> owner, Field field, Class<?> interfaceType) {
        try {
            field.setAccessible(true);
            Object container = field.get(null);
            if (container != null) {
                updateGenericContainer(container, interfaceType);
            }
        } catch (Exception e) {
            log.warn("Failed to update static generic field {}.{}: {}",
                     owner.getName(), field.getName(), e.getMessage());
        }
    }

    /** Updates one instance generic field on a single heap object. */
    private void applyInstanceGenericField(InstanceGenericSpec spec, Object obj) {
        try {
            Object container = spec.field().get(obj);
            if (container != null) {
                updateGenericContainer(container, spec.interfaceType());
            }
        } catch (Exception e) {
            log.warn("Failed to update instance generic field {}.{} on object: {}",
                     spec.declaringClass().getName(), spec.field().getName(), e.getMessage());
        }
    }

    /** Replaces keys/values of the map that are instances of {@code interfaceType} with their migrated versions. */
    private void updateGenericMap(Map<?, ?> map, Class<?> interfaceType) {
        if (map == null || map.isEmpty()) {
            return;
        }

        List<Map.Entry<Object, Object>> snapshot = createSnapshot(map);
        boolean isConcurrent = map instanceof ConcurrentMap;

        for (Map.Entry<Object, Object> entry : snapshot) {
            Object key = entry.getKey();
            Object value = entry.getValue();

            Object newKey = null;
            Object newValue = null;

            // Check if key implements the interface
            if (interfaceType.isInstance(key)) {
                newKey = forwarding.get(key);
            }

            // Check if value implements the interface
            if (interfaceType.isInstance(value)) {
                newValue = forwarding.get(value);
            }

            if (newKey != null || newValue != null) {
                try {
                    if (isConcurrent) {
                        @SuppressWarnings("unchecked")
                        ConcurrentMap<Object, Object> cmap = (ConcurrentMap<Object, Object>) map;
                        patchConcurrent(cmap, key, value, newKey, newValue);
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> mmap = (Map<Object, Object>) map;
                        patchRegular(mmap, key, newKey, value, newValue);
                    }
                } catch (Exception e) {
                    log.warn("Failed to update map entry: {}", e.getMessage());
                }
            }
        }
    }

    /** Dispatches to the list or set update path for a collection of {@code interfaceType} elements. */
    private void updateGenericCollection(Collection<?> collection, Class<?> interfaceType) {
        if (collection == null || collection.isEmpty()) {
            return;
        }

        if (collection instanceof List<?> list) {
            updateGenericList(list, interfaceType);
        } else {
            updateGenericSet(collection, interfaceType);
        }
    }

    @SuppressWarnings("unchecked")
    /** Replaces, in place by index, list elements of {@code interfaceType} with their migrated versions. */
    private void updateGenericList(List<?> list, Class<?> interfaceType) {
        List<Object> mutableList = (List<Object>) list;

        for (int i = 0; i < mutableList.size(); i++) {
            Object element = mutableList.get(i);

            if (interfaceType.isInstance(element)) {
                Object replacement = forwarding.get(element);
                if (replacement != null && replacement != element) {
                    try {
                        mutableList.set(i, replacement);
                        log.trace("Replaced element at index {} with migrated instance", i);
                    } catch (Exception e) {
                        log.warn("Failed to replace element at index {}: {}", i, e.getMessage());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Replaces set elements of {@code interfaceType} with their migrated versions by rebuilding the
     * collection in iteration order. Avoids {@code removeAll}/{@code addAll}, which is O(n²) on a
     * same-size set and matches by equals/hashCode rather than identity (and reorders ordered sets).
     */
    private void updateGenericSet(Collection<?> collection, Class<?> interfaceType) {
        Collection<Object> mutableCollection = (Collection<Object>) collection;

        List<Object> newContents = new ArrayList<>(mutableCollection.size());
        boolean changed = false;

        for (Object element : mutableCollection) {
            Object replacement = null;
            if (interfaceType.isInstance(element)) {
                Object r = forwarding.get(element);
                if (r != null && r != element) {
                    replacement = r;
                }
            }
            if (replacement != null) {
                newContents.add(replacement);
                changed = true;
            } else {
                newContents.add(element);
            }
        }

        if (changed) {
            replaceCollectionContents(mutableCollection, newContents);
        }
    }

    /**
     * Replaces the contents of {@code target} with {@code newContents} as an in-place rebuild
     * ({@code clear()} then {@code addAll()}). If {@code addAll} fails after the {@code clear}
     * (e.g. a sorted collection whose migrated element breaks the comparator), the original
     * contents are restored so a failed rebuild never leaves the registry empty or half-populated.
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

    /** Replaces, in place by index, array elements of {@code interfaceType} with their migrated versions. */
    private void updateGenericArray(Object array, Class<?> interfaceType) {
        if (array == null) {
            return;
        }

        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);

            if (interfaceType.isInstance(element)) {
                Object replacement = forwarding.get(element);
                if (replacement != null && replacement != element) {
                    try {
                        Array.set(array, i, replacement);
                        log.trace("Replaced array element at index {}", i);
                    } catch (Exception e) {
                        log.warn("Failed to replace array element at index {}: {}", i, e.getMessage());
                    }
                }
            }
        }
    }

    /** Best-effort update of a non-JDK container: replaces matching fields and recurses into collection-like fields. */
    private void updateCustomGenericContainer(Object container, Class<?> interfaceType) {
        Class<?> containerClass = container.getClass();

        // Try to find and update fields that are collections/maps/arrays
        for (Field field : containerClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object fieldValue = field.get(container);

                if (fieldValue == null) {
                    continue;
                }

                // Check if field itself implements the interface
                if (interfaceType.isInstance(fieldValue)) {
                    Object replacement = forwarding.get(fieldValue);
                    if (replacement != null && replacement != fieldValue) {
                        field.set(container, replacement);
                        log.trace("Replaced field {} with migrated instance", field.getName());
                        continue;
                    }
                }

                // Recursively update container-like fields
                if (fieldValue instanceof Collection || fieldValue instanceof Map || fieldValue.getClass().isArray()) {
                    updateGenericContainer(fieldValue, interfaceType);
                }
            } catch (Exception e) {
                log.warn("Failed to update field {}: {}", field.getName(), e.getMessage());
            }
        }

        // Also check if container implements Iterable
        if (container instanceof Iterable<?> iterable && !(container instanceof Collection)) {
            updateGenericIterable(iterable, interfaceType);
        }
    }

    /** For an opaque Iterable, deep-patches each matching element's internals (the structure can't be rewritten). */
    private void updateGenericIterable(Iterable<?> iterable, Class<?> interfaceType) {
        // For generic iterables, we can only patch the objects themselves
        // since we can't modify the iterable structure without knowing its type
        for (Object element : iterable) {
            if (interfaceType.isInstance(element)) {
                // Try to patch the object's internal references
                referencePatcher.patchObject(element);
            }
        }
    }

    /** Patches an {@code @UpdateRegistry} static field by type (Map/Collection/array/custom) per the annotation. */
    private void patchStaticRegistryField(Class<?> owner, Field field, UpdateRegistry ann) {
        try {
            field.setAccessible(true);
            Object registry = field.get(null);
            if (registry == null) return;

            // If the static reference itself points to an old object, replace it. Isolate this:
            // a static-final or type-mismatched field can't be reassigned, but we can still patch
            // the existing registry's contents below.
            Object directReplacement = forwarding.get(registry);
            if (directReplacement != null && directReplacement != registry) {
                try {
                    field.set(null, directReplacement);
                    registry = directReplacement;
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    log.warn("Could not replace static registry field {}#{} reference: {}",
                            owner.getName(), field.getName(), e.getMessage());
                }
            }

            // Process by type
            if (registry instanceof Map) {
                patchMap((Map<?, ?>) registry, ann.replaceKeys(), ann.replaceValues(), ann.deep());
            } else if (registry instanceof Collection) {
                patchCollection((Collection<?>) registry, ann.deep());
            } else if (registry.getClass().isArray()) {
                patchArray(registry, ann.deep());
            } else {
                // Best-effort for custom registries:
                // 1) Try to recursively patch internals (deep)
                // 2) If replace/put/remove methods exist, try to use them
                if (ann.deep()) {
                    referencePatcher.patchObject(registry);
                }
                if (ann.reflective()) {
                    tryBestReflectiveRegistryOps(registry, ann);
                }
            }

            if (registry instanceof RegistryAware aware)
                safelyNotifyRegistryUpdated(aware, owner, field.getName());
        } catch (IllegalAccessException | IllegalArgumentException e) {
            // best-effort: one inaccessible or type-mismatched registry field must not abort the
            // whole migration.
            log.warn("Failed to read static registry field {}#{}: {}", owner.getName(), field.getName(), e.getMessage());
        }
    }

    /** Invokes the {@link RegistryAware} callback, isolating (logging) any exception it throws. */
    private void safelyNotifyRegistryUpdated(RegistryAware aware, Class<?> owner, String fieldName) {
        try {
            aware.onRegistryUpdated();
        } catch (Exception e) {
            log.warn(
                "RegistryAware.onRegistryUpdated failed for {}#{}",
                owner.getName(),
                fieldName,
                e
            );
        }
    }

    /** Patches one instance {@code @UpdateRegistry} field on a single heap object. */
    private void applyInstanceRegistryField(InstanceRegistrySpec spec, Object obj) {
        Field field = spec.field();
        try {
            Object value = field.get(obj);
            if (value == null) return;

            Object replacement = forwarding.get(value);
            if (replacement != null && replacement != value) {
                field.set(obj, replacement);
            } else {
                patchRegistryValue(value, spec.ann());
            }
        } catch (IllegalAccessException | IllegalArgumentException e) {
            // best-effort: skip objects whose registry field can't be accessed or whose
            // replacement isn't assignable to the field type.
            log.warn("Failed to patch instance registry field {}: {}", field, e.getMessage());
        }
    }

    /** Patches an instance registry field value by type (Map/Collection/other) per the annotation. */
    private void patchRegistryValue(Object value, UpdateRegistry ann) {
        if (value instanceof Map) {
            patchMap((Map<?, ?>) value, ann.replaceKeys(), ann.replaceValues(), ann.deep());
        } else if (value instanceof Collection) {
            patchCollection((Collection<?>) value, ann.deep());
        } else if (value.getClass().isArray()) {
            patchArray(value, ann.deep());
        } else if (ann.deep()) {
            referencePatcher.patchObject(value);
        }
    }

    // ---------------- Map ----------------

    /** Replaces migrated keys/values in the real map (per the flags) and, when {@code deep}, deep-patches remaining values. */
    @SuppressWarnings("unchecked")
    private void patchMap(Map<?, ?> rawMap, boolean replaceKeys, boolean replaceValues, boolean deep) {
        if (rawMap == null || rawMap.isEmpty()) {
            return;
        }

        // Patch the REAL map. We iterate a snapshot of the entries (taken before any
        // mutation) so that remove/put against the live map can't throw
        // ConcurrentModificationException. Earlier this built and mutated a throwaway
        // HashMap copy, so the real registry was never updated.
        Map<Object, Object> target = (Map<Object, Object>) rawMap;
        List<Map.Entry<Object, Object>> snapshot = createSnapshot(rawMap);
        boolean isConcurrent = rawMap instanceof ConcurrentMap;

        patchEntries(snapshot, target, isConcurrent, replaceKeys, replaceValues);
        if (deep) {
            deepPatchValues(target);
        }
    }

    /** Copies a map's entries into a list so the map can be mutated without ConcurrentModificationException. */
    private List<Map.Entry<Object, Object>> createSnapshot(Map<?, ?> map) {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
        }
        return entries;
    }

    /** Applies key/value replacements for each snapshot entry to the target map, with a fallback on failure. */
    private void patchEntries(
        List<Map.Entry<Object, Object>> entries,
        Map<Object, Object> map,
        boolean isConcurrent,
        boolean replaceKeys,
        boolean replaceValues) {
        for (Map.Entry<Object, Object> entry : entries) {
            Object oldKey = entry.getKey();
            Object oldVal = entry.getValue();

            Object newKey = replaceKeys ? forwarding.get(oldKey) : null;
            Object newVal = replaceValues ? forwarding.get(oldVal) : null;

            if (newKey == null && newVal == null) {
                continue;
            }

            try {
                if (isConcurrent) {
                    patchConcurrent((ConcurrentMap<Object, Object>) map, oldKey, oldVal, newKey, newVal);
                } else {
                    patchRegular(map, oldKey, newKey, oldVal, newVal);
                }
            } catch (Exception e) {
                tryFallbackMapReplace(map, oldKey, newKey, oldVal, newVal);
            }
        }
    }

    /** Replaces a single entry in a ConcurrentMap using atomic replace/remove+put as appropriate. */
    private void patchConcurrent(ConcurrentMap<Object, Object> cmap,
                             Object oldKey, Object oldVal,
                             Object newKey, Object newVal) {
        boolean keyChanged = newKey != null && newKey != oldKey;
        boolean valChanged = newVal != null && newVal != oldVal;

        if (!keyChanged && valChanged) {
            cmap.replace(oldKey, oldVal, newVal);
        } else {
            cmap.remove(oldKey, oldVal);
            Object putKey = newKey != null ? newKey : oldKey;
            Object putVal = newVal != null ? newVal : oldVal;
            cmap.put(putKey, putVal);
        }
    }

    /** Replaces a single entry in a non-concurrent map via remove-then-put. */
    private void patchRegular(Map<Object, Object> map,
                            Object oldKey, Object newKey,
                            Object oldVal, Object newVal) {
        map.remove(oldKey);

        Object putKey = newKey != null ? newKey : oldKey;
        Object putVal = newVal != null ? newVal : oldVal;
        map.put(putKey, putVal);
    }

    /** Deep-patches the internal references of every value remaining in the map. */
    private void deepPatchValues(Map<?, ?> map) {
        for (Object value : map.values()) {
            referencePatcher.patchObject(value);
        }
    }

    /** Last-resort entry replacement for maps that reject remove/put, via entrySet iteration. */
    private void tryFallbackMapReplace(
        Map<Object, Object> map,
        Object oldKey,
        Object newKey,
        Object oldVal,
        Object newVal) {
        try {
            map.entrySet().removeIf(e -> e.getKey() == oldKey);
            map.put(newKey != null ? newKey : oldKey,
                    newVal != null ? newVal : oldVal);
        } catch (Exception ignore) {
            // best-effort
        }
    }

    // ---------------- Collection ----------------

    /** Patches a registry collection in place: replaces migrated elements and (when {@code deep}) deep-patches the rest. */
    @SuppressWarnings("unchecked")
    private void patchCollection(Collection<?> rawCollection, boolean deep) {
        if (rawCollection == null || rawCollection.isEmpty()) {
            return;
        }

        // Patch the REAL collection in place. Earlier this copied into a new ArrayList
        // and mutated the copy, so element replacements never reached the registry.
        Collection<Object> collection = (Collection<Object>) rawCollection;

        if (collection instanceof List) {
            patchListInPlace((List<Object>) collection, deep);
        } else {
            patchGenericCollection(collection, deep);
        }
    }

    /** Replaces migrated list elements in place; (when {@code deep}) deep-patches the rest. */
    private void patchListInPlace(List<Object> list, boolean deep) {
        // RandomAccess lists are cheapest by index; sequential lists (LinkedList) use a
        // ListIterator to stay O(n) instead of O(n²).
        if (list instanceof RandomAccess) {
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                Object replacement = (value != null) ? forwarding.get(value) : null;
                if (replacement != null && replacement != value) {
                    safelyReplaceAtIndex(list, i, replacement);
                } else if (deep && value != null) {
                    referencePatcher.patchObject(value);
                }
            }
        } else {
            ListIterator<Object> it = list.listIterator();
            while (it.hasNext()) {
                Object value = it.next();
                Object replacement = (value != null) ? forwarding.get(value) : null;
                if (replacement != null && replacement != value) {
                    try {
                        it.set(replacement);
                    } catch (Exception e) {
                        log.warn("Failed to replace list element: {}", e.getMessage());
                    }
                } else if (deep && value != null) {
                    referencePatcher.patchObject(value);
                }
            }
        }
    }

    /** Sets a list element by index, falling back to remove+add (e.g. for CopyOnWriteArrayList). */
    private void safelyReplaceAtIndex(List<Object> list, int index, Object newValue) {
        try {
            list.set(index, newValue);
        } catch (Exception e) {
            // fallback: remove + insert (works even for CopyOnWriteArrayList)
            try {
                list.remove(index);
                list.add(index, newValue);
            } catch (Exception ignored) {
                // best-effort: silently ignore
            }
        }
    }

    /**
     * Replaces migrated elements of a non-list collection by rebuilding it in iteration order;
     * (when {@code deep}) deep-patches the rest. Avoids removeAll/addAll (O(n²) on a same-size set,
     * equals-based, order-destroying — see {@link #updateGenericSet}).
     */
    private void patchGenericCollection(Collection<Object> col, boolean deep) {
        List<Object> newContents = new ArrayList<>(col.size());
        boolean changed = false;

        for (Object value : col) {
            Object replacement = (value != null) ? forwarding.get(value) : null;
            if (replacement != null && replacement != value) {
                newContents.add(replacement);
                changed = true;
            } else {
                newContents.add(value);
                if (deep && value != null) {
                    referencePatcher.patchObject(value);
                }
            }
        }

        if (changed) {
            replaceCollectionContents(col, newContents);
        }
    }

    // ---------------- Array ----------------

    /** Replaces migrated elements of an object array in place; (when {@code deep}) deep-patches the rest. */
    private void patchArray(Object array, boolean deep) {
        if (array == null) {
            return;
        }

        int len = Array.getLength(array);
        for (int i = 0; i < len; i++) {
            Object value = Array.get(array, i);
            Object replacement = (value != null) ? forwarding.get(value) : null;

            if (replacement != null && replacement != value) {
                try {
                    Array.set(array, i, replacement);
                } catch (IllegalArgumentException | ArrayStoreException e) {
                    // replacement not assignable to the array's component type; leave as-is
                    log.warn("Failed to replace array element at index {}: {}", i, e.getMessage());
                }
            } else if (deep && value != null) {
                referencePatcher.patchObject(value);
            }
        }
    }

    // ---------------- Reflective ops for custom registries ----------------

    /** For custom registries (when {@code reflective=true}): patches values returned by collection-like getters. */
    private void tryBestReflectiveRegistryOps(Object registry, UpdateRegistry ann) {
        if (registry == null || !ann.reflective()) {
            return;
        }

        Class<?> clazz = registry.getClass();
        if (!hasAnyModifyingMethod(clazz)) {
            return;
        }

        patchPublicCollectionLikeGetters(registry, clazz, ann);
    }

    /**
     * @return true if the class exposes a put/replace/remove method (a sign it is a mutable
     * registry). Matches by name and arity rather than exact {@code Object} parameter types, so
     * typed registries such as {@code put(String, User)} are detected.
     */
    private boolean hasAnyModifyingMethod(Class<?> clazz) {
        for (Method m : clazz.getMethods()) {
            String name = m.getName();
            if (m.getParameterCount() >= 1
                    && (name.equals("put") || name.equals("replace") || name.equals("remove"))) {
                return true;
            }
        }
        return false;
    }

    /** Invokes each zero-arg getter returning a Map/Collection/array and patches the returned value. */
    private void patchPublicCollectionLikeGetters(
        Object registry,
        Class<?> clazz,
        UpdateRegistry ann) {

        for (Method method : clazz.getDeclaredMethods()) {
            if (isSuitableGetter(method)) {
                Object value = safelyInvokeGetter(registry, method);
                if (value != null) {
                    patchValueIfSupported(value, ann);
                }
            }
        }
    }


    /** @return true if {@code m} is a non-static, zero-arg method returning a collection-like type. */
    private boolean isSuitableGetter(Method m) {
        if (Modifier.isStatic(m.getModifiers())) {
            return false;
        }
        if (m.getParameterCount() != 0) {
            return false;
        }

        Class<?> returnType = m.getReturnType();
        return isCollectionLike(returnType);
    }

    /** @return true if the type is a Map, Collection, or array. */
    private boolean isCollectionLike(Class<?> type) {
        return Map.class.isAssignableFrom(type) ||
            Collection.class.isAssignableFrom(type) ||
            type.isArray();
    }

    /** Invokes a getter reflectively, returning null on any failure. */
    private Object safelyInvokeGetter(Object registry, Method method) {
        try {
            method.setAccessible(true);
            return method.invoke(registry);
        } catch (Exception e) {
            // best-effort: failed to invoke getter, skip
            return null;
        }
    }

    /** Patches a getter-returned value by type (Map/Collection/array), or deep-patches it when {@code deep=true}. */
    private void patchValueIfSupported(Object value, UpdateRegistry ann) {
        if (value == null) {
            return;
        }

        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            patchMap(map, ann.replaceKeys(), ann.replaceValues(), ann.deep());
        }
        else if (value instanceof Collection) {
            Collection<?> col = (Collection<?>) value;
            patchCollection(col, ann.deep());
        }
        else if (value.getClass().isArray()) {
            patchArray(value, ann.deep());
        }
        else if (ann.deep()) {
            referencePatcher.patchObject(value);
        }
    }
}
