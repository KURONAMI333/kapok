package com.kuronami.kapok.chunk;

import java.util.function.LongSupplier;

/**
 * Time limit for one tick's replay batch in {@link ChunkPostProcessGate#retry}.
 *
 * <p>The count limit ({@link ChunkPostProcessRule#MAX_PER_TICK}) bounds how many
 * chunks a tick may touch but not how long that takes: a chunk whose replay
 * walks a large fluid network costs milliseconds, so sixteen of them after a
 * stall turned one tick into 100-250 ms. The rule below adds the missing bound
 * and keeps the two properties that make the queue safe:
 *
 * <ul>
 * <li><b>At least one chunk per tick.</b> The limit is only consulted after a
 *     chunk has been taken, so a queue can never stop making progress. A limit
 *     of zero would otherwise freeze the queue, and a chunk's send stays held
 *     for as long as it waits.</li>
 * <li><b>Measured around the work, not the scan.</b> The clock starts after
 *     {@link ChunkPostProcessQueue#collectDue} has scanned the queue and is read
 *     after each replay, so only the replays count against the limit. The scan
 *     is bounded separately: it runs once per tick.</li>
 * </ul>
 *
 * <p>{@code -Dkapok.chunk.postProcessBudgetMs} sets the limit in
 * milliseconds (default {@value #DEFAULT_MILLIS}). Zero or a negative value
 * turns the time limit off and leaves the count limit alone, which is the
 * measurement switch for comparing against the count-only behaviour.
 *
 * <p>The clock is injected so the decision is tested without sleeping. Not
 * thread-safe; {@link ChunkPostProcessGate} reads it from the server thread.
 */
public final class ChunkPostProcessBudget {

    /** Default limit per level tick. */
    public static final double DEFAULT_MILLIS = 5.0;

    private final long budgetNanos;
    private final LongSupplier clock;

    /**
     * @param budgetNanos limit per tick; {@code <= 0} disables the time limit
     * @param clock       monotonic source in nanoseconds, e.g. {@code System::nanoTime}
     */
    public ChunkPostProcessBudget(long budgetNanos, LongSupplier clock) {
        this.budgetNanos = budgetNanos;
        this.clock = clock;
    }

    /**
     * Reads {@code -Dkapok.chunk.postProcessBudgetMs}. A value that is not a
     * finite number ({@code five}, {@code NaN}, {@code Infinity}) keeps the
     * default: a typo in a JVM flag must not silently disable the limit.
     */
    public static ChunkPostProcessBudget fromProperty(LongSupplier clock) {
        return new ChunkPostProcessBudget(nanos(fromPropertyMillis()), clock);
    }

    static double fromPropertyMillis() {
        String raw = System.getProperty("kapok.chunk.postProcessBudgetMs");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MILLIS;
        }
        try {
            double millis = Double.parseDouble(raw.trim());
            return Double.isFinite(millis) ? millis : DEFAULT_MILLIS;
        } catch (NumberFormatException e) {
            return DEFAULT_MILLIS;
        }
    }

    static long nanos(double millis) {
        if (millis <= 0) {
            return 0L;
        }
        return (long) (millis * 1_000_000.0);
    }

    /** @return {@code false} when the time limit is off and only the count limit applies */
    public boolean isLimited() {
        return budgetNanos > 0;
    }

    /** Starts measuring a tick's batch. */
    public long start() {
        return clock.getAsLong();
    }

    /**
     * @param startedAt value returned by {@link #start()} for this batch
     * @param taken     how many chunks this batch has taken and run so far
     * @return whether the batch should stop; never true before the first chunk,
     *         and never true when the time limit is off
     */
    public boolean exhausted(long startedAt, int taken) {
        if (taken <= 0 || budgetNanos <= 0) {
            return false;
        }
        return clock.getAsLong() - startedAt >= budgetNanos;
    }
}
