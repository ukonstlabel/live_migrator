package migration.migrator;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;
import migration.model.NewUser;
import service.model.OldUser;

@Migrator
public class UserMigrator implements ClassMigrator<OldUser, NewUser> {

    @Override
    public NewUser migrate(OldUser old) {
        // Carry the friends list over; the engine patches its elements (OldUser → NewUser) after.
        return new NewUser(old.id, old.name, old.friends);
    }
}
