package com.kuronami.kapok.mixin.chunk;

import com.kuronami.kapok.chunk.ChunkLoadGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Opens the {@link ChunkLoadGuard} window around the one place the server
 * posts {@link ChunkEvent.Load}.
 *
 * <h3>The injection point</h3>
 * <p>The post happens inside the supplier of
 * {@code CompletableFuture.supplyAsync} in {@code ChunkStatusTasks.full}
 * (sources {@code net/minecraft/world/level/chunk/status/ChunkStatusTasks.java},
 * line 215, inside the second {@code currentlyLoading} try/finally). Compiled
 * that supplier is the synthetic method {@code lambda$full$2}, whose body
 * holds exactly one {@code IEventBus.post} call (bytecode offset 157), so the
 * {@code @WrapOperation} below names the method and the instruction in full;
 * a renamed supplier or a second post makes the audit fail rather than
 * quietly missing the window.
 *
 * <h3>Why wrap the call instead of injecting at head/tail</h3>
 * <p>The window must cover the post itself and nothing before or after it:
 * statements around the post in the same try block
 * ({@code registerAllBlockEntitiesAfterLevelLoad},
 * {@code registerTickContainerInLevel}) run on the same thread with
 * {@code currentlyLoading} set, and their own {@code getChunk} calls are
 * legitimate waits. A head/tail pair on the supplier would also cover those.
 * Wrapping the single {@code post} leaves them untouched. The wrap only acts
 * on {@code ChunkEvent.Load} instances, so if NeoForge ever posts another
 * event from the same spot the window still opens only for load.
 *
 * <h3>Closing on failure</h3>
 * <p>The open/close pair is in try/finally inside the wrap, so a listener
 * that throws still closes the window before the exception continues into the
 * try/finally that clears {@code currentlyLoading}. A leaked window would
 * turn later {@code requireChunk = false} lookups into stray {@code null}s.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkLoadWindowMixin {

    @WrapOperation(
            method = "lambda$full$2(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/server/level/GenerationChunkHolder;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),
            require = 1,
            allow = 1)
    private static Event kapok$wrapLoadPost(IEventBus bus, Event event, Operation<Event> original) {
        if (!ChunkLoadGuard.GUARD) {
            return original.call(bus, event);
        }
        if (event instanceof ChunkEvent.Load load) {
            ChunkLoadGuard.openWindow(load.getChunk().getPos().toLong());
            try {
                return original.call(bus, event);
            } finally {
                ChunkLoadGuard.closeWindow();
            }
        }
        return original.call(bus, event);
    }
}
