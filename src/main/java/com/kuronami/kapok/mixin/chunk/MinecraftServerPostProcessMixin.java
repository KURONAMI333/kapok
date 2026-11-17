package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.chunk.ChunkPostProcessGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the post-processing that is still held when the server stops, just
 * before the save.
 *
 * <p>{@code ServerLevel.tick} is not called during the shutdown loop, so the
 * per-tick retry in {@link ServerLevelPostProcessMixin} cannot drain the queue
 * there. The loop that waits on {@code ChunkMap.hasWork()} runs before
 * {@code saveAllChunks} with the players already removed; each round it drops
 * the level's closing tickets and ticks the chunk source
 * ({@code ServerChunkCache.tick}, with chunk ticking off), so the held chunks
 * unload instead of being replayed, and almost nothing is left by the time this
 * runs.
 *
 * <p>What is left is still replayed, because the replay is what promotes the
 * pending block entities and applies the block updates collected during
 * generation. A chunk that cannot be replayed here is saved as it is, with its
 * {@code PostProcessing} flag and its pending block entities; vanilla's own
 * load path handles that data the next time the chunk is loaded.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPostProcessMixin {

    @Inject(
            method = "stopServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"))
    private void kapok$drainPostProcessBeforeSave(CallbackInfo ci) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        for (ServerLevel level : self.getAllLevels()) {
            if (level == null) {
                continue;
            }
            try {
                ChunkPostProcessGate.drain(level);
            } catch (RuntimeException e) {
                // Shutdown must not fail because one chunk's replay did.
                com.kuronami.kapok.Kapok.LOGGER.error(
                        "Kapok: draining deferred post-processing failed for {}", level.dimension().location(), e);
            }
        }
        ChunkPostProcessGate.logSendHoldCounts();
        com.kuronami.kapok.chunk.StaleUnloadFilter.logCount();
    }
}
