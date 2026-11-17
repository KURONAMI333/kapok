package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.chunk.ChunkPostProcessGate;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the send of a chunk whose post-processing was put off by
 * {@link LevelChunkPostProcessMixin}.
 *
 * <p>{@code ChunkMap.markChunkPendingToSend} is the one place every path that
 * puts a chunk on a player's send list goes through. A chunk that is still in
 * this level's queue has not been replayed yet, so sending it would give the
 * client the chunk as it stands before the replay: block entities the replay
 * promotes would be missing from the packet, and the block states the replay
 * writes arrive without a client update. {@link ChunkPostProcessGate#retry}
 * puts the chunk back on the send list once the replay is done.
 *
 * <p>The descriptor is written out because the name is overloaded: the other
 * {@code markChunkPendingToSend} takes a {@code ChunkPos}. The target is
 * static, so this handler is too.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSendHoldMixin {

    @Inject(
            method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void kapok$holdSendUntilPostProcess(
            ServerPlayer player, LevelChunk chunk, CallbackInfo ci) {
        Level level = chunk.getLevel();
        if (level instanceof ServerLevel serverLevel && ChunkPostProcessGate.holdsSend(serverLevel, chunk)) {
            ci.cancel();
        }
    }
}
