package migration;

import migrator.engine.MigrationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.ServiceMain;
import service.UserFactory;

import java.lang.instrument.Instrumentation;
import java.util.Set;

/**
 * Java Agent for live migration. Loaded dynamically via Attach API.
 */
public class MigrationAgent {

    private static final Logger log = LoggerFactory.getLogger(MigrationAgent.class);

    public static void agentmain(String agentArgs, Instrumentation inst) {
        log.info("Migration agent loaded");

        try {
            ClassLoader cl = MigrationAgent.class.getClassLoader();
            MigrationEngine.createAndMigrate(Set.of(ServiceMain.class), cl, agentArgs);

            log.info("Migration complete: {}", MigrationEngine.getLastMetrics().summary());

            UserFactory.setInstance(new NewUserFactory());
            log.info("UserFactory replaced");

        } catch (Exception e) {
            log.error("Migration failed", e);
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("Agent loaded at startup");
    }
}
