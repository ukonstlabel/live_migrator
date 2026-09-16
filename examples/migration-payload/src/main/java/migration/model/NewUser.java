package migration.model;

import service.model.User;

public class NewUser implements User {

    public final int id;
    public final String name;
    /** Carried over from OldUser; the engine patches its elements to NewUser after migration. */
    public final java.util.List<User> friends;

    public NewUser(int id, String name) {
        this(id, name, new java.util.ArrayList<>());
    }

    public NewUser(int id, String name, java.util.List<User> friends) {
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
        return "NewUser{id=" + id + ", name='" + name + "'}";
    }
}
