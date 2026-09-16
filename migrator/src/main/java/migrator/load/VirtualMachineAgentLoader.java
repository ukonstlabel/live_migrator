package migrator.load;

import com.sun.tools.attach.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import migrator.exceptions.MigrateException;

/**
 * Agent loader implementation using the Java Attach API.
 *
 * <p>This class attaches to a running JVM process and dynamically loads
 * the migration agent JAR. The agent's {@code agentmain} method is then
 * invoked to trigger the migration.
 *
 * <h2>Usage:</h2>
 * <pre>
 * AgentLoader loader = new VirtualMachineAgentLoader();
 * LoaderResult result = loader.load("12345", "/path/to/agent.jar");
 * </pre>
 *
 * <p>On failure this loader throws {@link MigrateException} rather than returning an
 * unsuccessful {@link LoaderResult}; a returned result is always a success.
 *
 * @see AgentLoader
 */
public class VirtualMachineAgentLoader implements AgentLoader {

    private static final Logger log = LoggerFactory.getLogger(VirtualMachineAgentLoader.class);

    /**
     * Loads the agent, passing {@code agentJarPath} itself as the agent arguments: the
     * migration agent uses that argument as the JAR path to scan for migration components.
     */
    @Override
    public LoaderResult load(String pid, String agentJarPath) throws MigrateException {
        return load(pid, agentJarPath, agentJarPath);
    }

    @Override
    public LoaderResult load(String pid, String agentJarPath, String agentArgs) throws MigrateException {
        if (pid == null || pid.isBlank()) {
            throw new MigrateException("PID cannot be null or empty");
        }
        if (agentJarPath == null || agentJarPath.isBlank()) {
            throw new MigrateException("Agent JAR path cannot be null or empty");
        }

        log.info("Attaching to JVM with PID: {}", pid);
        log.info("Loading agent: {}", agentJarPath);

        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            vm.loadAgent(agentJarPath, agentArgs);
            log.info("Agent loaded successfully into JVM {}", pid);
            return LoaderResult.success(pid, agentJarPath);
        } catch (Exception e) {
            String message = "Failed to attach and load agent: " + e;
            log.error(message, e);
            throw new MigrateException(message, e);
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                    log.debug("Detached from JVM {}", pid);
                } catch (Exception e) {
                    log.warn("Failed to detach from JVM {}: {}", pid, e.getMessage());
                }
            }
        }
    }

    /**
     * Convenience method to attach, load agent, and run migration in one call.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(String pid, String agentJarPath) throws MigrateException {
        return new VirtualMachineAgentLoader().load(pid, agentJarPath);
    }

    /**
     * Convenience method to attach, load agent, and run migration with custom arguments.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @param agentArgs additional arguments to pass to the agent
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(String pid, String agentJarPath, String agentArgs) throws MigrateException {
        return new VirtualMachineAgentLoader().load(pid, agentJarPath, agentArgs);
    }

    /**
     * Command-line entry point for attaching to a JVM and loading the migration agent.
     *
     * <h2>Usage:</h2>
     * <pre>
     * java migrator.load.VirtualMachineAgentLoader &lt;PID&gt; &lt;agent-jar-path&gt;
     * </pre>
     *
     * @param args command-line arguments: PID and agent JAR path
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java migrator.load.VirtualMachineAgentLoader <PID> <agent-jar-path>");
            System.exit(1);
        }

        String pid = args[0];
        String agentJar = args[1];

        System.out.println("Attaching to JVM with PID: " + pid);
        System.out.println("Loading agent: " + agentJar);

        try {
            LoaderResult result = attachAndLoad(pid, agentJar);
            System.out.println(result.message());
            System.exit(result.success() ? 0 : 1);
        } catch (MigrateException e) {
            System.err.println("Failed to attach and load agent: " + e.getMessage());
            System.exit(1);
        }
    }
}
