package service;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import migrator.metrics.MigrationMetrics;
import migrator.state.MigrationHistoryEntry;
import migrator.state.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.model.User;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple long-running HTTP service that manages Users.
 *
 * <p>This service demonstrates live migration capabilities:
 * <ul>
 *   <li>The service is completely unaware of migration - it only knows about the User interface</li>
 *   <li>Users are created via {@link UserFactory} which can be replaced during migration</li>
 *   <li>The migration agent can be loaded into this running JVM to migrate OldUser → NewUser</li>
 * </ul>
 *
 * <h2>HTTP Endpoints:</h2>
 * <ul>
 *   <li>GET / - Health check</li>
 *   <li>GET /users - List all users with their implementation class</li>
 *   <li>POST /users - Create a new user (body: name=value)</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>
 * # Start the service
 * ./run-service.sh
 *
 * # In another terminal, trigger migration
 * ./run-migration.sh
 * </pre>
 *
 * @see UserFactory
 * @see service.model.User
 */
public class ServiceMain {

    private static final Logger log = LoggerFactory.getLogger(ServiceMain.class);
    public static final List<User> users = new CopyOnWriteArrayList<>();
    /**
     * Id → user index (collection variety alongside the List). The migration engine must patch this
     * Map's values OldUser → NewUser too; exercising Map patching under load.
     */
    public static final java.util.Map<Integer, User> byId = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger idCounter = new AtomicInteger(1);

    /**
     * Intake gate for new users. The migration phase listener flips this to {@code false} in
     * {@code onBeforeCriticalPhase} (and back to {@code true} in {@code onAfterCriticalPhase}) so the
     * application stops creating source-class instances while the engine runs its straggler rescan
     * under quiescence — guaranteeing every {@code OldUser} is migrated. See MigrationPhaseListener.
     */
    public static final java.util.concurrent.atomic.AtomicBoolean acceptingUsers =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private static final long startTimeMs = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        populateInitialState();

        int port = Integer.getInteger("service.port", 8080);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Configurable pool so the server itself isn't the bottleneck under e2e load.
        server.setExecutor(Executors.newFixedThreadPool(Integer.getInteger("service.threads", 32)));

