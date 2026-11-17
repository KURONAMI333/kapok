package com.kuronami.kapok.chunk;

import com.kuronami.kapok.Kapok;
import com.kuronami.kapok.mixin.chunk.ChunkAccessAccessor;
import com.kuronami.kapok.mixin.chunk.ChunkMapAccessor;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;

/**
 * Holds the chunks whose {@code postProcessGeneration} was put off, per level,
 * and runs them at the head of {@code ServerLevel.tick}.
 *
 * <p>{@link ChunkPostProcessRule} is the decision; this class is the game-side
 * state around it: a queue on each level, plus a marker of whether the thread is
 * currently inside a chunk generation wait. The predicate and the queue are
 * plain Java so the decision is tested without a game.
 *
 * <p><b>Wait marker.</b> {@link #enterWait()} / {@link #exitWait()} bracket the
 * main thread {@code ServerChunkCache.getChunk} body in
 * {@code ServerChunkCacheGuardMixin}. {@code managedBlock} inside that body runs
 * other queued main thread tasks, so a {@code prepareTickingChunk} reached from
 * there is inside a wait even though no stack frame of {@code getChunk} is
 * obvious at that point.
 *
 * <p><b>Queue state lives on the level.</b> {@link Host} is implemented by the
 * {@code ServerLevel} mixin's own field, so unloading a level takes its queue
 * with it; a static map keyed by level would keep every level ever loaded alive
 * through the chunks it holds.
 *
 * <p><b>The replay must not be deferred again, but what it reaches must be.</b>
 * {@link #retry} calls {@code postProcessGeneration} directly, and that call
 * passes through the same {@code HEAD} injection as every other one.
 * {@link #REPLAYING} names the chunk being replayed so that this one call is let
 * through even when the chunk was forced out by age with its neighbours still
 * missing (deferring it again would hold it forever). The marker is compared by
 * identity, so a <em>different</em> chunk reached while the replay waits is still
 * deferred — that is the chain this class exists to break.
 *
 * <p><b>A held chunk's send waits with it.</b> Vanilla runs
 * {@code postProcessGeneration} from {@code ChunkMap.prepareTickingChunk} and
 * then marks the chunk for sending inside the same call, so in vanilla the
 * post-processing is always finished before a client can see the chunk. Deferring
 * it would put the chunk on the send list first, and two things a chunk needs
 * before it is sent would then be missing from the packet:
 * <ul>
 * <li>{@code LevelChunk.getBlockEntities} only lists the block entities that the
 *     post-processing promoted, so a chest or a spawner placed while the chunk
 *     was generating would arrive as a plain block;</li>
 * <li>the block states the replay writes go through
 *     {@code setBlock(pos, state, 20)}, and flag 20 does not notify clients, so
 *     fences, stairs and the like would keep the shape the client was given.</li>
 * </ul>
 * The send is therefore held at {@code ChunkMap.markChunkPendingToSend} for as
 * long as the chunk is in the queue, and {@code ChunkMap.onChunkReadyToSend} is
 * called once more when the replay is done.
 *
 * <p><b>A held chunk can go away.</b> The queue keeps the chunk instance, not a
 * position. A chunk that unloads and later loads again is a new instance, and
 * running the old one would send its block changes through the level, which can
 * pull the chunk back in. {@link #isLive} is checked before every replay; a held
 * chunk that is no longer the level's chunk for its position is dropped.
 */
public final class ChunkPostProcessGate {

    /** Implemented by the {@code ServerLevel} mixin. */
    public interface Host {
        /** The level's queue, created when it is first asked for. */
        ChunkPostProcessQueue<LevelChunk> kapok$postProcessQueue();

        /**
         * The same queue, or {@code null} when nothing has been held on this
         * level yet. For the callers that only ask whether something is held.
         */
        ChunkPostProcessQueue<LevelChunk> kapok$postProcessQueueIfAny();
    }

