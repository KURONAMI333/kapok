package com.kuronami.kapokcore;

/**
 * Accepts only the NeoForge line this jar was read against.
 *
 * <p>This jar carries no mod id and no {@code neoforge.mods.toml}, so no launcher and
 * no dependency declaration will ever check what it runs on. It also uses no Mixin, so
 * there is no {@code require = 1} to fail loudly when the loader moves underneath it.
 * The whole optimisation is an equivalence argument about internals of
 * {@code JarSigningData} and {@code ModFile#scanFile} - once those move, the argument is
 * unsupported and the correct behaviour is to do nothing.
 *
 * <p>The line is accepted as a whole rather than one patch version at a time: NeoForge
 * 21.1 patch releases carry loader fixes, and the classes this argument rests on are
 * byte-identical on the 21.1 line as far as it has been read - {@code ModFile},
 * {@code ModClassVisitor} and {@code ModFileScanData} compare equal across loader
 * 4.0.42 (NeoForge 21.1.231), 4.0.43 (21.1.247) and 4.0.45 (21.1.256).
 * If a future 21.1 patch changes any of them, this gate will not notice; the failure
 * would be a class read wrongly rather than a log line. Widening or narrowing what the
 * gate accepts is a support decision, not a build detail.
 */
public final class VersionGate {
    /** The only NeoForge line whose internals this jar has been checked against. */
    public static final String SUPPORTED_LINE = "21.1";

    private VersionGate() {
    }

    /**
     * @param neoForgeVersion the value of {@code FMLLoader.versionInfo().neoForgeVersion()},
     *                        which may be {@code null} if the loader never set it
     * @return true only for {@code 21.1.<digit>...}; anything unparseable, absent or from
     *         another line is a refusal, because "we could not tell" must land on off
     */
    public static boolean supports(String neoForgeVersion) {
        if (neoForgeVersion == null) {
            return false;
        }
        String version = neoForgeVersion.trim();
        if (version.isEmpty()) {
            return false;
        }
        String[] parts = version.split("[.]");
        if (parts.length < 3) {
            return false;
        }
        if (!"21".equals(parts[0]) || !"1".equals(parts[1])) {
            return false;
        }
        // 21.1.247 and 21.1.0-beta both qualify; 21.1.x-something is still the 21.1 line.
        return !parts[2].isEmpty() && Character.isDigit(parts[2].charAt(0));
    }

    /** For log lines, so an absent version does not print as "null". */
    public static String describe(String neoForgeVersion) {
        if (neoForgeVersion == null || neoForgeVersion.trim().isEmpty()) {
            return "<unknown>";
        }
        return neoForgeVersion.trim();
    }
}