        // GET /users - list all users
        server.createContext("/users", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGetUsers(exchange);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                handlePostUser(exchange);
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        // GET / - simple health check (legacy)
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, "Service is running\n");
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        // GET /health - comprehensive health check with JVM metrics
        server.createContext("/health", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            if ("/health".equals(path)) {
                handleHealthCheck(exchange);
            } else if ("/health/migration".equals(path)) {
                handleMigrationStatus(exchange);
            } else if ("/health/migration/history".equals(path)) {
                handleMigrationHistory(exchange);
            } else {
                sendResponse(exchange, 404, "Not Found");
            }
        });

        // POST /admin/dump - serialize current state to service.stateFile (S1 restart snapshot)
        server.createContext("/admin/dump", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                handleAdminDump(exchange);
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        server.start();
        log.info("Service started on http://localhost:{}", port);
        log.info("Endpoints:");
        log.info("  GET  /users                    - List all users");
        log.info("  POST /users                    - Create user (body: name=<name>)");
        log.info("  GET  /health                   - JVM health status");
        log.info("  GET  /health/migration         - Migration status");
        log.info("  GET  /health/migration/history - Migration history");

        // Keep the application running
        Thread.currentThread().join();
    }

    private static void handleGetUsers(HttpExchange exchange) throws IOException {
        // Iterate every (migrated) instance so the load actually touches migrated state and the
        // loop is JIT-sensitive (a cold-restarted JVM is slower here until it re-warms). The
        // response body is bounded so large M stays practical.
        int total = users.size();
        long checksum = 0;
        int oldCount = 0;
        for (User user : users) {
            checksum += 31L * user.getId() + user.getName().hashCode();
            if (user.getClass().getSimpleName().equals("OldUser")) oldCount++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Users (").append(total).append(" total, ")
          .append(oldCount).append(" OldUser, checksum=").append(checksum).append("):\n");
        int cap = Math.min(total, 50);
        int i = 0;
        for (User user : users) {
            if (i++ >= cap) break;
            sb.append(String.format("  [%d] %s - %s%n",
                user.getId(), user.getName(), user.getClass().getSimpleName()));
        }
        sendResponse(exchange, 200, sb.toString());
    }

    // ─── State lifecycle: initial population, snapshot dump/restore (S1 baseline) ───

    /** Initial state: restore a serialized snapshot if requested, else generate N users via the factory. */
    private static void populateInitialState() throws Exception {
        String stateFile = System.getProperty("service.stateFile");
        if (Boolean.getBoolean("service.loadState") && stateFile != null
                && java.nio.file.Files.exists(java.nio.file.Path.of(stateFile))) {
            loadStateFrom(stateFile);
            return;
        }
        int n = Integer.getInteger("service.initialUsers", -1);
        int friends = Integer.getInteger("service.friends", 0);   // refs/user (reference density)
        UserFactory factory = UserFactory.getInstance();
        // Build into a plain list and addAll once: users is a CopyOnWriteArrayList where
        // repeated add() would be O(N^2).
        List<User> batch = new ArrayList<>();
        if (n < 0) {
            batch.add(factory.createUser(idCounter.getAndIncrement(), "Alice"));
            batch.add(factory.createUser(idCounter.getAndIncrement(), "Bob"));
            batch.add(factory.createUser(idCounter.getAndIncrement(), "Charlie"));
        } else {
            for (int i = 0; i < n; i++) {
                batch.add(factory.createUser(idCounter.getAndIncrement(), "user-" + i));
            }
            log.info("Pre-populated {} users", n);
        }
        // Optional deterministic friend graph (reference density) for migration-under-load studies.
        if (friends > 0 && batch.size() > 1) {
            int m = batch.size();
            for (int i = 0; i < m; i++) {
                if (batch.get(i) instanceof service.model.OldUser ou) {
                    for (int k = 0; k < friends; k++) ou.friends.add(batch.get((i + 1 + k) % m));
                }
            }
            log.info("Wired friend graph: {} refs/user (edges={})", friends, (long) m * friends);
        }
        seedPrewarmDecoys(batch);
        users.addAll(batch);
        for (User u : batch) byId.put(u.getId(), u);
    }

    /**
     * Probe hook: seed extra {@link User} implementations so the hot loop in
     * {@link #handleGetUsers} is already polymorphic before any migration runs.
     *
     * <p>{@code -Dservice.prewarm=bi} adds {@link service.model.DecoyUser} (2 implementors seen at
     * the call site), {@code =mega} also adds {@link service.model.Decoy2User} (3 implementors,
     * which pushes C2 past its bimorphic-inlining limit). Off by default, so the standard S0/S1/S2/S3
     * runs are unchanged. See DecoyUser for why this isolates JIT effects from patching effects.
     */
    private static void seedPrewarmDecoys(List<User> batch) {
        String mode = System.getProperty("service.prewarm", "off");
        if ("off".equals(mode)) return;
        int n = Integer.getInteger("service.prewarmCount", 64);
        for (int i = 0; i < n; i++) {
            batch.add(new service.model.DecoyUser(idCounter.getAndIncrement(), "decoy-" + i));
            if ("mega".equals(mode)) {
                batch.add(new service.model.Decoy2User(idCounter.getAndIncrement(), "decoy2-" + i));
            }
        }
        log.info("Prewarm decoys seeded: mode={} count={}", mode, n);
    }

    @SuppressWarnings("unchecked")
    private static void loadStateFrom(String stateFile) throws Exception {
        long t = System.currentTimeMillis();
        List<User> restored;
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(
                new java.io.BufferedInputStream(
                        java.nio.file.Files.newInputStream(java.nio.file.Path.of(stateFile))))) {
            restored = (List<User>) ois.readObject();
        }
        users.addAll(restored);
        int maxId = 0;
        for (User u : restored) { byId.put(u.getId(), u); maxId = Math.max(maxId, u.getId()); }
        idCounter.set(maxId + 1);
        log.info("Restored {} users from {} in {}ms", users.size(), stateFile,
                System.currentTimeMillis() - t);
    }

    /** Serializes current state to service.stateFile so a restarted instance can reload it (S1 baseline). */
    private static void handleAdminDump(HttpExchange exchange) throws IOException {
        String stateFile = System.getProperty("service.stateFile");
        if (stateFile == null) {
            sendResponse(exchange, 400, "service.stateFile not configured\n");
            return;
        }
        long t = System.currentTimeMillis();
        List<User> snapshot = new ArrayList<>(users);
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(
                new java.io.BufferedOutputStream(
                        java.nio.file.Files.newOutputStream(java.nio.file.Path.of(stateFile))))) {
            oos.writeObject(snapshot);
        }
        long ms = System.currentTimeMillis() - t;
        log.info("Dumped {} users to {} in {}ms", snapshot.size(), stateFile, ms);
        sendResponse(exchange, 200, "Dumped " + snapshot.size() + " users in " + ms + "ms\n");
    }

    private static void handlePostUser(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = "User" + idCounter.get();

        // Simple parsing: name=value (tolerate a missing or empty value)
        int idx = body.indexOf("name=");
        if (idx >= 0) {
            String parsed = body.substring(idx + "name=".length()).split("&", 2)[0].trim();
            if (!parsed.isEmpty()) {
                name = parsed;
            }
        }

        // Intake is closed while the migration is in its critical (quiesced) phase, so no new
        // source-class instances are created during the engine's straggler rescan.
        if (!acceptingUsers.get()) {
            sendResponse(exchange, 503, "Service temporarily not accepting new users (migration in progress)\n");
            return;
        }

        // Use factory to create user - after migration this will create NewUser
        User newUser = UserFactory.getInstance().createUser(idCounter.getAndIncrement(), name);
        users.add(newUser);
        byId.put(newUser.getId(), newUser);

        log.info("Created user: {}", newUser);
        sendResponse(exchange, 201, "Created: " + newUser + " (" + newUser.getClass().getSimpleName() + ")\n");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleHealthCheck(HttpExchange exchange) throws IOException {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heap_used", memoryBean.getHeapMemoryUsage().getUsed());
        jvm.put("heap_max", memoryBean.getHeapMemoryUsage().getMax());
        jvm.put("cpu_load", osBean.getSystemLoadAverage());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("uptime_ms", System.currentTimeMillis() - startTimeMs);
        response.put("jvm", jvm);

        sendJsonResponse(exchange, 200, JsonWriter.toJson(response));
    }

    private static void handleMigrationStatus(HttpExchange exchange) throws IOException {
        MigrationState state = MigrationState.getInstance();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", state.getStatus().name());
        response.put("current_phase", state.getCurrentPhase() != null ? state.getCurrentPhase().name() : null);

        MigrationMetrics lastMetrics = state.getLastMetrics();
        if (lastMetrics != null) {
            Map<String, Object> lastMigration = new LinkedHashMap<>();
            lastMigration.put("id", lastMetrics.migrationId());
            lastMigration.put("timestamp", lastMetrics.endTime() != null ? lastMetrics.endTime().toString() : null);
            lastMigration.put("duration_ms", lastMetrics.totalDurationMs());
            lastMigration.put("objects_migrated", lastMetrics.objectsMigrated());
            lastMigration.put("objects_patched", lastMetrics.objectsPatched());
            response.put("last_migration", lastMigration);
        } else {
            response.put("last_migration", null);
        }

        if (state.getLastError() != null) {
            response.put("last_error", state.getLastError());
        }

        sendJsonResponse(exchange, 200, JsonWriter.toJson(response));
    }

    private static void handleMigrationHistory(HttpExchange exchange) throws IOException {
        List<MigrationHistoryEntry> history = MigrationState.getInstance().getHistory();

        List<Map<String, Object>> historyList = new ArrayList<>();
        for (MigrationHistoryEntry entry : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.migrationId());
            item.put("timestamp", entry.timestamp().toString());
            item.put("status", entry.status().name());

            if (entry.metrics() != null) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("duration_ms", entry.metrics().totalDurationMs());
                metrics.put("objects_migrated", entry.metrics().objectsMigrated());
                metrics.put("objects_patched", entry.metrics().objectsPatched());
                metrics.put("heap_delta", entry.metrics().heapDelta());
                item.put("metrics", metrics);
            }

            if (entry.errorMessage() != null) {
                item.put("error", entry.errorMessage());
            }

            historyList.add(item);
        }

        sendJsonResponse(exchange, 200, JsonWriter.toJson(historyList));
    }
}