    /**
     * Depth of nested main thread chunk waits. A plain field because every
     * reader and writer is the server main thread: {@link #enterWait()} /
     * {@link #exitWait()} bracket {@code getChunk}'s main-thread body in
     * {@code ServerChunkCacheGuardMixin} (the off-thread branch returns before
     * them), {@link #insideWait()} is called from {@link #shouldDefer} after
     * {@code server.isSameThread()} and from {@link #retry}, which runs at the
     * head of {@code ServerLevel.tick}. A {@link ThreadLocal} here meant a map
     * lookup on every {@code getChunk} call in the server.
     */
    private static int waitDepth;

    /** The chunk {@link #retry} or {@link #drain} is replaying right now, if any. */
    private static final ThreadLocal<LevelChunk> REPLAYING = new ThreadLocal<>();

    /** Time limit for one {@link #retry} batch; {@code -Dkapok.chunk.postProcessBudgetMs}. */
    private static final ChunkPostProcessBudget BUDGET = ChunkPostProcessBudget.fromProperty(System::nanoTime);

    /** Sends held back because the chunk was still in the queue. Server thread only. */
    private static long heldSends;

    /** Chunks put back on the send list after their replay. Server thread only. */
    private static long releasedSends;

    /** Releases that threw. Server thread only. */
    private static long failedReleases;

    /** Batch stops caused by {@link #BUDGET} rather than by the count limit. Server thread only. */
    private static long budgetStops;

    /**
     * Whether a failed send release has been logged for this world. The same
     * failure would otherwise be printed every time its chunk is replayed.
     */
    private static boolean warnedReleaseSend;

    private ChunkPostProcessGate() {
    }

    /** Marks the start of a main thread chunk generation wait. */
    public static void enterWait() {
        waitDepth++;
    }

    /** Marks the end of such a wait; unbalanced calls cannot go below zero. */
    public static void exitWait() {
        if (waitDepth > 0) {
            waitDepth--;
        }
    }

    public static boolean insideWait() {
        return waitDepth > 0;
    }

    /**
     * Called at the head of {@code LevelChunk.postProcessGeneration}.
     *
     * <p>A chunk with nothing to replay runs as is: vanilla's method is a no-op
     * there, and holding it would only delay its send.
     *
     * @return {@code true} when the caller must skip the method; the chunk is
     *         then in the level's queue and {@link #retry} runs it later
     */
    public static boolean shouldDefer(ServerLevel level, LevelChunk chunk) {
        if (!ChunkPostProcessRule.ENABLED) {
            return false;
        }
        if (chunk == REPLAYING.get()) {
            // This is the replay itself; answering "defer" would put it back.
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            // The queue is server-thread state, and vanilla only calls this from
            // the main thread. A mod calling it from a worker runs it as is.
            return false;
        }
        ChunkPostProcessQueue<LevelChunk> queue = ((Host) level).kapok$postProcessQueue();
        if (queue.isHeld(chunk)) {
            // The chunk cannot be asked to post-process twice by vanilla, but
            // if it is, the one already waiting is the one that will run.
            return true;
        }
        if (!queue.hasRoom()) {
            return false;
        }
        if (!ChunkPostProcessRule.shouldDefer(
                true,
                anythingToReplay(chunk),
                insideWait(),
                allNeighboursPresent(level, chunk.getPos()),
                false)) {
            return false;
        }
        queue.hold(chunk);
        return true;
    }

    /**
     * Whether vanilla's {@code postProcessGeneration} would do anything to this
     * chunk.
     *
     * <p>The three inputs are the three things that method walks: the scheduled
     * fluid and block updates, the block entities that were placed before the
     * chunk held them, and the pre-1.13 upgrade data.
     */
    private static boolean anythingToReplay(LevelChunk chunk) {
        UpgradeData upgradeData = chunk.getUpgradeData();
        if (upgradeData != null && !upgradeData.isEmpty()) {
            return true;
        }
        ShortList[] postProcessing = chunk.getPostProcessing();
        if (postProcessing != null) {
            for (ShortList positions : postProcessing) {
                if (positions != null && !positions.isEmpty()) {
                    return true;
                }
            }
        }
        return !((ChunkAccessAccessor) chunk).kapok$pendingBlockEntities().isEmpty();
    }

