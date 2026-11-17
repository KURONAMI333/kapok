package com.kuronami.kapok.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * One tick's replay batch: where the count limit stops, where the time limit
 * stops, and what the caller is left holding when either one does. The clock is
 * injected, so no test sleeps and none needs a game.
 */
class ChunkPostProcessBatchTest {

    private final AtomicLong nanos = new AtomicLong(1_000L);

    private ChunkPostProcessBudget budget(double millis) {
        return new ChunkPostProcessBudget(ChunkPostProcessBudget.nanos(millis), nanos::get);
    }

    @Test
    void nothingDueMeansTheActionIsNotCalled() {
        List<String> ran = new ArrayList<>();
        assertEquals(ChunkPostProcessBatch.Stop.NOTHING_DUE,
                ChunkPostProcessBatch.run(List.<String>of(), 16, budget(5.0), ran::add));
        assertEquals(List.of(), ran);
    }

    @Test
    void everyDueItemRunsAndTheBatchFinishes() {
        List<String> ran = new ArrayList<>();
        assertEquals(ChunkPostProcessBatch.Stop.FINISHED,
                ChunkPostProcessBatch.run(List.of("a", "b"), 16, budget(5.0), ran::add));
        assertEquals(List.of("a", "b"), ran, "oldest first, as collected");
    }

    @Test
    void theCountLimitStopsBeforeTheNextItemAndLeavesItUnrun() {
        List<String> ran = new ArrayList<>();
        assertEquals(ChunkPostProcessBatch.Stop.COUNT_LIMIT,
                ChunkPostProcessBatch.run(List.of("a", "b", "c"), 2, budget(5.0), ran::add));
        assertEquals(List.of("a", "b"), ran, "the items past the limit are not handed to the action");
    }

    @Test
    void anItemThatDoesNoWorkStillCountsAgainstTheCountLimit() {
        // A chunk that is no longer the level's chunk for its position is
        // handed to the action and taken out of the queue even though replaying
        // it does nothing; that still costs a place and must not be retried.
        AtomicLong calls = new AtomicLong();
        assertEquals(ChunkPostProcessBatch.Stop.COUNT_LIMIT,
                ChunkPostProcessBatch.run(List.of("a", "b", "c"), 2, budget(5.0), item -> calls.incrementAndGet()));
        assertEquals(2, calls.get());
    }

    @Test
    void theTimeLimitStopsAfterAtLeastOneItem() {
        ChunkPostProcessBudget budget = budget(5.0);
        List<String> ran = new ArrayList<>();
        // Each item takes 3 ms; the first is inside the budget, the second spends it.
        assertEquals(ChunkPostProcessBatch.Stop.TIME_LIMIT,
                ChunkPostProcessBatch.run(List.of("a", "b", "c"), 16, budget, item -> {
                    ran.add(item);
                    nanos.addAndGet(3_000_000L);
                }));
        assertEquals(List.of("a", "b"), ran, "the limit is read after the item, so item b still ran");
    }

    @Test
    void spendingTheBudgetOnTheLastItemIsAFinishNotATimeStop() {
        List<String> ran = new ArrayList<>();
        ChunkPostProcessBatch.Stop stop = ChunkPostProcessBatch.run(List.of("a", "b"), 16, budget(1.0), item -> {
            ran.add(item);
            nanos.addAndGet(2_000_000L);
        });
        assertEquals(List.of("a"), ran);
        assertEquals(ChunkPostProcessBatch.Stop.TIME_LIMIT, stop, "b was left over");

        ran.clear();
        stop = ChunkPostProcessBatch.run(List.of("only"), 16, budget(1.0), item -> {
            ran.add(item);
            nanos.addAndGet(2_000_000L);
        });
        assertEquals(List.of("only"), ran);
        assertEquals(ChunkPostProcessBatch.Stop.FINISHED, stop, "nothing was left over");
    }

    @Test
    void aFirstItemThatRunsOverTheWholeBudgetStillCounts() {
        List<String> ran = new ArrayList<>();
        assertEquals(ChunkPostProcessBatch.Stop.TIME_LIMIT,
                ChunkPostProcessBatch.run(List.of("a", "b"), 16, budget(1.0), item -> {
                    ran.add(item);
                    nanos.addAndGet(50_000_000L);
                }));
        assertEquals(List.of("a"), ran, "a batch that starts always runs at least one item");
    }

    @Test
    void anUnlimitedBudgetOnlyObeysTheCountLimit() {
        List<String> ran = new ArrayList<>();
        assertEquals(ChunkPostProcessBatch.Stop.FINISHED,
                ChunkPostProcessBatch.run(List.of("a", "b", "c"), 16, budget(0.0), item -> {
                    ran.add(item);
                    nanos.addAndGet(3_600_000_000_000L);
                }));
        assertEquals(List.of("a", "b", "c"), ran);
    }

    @Test
    void aNonPositiveCountLimitRunsNothing() {
        List<String> ran = new ArrayList<>();
        assertEquals(ChunkPostProcessBatch.Stop.COUNT_LIMIT,
                ChunkPostProcessBatch.run(List.of("a"), 0, budget(5.0), ran::add));
        assertEquals(List.of(), ran);
    }

    @Test
    void aTimeLimitedBatchLeavesTheUnreachedItemsHeldWithTheirAge() {
        ChunkPostProcessQueue<String> queue = new ChunkPostProcessQueue<>(8, 40);
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        queue.ageAll();

        List<String> due = queue.collectDue((item, age) -> true, 16);
        assertEquals(List.of("a", "b", "c"), due);

        ChunkPostProcessBatch.Stop stop = ChunkPostProcessBatch.run(due, 16, budget(5.0), item -> {
            assertTrue(queue.take(item));
            nanos.addAndGet(3_000_000L);
        });

        assertEquals(ChunkPostProcessBatch.Stop.TIME_LIMIT, stop);
        assertFalse(queue.isHeld("a"), "a ran, so it left the queue");
        assertFalse(queue.isHeld("b"), "b ran too");
        assertTrue(queue.isHeld("c"), "c was collected but not reached");
        assertEquals(1, queue.ageOf("c"), "age is not reset by a batch that stopped early");
        assertEquals(1, queue.size());
    }
}
