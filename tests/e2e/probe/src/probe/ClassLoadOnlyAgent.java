package probe;

import java.lang.instrument.Instrumentation;
import probe.model.ProbeUser;

/**
 * Control D: attach, load ONE new implementor of service.model.User, and do nothing else.
 * No heap walk, no reference patching, no registry update, no factory swap — the application's
 * object graph is untouched. Anything that shows up in the client timeline after this attach is
 * attributable to class loading (CHA invalidation -> deopt -> recompile), not to migration logic.
 *
 * agentArgs: "insert=N" additionally puts N ProbeUser instances into ServiceMain.users, which makes
 * the hot call site's type profile polymorphic as well (default 0 = class load only).
 */
public class ClassLoadOnlyAgent {
    /** Keeps the instance reachable so the class cannot be unloaded. */
    public static volatile Object pinned;

    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        long t = System.currentTimeMillis();
        pinned = new ProbeUser(-1, "probe");
        int insert = 0;
        if (agentArgs != null && agentArgs.startsWith("insert=")) {
            insert = Integer.parseInt(agentArgs.substring("insert=".length()).trim());
        }
        if (insert > 0) {
            java.util.List<Object> batch = new java.util.ArrayList<>();
            for (int i = 0; i < insert; i++) batch.add(new ProbeUser(-1000 - i, "probe-" + i));
            @SuppressWarnings("unchecked")
            java.util.List<Object> users = (java.util.List<Object>) (java.util.List<?>) service.ServiceMain.users;
            users.addAll(batch);
        }
        System.out.println("[PROBE] ClassLoadOnlyAgent: loaded " + pinned.getClass().getName()
                + ", inserted=" + insert + ", epochMs=" + t);
    }
}
