package com.kuronami.kapokcore;

/**
 * Which branch of {@code JarModsDotTomlModFileReader#createModFile} a jar falls into.
 *
 * <p>Extracted so the branch <em>order</em> can be pinned by a test. The order is the
 * subtle part: the built-in reader resolves {@code FMLModType} from the manifest
 * <em>before</em> it looks for a {@code neoforge.mods.toml}, so a jar that declares a
 * type the loader does not know is rejected even when it also carries a mods.toml.
 * Getting that backwards would make this jar quietly swallow an error the loader raises.
 */
public enum ClaimPlan {
    /**
     * The manifest names an {@code FMLModType} that does not exist. We hand the jar back
     * so the built-in reader can raise the properly attributed error against it.
     */
    DECLINE_UNKNOWN_TYPE,
    /** Has a {@code neoforge.mods.toml}: a normal mod. */
    CLAIM_WITH_MODS_TOML,
    /** No mods.toml, but a valid {@code FMLModType}: a library or game library. */
    CLAIM_AS_MANIFEST_MOD,
    /** Neither. The built-in readers return null here too, which is what keeps
     * {@code NestedLibraryModReader} and the "no reader felt responsible" path working. */
    DECLINE_NOT_A_MOD;

    /**
     * @param declaredType the raw {@code FMLModType} manifest value, or null if absent
     * @param typeIsKnown  whether {@code IModFile.Type.valueOf} accepted it (meaningless
     *                     when {@code declaredType} is null)
     * @param hasModsToml  whether {@code META-INF/neoforge.mods.toml} is present
     */
    public static ClaimPlan of(String declaredType, boolean typeIsKnown, boolean hasModsToml) {
        if (declaredType != null && !typeIsKnown) {
            return DECLINE_UNKNOWN_TYPE;
        }
        if (hasModsToml) {
            return CLAIM_WITH_MODS_TOML;
        }
        if (declaredType == null) {
            return DECLINE_NOT_A_MOD;
        }
        return CLAIM_AS_MANIFEST_MOD;
    }
}
