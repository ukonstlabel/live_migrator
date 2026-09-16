package migrator.heap;

import com.sun.tools.attach.VirtualMachine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only helper that makes the native JVMTI agent ({@code agent/agent.c}) available inside the
 * surefire JVM so the native methods of {@link NativeHeapWalker} can be exercised by real tests.
 *
 * <p>The surefire JVM is <em>not</em> started with {@code -agentpath}, so the native methods are
 * unbound by default and any call throws {@link UnsatisfiedLinkError}. This helper loads the agent
 * at runtime by self-attaching via the Attach API and calling
 * {@link VirtualMachine#loadAgentPath(String)}, which triggers {@code Agent_OnAttach} → JVMTI
 * initialization and binds the {@code Java_migrator_heap_NativeHeapWalker_*} symbols.
 *
 * <p>Self-attach requires {@code -Djdk.attach.allowAttachSelf=true} (set in the module's surefire
 * {@code argLine}). The agent shared library is rebuilt from source when a C toolchain and JDK
 * headers are present, otherwise the committed {@code agent/libagent.so} is used. If none of this
 * is possible (no toolchain, no prebuilt library, attach disabled, non-Linux, …) loading fails and
 * tests that depend on it skip via JUnit assumptions rather than failing the build.
 */
final class NativeAgentSupport {

    private static volatile boolean attempted = false;
    private static volatile boolean loaded = false;
    private static volatile String skipReason = "not attempted";

    private NativeAgentSupport() {}

    /** @return true once the native agent is loaded and its methods are callable. */
    static synchronized boolean ensureLoaded() {
        if (attempted) return loaded;
        attempted = true;
        try {
            Path lib = resolveAgentLibrary();
            if (lib == null) {
                skipReason = "could not build or locate libagent.so";
                return false;
            }
            String pid = String.valueOf(ProcessHandle.current().pid());
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                vm.loadAgentPath(lib.toAbsolutePath().toString());
            } finally {
                vm.detach();
            }
            // Smoke check: a native call must now succeed instead of throwing UnsatisfiedLinkError.
            new NativeHeapWalker().snapshotObjects(NativeAgentSupport.class);
            loaded = true;
            skipReason = null;
            return true;
        } catch (Throwable t) {
            skipReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            loaded = false;
            return false;
        }
    }

    static String skipReason() {
        return skipReason;
    }

    /**
     * Builds {@code libagent.so} from {@code agent/agent.c} into the module's {@code target/}
     * directory when gcc and the JDK headers are available; otherwise returns the committed
     * {@code agent/libagent.so} if present.
     */
    private static Path resolveAgentLibrary() throws Exception {
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return null;

        Path source = projectRoot.resolve("agent").resolve("agent.c");
        Path prebuilt = projectRoot.resolve("agent").resolve("libagent.so");

        Path built = tryBuild(source);
        if (built != null) return built;

        return Files.isReadable(prebuilt) ? prebuilt : null;
    }

    /** Walks up from the working directory looking for the {@code agent/agent.c} source file. */
    private static Path findProjectRoot() {
        Path dir = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isReadable(dir.resolve("agent").resolve("agent.c"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    /** Compiles the agent against the running JDK's headers. Returns null if the build is not possible. */
    private static Path tryBuild(Path source) {
        if (!Files.isReadable(source)) return null;

        String javaHome = System.getProperty("java.home");
        Path include = Path.of(javaHome, "include");
        Path includeOs = include.resolve(osIncludeDir());
        if (!Files.isReadable(include.resolve("jni.h")) || !Files.isReadable(includeOs)) {
            return null; // JRE without headers (or unknown OS layout)
        }

        Path out = Path.of(System.getProperty("user.dir"), "target", "libagent-test.so");
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
            String output = new String(p.getInputStream().readAllBytes());
            boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || !Files.isReadable(out)) {
                System.err.println("[NativeAgentSupport] gcc build failed:\n" + output);
                return null;
            }
            return out;
        } catch (Exception e) {
            return null; // no gcc on PATH, etc.
        }
    }

    private static String osIncludeDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) return "linux";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "win32";
        return "linux";
    }
}
