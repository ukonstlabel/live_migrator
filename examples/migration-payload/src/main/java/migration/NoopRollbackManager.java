package migration;

import migrator.annotations.RollbackComponent;
import migrator.commit.RollbackManager;
import migrator.crac.NoopCracController;

@RollbackComponent
public class NoopRollbackManager extends RollbackManager {

    public NoopRollbackManager() {
        super(NoopCracController.INSTANCE);
    }

    @Override
    public void rollback() {
        System.out.println("[MIGRATION] Rollback");
    }
}
