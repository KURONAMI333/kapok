package com.kuronami.kapok.chunk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Only a retry for the holder that {@code pendingUnloads} still has at its
 * position goes through; the first call from {@code processUnloads} is always
 * that case, because the holder was put there just before.
 */
class StaleUnloadFilterTest {

    @Test
    void theHolderStillPendingIsKept() {
        Object holder = new Object();
        assertFalse(StaleUnloadFilter.shouldDrop(holder, holder));
    }

    @Test
    void aHolderLoadedAgainIsDropped() {
        assertTrue(StaleUnloadFilter.shouldDrop(null, new Object()));
    }

    @Test
    void aHolderReplacedByAnotherAtThePositionIsDropped() {
        assertTrue(StaleUnloadFilter.shouldDrop(new Object(), new Object()));
    }
}
