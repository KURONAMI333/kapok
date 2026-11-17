package com.kuronami.kapok.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The guard decides "answer null instead of waiting" from primitives, so the
 * whole contract is pinned here without a game: which calls get the null and
 * which keep the vanilla wait, and how nested windows restore the outer
 * position.
 */
class ChunkLoadGuardTest {

    private static final long SELF = ChunkLoadGuard.pack(10, 20);

    @BeforeEach
    void resetBefore() {
        ChunkLoadGuard.resetForTest();
    }

    @AfterEach
    void resetAfter() {
        ChunkLoadGuard.resetForTest();
    }

    @Test
    void nullInsideWindowForUnloadedNeighbor() {
        ChunkLoadGuard.openWindow(SELF);
        assertTrue(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
    }

    @Test
    void notNullOutsideWindow() {
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
    }

    @Test
    void notNullForSelfChunk() {
        ChunkLoadGuard.openWindow(SELF);
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 10, 20));
    }

    @Test
    void notNullWhenChunkAlreadyLoaded() {
        ChunkLoadGuard.openWindow(SELF);
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, true, 11, 20));
    }

    @Test
    void notNullWhenChunkRequired() {
        ChunkLoadGuard.openWindow(SELF);
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, true, false, 11, 20));
    }

    @Test
    void notNullOffMainThread() {
        ChunkLoadGuard.openWindow(SELF);
        assertFalse(ChunkLoadGuard.shouldReturnNull(false, false, false, 11, 20));
    }

    @Test
    void mapPreviewReturnsNullForUnloadedBiomes() {
        ChunkLoadGuard.enterMapPreview();
        try {
            assertTrue(ChunkLoadGuard.inMapPreview());
            assertTrue(ChunkLoadGuard.shouldReturnNullForMapPreview(false, true, false));
        } finally {
            ChunkLoadGuard.exitMapPreview();
        }
        assertFalse(ChunkLoadGuard.inMapPreview());
    }

    @Test
    void mapPreviewKeepsVanillaWhenChunkIsPresent() {
        ChunkLoadGuard.enterMapPreview();
        try {
            assertFalse(ChunkLoadGuard.shouldReturnNullForMapPreview(false, true, true));
        } finally {
            ChunkLoadGuard.exitMapPreview();
        }
    }

    @Test
    void mapPreviewKeepsVanillaWhenChunkRequired() {
        ChunkLoadGuard.enterMapPreview();
        try {
            assertFalse(ChunkLoadGuard.shouldReturnNullForMapPreview(true, true, false));
        } finally {
            ChunkLoadGuard.exitMapPreview();
        }
    }

    @Test
    void mapPreviewKeepsVanillaForOtherStatuses() {
        ChunkLoadGuard.enterMapPreview();
        try {
            assertFalse(ChunkLoadGuard.shouldReturnNullForMapPreview(false, false, false));
        } finally {
            ChunkLoadGuard.exitMapPreview();
        }
    }

    @Test
    void mapPreviewNestsAndNeverGoesNegative() {
        ChunkLoadGuard.enterMapPreview();
        ChunkLoadGuard.enterMapPreview();
        ChunkLoadGuard.exitMapPreview();
        assertTrue(ChunkLoadGuard.inMapPreview());
        ChunkLoadGuard.exitMapPreview();
        assertFalse(ChunkLoadGuard.inMapPreview());
        ChunkLoadGuard.exitMapPreview();
        assertFalse(ChunkLoadGuard.inMapPreview());
    }

    @Test
    void resetClearsMapPreview() {
        ChunkLoadGuard.enterMapPreview();
        ChunkLoadGuard.resetForTest();
        assertFalse(ChunkLoadGuard.inMapPreview());
    }

    @Test
    void thePreviewFastPathCountsAllThreadsAndTheDepthStaysPerThread() throws Exception {
        assertEquals(0, ChunkLoadGuard.mapPreviewTotalForTest(), "idle before anything starts");
        assertFalse(ChunkLoadGuard.inMapPreview());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ChunkLoadGuard.enterMapPreview();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ChunkLoadGuard.exitMapPreview();
        }, "kapok-preview-test");
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(1, ChunkLoadGuard.mapPreviewTotalForTest(),
                    "the total is what the fast path reads, so another thread's preview opens it");
            assertFalse(ChunkLoadGuard.inMapPreview(),
                    "but this thread is not previewing, so the per-thread depth must still decide");
        } finally {
            release.countDown();
            worker.join(5_000L);
        }
        assertEquals(0, ChunkLoadGuard.mapPreviewTotalForTest(), "the total comes back to idle");
        assertFalse(ChunkLoadGuard.inMapPreview());
    }

    @Test
    void resetForServerStopGivesBackOnlyThisThreadsPreviewDepth() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch reset = new CountDownLatch(1);
        CountDownLatch left = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ChunkLoadGuard.enterMapPreview();
            entered.countDown();
            try {
                reset.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The reset on the main thread must not have counted this depth away.
            ChunkLoadGuard.exitMapPreview();
            left.countDown();
        }, "kapok-reset-race-test");
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        assertEquals(1, ChunkLoadGuard.mapPreviewTotalForTest(), "the worker is previewing");
        ChunkLoadGuard.resetForServerStop();
        assertEquals(1, ChunkLoadGuard.mapPreviewTotalForTest(),
                "another thread's preview depth stays counted");
        assertFalse(ChunkLoadGuard.inMapPreview(), "the main thread itself is not previewing");

        reset.countDown();
        assertTrue(left.await(5, TimeUnit.SECONDS));
        worker.join(5_000L);
        assertEquals(0, ChunkLoadGuard.mapPreviewTotalForTest(),
                "the worker's own exit must not take the total below zero");

        // The fast path is off at zero, so the per-thread read decides again.
        ChunkLoadGuard.enterMapPreview();
        assertTrue(ChunkLoadGuard.inMapPreview(), "the main thread can start a fresh preview");
        assertEquals(1, ChunkLoadGuard.mapPreviewTotalForTest());
        ChunkLoadGuard.exitMapPreview();
        assertEquals(0, ChunkLoadGuard.mapPreviewTotalForTest());
    }

    @Test
    void resetForServerStopDropsLeakedStateSoTheNextWorldStartsClean() {
        ChunkLoadGuard.openWindow(SELF);
        ChunkLoadGuard.suspend();
        ChunkLoadGuard.enterMapPreview();
        ChunkLoadGuard.resetForServerStop();
        assertEquals(0, ChunkLoadGuard.depthForTest());
        assertEquals(0, ChunkLoadGuard.mapPreviewTotalForTest());
        assertFalse(ChunkLoadGuard.inWindow());
        assertFalse(ChunkLoadGuard.inMapPreview());
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
    }

    @Test
    void nestedWindowsRestoreOuterPosition() {
        long outer = ChunkLoadGuard.pack(0, 0);
        long inner = ChunkLoadGuard.pack(1, 0);
        ChunkLoadGuard.openWindow(outer);
        ChunkLoadGuard.openWindow(inner);
        ChunkLoadGuard.closeWindow();
        assertTrue(ChunkLoadGuard.inWindow());
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 0, 0));
        assertTrue(ChunkLoadGuard.shouldReturnNull(true, false, false, 1, 0));
        ChunkLoadGuard.closeWindow();
        assertFalse(ChunkLoadGuard.inWindow());
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 1, 0));
    }

    @Test
    void closingWithoutOpeningIsHarmless() {
        ChunkLoadGuard.closeWindow();
        assertFalse(ChunkLoadGuard.inWindow());
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
    }

    @Test
    void suspendHidesWindowUntilResume() {
        ChunkLoadGuard.openWindow(SELF);
        ChunkLoadGuard.suspend();
        assertFalse(ChunkLoadGuard.inWindow());
        assertFalse(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
        ChunkLoadGuard.resume();
        assertTrue(ChunkLoadGuard.inWindow());
        assertTrue(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
    }

    @Test
    void windowOpenedWhileSuspendedAppliesAndRestoresSuspension() {
        ChunkLoadGuard.openWindow(SELF);
        ChunkLoadGuard.suspend();
        ChunkLoadGuard.openWindow(ChunkLoadGuard.pack(1, 0));
        assertTrue(ChunkLoadGuard.shouldReturnNull(true, false, false, 11, 20));
        ChunkLoadGuard.closeWindow();
        assertFalse(ChunkLoadGuard.inWindow());
        ChunkLoadGuard.resume();
        assertTrue(ChunkLoadGuard.inWindow());
    }

    @Test
    void resumeWithoutSuspendDoesNotCloseWindow() {
        ChunkLoadGuard.openWindow(SELF);
        ChunkLoadGuard.resume();
        assertTrue(ChunkLoadGuard.inWindow());
    }

    @Test
    void suspendAndWindowsNestBackToEmpty() {
        ChunkLoadGuard.openWindow(SELF);
        ChunkLoadGuard.suspend();
        assertFalse(ChunkLoadGuard.inWindow());
        ChunkLoadGuard.openWindow(ChunkLoadGuard.pack(1, 1));
        assertTrue(ChunkLoadGuard.inWindow());
        ChunkLoadGuard.closeWindow();
        assertFalse(ChunkLoadGuard.inWindow());
        ChunkLoadGuard.resume();
        assertTrue(ChunkLoadGuard.inWindow());
        ChunkLoadGuard.closeWindow();
        assertEquals(0, ChunkLoadGuard.depthForTest());
    }

    @Test
    void closeWindowDoesNotPopSuspendMarker() {
        ChunkLoadGuard.suspend();
        ChunkLoadGuard.closeWindow();
        assertEquals(1, ChunkLoadGuard.depthForTest());
        ChunkLoadGuard.resume();
        assertEquals(0, ChunkLoadGuard.depthForTest());
    }

    @Test
    void unwrapResultMatchesVanilla() {
        Object chunk = new Object();
        assertEquals(chunk, ChunkLoadGuard.unwrapResult(chunk, true, () -> "unused"));
        assertEquals(chunk, ChunkLoadGuard.unwrapResult(chunk, false, () -> "unused"));
        assertNull(ChunkLoadGuard.unwrapResult(null, false, () -> "Unloaded chunk"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChunkLoadGuard.unwrapResult(null, true, () -> "Unloaded chunk"));
        assertEquals("Chunk not there when requested: Unloaded chunk", e.getMessage());
    }
}
