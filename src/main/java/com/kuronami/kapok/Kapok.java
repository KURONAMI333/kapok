package com.kuronami.kapok;

import com.kuronami.kapok.chunk.ChunkPostProcessLifecycle;
import com.kuronami.kapok.diag.FrameIntervalDiagnostics;
import com.kuronami.kapok.diag.QuitAfterJoin;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Kapok — entry point.
 *
 * <p>The engine hands out work assuming a fast machine. On a slow one that
 * assumption breaks, and the mitigations the engine already ships stop
 * firing precisely when they are needed. Kapok changes how the work is
 * handed out instead of trying to make any single step faster.
 *
 * <p>What it changes in the running game: chunk generation no longer parks the
 * server thread waiting on its own neighbours, and a treasure map reads
 * unloaded ground from the noise source instead of generating it. The startup
 * half of the product (reading mod classes once, and from the second launch
 * not at all) lives in the service layer of the same jar, see the
 * {@code core} subproject.
 *
 * <p>There is no config. The defaults are the product.
 *
 * <p>This class only holds identity/logging shared across features.
 */
@Mod(Kapok.MODID)
public final class Kapok {
    public static final String MODID = "kapok";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Kapok(IEventBus modEventBus, ModContainer modContainer) {
        // Registers nothing unless an acceptance run asked for it on the command line.
        QuitAfterJoin.install(modEventBus);
        FrameIntervalDiagnostics.install(modEventBus);
        // The chunk state is static, so it is reset on both server start and stop.
        ChunkPostProcessLifecycle.install();
    }
}
