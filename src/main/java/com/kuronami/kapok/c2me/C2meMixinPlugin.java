package com.kuronami.kapok.c2me;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Stands the chunk mixins down when c2me is installed.
 *
 * <p>c2me already owns chunk loading: it loads chunks off the main thread.
 * Kapok's chunk guard covers the same ground, and running both makes the
 * famous pack slower rather than faster. Measured on BMC5 (NeoForge 21.1.250,
 * 289 mods, c2me {@code 0.4.0-alpha.0.120+1.21.1}): with Kapok present mspt
 * was 9.66 against 5.42/5.83 without it, while without c2me the gap shrank
 * from +4.43 to +0.31. Turning {@code kapok.chunk.guard} off brought mspt to
 * 5.87, level with Kapok absent. Rather than duplicate c2me's loading, this
 * plugin applies no chunk mixin at all when c2me is present.
 *
 * <p>Nothing changes when c2me is not installed: the chunk mixins apply as
 * usual. The measurement switch {@code kapok.chunk.guard} is left as it is.
 *
 * <p>{@code LoadingModList} rather than {@code ModList}, because {@code ModList}
 * does not exist yet when mixin configs are read. Returning false from
 * {@link #shouldApplyMixin} is safe under {@code "required": true} because
 * mixin asks the plugin while reading a mixin's declared targets, before it
 * tries to resolve them.
 *
 * <p>If the mod list is somehow unavailable, the mixins stay armed, which is
 * the behaviour these packs had before this plugin existed.
 */
public final class C2meMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Kapok");

    private static final String C2ME = "c2me";

    private static boolean logged;

    private boolean armed = true;

    @Override
    public void onLoad(String mixinPackage) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null) {
            // Not a shape this was written for; saying so rather than deciding
            // quietly. The mixins stay armed, which is what these packs did
            // before this plugin existed.
            LOGGER.warn("c2me cannot be checked for yet (the mod list is not available);"
                    + " the chunk mixins stay applied.");
            return;
        }
        if (mods.getModFileById(C2ME) != null) {
            armed = false;
            if (!logged) {
                logged = true;
                LOGGER.info("c2me is present; Kapok leaves chunk loading to it,"
                        + " so the chunk mixins are not applied.");
            }
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return armed;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
