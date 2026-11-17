package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.chunk.ChunkPostProcessGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts off {@code LevelChunk.postProcessGeneration} when it is about to run
 * inside a main thread chunk wait, or before the chunk's 3x3 neighbourhood is
 * there at FULL. See {@link ChunkPostProcessGate} and
 * {@link com.kuronami.kapok.chunk.ChunkPostProcessRule}.
 *
 * <p>One injected method, no dispatch: whether to skip is decided before
 * anything in the body runs, and the body is replayed unchanged later by
 * {@link ServerLevelPostProcessMixin}.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkPostProcessMixin {

    @Inject(method = "postProcessGeneration", at = @At("HEAD"), cancellable = true)
    private void kapok$deferPostProcessGeneration(CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        Level level = self.getLevel();
        if (level instanceof ServerLevel serverLevel && ChunkPostProcessGate.shouldDefer(serverLevel, self)) {
            ci.cancel();
        }
    }
}
