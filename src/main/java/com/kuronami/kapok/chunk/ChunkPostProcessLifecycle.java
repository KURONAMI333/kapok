package com.kuronami.kapok.chunk;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Resets the per-world state of {@link ChunkPostProcessGate} and
 * {@link ChunkLoadGuard} when a server starts and when it stops.
 *
 * <p>Both are static fields, so whichever world wrote them last is what the next
 * one would start from. The stop event is not enough on its own: a stop that
 * never reached its event leaves the same state behind. Resetting at the start
 * covers that, and resetting at the stop also drops what the shutdown itself
 * touched. The start reset runs on {@code ServerAboutToStartEvent}, which fires before
 * {@code loadLevel} prepares the spawn chunks, so holds counted while preparing
 * them are kept.
 */
public final class ChunkPostProcessLifecycle {

    private ChunkPostProcessLifecycle() {
    }

    /** Registers the two resets on the game event bus; called once at mod load. */
    public static void install() {
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, event -> ChunkPostProcessGate.resetForWorld());
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ChunkPostProcessGate.resetForWorld());
    }
}
