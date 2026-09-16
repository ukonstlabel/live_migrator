package service.model;

/**
 * Common interface for all User implementations.
 *
 * <p>The service code works with this interface, allowing the underlying
 * implementation to change from OldUser to NewUser during migration
 * without affecting the service logic.
 *
 * @see OldUser
 */
public interface User {
    int getId();
    String getName();
}
