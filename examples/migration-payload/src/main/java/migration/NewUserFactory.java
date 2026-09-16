package migration;

import migration.model.NewUser;
import service.UserFactory;
import service.model.User;

public class NewUserFactory extends UserFactory {

    @Override
    public User createUser(int id, String name) {
        return new NewUser(id, name);
    }
}
