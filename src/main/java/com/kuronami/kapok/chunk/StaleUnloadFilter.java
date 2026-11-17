package com.kuronami.kapok.chunk;

import com.kuronami.kapok.Kapok;

/**
 * Decides whether an unload retry in {@code ChunkMap.scheduleUnload} still has
 * anything to do, and counts the ones it drops.
 *
 * <h3>Why vanilla can spin</h3>
 * <p>{@code ChunkMap.processUnloads} moves a holder whose generation ref count is
 * zero from {@code updatingChunkMap} to {@code pendingUnloads} and calls
 * {@code scheduleUnload}. That registers a step on the holder's save-sync future
 * which, if {@code isReadyForSaving()} is false, calls {@code scheduleUnload}
 * again. When the save-sync future is already done, the step is handed straight
 * to the unload queue, so a holder that is not ready because its generation ref
 * count is above zero goes round the queue inside one {@code processUnloads}
 * call for as long as time is left. With {@code () -> true}, which is what the
 * stop loop and {@code saveAllChunks} pass, the loop only ends when the ref count
 * drops, and the main thread runs no other task meanwhile. If the generation
 * work that holds the ref count is waiting for the main thread, it never drops
 * and the save never completes (observed on All the Mons: the server thread in
 * this loop for 105 s after "Saving worlds", until the process was killed).
 *
 * <p>ModernFix's {@code release_protochunks} puts a holder that is still in
 * {@code updatingChunkMap} into {@code pendingUnloads} and calls the retry
 * lambda directly; that call removes it again before returning and does not go
 * through {@code scheduleUnload}, so the reasoning below is unaffected.
 *
 * <h3>Why the dropped retries had nothing to do</h3>
 * <p>The ref count only goes up in {@code ChunkMap.acquireGeneration}, which
 * reads {@code updatingChunkMap}, and a holder only enters
 * {@code pendingUnloads} with a ref count of zero, on the main thread, when it
 * leaves {@code updatingChunkMap}. So a holder still in {@code pendingUnloads}
 * has a ref count of zero, and a holder whose ref count is above zero has been
 * taken back by {@code updateChunkScheduling} (which removes it from
 * {@code pendingUnloads}). For such a holder the retry ends in
 * {@code pendingUnloads.remove(pos, holder)} returning false, which does
 * nothing; if the holder is dropped again later, {@code processUnloads} puts it
 * back in {@code pendingUnloads} and calls {@code scheduleUnload} for it anew.
 * A retry for a holder that is not the one in {@code pendingUnloads} at its
 * position is therefore skipped here. The first call from
 * {@code processUnloads} always passes, because the holder was put there on
 * the line before.
 *
 * <p>Off with {@code -Dkapok.chunk.dropStaleUnloads=false}, for measuring
 * against vanilla.
 */
public final class StaleUnloadFilter {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("kapok.chunk.dropStaleUnloads"));

    /** Retries skipped this session. Server thread only. */
    private static long dropped;

    private StaleUnloadFilter() {
    }

    /**
     * @param pending the holder {@code pendingUnloads} has at this position, or {@code null}
     * @param holder the holder the unload was scheduled for
     * @return {@code true} when the call has nothing to do and is skipped
     */
    public static boolean shouldDrop(Object pending, Object holder) {
        if (!ENABLED || pending == holder) {
            return false;
        }
        dropped++;
        return true;
    }

    /** Writes how many retries were skipped, and starts the count over for the next world. */
    public static void logCount() {
        long n = dropped;
        dropped = 0;
        if (n > 0) {
            Kapok.LOGGER.info("Kapok: skipped {} unload retr{} for chunks that had been loaded again",
                    n, n == 1 ? "y" : "ies");
        }
    }
}
