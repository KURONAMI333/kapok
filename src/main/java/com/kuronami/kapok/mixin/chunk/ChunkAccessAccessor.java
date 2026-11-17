package com.kuronami.kapok.mixin.chunk;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The block entities a chunk has not promoted yet.
 *
 * <p>{@code pendingBlockEntities} is protected and has no public reader on
 * {@code ChunkAccess}: {@code getBlockEntityNbts} exists only on
 * {@code ProtoChunk}, and {@code getBlockEntitiesPos} mixes the pending
 * positions with the loaded ones. Asking the field directly is what makes
 * "nothing left to replay" an exact answer.
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {

    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> kapok$pendingBlockEntities();
}
