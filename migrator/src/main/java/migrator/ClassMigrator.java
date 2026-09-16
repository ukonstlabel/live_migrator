package migrator;

import migrator.exceptions.MigrateException;

/**
 * Core interface for implementing class migration logic.
 *
 * <p>Implement this interface to define how instances of an old class type
 * should be transformed into instances of a new class type during live migration.
 * The migration engine will discover implementations via the {@link migrator.annotations.Migrator}
 * annotation and invoke them for each object found during heap walking.
 *
 * <h2>Example:</h2>
 * <pre>
 * {@literal @}Migrator
 * public class UserV1ToV2Migrator implements ClassMigrator&lt;UserV1, UserV2&gt; {
 *     {@literal @}Override
 *     public UserV2 migrate(UserV1 old) {
 *         return new UserV2(old.getName(), old.getEmail(), LocalDateTime.now());
 *     }
 *
 *     {@literal @}Override
 *     public void validate(UserV2 migrated) throws MigrateException {
 *         if (migrated.getEmail() == null) {
 *             throw new MigrateException("Email cannot be null");
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Statelessness:</h2>
 * <p>Implementations should be stateless and free of external side effects. The engine
 * invokes {@link #migrate(Object)} once per discovered instance; keep it idempotent so a
 * re-run is safe.
 *
 * @param <OldT> the source class type being migrated from
 * @param <NewT> the target class type being migrated to
 * @see migrator.annotations.Migrator
 * @see migrator.engine.MigrationEngine
 */
public interface ClassMigrator<OldT, NewT> {

    /**
     * Transform an instance of the old class into an instance of the new class.
     *
     * <p>This method is called for each object of type {@code OldT} discovered
     * during heap walking. Implementations should:
     * <ul>
     *   <li>Create a new instance of {@code NewT}</li>
     *   <li>Copy and transform data from the old instance</li>
     *   <li>Avoid external side effects (database writes, network calls, etc.)</li>
     *   <li>Be idempotent if possible</li>
     * </ul>
     *
     * @param old the old instance to migrate (never null)
     * @return the new instance (must not be null)
     * @throws MigrateException if migration fails for this instance
     */
    NewT migrate(OldT old) throws MigrateException;

    /**
     * Optional validation hook called after migration.
     *
     * <p>Override this method to perform post-migration validation on the
     * newly created instance. This is called after {@link #migrate(Object)}
     * returns successfully.
     *
     * <p>The default implementation does nothing.
     *
     * @param migrated the newly created instance to validate
     * @throws MigrateException if validation fails
     */
    default void validate(NewT migrated) throws MigrateException {}
}
