package com.kuronami.kapok.diag;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fixed-storage measurement of frame-start intervals for one joined world.
 *
 * <p>The first frame at or after the warmup boundary becomes the baseline. Its
 * distance from a frame inside warmup is deliberately not a sample.
 */
public final class FrameIntervalProbe {
    private final long warmupNanos;
    private final long[] intervals;

    private boolean joined;
    private long joinedAt;
    private boolean hasPreviousFrame;
    private long previousFrame;
    private int retained;
    private long overflow;
    private long totalNanos;
    private long maxNanos;

    public FrameIntervalProbe(long warmupNanos, int capacity) {
        if (warmupNanos < 0L) {
            throw new IllegalArgumentException("warmup must not be negative");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.warmupNanos = warmupNanos;
        this.intervals = new long[capacity];
    }

    /** Starts a fresh run. Previously retained samples become unreachable immediately. */
    public void join(long now) {
        joined = true;
        joinedAt = now;
        hasPreviousFrame = false;
        retained = 0;
        overflow = 0L;
        totalNanos = 0L;
        maxNanos = 0L;
    }

    /** Records a frame start only while a joined run is past its warmup. */
    public void frame(long now) {
        if (!joined || now - joinedAt < warmupNanos) {
            return;
        }
        if (!hasPreviousFrame) {
            previousFrame = now;
            hasPreviousFrame = true;
            return;
        }

        long interval = now - previousFrame;
        previousFrame = now;
        if (interval < 0L) {
            // System.nanoTime differences are non-negative for realistic run lengths.
            // If another clock source violates that contract, rebase without polluting data.
            return;
        }
        totalNanos = saturatingAdd(totalNanos, interval);
        maxNanos = Math.max(maxNanos, interval);
        if (retained < intervals.length) {
            intervals[retained++] = interval;
        } else {
            overflow++;
        }
    }

    /** Finishes and returns this joined run once. */
    public Optional<Summary> finish() {
        if (!joined) {
            return Optional.empty();
        }
        joined = false;

        long[] sorted = Arrays.copyOf(intervals, retained);
        Arrays.sort(sorted);
        long count = (long)retained + overflow;
        return Optional.of(new Summary(
                count,
                retained,
                overflow,
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                maxNanos,
                totalNanos));
    }

    private static long percentile(long[] sorted, int percent) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (percent * sorted.length + 99) / 100;
        return sorted[rank - 1];
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /** Percentiles use nearest-rank over retained samples; totals include overflow. */
    public record Summary(
            long count,
            int retained,
            long overflow,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos,
            long totalNanos) {
    }
}
