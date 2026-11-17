package com.kuronami.kapok.chunk;

import java.util.List;
import java.util.function.Consumer;

/**
 * One tick's replay batch in {@link ChunkPostProcessGate#retry}: up to a count
 * limit of the due items, and a time limit around the work.
 *
 * <p>The count limit is checked before each item and the time limit after it,
 * both against the count of items already run, so with a positive count limit a
 * batch that starts always runs at least one item and a queue cannot be frozen
 * by the clock. An item the batch does not reach is simply not handed to the caller,
 * which is what lets {@link ChunkPostProcessQueue#collectDue} leave the rest
 * held with their age.
 *
 * <p><b>Every item handed to the action is counted</b>, whether or not the
 * action did any work with it. In the game one of those items can be a chunk
 * that is no longer the level's chunk for its position; taking it out of the
 * queue is still work that must not be repeated next tick.
 *
 * <p>The clock comes from {@link ChunkPostProcessBudget}, so the decision is
 * tested without sleeping and without a game.
 */
public final class ChunkPostProcessBatch {

    /** Why a batch stopped. */
    public enum Stop {
        /** Nothing was due; the action was not called. */
        NOTHING_DUE,
        /** Every collected item ran and neither limit was reached. */
        FINISHED,
        /** The count limit was reached with items still collected but not run. */
        COUNT_LIMIT,
        /** The time limit was reached with items still collected but not run. */
        TIME_LIMIT
    }

    private ChunkPostProcessBatch() {
    }

    /**
     * Runs the collected items oldest first until one of the limits is reached.
     *
     * @param due      items from {@link ChunkPostProcessQueue#collectDue}, in
     *                 queue order
     * @param maxItems most items this batch may run; {@code <= 0} runs none
     * @param budget   the time limit for this batch
     * @param action   what to do with one item, e.g. take it out of the queue
     *                 and replay it
     * @return why the batch stopped
     */
    public static <T> Stop run(List<T> due, int maxItems, ChunkPostProcessBudget budget, Consumer<? super T> action) {
        if (due.isEmpty()) {
            return Stop.NOTHING_DUE;
        }
        if (maxItems <= 0) {
            return Stop.COUNT_LIMIT;
        }
        long startedAt = budget.start();
        int ran = 0;
        int size = due.size();
        for (T item : due) {
            if (ran >= maxItems) {
                return Stop.COUNT_LIMIT;
            }
            action.accept(item);
            ran++;
            if (ran < size && budget.exhausted(startedAt, ran)) {
                return Stop.TIME_LIMIT;
            }
        }
        return Stop.FINISHED;
    }
}
