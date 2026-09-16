package service.model;

/**
 * Original User implementation used by the service before migration.
 *
 * <p>During migration, all OldUser instances in the heap are converted
 * to NewUser instances by the migration engine.
 *
 * @see User
 * @see migration.migrator.UserMigrator
 */
public class OldUser implements User, java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final String name;
    /**
     * Optional references to other users (a social-graph fan-out). Holds {@link User} elements so
     * that after migration the engine patches them in place OldUser → NewUser. Used to study
     * migration under realistic reference density. Empty by default.
     */
    public final java.util.List<User> friends;

    public OldUser(int id, String name) {
        this(id, name, new java.util.ArrayList<>());
    }

    public OldUser(int id, String name, java.util.List<User> friends) {
        this.id = id;
        this.name = name;
        this.friends = friends;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "OldUser{id=" + id + ", name='" + name + "'}";
    }
}
