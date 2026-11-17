package com.kuronami.kapok.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The time limit of one {@code retry} batch. The clock is injected, so the
 * decision is pinned without sleeping: where the batch stops, where it must keep
 * going regardless, and what the property switch does.
 */
class ChunkPostProcessBudgetTest {

    private final AtomicLong nanos = new AtomicLong(1_000L);

    private ChunkPostProcessBudget budget(double millis) {
        return new ChunkPostProcessBudget(ChunkPostProcessBudget.nanos(millis), nanos::get);
    }

    @Test
    void stopsOnlyAfterTheLimitAndAfterAtLeastOneChunk() {
        ChunkPostProcessBudget budget = budget(5.0);
        long start = budget.start();
        assertFalse(budget.exhausted(start, 0), "nothing has run yet");
        nanos.addAndGet(4_999_000L);
        assertFalse(budget.exhausted(start, 0), "the minimum is one chunk, not one chunk per clock read");
        assertFalse(budget.exhausted(start, 1), "still inside the budget");
        nanos.addAndGet(1_000L);
        assertTrue(budget.exhausted(start, 1), "the budget is spent");
        assertTrue(budget.exhausted(start, 16), "the count limit does not lift the time limit");
    }

    @Test
    void aChunkThatRunsOverTheLimitStillCountsAsProgress() {
        ChunkPostProcessBudget budget = budget(1.0);
        long start = budget.start();
        // One chunk took far longer than the whole budget.
        nanos.addAndGet(50_000_000L);
        assertFalse(budget.exhausted(start, 0));
        assertTrue(budget.exhausted(start, 1));
    }

    @Test
    void zeroOrNegativeDisablesTheTimeLimit() {
        for (double millis : new double[] {0.0, -1.0}) {
            ChunkPostProcessBudget budget = budget(millis);
            assertFalse(budget.isLimited(), "millis " + millis);
            long start = budget.start();
            nanos.addAndGet(3_600_000_000_000L);
            assertFalse(budget.exhausted(start, 16), "millis " + millis);
        }
    }

    @Test
    void aPositiveLimitIsReportedAsLimited() {
        assertTrue(budget(5.0).isLimited());
        assertTrue(budget(0.001).isLimited());
    }

    @Test
    void elapsedIsMeasuredFromTheStartOfTheBatch() {
        ChunkPostProcessBudget budget = budget(2.0);
        nanos.addAndGet(9_000_000L); // time before the batch does not count
        long start = budget.start();
        assertFalse(budget.exhausted(start, 1));
        nanos.addAndGet(2_000_000L);
        assertTrue(budget.exhausted(start, 1));
    }

    @Test
    void millisToNanosKeepsTheDefaultAndTheSwitchApart() {
        assertEquals(5_000_000L, ChunkPostProcessBudget.nanos(5.0));
        assertEquals(500_000L, ChunkPostProcessBudget.nanos(0.5));
        assertEquals(0L, ChunkPostProcessBudget.nanos(0.0));
        assertEquals(0L, ChunkPostProcessBudget.nanos(-3.0));
    }

    @Test
    void thePropertyDefaultsAndFallsBackOnGarbage() {
        String previous = System.getProperty("kapok.chunk.postProcessBudgetMs");
        try {
            System.clearProperty("kapok.chunk.postProcessBudgetMs");
            assertEquals(ChunkPostProcessBudget.DEFAULT_MILLIS, ChunkPostProcessBudget.fromPropertyMillis());
            System.setProperty("kapok.chunk.postProcessBudgetMs", "12.5");
            assertEquals(12.5, ChunkPostProcessBudget.fromPropertyMillis());
            System.setProperty("kapok.chunk.postProcessBudgetMs", "0");
            assertEquals(0.0, ChunkPostProcessBudget.fromPropertyMillis());
            System.setProperty("kapok.chunk.postProcessBudgetMs", "NaN");
            assertEquals(ChunkPostProcessBudget.DEFAULT_MILLIS, ChunkPostProcessBudget.fromPropertyMillis(),
                    "NaN must not turn the limit off");
            System.setProperty("kapok.chunk.postProcessBudgetMs", "Infinity");
            assertEquals(ChunkPostProcessBudget.DEFAULT_MILLIS, ChunkPostProcessBudget.fromPropertyMillis());
            System.setProperty("kapok.chunk.postProcessBudgetMs", "five");
            assertEquals(ChunkPostProcessBudget.DEFAULT_MILLIS, ChunkPostProcessBudget.fromPropertyMillis(),
                    "a typo must not silently turn the limit off");
        } finally {
            if (previous == null) {
                System.clearProperty("kapok.chunk.postProcessBudgetMs");
            } else {
                System.setProperty("kapok.chunk.postProcessBudgetMs", previous);
            }
        }
    }
}
