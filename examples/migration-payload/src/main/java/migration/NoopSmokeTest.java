package migration;

import migrator.annotations.SmokeTestComponent;
import migrator.plan.MigratorDescriptor;
import migrator.smoke.SmokeTest;
import migrator.smoke.SmokeTestResult;

import java.util.List;
import java.util.Map;

@SmokeTestComponent
public class NoopSmokeTest implements SmokeTest {

    @Override
    public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> created) {
        System.out.println("[MIGRATION] Smoke test passed");
        return SmokeTestResult.ok("NoopSmokeTest");
    }
}
