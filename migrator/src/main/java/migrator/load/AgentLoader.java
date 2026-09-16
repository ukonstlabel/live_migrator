package migrator.load;

import migrator.exceptions.MigrateException;

/**
 * Interface for loading migration agents into JVM processes.
 *
 * <p>Implementations of this interface handle the mechanics of attaching
 * to a JVM and loading the migration agent JAR.
 */
public interface AgentLoader {

    /**
     * Load the migration agent into the target JVM.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    LoaderResult load(String pid, String agentJarPath) throws MigrateException;

    /**
     * Load the migration agent into the target JVM with additional arguments.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @param agentArgs additional arguments to pass to the agent
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    LoaderResult load(String pid, String agentJarPath, String agentArgs) throws MigrateException;
}
