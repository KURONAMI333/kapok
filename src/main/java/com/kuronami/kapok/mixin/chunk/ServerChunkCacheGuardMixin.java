package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.Kapok;
import com.kuronami.kapok.chunk.ChunkLoadGuard;
import com.kuronami.kapok.chunk.ChunkPostProcessGate;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Keeps {@code ServerChunkCache.getChunk} from parking the server main thread
 * on chunk generation in two situations.
 *
 * <h3>The injection point</h3>
 * <p>One {@code @WrapMethod} around
 * {@code getChunk(int, int, ChunkStatus, boolean)}
 * ({@code net/minecraft/server/level/ServerChunkCache.java} line 136 in
 * NeoForge 21.1.247). Wrapping the whole method does not depend on how many
 * return instructions the body has: vanilla has four, Lithium's
 * {@code @Overwrite} has three, and an earlier RETURN injector with a fixed
 * {@code allow} crashed on one or the other. The wrap also gives a real
 * try/finally, so an exception from the body (for example "Chunk not there
 * when requested") cannot leave state behind.
 *
 * <h3>1. Inside a ChunkEvent.Load window (main thread)</h3>
 * <p>{@link ChunkLoadGuard#shouldReturnNull} answers {@code null} for a
 * {@code requireChunk = false} lookup of another chunk that is not present at
 * the requested status. "Present" is {@code getChunkNow} (FULL, or the chunk
 * currently loading) or the holder already having a chunk at the requested
 * status, so a STRUCTURE_STARTS lookup of an existing proto chunk still gets
 * its chunk. Every call that does reach the body runs with the window
 * suspended, because the body may wait in {@code managedBlock}, which runs
 * unrelated main thread tasks that must not see the listener's window.
 *
 * <h3>2. Calls from other threads</h3>
 * <p>Vanilla runs the whole call as a main thread task and joins it, so the
 * main thread waits in {@code managedBlock} until the chunk is generated
 * (observed: 44 s stalls on ATM10, worldgen workers and structure locators
 * asking a {@code ServerLevel} for chunks). With
 * {@link ChunkLoadGuard#OFF_THREAD_FUTURE} the wrap instead schedules the
 * generation on the main thread and lets the calling thread wait for the
 * future, while the main thread keeps ticking. Two things vanilla got for free
 * from the parked main thread have to be supplied here:
 * <ul>
 * <li><b>The ticket must outlive the wait.</b> {@code getChunkFutureMainThread}
 *     only adds {@code TicketType.UNKNOWN}, which expires after one tick. A
 *     ticking main thread would drop it before generation finishes and the
 *     future would complete as "Unloaded chunk". A {@code requireChunk} wait
 *     therefore holds {@link #KAPOK_OFF_THREAD_WAIT} (no timeout, unique key
 *     per wait) at the same level until the future completes, and the ticket is
 *     removed on the main thread afterwards. {@code requireChunk = false} adds
 *     no ticket, like vanilla.</li>
 * <li><b>The Neo deadlock bypass.</b> Vanilla's body returns
 *     {@code holder.currentlyLoading} when the chunk is in the middle of its own
 *     load event (line 154). Without it, a load listener that waits on the main
 *     thread for chunk Y while Y's worker asks for the loading chunk X would
 *     wait on each other. The scheduling step on the main thread checks
 *     {@code currentlyLoading} first and answers with it.</li>
 * </ul>
 * <p>The result handling matches the vanilla body (null when absent,
 * IllegalStateException when required); the main thread's four-entry lookup
 * cache is neither read nor written for these calls. Because the main thread
 * keeps ticking, a {@code requireChunk = false} wait (no ticket of its own) can
 * see its chunk's other tickets go away mid-generation and get {@code null}
 * more often than in vanilla; such callers already accept {@code null}. A wait longer than
 * {@link #KAPOK_SLOW_WAIT_SECONDS} logs one WARN with the waiting thread's
 * stack (at most one such WARN per that many seconds) and then keeps waiting.
 *
 * <h3>3. Marking the wait</h3>
 * <p>The main thread paths are bracketed with
 * {@link ChunkPostProcessGate#enterWait()} and
 * {@link ChunkPostProcessGate#exitWait()}. A wait runs the main thread task
 * queue from inside {@code managedBlock}, so a chunk's post-processing can be
 * reached from there without any {@code getChunk} frame above it; the marker is
 * what lets it know it is inside a wait.
 *
 * <h3>4. Rendering a map preview</h3>
 * <p>While {@link ChunkLoadGuard#inMapPreview()} is true (see
 * {@code MapItemBiomePreviewMixin}), a {@code requireChunk = false} lookup of
 * the BIOMES status answers {@code null} when the chunk is not present yet,
 * even outside a load-event window. The caller ({@code LevelReader.getNoiseBiome})
 * then falls back to the noise source, which is where a generated chunk takes
 * its biome from anyway. This is what keeps one treasure map from generating
 * up to 1024 chunks on the main thread.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheGuardMixin {

    /** Holds an off-thread {@code requireChunk} wait's chunk until the wait ends. */
    @Unique
    private static final TicketType<Long> KAPOK_OFF_THREAD_WAIT =
            TicketType.create("kapok_offthread_wait", Long::compareTo);

    @Unique
    private static final AtomicLong KAPOK_WAIT_IDS = new AtomicLong();

    @Unique
    private static final long KAPOK_SLOW_WAIT_SECONDS = 30L;

    /** Throttles the slow-wait WARN to one per {@link #KAPOK_SLOW_WAIT_SECONDS} across all threads. */
    @Unique
    private static final AtomicLong KAPOK_LAST_SLOW_WARN = new AtomicLong(System.nanoTime() - TimeUnit.HOURS.toNanos(1));

    @Shadow
    @Final
    Thread mainThread;

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    private ChunkHolder getVisibleChunkIfPresent(long chunkPos) {
        throw new AssertionError();
    }

    @Shadow
    private CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(
            int x, int z, ChunkStatus status, boolean requireChunk) {
        throw new AssertionError();
    }

    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess kapok$guardGetChunk(
            int x, int z, ChunkStatus status, boolean requireChunk, Operation<ChunkAccess> original) {
        if (!ChunkLoadGuard.GUARD) {
            return original.call(x, z, status, requireChunk);
        }
        if (Thread.currentThread() != this.mainThread) {
            if (!ChunkLoadGuard.OFF_THREAD_FUTURE) {
                return original.call(x, z, status, requireChunk);
            }
            CompletableFuture<ChunkResult<ChunkAccess>> future = CompletableFuture
                    .supplyAsync(() -> this.kapok$scheduleOffThread(x, z, status, requireChunk), this.kapok$mainExecutor())
                    .thenCompose(Function.identity());
            ChunkResult<ChunkAccess> result = kapok$await(future, x, z, status);
            return ChunkLoadGuard.unwrapResult(result.orElse(null), requireChunk, result::getError);
        }
        if (ChunkLoadGuard.NO_BIOME_LOAD && !requireChunk && status == ChunkStatus.BIOMES
                && ChunkLoadGuard.inMapPreview()
                && ChunkLoadGuard.shouldReturnNullForMapPreview(
                        requireChunk, status == ChunkStatus.BIOMES, this.kapok$present(x, z, status))) {
            return null;
        }
        ChunkPostProcessGate.enterWait();
        try {
            if (!ChunkLoadGuard.inWindow()) {
                return original.call(x, z, status, requireChunk);
            }
            if (!requireChunk && ChunkLoadGuard.shouldReturnNull(true, false, this.kapok$present(x, z, status), x, z)) {
                return null;
            }
            ChunkLoadGuard.suspend();
            try {
                return original.call(x, z, status, requireChunk);
            } finally {
                ChunkLoadGuard.resume();
            }
        } finally {
            ChunkPostProcessGate.exitWait();
        }
    }

    /** Runs on the main thread. See the class comment, "Calls from other threads". */
    @Unique
    private CompletableFuture<ChunkResult<ChunkAccess>> kapok$scheduleOffThread(
            int x, int z, ChunkStatus status, boolean requireChunk) {
        long pos = ChunkPos.asLong(x, z);
        ChunkHolder holder = this.getVisibleChunkIfPresent(pos);
        if (holder != null && holder.currentlyLoading != null) {
            return CompletableFuture.completedFuture(ChunkResult.of(holder.currentlyLoading));
        }
        if (!requireChunk) {
            return this.getChunkFutureMainThread(x, z, status, false);
        }
        ChunkPos chunkPos = new ChunkPos(x, z);
        int level = ChunkLevel.byStatus(status);
        Long key = KAPOK_WAIT_IDS.incrementAndGet();
        this.distanceManager.addTicket(KAPOK_OFF_THREAD_WAIT, chunkPos, level, key);
        CompletableFuture<ChunkResult<ChunkAccess>> generation;
        try {
            generation = this.getChunkFutureMainThread(x, z, status, true);
        } catch (RuntimeException | Error e) {
            this.distanceManager.removeTicket(KAPOK_OFF_THREAD_WAIT, chunkPos, level, key);
            throw e;
        }
        // Hand over to a fresh UNKNOWN ticket before dropping ours, so the caller
        // gets the same one-tick grace vanilla gives it (there the main thread
        // was parked, so UNKNOWN had not expired yet when the caller resumed).
        generation.whenCompleteAsync((result, error) -> {
            this.distanceManager.addTicket(TicketType.UNKNOWN, chunkPos, level, chunkPos);
            this.distanceManager.removeTicket(KAPOK_OFF_THREAD_WAIT, chunkPos, level, key);
        }, this.kapok$mainExecutor());
        return generation;
    }

    /** The same instance as {@code ServerChunkCache.mainThreadProcessor}; see {@link ChunkMapAccessor}. */
    @Unique
    private BlockableEventLoop<Runnable> kapok$mainExecutor() {
        return ((ChunkMapAccessor) this.chunkMap).kapok$getMainThreadExecutor();
    }

    @Unique
    private static ChunkResult<ChunkAccess> kapok$await(
            CompletableFuture<ChunkResult<ChunkAccess>> future, int x, int z, ChunkStatus status) {
        try {
            return future.get(KAPOK_SLOW_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            long now = System.nanoTime();
            long last = KAPOK_LAST_SLOW_WARN.get();
            if (now - last < TimeUnit.SECONDS.toNanos(KAPOK_SLOW_WAIT_SECONDS) || !KAPOK_LAST_SLOW_WARN.compareAndSet(last, now)) {
                return future.join();
            }
            Kapok.LOGGER.warn("Kapok: off-thread getChunk({}, {}, {}) still waiting after {} s",
                    x, z, status, KAPOK_SLOW_WAIT_SECONDS, new Throwable("waiting thread " + Thread.currentThread().getName()));
            return future.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return future.join();
        } catch (ExecutionException e) {
            return future.join();
        }
    }

    @Unique
    private boolean kapok$present(int x, int z, ChunkStatus status) {
        if (((ServerChunkCache) (Object) this).getChunkNow(x, z) != null) {
            return true;
        }
        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        return holder != null && holder.getChunkIfPresent(status) != null;
    }
}
