package migrator.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import migrator.plan.MigratorDescriptor;

/**
 * Executes smoke tests and health checks after migration completes.
 *
 * <p>This class is synchronous and thread-safe. It executes all registered
 * health checks first, then all smoke tests, collecting results into a
 * {@link SmokeTestReport}.
 *
 * <h2>Usage:</h2>
 * <pre>
 * SmokeTestRunner runner = new SmokeTestRunner.Builder()
 *     .addHealthCheck(() -&gt; checkDatabaseConnection())
 *     .addSmokeTest(new UserValidationTest())
 *     .build();
 *
 * SmokeTestReport report = runner.runAll(createdPerMigrator);
 * if (!report.success()) {
 *     // Handle failure
 * }
 * </pre>
 *
 * @see SmokeTest
 * @see HealthCheck
 * @see SmokeTestReport
 */
public class SmokeTestRunner {

    private final List<HealthCheck> healthChecks;
    private final List<SmokeTest> smokeTests;

    private SmokeTestRunner(List<HealthCheck> healthChecks, List<SmokeTest> smokeTests) {
        this.healthChecks = List.copyOf(healthChecks);
        this.smokeTests = List.copyOf(smokeTests);
    }

    /**
     * Runs all health checks and smoke tests.
     *
     * <p>Health checks are executed first, followed by smoke tests.
     * Each test is isolated - a failure in one test does not prevent
     * other tests from running.
     *
     * @param createdPerMigrator mapping from migrator to created objects
     * @return a report containing all test results
     */
    public SmokeTestReport runAll(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
        // Tolerate a null map: health checks don't need it, and smoke tests get an empty map
        // rather than NPE'ing on null.
        Map<MigratorDescriptor, List<Object>> created =
                createdPerMigrator != null ? createdPerMigrator : Map.of();

        List<SmokeTestResult> results = new ArrayList<>();
        results.addAll(runHealthChecks());
        results.addAll(runSmokeTests(created));

        return SmokeTestReport.of(results);
    }

    /** Runs every registered health check, isolating failures into individual results. */
    private List<SmokeTestResult> runHealthChecks() {
        List<SmokeTestResult> results = new ArrayList<>();
        for (int i = 0; i < healthChecks.size(); i++) {
            HealthCheck hc = healthChecks.get(i);
            String name = "healthcheck#" + i;
            try {
                boolean ok = hc.check();
                results.add(ok ? SmokeTestResult.ok(name)
                            : SmokeTestResult.fail(name, "returned false", null));
            } catch (Throwable t) {
                rethrowIfFatal(t);
                results.add(SmokeTestResult.fail(name, "threw: " + t, t));
            }
        }
        return results;
    }

    /** Runs every registered smoke test, naming unnamed/null results and isolating failures. */
    private List<SmokeTestResult> runSmokeTests(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
        List<SmokeTestResult> results = new ArrayList<>();
        for (int i = 0; i < smokeTests.size(); i++) {
            SmokeTest st = smokeTests.get(i);
            String name = "smoketest#" + i;
            try {
                SmokeTestResult r = st.run(createdPerMigrator);
                if (r == null) {
                    results.add(SmokeTestResult.fail(name, "returned null result", null));
                } else if (r.name() == null) {
                    results.add(r.withName(name));
                } else {
                    results.add(r);
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
                results.add(SmokeTestResult.fail(name, "threw: " + t, t));
            }
        }
        return results;
    }

    /**
     * Catches {@link Throwable} so that a single test's failure — including an
     * {@link AssertionError} (the natural way to write a smoke-test assertion) or any other
     * {@link Error} — is recorded as a failed result rather than aborting the whole run.
     * Truly unrecoverable VM-level failures are rethrown so they are never swallowed.
     */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof LinkageError) {
            throw (Error) t;
        }
    }

    /**
     * Builder for constructing {@link SmokeTestRunner} instances.
     */
    public static final class Builder {
        private final List<HealthCheck> healthChecks = new ArrayList<>();
        private final List<SmokeTest> smokeTests = new ArrayList<>();

        /**
         * Adds a health check to the runner.
         *
         * @param hc the health check to add
         * @return this builder for method chaining
         */
        public Builder addHealthCheck(HealthCheck hc) {
            this.healthChecks.add(hc);
            return this;
        }

        /**
         * Adds a smoke test to the runner.
         *
         * @param st the smoke test to add
         * @return this builder for method chaining
         */
        public Builder addSmokeTest(SmokeTest st) {
            this.smokeTests.add(st);
            return this;
        }

        /**
         * Builds the smoke test runner with all registered tests.
         *
         * @return a new SmokeTestRunner instance
         */
        public SmokeTestRunner build() {
            return new SmokeTestRunner(healthChecks, smokeTests);
        }
    }
}
