package com.kuronami.kapok.mixin.map;

import com.kuronami.kapok.chunk.ChunkLoadGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks the one call tree that asks the server for the biome of ground it has
 * never loaded: {@code MapItem.renderBiomePreviewMap}
 * ({@code net/minecraft/world/item/MapItem.java} line 213 in NeoForge
 * 21.1.247). Its 128x128 loop calls {@code LevelReader.getBiome}, and each call
 * falls through to {@code getChunk(..., ChunkStatus.BIOMES, false)}, which in
 * vanilla <em>waits</em> for that chunk to generate. A treasure map from a
 * villager therefore generates up to 1024 chunks on the server thread for one
 * item.
 *
 * <p>See {@link ChunkLoadGuard} ("Map preview") for what the guard does and how
 * it can be turned off. The window is opened around the whole method, so the
 * caller stays untouched: {@code VillagerTrades} and
 * {@code ExplorationMapFunction} both call it from the server thread.
 */
@Mixin(MapItem.class)
public abstract class MapItemBiomePreviewMixin {

    @WrapMethod(method = "renderBiomePreviewMap")
    private static void kapok$markMapPreview(
            ServerLevel level, ItemStack stack, Operation<Void> original) {
        ChunkLoadGuard.enterMapPreview();
        try {
            original.call(level, stack);
        } finally {
            ChunkLoadGuard.exitMapPreview();
        }
    }
}
