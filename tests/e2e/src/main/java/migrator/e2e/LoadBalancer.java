package migrator.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal reverse-proxy load balancer (round-robin + connection-failover) for S2/S3. It
 * stays live and warm for the whole run, so it adds no warmup tail at t0 — just a constant
 * overhead, like a real LB.
 *
 * <p>The active backend set is changed at runtime via {@code POST /lb/active} (body — a
 * comma-separated list of {@code host:port}). This is how the orchestration drives it:
 * <ul>
 *   <li><b>S2 (rolling):</b> remove a backend from the set (drain) → kill → start a cold one →
 *       wait for health → put it back; one at a time.</li>
 *   <li><b>S3 (blue-green):</b> bring up the green set → atomically replace active with green →
 *       kill blue.</li>
 * </ul>
 *
 * <p>A request is proxied to the next active backend; on a connection failure (backend is
 * down) the next one is tried — so draining before killing gives the client zero downtime,
 * while the "killed exactly when picked" race is covered by the failover.
 *
 * <pre>{@code
 * java -cp e2e-loadgen.jar migrator.e2e.LoadBalancer \
 *      port=8080 active=localhost:8081,localhost:8082 timeoutMs=10000
 * }</pre>
 */
public final class LoadBalancer {

    private volatile List<String> active;
    private final AtomicInteger rr = new AtomicInteger();
    private final HttpClient client;
    private final Duration reqTimeout;

    private LoadBalancer(List<String> initial, int timeoutMs) {
        this.active = List.copyOf(initial);
        this.reqTimeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(1000))
                .executor(Executors.newFixedThreadPool(
                        Math.max(16, Runtime.getRuntime().availableProcessors() * 8)))
                .build();
    }

    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) kv.put(a.substring(0, eq).trim(), a.substring(eq + 1).trim());
        }
        int port = Integer.parseInt(kv.getOrDefault("port", "8080"));
        int timeoutMs = Integer.parseInt(kv.getOrDefault("timeoutMs", "10000"));
        List<String> initial = parseList(kv.getOrDefault("active", "localhost:8081"));

        LoadBalancer lb = new LoadBalancer(initial, timeoutMs);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(
                Integer.getInteger("lb.threads", 64)));
        server.createContext("/lb/active", lb::handleAdmin);   // longest-prefix: admin wins
        server.createContext("/", lb::handleProxy);            // everything else proxied
        server.start();
        System.out.printf("[lb] front :%d -> %s (timeout %dms)%n", port, initial, timeoutMs);
    }

    // ─── Admin: change the active set at runtime ───
    private void handleAdmin(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            List<String> next = parseList(body);
            if (next.isEmpty()) { respond(ex, 400, "empty active set\n"); return; }
            this.active = List.copyOf(next);
            System.out.println("[lb] active -> " + next);
            respond(ex, 200, "ok: " + next + "\n");
        } else {
            respond(ex, 200, active + "\n");
        }
    }

    // ─── Proxy with round-robin and connection-failover ───
    private void handleProxy(HttpExchange ex) throws IOException {
        List<String> backends = this.active;             // snapshot
        int n = backends.size();
        if (n == 0) { respond(ex, 503, "no active backends\n"); return; }

        byte[] body = ex.getRequestBody().readAllBytes();
        String method = ex.getRequestMethod();
        String pathAndQuery = ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() != null ? "?" + ex.getRequestURI().getRawQuery() : "");
        String ct = ex.getRequestHeaders().getFirst("Content-Type");

        int start = Math.floorMod(rr.getAndIncrement(), n);
        for (int i = 0; i < n; i++) {
            String backend = backends.get((start + i) % n);
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://" + backend + pathAndQuery))
                        .timeout(reqTimeout)
                        .method(method, body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
                if (ct != null) rb.header("Content-Type", ct);
                HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                // Got a response (including 5xx — that is a valid backend reply, not a connection failure).
                byte[] rbody = resp.body();
                ex.sendResponseHeaders(resp.statusCode(), rbody.length == 0 ? -1 : rbody.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(rbody); }
                return;
            } catch (IOException | InterruptedException e) {
                // Backend unreachable (connection refused/reset) — try the next active one.
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        respond(ex, 502, "all active backends failed\n");
    }

    private static void respond(HttpExchange ex, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static List<String> parseList(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(csv.split(","))) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
