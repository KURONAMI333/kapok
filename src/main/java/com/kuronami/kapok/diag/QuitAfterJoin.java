package com.kuronami.kapok.diag;

import com.kuronami.kapok.Kapok;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Leaves the game a fixed time after joining a world, so that an unattended run can end
 * the way a player ending it would.
 *
 * <p>Exists for one reason. Proving that the game shuts down cleanly needs a launch that
 * starts, loads a world and then ends on its own: a run that is killed, or that ends in a
 * crash, proves nothing about the shutdown path. Nothing in the game does that.
 *
 * <p>Not a feature, and not reachable as one. Without
 * {@code -Dkapok.diag.quitAfterJoinSeconds=N} on the command line this class registers
 * no listeners at all, so an installed copy has no behaviour to speak of; the property is
 * something an acceptance run passes and a player has no reason to have heard of.
 *
 * <p>The shutdown goes through {@link Minecraft#stop()}, which is what closing the window
 * does: it clears the running flag, and the main loop then unwinds through
 * {@code destroy()} - saving the world, logging {@code Stopping!} and exiting with a zero
 * status. Calling it from a tick means it happens on the game thread, which is the one
 * shutdown path the game supports.
 */
public final class QuitAfterJoin {
    /** Seconds to stay in the world before leaving. Absent on every ordinary launch. */
    public static final String PROPERTY = "kapok.diag.quitAfterJoinSeconds";

    private static final int TICKS_PER_SECOND = 20;

    /** Counts up from the join; -1 while there is nothing to wait for. */
    private static int remainingTicks = -1;

    private static boolean leaving;

    private QuitAfterJoin() {
    }

    /**
     * Registers the hook when it has been asked for.
     *
     * <p>Called from the mod constructor on both sides. The listeners go on in
     * {@code FMLClientSetupEvent}, which is the client-only half of loading, so a
     * dedicated server never reaches the code that mentions a client class.
     */
    public static void install(IEventBus modEventBus) {
        if (seconds() < 0) {
            return;
        }
        modEventBus.addListener(QuitAfterJoin::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        int seconds = seconds();
        if (seconds < 0) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(QuitAfterJoin::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(QuitAfterJoin::onClientTick);
        Kapok.LOGGER.info("Kapok diagnostics: this launch will leave the game {} seconds"
                + " after joining a world ({})", seconds, PROPERTY);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (remainingTicks < 0 && !leaving) {
            remainingTicks = seconds() * TICKS_PER_SECOND;
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (remainingTicks < 0 || leaving) {
            return;
        }
        if (--remainingTicks > 0) {
            return;
        }
        // Once only. stop() returns immediately and the loop takes several more ticks to
        // notice, so without this the shutdown event would be posted again every tick.
        leaving = true;
        FrameIntervalDiagnostics.reportBeforeExit();
        Kapok.LOGGER.info("Kapok diagnostics: leaving the game now ({})", PROPERTY);
        Minecraft.getInstance().stop();
    }

    /** @return the configured seconds, or -1 when the property is absent or unusable */
    private static int seconds() {
        String value = System.getProperty(PROPERTY);
        if (value == null) {
            return -1;
        }
        try {
            int seconds = Integer.parseInt(value.trim());
            return seconds < 0 ? -1 : seconds;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
