package com.kuronami.kapok.mixin.map;

import com.kuronami.kapok.chunk.ChunkLoadGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Answers {@code null} for a map-preview biome lookup that would otherwise
 * park the server main thread in vanilla {@code managedBlock} generation.
 *
 * <p>What this does: while {@link ChunkLoadGuard#inMapPreview()} is true (see
 * {@code MapItemBiomePreviewMixin}), a {@code requireChunk = false} lookup of
 * the BIOMES status answers {@code null} when the chunk is not present yet.
 * Every other call passes straight through to the original method.
 *
 * <p>Why it must run whether or not c2me is installed: c2me does not own the
 * vanilla {@code managedBlock} wait that sorting a treasure map causes. On
 * ATM10 with a 6 GB heap and 4 players over 300 s, the guard off measured
 * mspt 193.0 with 17 stalls over 2 s (longest 23.1 s) against mspt 6.6 with
 * zero stalls with the guard on. Those packs are exactly the weak-PC packs
 * where c2me tends to be present, so losing the map-preview branch together
 * with the chunk-guard mixins hurts the case the guard helps most.
 *
 * <p>This mixin carries no {@code C2meMixinPlugin} gate (it is not listed in
 * {@code kapok-chunk.mixins.json}), so
 * {@code kapok-map.mixins.json} applies with c2me present. It is the only
 * chunk-related work Kapok keeps in that case.
 *
 * <p>The present check is kept in step with
 * {@code ServerChunkCacheGuardMixin#kapok$present} (a {@code @Shadow}
 * method cannot be shared across mixins, so it is duplicated here).
 *
 * <p>{@link ChunkLoadGuard#GUARD} is required as well, matching
 * {@code ServerChunkCacheGuardMixin}: the map branch sits inside its
 * {@code GUARD} check, so {@code -Dkapok.chunk.guard=false} has to turn it
 * off here too. Without that, the measurement switch would compare against a
 * half-vanilla server on exactly the packs this mixin serves.
 */
@Mixin(ServerChunkCache.class)
public abstract class MapPreviewChunkGuardMixin {

    @Shadow
    private ChunkHolder getVisibleChunkIfPresent(long chunkPos) {
        throw new AssertionError();
    }

    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess kapok$mapPreviewGetChunk(
            int x, int z, ChunkStatus status, boolean requireChunk, Operation<ChunkAccess> original) {
        if (!requireChunk && status == ChunkStatus.BIOMES && ChunkLoadGuard.GUARD
                && ChunkLoadGuard.NO_BIOME_LOAD && ChunkLoadGuard.inMapPreview()
                && ChunkLoadGuard.shouldReturnNullForMapPreview(
                        requireChunk, status == ChunkStatus.BIOMES, this.kapok$present(x, z, status))) {
            return null;
        }
        return original.call(x, z, status, requireChunk);
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
