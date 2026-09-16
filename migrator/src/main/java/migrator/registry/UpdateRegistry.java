package migrator.registry;

import migrator.patch.ReferencePatcher;

import java.lang.annotation.*;

/**
 * Marks a field as a registry or cache that should be updated during migration.
 *
 * <p>The annotated field can be any of the following types:
 * <ul>
 *   <li>{@link java.util.Map} - keys and/or values will be replaced</li>
 *   <li>{@link java.util.Collection} - elements will be replaced</li>
 *   <li>Object array - elements will be replaced</li>
 *   <li>Custom object - best-effort patching via reflection</li>
 * </ul>
 *
 * <h2>Example:</h2>
 * <pre>
 * public class UserService {
 *     {@literal @}UpdateRegistry(replaceValues = true, deep = true)
 *     private static final Map&lt;String, User&gt; userCache = new ConcurrentHashMap&lt;&gt;();
 * }
 * </pre>
 *
 * @see RegistryUpdater
 * @see RegistryAware
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UpdateRegistry {
    /**
     * Whether to replace Map keys that have been migrated.
     *
     * @return true to replace keys, false to leave them unchanged
     */
    boolean replaceKeys() default true;

    /**
     * Whether to replace Map values or Collection elements that have been migrated.
     *
     * @return true to replace values/elements, false to leave them unchanged
     */
    boolean replaceValues() default true;

    /**
     * Whether to recursively patch internal objects via {@link ReferencePatcher}.
     *
     * @return true to enable deep patching
     */
    boolean deep() default true;

    /**
     * Whether to use reflection to find and invoke mutating methods on custom registries.
     *
     * @return true to enable reflective operations
     */
    boolean reflective() default false;
}
