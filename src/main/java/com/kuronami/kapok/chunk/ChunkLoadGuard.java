package com.kuronami.kapok.chunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Answers one question during server chunk generation: when the
 * {@code ChunkEvent.Load} for a chunk is being posted on the main thread, a
 * {@code ServerChunkCache.getChunk(..., requireChunk = false)} for some other
 * chunk that is not loaded yet must not park the main thread waiting for that
 * chunk's generation to finish. It answers {@code null} instead, which is
 * exactly what a {@code requireChunk = false} caller already tolerates.
 *
 * <p>The window (depth, plus the position of the chunk whose load event is
 * currently being posted) is a stack in a plain static field so nested load
 * events restore the outer position when the inner one closes. Only the server
 * main thread touches that stack: {@code ChunkStatusTasks.full} runs its
 * supplier, the synthetic method {@code lambda$full$2}, through
 * {@code WorldGenContext.mainThreadMailBox().tell(...)}, and every {@link #openWindow} /
 * {@link #closeWindow} / {@link #suspend} / {@link #resume} caller is reached
 * from {@code ServerChunkCache.getChunk}'s main-thread branch. A per-thread
 * {@link ThreadLocal} cost four map lookups per {@code getChunk} call on the
 * hottest path in the server, which is why it is not used here. The decision
 * itself is a pure function of primitives ({@link #shouldReturnNull}) so it can
 * be unit tested without a game.
 *
 * <p>Injection points (NeoForge 21.1.247, sources jar):
 *
 * <ul>
 * <li>Window open/close: {@code ChunkStatusTasks.full} posts
 *     {@code ChunkEvent.Load} inside the supplier passed to
 *     {@code CompletableFuture.supplyAsync} (sources
 *     {@code net/minecraft/world/level/chunk/status/ChunkStatusTasks.java},
 *     the post at line 215; compiled as the synthetic method
 *     {@code lambda$full$2}, where the {@code IEventBus.post} call sits at
 *     bytecode offset 157). The window wraps exactly that one call.
 * <li>Decision: {@code ServerChunkCache.getChunk}
 *     ({@code net/minecraft/server/level/ServerChunkCache.java} line 136). The
 *     mixin consults this guard at the head, and asks
 *     {@code getChunkNow} (same file, line 173) whether the chunk is already
 *     there.</li>
 * </ul>
 *
 * <p>Calls that ask for the chunk ({@code requireChunk = true}), calls off the
 * main thread, calls for the chunk whose event is being posted, and calls for
 * chunks that are already loaded all keep the vanilla behaviour.
 *
 * <p><b>Suspension.</b> A {@code requireChunk = true} call inside the window
 * still waits in {@code managedBlock}, and that loop runs other queued main
 * thread tasks. Those tasks are not part of the load listener, so the window
 * must not apply to them: {@link #suspend()} pushes a marker that hides the
 * window until {@link #resume()} pops it. A load event posted by one of those
 * tasks opens its own window on top of the marker as usual.
 *
 * <p><b>Off-main-thread requests.</b> Vanilla answers a {@code getChunk} from
 * another thread by running the whole call as a main thread task, so the main
 * thread parks in {@code managedBlock} until the chunk is generated while the
 * caller sits in {@code join}. {@link #OFF_THREAD_FUTURE} makes the mixin use
 * the future path vanilla already has in {@code ServerChunkCache.getChunkFuture}
 * instead: the main thread only schedules the generation and the calling
 * thread waits for it. Disable with {@code -Dkapok.chunk.offThreadFuture=false}.
 *
 * <p>A previous version also raised the ticket level of the remaining waits
 * (candidate 2). It was removed: a level 31 ticket does not just reorder the
 * queue, it also asks for ENTITY_TICKING on the chunk and FULL on the 5x5
 * around it, which schedules more load events, not fewer.
 *
 * <p><b>Map preview.</b> {@code MapItem.renderBiomePreviewMap} asks
 * {@code LevelReader.getBiome} 16384 times, and each of those becomes
 * {@code getChunk(..., ChunkStatus.BIOMES, false)} ({@code LevelReader.java}
 * line 59). A treasure map bought from a villager points at ground the server
 * has never loaded, so every one of those calls waits in {@code managedBlock}
 * for a chunk to generate: up to 1024 chunks for one map. Measured on ATM10
 * with a 6 GB heap, that ran into a 60 s watchdog with nine full GCs behind
 * it. While a map preview is being rendered, {@link #enterMapPreview()} makes
 * the mixin answer {@code null} for those lookups instead, which is the branch
 * vanilla already has for an absent chunk: the biome then comes from the noise
 * source, which is where a generated chunk takes it from as well.
 * {@link #NO_BIOME_LOAD} turns this off with
 * {@code -Dkapok.chunk.noBiomeLoad=false}.
 */
public final class ChunkLoadGuard {

    /** See the class comment, "Off-main-thread requests". */
    public static final boolean OFF_THREAD_FUTURE =
            !"false".equalsIgnoreCase(System.getProperty("kapok.chunk.offThreadFuture"));

    /** See the class comment, "Map preview". */
    public static final boolean NO_BIOME_LOAD =
            !"false".equalsIgnoreCase(System.getProperty("kapok.chunk.noBiomeLoad"));

    /**
     * Off with {@code -Dkapok.chunk.guard=false}, for measuring
     * the change against vanilla behaviour. Measurement-only: not a product
     * escape hatch, only an external A/N switch.
     */
    public static final boolean GUARD =
            !"false".equalsIgnoreCase(System.getProperty("kapok.chunk.guard"));

    /** Marker pushed by {@link #suspend()}; never equal to a boxed chunk position. */
    private static final Object SUSPENDED = new Object();

    /**
     * Windows and suspend markers, innermost last. Server main thread only; see
     * the class comment.
     */
    private static final Deque<Object> OPEN = new ArrayDeque<>();

    /**
     * Depth of {@code MapItem.renderBiomePreviewMap} on this thread.
     *
     * <p>Kept per thread because the method is not guaranteed to be entered from
     * the server main thread: vanilla calls it from
     * {@code VillagerTrades.getOffer} and {@code ExplorationMapFunction} on the
     * main thread, but a mod could call it from a worker. The thread-local read
     * is behind {@link #MAP_PREVIEW_TOTAL} so the idle path (the overwhelming
     * majority of {@code getChunk} calls) does not pay for it.
     */
    private static final ThreadLocal<int[]> MAP_PREVIEW = ThreadLocal.withInitial(() -> new int[1]);

    /** Sum of {@link #MAP_PREVIEW} depths across all threads; zero means nobody is previewing. */
    private static final AtomicInteger MAP_PREVIEW_TOTAL = new AtomicInteger();

    private ChunkLoadGuard() {
    }

    /** Opens a window for the chunk whose load event is about to be posted. */
    public static void openWindow(long eventChunkPos) {
        OPEN.push(eventChunkPos);
    }

    /**
     * Closes the innermost open window, restoring any outer one. Does nothing
     * when the top of the stack is not a window (an unbalanced suspend must not
     * make this pop the marker instead).
     */
    public static void closeWindow() {
        if (OPEN.peek() instanceof Long) {
            OPEN.pop();
        }
    }

    /** Hides the current window while a wait runs unrelated main thread tasks. */
    public static void suspend() {
        OPEN.push(SUSPENDED);
    }

    /** Undoes the innermost {@link #suspend()}. */
    public static void resume() {
        if (!OPEN.isEmpty() && OPEN.peek() == SUSPENDED) {
            OPEN.pop();
        }
    }

    /** @return whether a load-event window is currently open on this thread. */
    public static boolean inWindow() {
        return OPEN.peek() instanceof Long;
    }

    /** Chunk position packed as {@code (x & 0xffffffff) | (z & 0xffffffff) << 32}. */
    public static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    /**
     * @return true when the caller should answer {@code null} instead of
     *         waiting for the chunk to generate: a window is open, on the main
     *         thread, the chunk is not required, it is not the chunk whose
     *         event is being posted, and it is not loaded yet.
     */
    public static boolean shouldReturnNull(boolean onMainThread, boolean requireChunk, boolean present, int x, int z) {
        // The window stack is server-main-thread state, so the off-thread answer
        // is decided before it is read: no cross-thread access, and the caller
        // that only asks "is a window open" does not pay for the deque peek.
        if (!onMainThread || requireChunk || present) {
            return false;
        }
        Object top = OPEN.peek();
        if (!(top instanceof Long self)) {
            return false;
        }
        return pack(x, z) != self;
    }

    /** Opens a map preview window. See the class comment, "Map preview". */
    public static void enterMapPreview() {
        MAP_PREVIEW.get()[0]++;
        MAP_PREVIEW_TOTAL.incrementAndGet();
    }

    /** Closes the innermost map preview window. */
    public static void exitMapPreview() {
        int[] depth = MAP_PREVIEW.get();
        if (depth[0] > 0) {
            depth[0]--;
            MAP_PREVIEW_TOTAL.decrementAndGet();
        }
    }

    /** @return whether a map preview is being rendered on this thread. */
    public static boolean inMapPreview() {
        // One volatile read on the idle path instead of a ThreadLocal lookup.
        if (MAP_PREVIEW_TOTAL.get() == 0) {
            return false;
        }
        return MAP_PREVIEW.get()[0] > 0;
    }

    /**
     * @return true when a biome lookup made while rendering a map preview
     *         should answer {@code null} instead of waiting for the chunk: the
     *         caller does not require the chunk, it asks for the biome status
     *         only, and the chunk is not present yet. Callers combine this
     *         with {@link #NO_BIOME_LOAD} and {@link #inMapPreview()}.
     */
    public static boolean shouldReturnNullForMapPreview(boolean requireChunk, boolean statusIsBiomes, boolean present) {
        return !requireChunk && statusIsBiomes && !present;
    }

    /**
     * The result handling of vanilla {@code getChunk}: the chunk, or
     * {@code null} when it is absent and not required, or an
     * {@link IllegalStateException} when it is absent and required.
     */
    public static <T> T unwrapResult(T chunk, boolean requireChunk, Supplier<String> error) {
        if (chunk == null && requireChunk) {
            throw new IllegalStateException("Chunk not there when requested: " + error.get());
        }
        return chunk;
    }

    /** @return the number of entries (windows and suspend markers) on this thread's stack. */
    static int depthForTest() {
        return OPEN.size();
    }

    /** @return the all-thread preview depth behind the {@link #inMapPreview()} fast path. */
    static int mapPreviewTotalForTest() {
        return MAP_PREVIEW_TOTAL.get();
    }

    /** Test hook: clears any window state left over between test cases. */
    static void resetForTest() {
        resetForServerStop();
    }

    /**
     * Forgets this thread's window state and map-preview marker. Called when the
     * server stops and when it starts, so state left behind by a world cannot
     * leak into the next one now that the window stack is a static field rather
     * than per-thread.
     *
     * <p><b>Only this thread's preview depth is given back.</b> Another thread
     * may be inside {@code MapItem.renderBiomePreviewMap} at this moment; its
     * depth has to stay counted, otherwise its own
     * {@link #exitMapPreview()} would take the total below zero and the fast
     * path in {@link #inMapPreview()} would then skip the per-thread read for
     * every thread while that one is still previewing.
     */
    public static void resetForServerStop() {
        OPEN.clear();
        int[] depth = MAP_PREVIEW.get();
        if (depth[0] != 0) {
            MAP_PREVIEW_TOTAL.addAndGet(-depth[0]);
            depth[0] = 0;
        }
    }
}
