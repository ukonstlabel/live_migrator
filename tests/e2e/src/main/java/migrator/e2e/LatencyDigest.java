package migrator.e2e;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Accumulator of latencies for a single time bucket with exact percentiles.
 *
 * <p>Stores raw values (ns) in a growable array and sorts on flush — for a ~1s-wide bucket
 * at a reasonable λ that is tens of thousands of values, bounded memory. As a safeguard
 * against pathological cases (a long bucket / very high λ), after {@link #CAP} values it
 * switches to reservoir sampling (Vitter R), keeping an unbiased percentile estimate at
 * fixed memory.
 *
 * <p>Not thread-safe: each bucket is written under its own lock in {@link Timeline}.
 */
final class LatencyDigest {

    private static final int CAP = 200_000;

    private long[] samples = new long[1024];
    private int size = 0;
    private long seen = 0;

    void record(long nanos) {
        seen++;
        if (size < CAP) {
            if (size == samples.length) samples = Arrays.copyOf(samples, samples.length * 2);
            samples[size++] = nanos;
        } else {
            // Reservoir: the i-th (i>=CAP) element is kept with probability CAP/seen.
            long r = ThreadLocalRandom.current().nextLong(seen);
            if (r < CAP) samples[(int) r] = nanos;
        }
    }

    long count() {
        return seen;
    }

    /** @return percentile {@code p∈[0,1]} in milliseconds, or NaN if empty. */
    double percentileMs(double p) {
        if (size == 0) return Double.NaN;
        long[] copy = Arrays.copyOf(samples, size);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p * size) - 1;
        if (idx < 0) idx = 0;
        if (idx >= size) idx = size - 1;
        return copy[idx] / 1_000_000.0;
    }

    double maxMs() {
        if (size == 0) return Double.NaN;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) max = Math.max(max, samples[i]);
        return max / 1_000_000.0;
    }
}
