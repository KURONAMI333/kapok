package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.chunk.StaleUnloadFilter;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips an unload retry for a chunk that has been loaded again. See
 * {@link StaleUnloadFilter} for why vanilla spins here and why the skipped
 * calls had nothing to do.
 *
 * <p>The injection is on {@code scheduleUnload} itself rather than on the
 * lambda that retries it: the method name is stable across NeoForge builds,
 * the lambda's synthetic name is not, and the method has exactly two callers
 * ({@code processUnloads} and that lambda).
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapStaleUnloadMixin {

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Inject(method = "scheduleUnload", at = @At("HEAD"), cancellable = true)
    private void kapok$skipStaleUnload(long chunkPos, ChunkHolder holder, CallbackInfo ci) {
        if (StaleUnloadFilter.shouldDrop(this.pendingUnloads.get(chunkPos), holder)) {
            ci.cancel();
        }
    }
}
