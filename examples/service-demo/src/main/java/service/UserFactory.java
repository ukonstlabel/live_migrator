package service;

import service.model.OldUser;
import service.model.User;

/**
 * Factory for creating User instances.
 *
 * <p>This factory uses a singleton pattern with a replaceable instance, allowing
 * the migration agent to swap the factory implementation at runtime. Before migration,
 * this factory creates {@link OldUser} instances. After migration, the factory is
 * replaced with one that creates NewUser instances.
 *
 * <h2>Usage in service code:</h2>
 * <pre>
 * User user = UserFactory.getInstance().createUser(id, name);
 * </pre>
 *
 * <h2>Usage in migration agent:</h2>
 * <pre>
 * // After migration completes, replace the factory
 * UserFactory.setInstance(new NewUserFactory());
 * </pre>
 *
 * @see service.model.User
 * @see service.model.OldUser
 */
public class UserFactory {

    private static volatile UserFactory INSTANCE = new UserFactory();

    public static UserFactory getInstance() {
        return INSTANCE;
    }

    public static void setInstance(UserFactory factory) {
        INSTANCE = factory;
    }

    /**
     * Creates a new User instance.
     * By default creates OldUser, but after migration this can be replaced
     * to create NewUser instead.
     */
    public User createUser(int id, String name) {
        return new OldUser(id, name);
    }
}
