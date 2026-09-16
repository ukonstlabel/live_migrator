package migrator.smoke;

/**
 * Immutable result of a single smoke test or health check execution.
 *
 * <p>Each result contains:
 * <ul>
 *   <li>A name identifying the test</li>
 *   <li>A pass/fail status</li>
 *   <li>An optional failure message</li>
 *   <li>An optional exception that caused the failure</li>
 * </ul>
 *
 * <p>Use the static factory methods {@link #ok(String)} and
 * {@link #fail(String, String, Throwable)} to create instances.
 *
 * @see SmokeTestReport
 * @see SmokeTest
 */
public final class SmokeTestResult {

    private final String name;
    private final boolean ok;
    private final String message;
    private final Throwable error;

    private SmokeTestResult(String name, boolean ok, String message, Throwable error) {
        this.name = name;
        this.ok = ok;
        this.message = message;
        this.error = error;
    }

    /**
     * Creates a successful test result.
     *
     * @param name the test name
     * @return a successful result
     */
    public static SmokeTestResult ok(String name) {
        return new SmokeTestResult(name, true, null, null);
    }

    /**
     * Creates a failed test result.
     *
     * @param name the test name
     * @param message the failure message
     * @param error the exception that caused the failure (may be null)
     * @return a failed result
     */
    public static SmokeTestResult fail(String name, String message, Throwable error) {
        return new SmokeTestResult(name, false, message, error);
    }

    /**
     * Creates a copy of this result with a different name.
     *
     * @param newName the new test name
     * @return a new result with the updated name
     */
    public SmokeTestResult withName(String newName) {
        return new SmokeTestResult(newName, ok, message, error);
    }

    /** Returns the test name. */
    public String name() { return name; }

    /** Returns true if the test passed. */
    public boolean isOk() { return ok; }

    /** Returns the failure message, or null if test passed. */
    public String message() { return message; }

    /** Returns the exception that caused the failure, or null if none. */
    public Throwable error() { return error; }
}
