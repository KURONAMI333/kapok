package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.chunk.ChunkPostProcessGate;
import com.kuronami.kapok.chunk.ChunkPostProcessQueue;
import com.kuronami.kapok.chunk.ChunkPostProcessRule;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the post-processing that {@link LevelChunkPostProcessMixin} put off, at
 * the head of the level tick, and owns this level's queue.
 *
 * <p>The field lives here rather than in a static map so that the queue goes
 * away with the level.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPostProcessMixin implements ChunkPostProcessGate.Host {

    @Unique
    private ChunkPostProcessQueue<LevelChunk> kapok$postProcessQueue;

    @Override
    public ChunkPostProcessQueue<LevelChunk> kapok$postProcessQueue() {
        if (this.kapok$postProcessQueue == null) {
            this.kapok$postProcessQueue = new ChunkPostProcessQueue<>(
                    ChunkPostProcessRule.MAX_PENDING, ChunkPostProcessRule.MAX_AGE_TICKS);
        }
        return this.kapok$postProcessQueue;
    }

    @Override
    public ChunkPostProcessQueue<LevelChunk> kapok$postProcessQueueIfAny() {
        return this.kapok$postProcessQueue;
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void kapok$retryPostProcess(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ChunkPostProcessGate.retry((ServerLevel) (Object) this);
    }
}
