package migrator.e2e;

import migrator.e2e.Timeline.Outcome;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load generator with coordinated-omission correction.
 *
 * <p><b>Open-loop:</b> requests are scheduled on a fixed time grid with step {@code 1/λ},
 * regardless of whether the previous one has returned. <b>CO correction:</b> latency is
 * measured from the <i>intended</i> send time, not the actual one — so when the service is
 * down (restart) or has closed intake (S0 critical phase), the "stuck" requests honestly
 * accumulate growing latency instead of masking it (as closed-loop would).
 *
 * <p>Requests are sent asynchronously ({@link HttpClient#sendAsync}); the scheduler does not
 * block on responses. A cap on concurrent requests bounds memory during long downtime —
 * exceeding it is recorded as {@link Outcome#SATURATED} (overload).
 *
 * <p>Output: {@code timeline.csv} (buckets around t0) + {@code summary.json}
 * (baseline p99, downtime, recovery/JIT tail, percentiles) + an ASCII timeline on stdout.
 *
 * <pre>{@code
 * java -jar e2e-loadgen.jar url=http://localhost:8080 rps=800 \
 *      t0EpochMs=<epoch> postSec=40 out=e2e-out label=S0-rep1
 * }</pre>
 */
public final class LoadGenerator {

    public static void main(String[] args) throws Exception {
        RunConfig cfg = RunConfig.parse(args);
        System.out.println("[loadgen] " + cfg);

        Timeline timeline = new Timeline(cfg);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.min(2000, cfg.timeoutMs)))
                .executor(Executors.newFixedThreadPool(
                        Math.max(8, Runtime.getRuntime().availableProcessors() * 4)))
                .build();

        URI usersUri = URI.create(cfg.url + "/users");
        Duration reqTimeout = Duration.ofMillis(cfg.timeoutMs);
        long permits = cfg.maxInflight;
        java.util.concurrent.Semaphore inflightGate = new java.util.concurrent.Semaphore((int) permits);
        AtomicLong inflight = new AtomicLong();

        final long startNanos = System.nanoTime();
        final long startEpochMs = System.currentTimeMillis();
        final double intervalNs = 1_000_000_000.0 / cfg.rps;
        final long stopEpochMs = cfg.t0EpochMs + cfg.postSec * 1000L;

        System.out.printf("[loadgen] firing at λ=%d rps until t0+%ds; t0 in %.1fs%n",
                cfg.rps, cfg.postSec, (cfg.t0EpochMs - startEpochMs) / 1000.0);

        long i = 0;
        while (true) {
            long intendedNanos = startNanos + (long) (i * intervalNs);
            long intendedEpochMs = startEpochMs + (intendedNanos - startNanos) / 1_000_000L;
            if (intendedEpochMs > stopEpochMs) break;
            parkUntil(intendedNanos);
            i++;

            if (!inflightGate.tryAcquire()) {
                // Overload: cannot even send — record as saturation with the timeout latency.
                timeline.record(intendedEpochMs, Outcome.SATURATED, cfg.timeoutMs * 1_000_000L);
                continue;
            }
            inflight.incrementAndGet();

            // Deterministic GET/POST interleave (Bresenham) so the open-loop schedule is fully
            // reproducible run-to-run — no RNG. POSTs are evenly spread at the configured ratio.
            long total = cfg.getWeight + cfg.postWeight;
            boolean isGet = cfg.postWeight == 0
                    || ((i + 1) * cfg.postWeight) / total == (i * cfg.postWeight) / total;
            HttpRequest req = isGet
                    ? HttpRequest.newBuilder(usersUri).timeout(reqTimeout).GET().build()
                    : HttpRequest.newBuilder(usersUri).timeout(reqTimeout)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("name=loadtest")).build();

            final long fIntendedNanos = intendedNanos;
            final long fIntendedEpochMs = intendedEpochMs;
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        long latNanos = System.nanoTime() - fIntendedNanos;
                        Outcome o = classify(resp, err);
                        timeline.record(fIntendedEpochMs, o, latNanos);
                        inflightGate.release();
                        inflight.decrementAndGet();
                    });
        }

        // Drain: wait for in-flight requests to complete, with a margin over the timeout.
        long drainDeadline = System.currentTimeMillis() + cfg.timeoutMs + 3000;
        while (inflight.get() > 0 && System.currentTimeMillis() < drainDeadline) {
            LockSupport.parkNanos(50_000_000L);
        }

        Path outDir = Path.of(cfg.out);
        timeline.writeCsv(outDir.resolve("timeline.csv"));
        Timeline.Summary summary = timeline.summarize();
        timeline.writeSummary(outDir.resolve("summary.json"), summary);

        System.out.println();
        System.out.println(timeline.asciiChart());
        System.out.printf("[loadgen] %s: baseline p99=%.1fms | downtime=%.1fs | recovery=%s | maxP99=%.1fms | req=%d (err=%d)%n",
                summary.label, summary.baselineP99Ms, summary.downtimeSec,
                Double.isNaN(summary.recoverySec) ? "n/a" : String.format("%.1fs", summary.recoverySec),
                summary.maxP99Ms, summary.totalRequests, summary.totalErrors);
        System.out.println("[loadgen] wrote " + outDir.resolve("timeline.csv") + " and summary.json");

        // HttpClient's executor threads are non-daemon; exit explicitly so the JVM terminates
        // promptly (the orchestration script waits on this process).
        client.close();
        System.exit(0);
    }

    private static Outcome classify(HttpResponse<Void> resp, Throwable err) {
        if (err != null) {
            Throwable c = unwrap(err);
            if (c instanceof HttpTimeoutException) return Outcome.TIMEOUT;
            if (c instanceof ConnectException) return Outcome.CONN_REFUSED;
            String msg = String.valueOf(c.getMessage()).toLowerCase();
            if (msg.contains("connection refused")) return Outcome.CONN_REFUSED;
            if (msg.contains("timed out") || msg.contains("timeout")) return Outcome.TIMEOUT;
            return Outcome.OTHER_ERROR;
        }
        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) return Outcome.SUCCESS;
        if (sc >= 500) return Outcome.STATUS_5XX;   // 503 = closed intake (visible POST downtime in S0)
        return Outcome.OTHER_ERROR;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }

    /** Park until the deadline with a final busy-spin for send-grid accuracy. */
    private static void parkUntil(long deadlineNanos) {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) return;
            if (remaining > 100_000) LockSupport.parkNanos(remaining - 50_000);
            else Thread.onSpinWait();
        }
    }

    private LoadGenerator() {}
}
