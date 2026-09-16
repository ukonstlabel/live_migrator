package migrator.bench;

import com.sun.tools.attach.VirtualMachine;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Loads two agents into the benchmark JVM:
 * <ul>
 *   <li><b>Native JVMTI agent</b> ({@code agent/agent.c}) — needed by Live Migrator (S0)
 *       to bind the {@code NativeHeapWalker} native methods. Loaded via self-attach through
 *       the Attach API, as in the test helper {@code NativeAgentSupport}. Requires
 *       {@code -Djdk.attach.allowAttachSelf=true} (added to the fork's jvmArgs).</li>
 *   <li><b>Instrumentation</b> via {@code byte-buddy-agent} — needed by S4
 *       (class redefinition) to call {@code redefineClasses}.</li>
 * </ul>
 *
 * Building the native agent: if gcc and the JDK headers are available it is compiled from
 * source against the current JDK; otherwise the committed {@code agent/libagent.so} is used.
 */
public final class BenchAgent {

    private static volatile boolean nativeLoaded = false;
    private static volatile Instrumentation instrumentation;

    private BenchAgent() {}

    /** @return Instrumentation for S4 (via self-attach byte-buddy-agent). */
    public static synchronized Instrumentation instrumentation() {
        if (instrumentation == null) {
            instrumentation = ByteBuddyAgent.install();
        }
        return instrumentation;
    }

    /** Loads the native JVMTI agent into the current JVM. Throws on failure. */
    public static synchronized void ensureNativeAgentLoaded() throws Exception {
        if (nativeLoaded) return;
        Path lib = resolveAgentLibrary();
        if (lib == null) {
            throw new IllegalStateException("Could not build or find libagent.so");
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgentPath(lib.toAbsolutePath().toString());
        } finally {
            vm.detach();
        }
        nativeLoaded = true;
    }

    // ─── Build/locate the native library (adapted from NativeAgentSupport) ───

    private static Path resolveAgentLibrary() throws Exception {
        Path root = findProjectRoot();
        if (root == null) return null;
        Path source = root.resolve("agent").resolve("agent.c");
        Path prebuilt = root.resolve("agent").resolve("libagent.so");
        Path built = tryBuild(source);
        if (built != null) return built;
        return Files.isReadable(prebuilt) ? prebuilt : null;
    }

    private static Path findProjectRoot() {
        Path dir = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isReadable(dir.resolve("agent").resolve("agent.c"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    private static Path tryBuild(Path source) {
        if (!Files.isReadable(source)) return null;
        String javaHome = System.getProperty("java.home");
        Path include = Path.of(javaHome, "include");
        Path includeOs = include.resolve(osIncludeDir());
        if (!Files.isReadable(include.resolve("jni.h")) || !Files.isReadable(includeOs)) {
            return null;
        }
        Path out = Path.of(System.getProperty("user.dir"), "target", "libagent-bench.so");
        try {
            Files.createDirectories(out.getParent());
            List<String> cmd = new ArrayList<>(List.of(
                    "gcc", "-fPIC",
                    "-I" + include,
                    "-I" + includeOs,
                    "-shared", "-O2",
                    "-o", out.toString(),
                    source.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            if (!done || p.exitValue() != 0) return null;
            return Files.isReadable(out) ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String osIncludeDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "win32";
        return "linux";
    }
}
