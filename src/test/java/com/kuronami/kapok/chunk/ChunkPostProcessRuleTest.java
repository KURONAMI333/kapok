package com.kuronami.kapok.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The deferral decision as a pure function: which calls are put off, and when a
 * held chunk is run. The game-side wiring is checked by the mixin audit; this
 * pins the rule that the wiring asks for.
 *
 * <p>If the new branch is removed (the rule always says "run now"), the first
 * three tests fail.
 */
class ChunkPostProcessRuleTest {

    @Test
    void defersInsideAWaitEvenWithNeighboursPresent() {
        assertTrue(ChunkPostProcessRule.shouldDefer(true, true, true, true, false));
    }

    @Test
    void defersWhenTheNeighbourhoodIsNotAllThere() {
        assertTrue(ChunkPostProcessRule.shouldDefer(true, true, false, false, false));
    }

    @Test
    void runsWhenOutsideAWaitAndAllNeighboursAreThere() {
        assertFalse(ChunkPostProcessRule.shouldDefer(true, true, false, true, false));
    }

    @Test
    void doesNothingWhenDisabled() {
        assertFalse(ChunkPostProcessRule.shouldDefer(false, true, true, false, false));
    }

    @Test
    void passesThroughWhenTheQueueIsFull() {
        assertFalse(ChunkPostProcessRule.shouldDefer(true, true, true, false, true));
    }

    @Test
    void runsAChunkWithNothingToReplayEvenInsideAWait() {
        // Vanilla's method is a no-op here, so holding it would only delay the
        // send. Disk-loaded chunks are usually in this state.
        assertFalse(ChunkPostProcessRule.shouldDefer(true, false, true, false, false));
    }

    @Test
    void heldChunkRunsAsSoonAsItsNeighbourhoodIsThere() {
        assertTrue(ChunkPostProcessRule.shouldRunNow(true, 0));
    }

    @Test
    void heldChunkWaitsWhileItsNeighbourhoodIsMissing() {
        assertFalse(ChunkPostProcessRule.shouldRunNow(false, ChunkPostProcessRule.MAX_AGE_TICKS - 1));
    }

    @Test
    void heldChunkIsForcedOutAtTheAgeLimit() {
        assertTrue(ChunkPostProcessRule.shouldRunNow(false, ChunkPostProcessRule.MAX_AGE_TICKS));
    }
}
