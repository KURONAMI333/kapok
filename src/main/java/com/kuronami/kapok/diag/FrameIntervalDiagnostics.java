package com.kuronami.kapok.diag;

import com.kuronami.kapok.Kapok;
import java.util.Locale;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in frame-start interval evidence for controlled before/after runs. */
public final class FrameIntervalDiagnostics {
    public static final String PROPERTY = "kapok.diag.frames";

    private static final long WARMUP_NANOS = 20_000_000_000L;
    private static final int CAPACITY = 120_000;

    private static FrameIntervalProbe probe;

    private FrameIntervalDiagnostics() {
    }

    /** Registers and allocates nothing unless the diagnostic property is true. */
    public static void install(IEventBus modEventBus) {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        modEventBus.addListener(FrameIntervalDiagnostics::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        probe = new FrameIntervalProbe(WARMUP_NANOS, CAPACITY);
        NeoForge.EVENT_BUS.addListener(FrameIntervalDiagnostics::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(FrameIntervalDiagnostics::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(FrameIntervalDiagnostics::onFrame);
        Kapok.LOGGER.info("Kapok frame interval diagnostic enabled: warmup=20 s, capacity={} ({})",
                CAPACITY, PROPERTY);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        probe.join(System.nanoTime());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reportOnce();
    }

    private static void onFrame(RenderFrameEvent.Pre event) {
        probe.frame(System.nanoTime());
    }

    /** Reports before the unattended acceptance hook asks Minecraft to stop. */
    public static void reportBeforeExit() {
        if (probe != null) {
            reportOnce();
        }
    }

    private static void reportOnce() {
        probe.finish().ifPresent(summary -> Kapok.LOGGER.info(
                "Kapok frame intervals: count={}, retained={}, overflow={}, p50_ms={}, p95_ms={},"
                        + " p99_ms={}, max_ms={}, total_interval_s={}"
                        + " (frame starts; includes VSync and foreground/background throttling; not render CPU time)",
                summary.count(),
                summary.retained(),
                summary.overflow(),
                millis(summary.p50Nanos()),
                millis(summary.p95Nanos()),
                millis(summary.p99Nanos()),
                millis(summary.maxNanos()),
                seconds(summary.totalNanos())));
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000_000.0);
    }
}
