package migration;

import migrator.annotations.CommitComponent;
import migrator.commit.CommitManager;
import migrator.crac.NoopCracController;

@CommitComponent
public class NoopCommitManager extends CommitManager {

    public NoopCommitManager() {
        super(NoopCracController.INSTANCE);
    }

    @Override
    public void commit() {
        System.out.println("[MIGRATION] Commit");
    }
}