    /**
     * Called at the head of {@code ChunkMap.markChunkPendingToSend}, which is
     * where every path that puts a chunk on a player's send list meets.
     *
     * @return {@code true} when the send has to wait; the chunk is in this
     *         level's queue and its post-processing has not run yet
     */
    public static boolean holdsSend(ServerLevel level, LevelChunk chunk) {
        if (!ChunkPostProcessRule.ENABLED) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            // The queue is server thread state.
            return false;
        }
        ChunkPostProcessQueue<LevelChunk> queue = ((Host) level).kapok$postProcessQueueIfAny();
        if (queue == null || !queue.isHeld(chunk)) {
            return false;
        }
        heldSends++;
        return true;
    }

    /**
     * Called at the head of {@code ServerLevel.tick}: runs held chunks whose
     * neighbourhood is there now, plus anything that has waited longer than
     * {@link ChunkPostProcessRule#MAX_AGE_TICKS} ticks, oldest first.
     *
     * <p>This runs before {@code tickPending}, which is where that tick's
     * scheduled block and fluid ticks are processed, so the order "post-process
     * before the chunk's own scheduled ticks" still holds for the chunk.
     *
     * <p><b>Two limits per tick.</b>
     * {@link ChunkPostProcessRule#MAX_PER_TICK} bounds the count, and
     * {@link ChunkPostProcessBudget} bounds the time (5 ms by default). Both
     * apply to the chunks forced out by age as well: the age is a reason to run
     * a chunk even without its neighbours, not a reason to run it in this tick.
     * The batch stops only after a chunk has run, so one chunk always makes
     * progress and a queue cannot be frozen by the clock.
     *
     * <p><b>One scan per tick.</b> The queue is aged and scanned once, for at
     * most {@link ChunkPostProcessRule#MAX_PER_TICK} due chunks, instead of once
     * per chunk taken. Every due check is nine {@code getChunkNow} calls, so
     * rescanning from the head for each of the sixteen chunks made a full queue
     * cost 16 x 4096 x 9 lookups in one tick. The collected chunks are left in
     * the queue and taken one at a time as they run, so a batch that stops early
     * leaves the chunks it did not reach held, with their age and their send
     * hold, for the next tick.
     *
     * <p>A chunk that is no longer the level's chunk for its position still uses
     * up one of the {@link ChunkPostProcessRule#MAX_PER_TICK} places: it is taken
     * out of the queue (which is the work this tick had for it) and not replayed.
     *
     * <p>A replayed chunk is put back on the send list here if it is still the
     * chunk {@code ChunkMap} would send, so a send that
     * {@link #holdsSend} held goes out in this tick rather than the next one.
     */
    public static void retry(ServerLevel level) {
        ChunkPostProcessQueue<LevelChunk> queue = ((Host) level).kapok$postProcessQueue();
        if (queue.size() == 0) {
            return;
        }
        dropVanished(level, queue);
        if (queue.size() == 0 || insideWait()) {
            // Running a replay from inside a wait is the chain this exists to
            // avoid; it can wait for the next tick.
            return;
        }
        queue.ageAll();
        List<LevelChunk> due = queue.collectDue(
                (held, age) -> ChunkPostProcessRule.shouldRunNow(allNeighboursPresent(level, held.getPos()), age),
                ChunkPostProcessRule.MAX_PER_TICK);
        ChunkPostProcessBatch.Stop stop = ChunkPostProcessBatch.run(
                due, ChunkPostProcessRule.MAX_PER_TICK, BUDGET, chunk -> replay(level, queue, chunk));
        if (stop == ChunkPostProcessBatch.Stop.TIME_LIMIT) {
            budgetStops++;
        }
    }

    /**
     * Takes one chunk the scan collected out of the queue and replays it when it
     * is still the level's chunk for its position.
     */
    private static void replay(ServerLevel level, ChunkPostProcessQueue<LevelChunk> queue, LevelChunk chunk) {
        queue.take(chunk);
        if (isLive(level, chunk)) {
            run(chunk);
            releaseSend(level, chunk);
        }
    }

    /**
     * Puts a chunk whose replay just ran back on the send list it was held from.
     *
     * <p>The chunk has already left the queue, so {@link #holdsSend} lets this
     * call through. Nothing is done when {@code ChunkMap} has not reached its own
     * {@code onChunkReadyToSend} for this chunk yet ({@code getChunkToSend} is
     * {@code null} until the chunk's send-sync future is done): vanilla calls it
     * later, and by then the queue no longer holds the chunk.
     *
     * <p>Never throws. This runs from the level tick after the replay, so an
     * exception here would end the tick for a chunk whose blocks are already
     * correct. The cost of catching it: {@code onChunkReadyToSend} walks every
     * player, so the players after the one that threw do not get this chunk
     * until their own tracking marks it again (they move out of range and back).
     * The first failure in a world is logged with its stack trace, and every
     * failure is counted in the line {@link #logSendHoldCounts} writes.
     */
    private static void releaseSend(ServerLevel level, LevelChunk chunk) {
        try {
            ChunkMap map = level.getChunkSource().chunkMap;
            if (map.getChunkToSend(chunk.getPos().toLong()) != chunk) {
                return;
            }
            ((ChunkMapAccessor) map).kapok$onChunkReadyToSend(chunk);
            releasedSends++;
        } catch (RuntimeException e) {
            failedReleases++;
            if (!warnedReleaseSend) {
                warnedReleaseSend = true;
                Kapok.LOGGER.warn("Kapok: could not put a replayed chunk back on the send list;"
                        + " players who have not received it get it when they next come into range;"
                        + " later failures in this world are counted, not logged", e);
            }
        }
    }

    /**
     * Writes the one line that says whether the send hold did anything in this
     * session. Changing no state: {@link #resetForWorld()} is what starts the
     * counts over, and it runs from the server start and stop events rather than
     * from here.
     *
     * <p>The two numbers count different things and can differ either way. A
     * hold is counted per player whose send was dropped; a release per chunk put
     * back on the send list. A chunk that unloaded while it waited leaves holds
     * with no release. A chunk deferred while no player was tracking it has no
     * hold, but is still released after its replay, and the release sends it to
     * whoever tracks it by then.
     *
     * <p>The batch count is how many ticks ran out of
     * {@link ChunkPostProcessBudget} with chunks still due, i.e. how often the
     * time limit moved work to the next tick. It is diagnostics for the
     * measurement, not a player-facing number.
     */
    public static void logSendHoldCounts() {
        long held = heldSends;
        long released = releasedSends;
        long failed = failedReleases;
        long stopped = budgetStops;
        if (failed > 0) {
            Kapok.LOGGER.warn("Kapok: held {} chunk send(s) until post-processing, released {},"
                    + " failed to release {}, time limit stopped {} tick(s)", held, released, failed, stopped);
        } else if (held > 0 || released > 0 || stopped > 0) {
            Kapok.LOGGER.info("Kapok: held {} chunk send(s) until post-processing, released {},"
                    + " time limit stopped {} tick(s)", held, released, stopped);
        }
    }

    /**
     * Forgets everything this class and {@link ChunkLoadGuard} keep across a
     * world: the counters, the once-per-world release warning, the wait depth
     * and the main-thread window and map-preview state.
     *
     * <p>Called when a server starts and when it stops, from
     * {@link ChunkPostProcessLifecycle}, so a world never inherits another one's
     * numbers or narrows its {@code getChunk} answers. The start call matters on
     * its own: a stop that did not run leaves the state behind exactly the same
     * way.
     */
    public static void resetForWorld() {
        heldSends = 0;
        releasedSends = 0;
        failedReleases = 0;
        budgetStops = 0;
        warnedReleaseSend = false;
        waitDepth = 0;
        ChunkLoadGuard.resetForServerStop();
    }

    /**
     * Runs what is still held and can still run, just before the server saves
     * its chunks.
     *
     * <p>By the time this is reached there is usually nothing left. The shutdown
     * loop in {@code MinecraftServer.stopServer} runs before
     * {@code saveAllChunks}: while {@code ChunkMap.hasWork()} is true it drops
     * the level's closing tickets each round
     * ({@code ServerChunkCache.removeTicketsOnClosing}) and ticks the chunk
     * source ({@code ServerChunkCache.tick}, with chunk ticking off). The level
     * itself is not ticked, so {@link #retry} does not run there, and neither
     * does the send pass; the chunks that would have been replayed unload
     * instead. A chunk still here is one this loop did not reach; it is written
     * to disk with its pending block entities and its {@code PostProcessing}
     * data as they are, and vanilla's own path deals with them the next time the
     * chunk is loaded. The replay below only covers the remainder because it is
     * what promotes pending block entities, so running it is closer to what
     * vanilla saves.
     *
     * <p><b>Only with the 3x3 present, and age does not force it.</b> Replaying
     * a chunk whose neighbour is missing makes {@code postProcessGeneration} ask
     * for that neighbour with {@code requireChunk = true}, i.e. start chunk
     * generation. During shutdown that generation cannot finish:
     * {@code ChunkMap.processUnloads} is already taking the chunk apart, the
     * generation refcount never reaches zero, and its unload task re-schedules
     * itself on the main thread forever. Measured 2026-11-05 on ATM10:
     * {@code stopServer} never returned (8+ minutes of spinning in
     * {@code processUnloads}) and one worker was waiting on
     * {@code getChunk(-194, -6, structure_references)}. The neighbourhood is
     * vanilla's own precondition for the call, so a chunk without it is dropped
     * here instead of being forced. That is why this uses
     * {@link ChunkPostProcessQueue#takeDueIgnoringAge}: the tick loop's age limit
     * exists to stop a chunk waiting forever, and here waiting is the safe
     * outcome.
     *
     * <p>Nothing is sent from here: the shutdown loop has already dropped the
     * players' chunk tracking, so there is no send left to put back.
     */
    public static void drain(ServerLevel level) {
        ChunkPostProcessQueue<LevelChunk> queue = ((Host) level).kapok$postProcessQueue();
        if (queue.size() == 0) {
            return;
        }
        dropVanished(level, queue);
        List<LevelChunk> due = queue.takeDueIgnoringAge(
                (chunk, age) -> allNeighboursPresent(level, chunk.getPos()), Integer.MAX_VALUE);
        for (LevelChunk chunk : due) {
            if (isLive(level, chunk)) {
                run(chunk);
            }
        }
        if (!due.isEmpty()) {
            Kapok.LOGGER.info("Kapok: replayed {} deferred chunk(s) before saving", due.size());
        }
        if (queue.size() > 0) {
            Kapok.LOGGER.info(
                    "Kapok: {} deferred chunk(s) left unreplayed at shutdown; their neighbours were not loaded",
                    queue.size());
        }
    }

    private static void dropVanished(ServerLevel level, ChunkPostProcessQueue<LevelChunk> queue) {
        int dropped = queue.purge(chunk -> isLive(level, chunk));
        if (dropped > 0) {
            Kapok.LOGGER.debug("Kapok: dropped {} deferred chunk(s) that unloaded before post-processing", dropped);
        }
    }

    private static void run(LevelChunk chunk) {
        LevelChunk previous = REPLAYING.get();
        REPLAYING.set(chunk);
        try {
            chunk.postProcessGeneration();
        } catch (RuntimeException e) {
            // Vanilla would have let this end the tick. Holding the exception
            // here keeps one chunk from stopping the server; the chunk keeps
            // its generated blocks, it only misses the replay.
            Kapok.LOGGER.error("Kapok: deferred post-processing failed for chunk {}", chunk.getPos(), e);
        } finally {
            if (previous == null) {
                REPLAYING.remove();
            } else {
                REPLAYING.set(previous);
            }
        }
    }

    /** Whether this instance is still the level's chunk for its position. */
    public static boolean isLive(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return level.getChunkSource().getChunkNow(pos.x, pos.z) == chunk;
    }

    /**
     * Whether the chunk and its 8 neighbours are all there at FULL.
     *
     * <p>That is the same 3x3 FULL range vanilla asks for before it schedules
     * {@code postProcessGeneration} ({@code getChunkRangeFuture(holder, 1,
     * FULL)}), so a call with a gap means the precondition was lost in the
     * meantime. {@code getChunkNow} neither asks for nor waits on generation.
     */
    public static boolean allNeighboursPresent(ServerLevel level, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(pos.x + dx, pos.z + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
