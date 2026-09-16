package migrator.registry;

/**
 * Callback interface for registries that need notification after migration updates.
 *
 * <p>Implement this interface on custom registry classes that maintain internal
 * indices, caches, or derived data structures. The {@link RegistryUpdater} will
 * call {@link #onRegistryUpdated()} after patching the registry, allowing it to
 * rebuild any invalidated internal state.
 *
 * <h2>Example:</h2>
 * <pre>
 * public class IndexedUserRegistry implements RegistryAware {
 *     private final Map&lt;String, User&gt; byId = new HashMap&lt;&gt;();
 *     private final Map&lt;String, User&gt; byEmail = new HashMap&lt;&gt;();
 *
 *     {@literal @}Override
 *     public void onRegistryUpdated() {
 *         // Rebuild the email index after migration
 *         byEmail.clear();
 *         byId.values().forEach(u -&gt; byEmail.put(u.getEmail(), u));
 *     }
 * }
 * </pre>
 *
 * @see RegistryUpdater
 * @see UpdateRegistry
 */
public interface RegistryAware {

    /**
     * Called after the registry has been patched during migration.
     *
     * <p>Implementations should use this callback to rebuild any internal
     * indices or caches that may have been invalidated by object replacement.
     */
    void onRegistryUpdated();
}
