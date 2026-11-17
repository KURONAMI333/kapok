package com.kuronami.kapok.chunk;

/**
 * When {@code LevelChunk.postProcessGeneration} may run, as a pure function of
 * primitives so it can be unit tested without a game.
 *
 * <h3>What the problem is</h3>
 * <p>Vanilla runs {@code postProcessGeneration} on the server main thread as
 * soon as a chunk becomes block-ticking ({@code ChunkMap.prepareTickingChunk}).
 * The method replays a block tick for every position that was placed while the
 * chunk was still generating; for fluids that is
 * {@code FlowingFluid.tick}, which reads {@code Level.getBlockState} of the six
 * neighbours. Those reads go through
 * {@code ServerChunkCache.getChunk(..., requireChunk = true)} and, when the
 * neighbour chunk is not there at FULL yet, park the main thread in
 * {@code managedBlock}.
 *
 * <p>That parking is not a short wait. {@code managedBlock} keeps running the
 * main thread task queue while it waits, so it runs the next chunk's
 * {@code prepareTickingChunk}, whose {@code postProcessGeneration} asks for its
 * own neighbours and parks again. On ATM10 (NeoForge 21.1.265) this nested
 * chain held one tick-and-a-half window for 36-50 s; the JFR samples of that
 * window are 66 % inside {@code postProcessGeneration}. Vanilla's own
 * precondition for the method is a 3x3 range at FULL
 * ({@code getChunkRangeFuture(holder, 1, FULL)}), so the method is meant to run
 * with every neighbour present.
 *
 * <h3>What the rule says</h3>
 * <p>{@code postProcessGeneration} is put off to a later tick when it has
 * something to do and either
 * <ul>
 * <li>it would run inside a main thread chunk wait (that is what turns one
 *     wait into a chain), or</li>
 * <li>the 3x3 neighbourhood is not present at FULL (that is what makes the
 *     fluid tick park in the first place).</li>
 * </ul>
 * <p>A chunk with nothing to replay is let through. Vanilla's method is a no-op
 * there: no scheduled fluid or block update, no pending block entity, no upgrade
 * data. Most chunks read from disk are in that state, and holding them would
 * delay their send by a tick for no work at all.
 * <p>It then runs in {@code ServerLevel.tick}'s head, before that tick
 * processes scheduled block and fluid ticks, which keeps vanilla's order
 * (post-processing before the chunk's scheduled ticks) while moving the work
 * out of the wait. A chunk that never gets its neighbours within
 * {@link #MAX_AGE_TICKS} ticks is processed anyway, so nothing is held forever.
 *
 * <p>Nothing here is specific to a mod: the rule only looks at the chunk
 * system's own state.
 */
public final class ChunkPostProcessRule {

    /**
     * Off with {@code -Dkapok.chunk.deferPostProcess=false}, for measuring
     * the change against vanilla behaviour.
     */
    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("kapok.chunk.deferPostProcess"));

    /** Upper bound on held chunks, so a pathological world cannot grow without limit. */
    public static final int MAX_PENDING = 4096;

    /** How many held chunks are retried per level tick, to keep the tick bounded. */
    public static final int MAX_PER_TICK = 16;

    /**
     * After this many ticks a held chunk is processed regardless of its
     * neighbours. Bounded so a chunk can never lose its post-processing (and
     * with it its pending block entities) by waiting forever.
     */
    public static final int MAX_AGE_TICKS = 40;

    private ChunkPostProcessRule() {
    }

    /**
     * @param enabled              {@link #ENABLED}
     * @param anythingToReplay     the chunk has post-processing work: a
     *                             scheduled fluid or block update, a pending
     *                             block entity, or upgrade data
     * @param insideChunkWait      the main thread is inside a chunk generation
     *                             wait right now
     * @param allNeighboursPresent the chunk and its 8 neighbours are present at
     *                             FULL
     * @param queueFull            no room for another held chunk
     * @return whether this call should be put off instead of running
     */
    public static boolean shouldDefer(
            boolean enabled,
            boolean anythingToReplay,
            boolean insideChunkWait,
            boolean allNeighboursPresent,
            boolean queueFull) {
        if (!enabled || !anythingToReplay || queueFull) {
            return false;
        }
        return insideChunkWait || !allNeighboursPresent;
    }

    /**
     * @param allNeighboursPresent the chunk and its 8 neighbours are present at
     *                             FULL
     * @param ageTicks             ticks this chunk has been held
     * @return whether the held call should run now
     */
    public static boolean shouldRunNow(boolean allNeighboursPresent, int ageTicks) {
        return allNeighboursPresent || ageTicks >= MAX_AGE_TICKS;
    }
}
