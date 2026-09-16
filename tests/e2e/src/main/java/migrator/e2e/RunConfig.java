package migrator.e2e;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration of a load-generator run. Parameters are given as {@code key=value}
 * arguments (see {@link #parse}); everything else uses sensible defaults.
 *
 * <p>The time model is anchored to the update trigger moment {@code t0} (an absolute
 * epoch-ms that the orchestrator tells both the generator and the update script — so the
 * client timeline and the update event share the same clock). The generator starts
 * immediately, warms up the service in the window {@code [start, t0)} and keeps loading for
 * another {@code postSec} seconds after {@code t0} to capture the recovery/warmup tail.
 */
public final class RunConfig {

    public String url = "http://localhost:8080";
    /** Target arrival rate λ (requests/s), open-loop. */
    public int rps = 500;
    /** Absolute update trigger moment (epoch-ms). 0 → start + 20s. */
    public long t0EpochMs = 0;
    /** How long to load after t0 (recovery tail + JIT warmup). */
    public int postSec = 40;
    /** Weight of GET /users (reads migratable state) vs POST /users (intake gate). */
    public int getWeight = 8;
    public int postWeight = 2;
    /** Per-request timeout; downtime shows up as high latency/errors, not silence. */
    public int timeoutMs = 10_000;
    /** Timeline bucket width. */
    public int bucketMs = 1000;
    /** Cap on concurrently in-flight requests (memory guard during long downtime). */
    public int maxInflight = 4096;
    /** Output directory (timeline.csv, summary.json). */
    public String out = "e2e-out";
    /** Run label (strategy/replica), recorded in the summary. */
    public String label = "run";
    /** How many times p99 must exceed baseline to count as "not yet recovered". */
    public double recoveryFactor = 2.0;

    public static RunConfig parse(String[] args) {
        Map<String, String> kv = new HashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) kv.put(a.substring(0, eq).trim(), a.substring(eq + 1).trim());
        }
        RunConfig c = new RunConfig();
        c.url = kv.getOrDefault("url", c.url);
        c.rps = intOf(kv, "rps", c.rps);
        c.t0EpochMs = longOf(kv, "t0EpochMs", c.t0EpochMs);
        c.postSec = intOf(kv, "postSec", c.postSec);
        c.getWeight = intOf(kv, "getWeight", c.getWeight);
        c.postWeight = intOf(kv, "postWeight", c.postWeight);
        c.timeoutMs = intOf(kv, "timeoutMs", c.timeoutMs);
        c.bucketMs = intOf(kv, "bucketMs", c.bucketMs);
        c.maxInflight = intOf(kv, "maxInflight", c.maxInflight);
        c.out = kv.getOrDefault("out", c.out);
        c.label = kv.getOrDefault("label", c.label);
        c.recoveryFactor = dblOf(kv, "recoveryFactor", c.recoveryFactor);
        if (c.t0EpochMs == 0) c.t0EpochMs = System.currentTimeMillis() + 20_000;
        return c;
    }

    private static int intOf(Map<String, String> kv, String k, int def) {
        return kv.containsKey(k) ? Integer.parseInt(kv.get(k)) : def;
    }

    private static long longOf(Map<String, String> kv, String k, long def) {
        return kv.containsKey(k) ? Long.parseLong(kv.get(k)) : def;
    }

    private static double dblOf(Map<String, String> kv, String k, double def) {
        return kv.containsKey(k) ? Double.parseDouble(kv.get(k)) : def;
    }

    @Override
    public String toString() {
        return "RunConfig{url=" + url + ", rps=" + rps + ", t0EpochMs=" + t0EpochMs
                + ", postSec=" + postSec + ", mix(get/post)=" + getWeight + "/" + postWeight
                + ", timeoutMs=" + timeoutMs + ", bucketMs=" + bucketMs
                + ", maxInflight=" + maxInflight + ", label=" + label + "}";
    }
}
