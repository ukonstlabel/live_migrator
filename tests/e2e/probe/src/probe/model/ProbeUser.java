package probe.model;

import service.model.User;

/**
 * Structurally identical to service.model.OldUser / migration.model.NewUser, but a distinct type
 * that is only loaded at attach time. Used by ClassLoadOnlyAgent.
 */
public class ProbeUser implements User {
    public final int id;
    public final String name;
    public final java.util.List<User> friends;

    public ProbeUser(int id, String name) { this(id, name, new java.util.ArrayList<>()); }
    public ProbeUser(int id, String name, java.util.List<User> friends) {
        this.id = id; this.name = name; this.friends = friends;
    }
    @Override public int getId() { return id; }
    @Override public String getName() { return name; }
    @Override public String toString() { return "ProbeUser{id=" + id + ", name='" + name + "'}"; }
}
