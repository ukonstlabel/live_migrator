package probe;

import java.lang.instrument.Instrumentation;

/**
 * Control E: attach and load an agent jar that defines NO new implementor of service.model.User.
 * Isolates the cost of the attach itself (JVMTI attach listener thread, agent jar appended to the
 * system class loader, safepoint) from the cost of loading a competing User implementation.
 */
public class NoopAgent {
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[PROBE] NoopAgent: attached, no new User implementor loaded, epochMs="
                + System.currentTimeMillis());
    }
}
