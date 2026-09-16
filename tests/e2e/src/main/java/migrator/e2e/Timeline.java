package migrator.e2e;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates the client timeline into time buckets relative to {@code t0} (the update
 * trigger). A bucket index can be negative (window before t0 — baseline/warmup) or positive
 * (window after — downtime + recovery tail). All latencies are
 * <b>coordinated-omission-corrected</b>: measured from the intended send time.
 *
 * <p>Records arrive from many {@link java.net.http.HttpClient#sendAsync} callbacks — writing
 * to a bucket is synchronized on the bucket itself.
 */
final class Timeline {

    /** Request outcome classification; we split the kinds of "unavailability" for diagnosis. */
    enum Outcome {
        SUCCESS,        // 2xx
        STATUS_5XX,     // server replied 5xx (incl. 503 — closed intake during the S0 critical phase)
        CONN_REFUSED,   // connection refused (process is down during a restart)
        TIMEOUT,        // request timeout exceeded
        SATURATED,      // could not send — in-flight cap exceeded (overload)
        OTHER_ERROR     // other I/O errors
    }

    static final class Bucket {
        final long index;
        long total, success, status5xx, connRefused, timeout, saturated, otherError;
        final LatencyDigest latAll = new LatencyDigest();      // all requests (downtime shows up as high latency)
        final LatencyDigest latSuccess = new LatencyDigest();  // successful only (clean service-"health" p99)

        Bucket(long index) { this.index = index; }

        synchronized void add(Outcome o, long latNanos) {
            total++;
            latAll.record(latNanos);
            switch (o) {
                case SUCCESS -> { success++; latSuccess.record(latNanos); }
                case STATUS_5XX -> status5xx++;
                case CONN_REFUSED -> connRefused++;
                case TIMEOUT -> timeout++;
                case SATURATED -> saturated++;
                case OTHER_ERROR -> otherError++;
            }
        }

        double availability() { return total == 0 ? 1.0 : (double) success / total; }
    }

    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final RunConfig cfg;

    Timeline(RunConfig cfg) { this.cfg = cfg; }

    /** @param intendedEpochMs the intended (not actual) send time — for CO-correct bucketing. */
    void record(long intendedEpochMs, Outcome o, long latNanos) {
        long idx = Math.floorDiv(intendedEpochMs - cfg.t0EpochMs, cfg.bucketMs);
        buckets.computeIfAbsent(idx, Bucket::new).add(o, latNanos);
    }

    private List<Bucket> sorted() {
        return new ArrayList<>(new TreeMap<>(buckets).values());
    }

    // ─── Export ─────────────────────────────────────────────────────

    void writeCsv(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("t_rel_s,total,success,availability,rps,"
                    + "p50_ms,p99_ms,p999_ms,max_ms,p99_success_ms,"
                    + "status5xx,conn_refused,timeout,saturated,other_error\n");
            double bsec = cfg.bucketMs / 1000.0;
            for (Bucket b : sorted()) {
                w.write(String.format(Locale.ROOT,
                        "%.1f,%d,%d,%.4f,%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d%n",
                        b.index * bsec, b.total, b.success, b.availability(), b.total / bsec,
                        b.latAll.percentileMs(0.50), b.latAll.percentileMs(0.99),
                        b.latAll.percentileMs(0.999), b.latAll.maxMs(),
                        b.latSuccess.percentileMs(0.99),
                        b.status5xx, b.connRefused, b.timeout, b.saturated, b.otherError));
            }
        }
    }

    /**
     * Run summary that automatically isolates the downtime window and the recovery tail.
     *
     * <p><b>downtime</b> — total duration of post-t0 buckets where availability is below the
     * threshold (50% successful). <b>recovery</b> — time from t0 until the p99 of successful
     * requests has stably returned below {@code baselineP99 × recoveryFactor} (the cold-JVM
     * JIT-warmup tail shows up exactly here — S0 has none).
     */
    Summary summarize() {
        List<Bucket> all = sorted();
        List<Bucket> pre = new ArrayList<>();
        List<Bucket> post = new ArrayList<>();
        for (Bucket b : all) (b.index < 0 ? pre : post).add(b);

        double baselineP99 = medianP99(pre);
        double bsec = cfg.bucketMs / 1000.0;

        double downtimeSec = 0;
        for (Bucket b : post) if (b.availability() < 0.5) downtimeSec += bsec;

        // Recovery: the first moment after t0 from which the p99 of successful requests stays
        // below the threshold to the end of the window (and availability is full).
        double recoverySec = Double.NaN;
        double threshold = baselineP99 * cfg.recoveryFactor;
        for (int i = 0; i < post.size(); i++) {
            if (recovered(post, i, threshold)) {
                recoverySec = post.get(i).index * bsec;
                break;
            }
        }

        double maxP99 = 0, maxLat = 0;
        long totalReq = 0, totalSuccess = 0, totalErr = 0;
        for (Bucket b : all) {
            totalReq += b.total;
            totalSuccess += b.success;
            totalErr += (b.total - b.success);
            double p99 = b.latAll.percentileMs(0.99);
            if (!Double.isNaN(p99)) maxP99 = Math.max(maxP99, p99);
            double mx = b.latAll.maxMs();
            if (!Double.isNaN(mx)) maxLat = Math.max(maxLat, mx);
        }

        Summary s = new Summary();
        s.label = cfg.label;
        s.baselineP99Ms = baselineP99;
        s.downtimeSec = downtimeSec;
        s.recoverySec = recoverySec;
        s.maxP99Ms = maxP99;
        s.maxLatencyMs = maxLat;
        s.totalRequests = totalReq;
        s.totalSuccess = totalSuccess;
        s.totalErrors = totalErr;
        return s;
    }

    /** Bucket i counts as "recovered" if it and all subsequent buckets are healthy. */
    private boolean recovered(List<Bucket> post, int i, double threshold) {
        for (int j = i; j < post.size(); j++) {
            Bucket b = post.get(j);
            double p99 = b.latSuccess.percentileMs(0.99);
            if (b.availability() < 0.999) return false;
            if (!Double.isNaN(p99) && p99 > threshold) return false;
        }
        return true;
    }

    private double medianP99(List<Bucket> pre) {
        List<Double> v = new ArrayList<>();
        for (Bucket b : pre) {
            double p = b.latSuccess.percentileMs(0.99);
            if (!Double.isNaN(p)) v.add(p);
        }
        if (v.isEmpty()) return Double.NaN;
        v.sort(Double::compareTo);
        return v.get(v.size() / 2);
    }

    /** Run summary numbers; serialized to summary.json by hand (no external libs). */
    static final class Summary {
        String label;
        double baselineP99Ms, downtimeSec, recoverySec, maxP99Ms, maxLatencyMs;
        long totalRequests, totalSuccess, totalErrors;

        String toJson(RunConfig cfg) {
            return "{\n"
                    + "  \"label\": \"" + label + "\",\n"
                    + "  \"rps_target\": " + cfg.rps + ",\n"
                    + "  \"t0_epoch_ms\": " + cfg.t0EpochMs + ",\n"
                    + "  \"baseline_p99_ms\": " + num(baselineP99Ms) + ",\n"
                    + "  \"downtime_sec\": " + num(downtimeSec) + ",\n"
                    + "  \"recovery_sec\": " + num(recoverySec) + ",\n"
                    + "  \"max_p99_ms\": " + num(maxP99Ms) + ",\n"
                    + "  \"max_latency_ms\": " + num(maxLatencyMs) + ",\n"
                    + "  \"total_requests\": " + totalRequests + ",\n"
                    + "  \"total_success\": " + totalSuccess + ",\n"
                    + "  \"total_errors\": " + totalErrors + "\n"
                    + "}\n";
        }

        private static String num(double d) {
            return Double.isNaN(d) ? "null" : String.format(Locale.ROOT, "%.3f", d);
        }
    }

    void writeSummary(Path file, Summary s) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, s.toJson(cfg), StandardCharsets.UTF_8);
    }

    /** Compact ASCII timeline for stdout: availability and p99 per bucket around t0. */
    String asciiChart() {
        StringBuilder sb = new StringBuilder();
        sb.append("  t(s) | avail | p99(all) ms | bar\n");
        sb.append("  -----+-------+-------------+--------------------------------\n");
        double bsec = cfg.bucketMs / 1000.0;
        for (Bucket b : sorted()) {
            double p99 = b.latAll.percentileMs(0.99);
            int bars = Double.isNaN(p99) ? 0 : (int) Math.min(40, Math.round(p99 / 25.0));
            sb.append(String.format(Locale.ROOT, "  %5.0f | %5.1f%% | %11.2f | %s%s%n",
                    b.index * bsec, b.availability() * 100,
                    Double.isNaN(p99) ? 0.0 : p99,
                    b.index == 0 ? "▶" : " ", "#".repeat(bars)));
        }
        return sb.toString();
    }
}
