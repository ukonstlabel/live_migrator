package migrator.smoke;

import java.util.List;

/**
 * Immutable report containing the results of all smoke tests and health checks.
 *
 * <p>A report is considered successful only if all contained {@link SmokeTestResult}s
 * indicate success. Use {@link #success()} to check overall status and
 * {@link #results()} to inspect individual test outcomes.
 *
 * @see SmokeTestRunner
 * @see SmokeTestResult
 */
public final class SmokeTestReport {
    private final boolean success;
    private final List<SmokeTestResult> results;

    /**
     * Creates a new smoke test report with an explicitly supplied success flag.
     *
     * <p>The flag is stored as-is and is <em>not</em> derived from {@code results}; callers that
     * want the conventional "successful iff every result is ok" semantics should prefer
     * {@link #of(List)}.
     *
     * @param success true if all tests passed
     * @param results the individual test results
     */
    public SmokeTestReport(boolean success, List<SmokeTestResult> results) {
        this.success = success;
        this.results = List.copyOf(results);
    }

    /**
     * Creates a report whose success is derived from the results: successful iff every result is ok
     * (an empty result list is considered successful). This is the canonical way to build a report
     * and guarantees {@link #success()} is consistent with {@link #results()}.
     *
     * @param results the individual test results
     * @return a report with success derived from {@code results}
     */
    public static SmokeTestReport of(List<SmokeTestResult> results) {
        boolean success = results.stream().allMatch(SmokeTestResult::isOk);
        return new SmokeTestReport(success, results);
    }

    /**
     * Returns true if all tests passed.
     *
     * @return true if all tests passed, false if any test failed
     */
    public boolean success() { return success; }

    /**
     * Returns the list of individual test results.
     *
     * @return an immutable list of test results
     */
    public List<SmokeTestResult> results() { return results; }
}
