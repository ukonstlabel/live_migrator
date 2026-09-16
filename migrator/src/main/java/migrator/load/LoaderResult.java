package migrator.load;

/**
 * Result of an agent loading operation.
 *
 * @param success whether the agent was loaded successfully
 * @param pid the process ID of the target JVM
 * @param agentJarPath the path to the loaded agent JAR
 * @param message descriptive message about the result
 */
public record LoaderResult(
        boolean success,
        String pid,
        String agentJarPath,
        String message
) {

    /**
     * Create a successful result.
     *
     * @param pid the process ID
     * @param agentJarPath the agent JAR path
     * @return a successful LoaderResult
     */
    public static LoaderResult success(String pid, String agentJarPath) {
        return new LoaderResult(true, pid, agentJarPath, "Agent loaded successfully");
    }

    /**
     * Create a successful result with custom message.
     *
     * @param pid the process ID
     * @param agentJarPath the agent JAR path
     * @param message the result message
     * @return a successful LoaderResult
     */
    public static LoaderResult success(String pid, String agentJarPath, String message) {
        return new LoaderResult(true, pid, agentJarPath, message);
    }

    /**
     * Create a failure result.
     *
     * @param pid the process ID
     * @param agentJarPath the agent JAR path
     * @param message the error message
     * @return a failed LoaderResult
     */
    public static LoaderResult failure(String pid, String agentJarPath, String message) {
        return new LoaderResult(false, pid, agentJarPath, message);
    }
}
