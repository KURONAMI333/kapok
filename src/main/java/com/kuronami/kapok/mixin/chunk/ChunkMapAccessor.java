package com.kuronami.kapok.mixin.chunk;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code ServerChunkCache.mainThreadProcessor} has a package-private type, so
 * the guard reaches the same executor through {@code ChunkMap}, which receives
 * that instance in its constructor ({@code ServerChunkCache.java} line 94 in
 * NeoForge 21.1.247) and stores it as a {@code BlockableEventLoop<Runnable>}.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> kapok$getMainThreadExecutor();

    /**
     * Puts a chunk on the send list of every player tracking its position.
     *
     * <p>This is the step vanilla runs right after {@code postProcessGeneration}
     * in {@code prepareTickingChunk}. Calling it again is what releases a send
     * that was held while the chunk waited in the queue.
     */
    @Invoker("onChunkReadyToSend")
    void kapok$onChunkReadyToSend(LevelChunk chunk);
}
