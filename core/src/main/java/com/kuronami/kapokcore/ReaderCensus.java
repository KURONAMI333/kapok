package com.kuronami.kapokcore;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether anyone else is claiming mod jars.
 *
 * <p>This jar registers an {@code IModFileReader} at priority 1, above the loader's own
 * readers at 0 and -1000, which means it is offered every jar in {@code mods/} before
 * anybody else. That is only defensible while nothing else is trying to do the same.
 * The test is an allowlist, not a denylist, for two reasons:
 *
 * <ul>
 *   <li>An allowlist needs only the class <em>name</em> of each provider, so the check
 *       never constructs a third party's reader - no constructor, no static initialiser,
 *       no provider factory.
 *   <li>{@code ServiceLoader.Provider#type()} returns the declared <em>return type</em>
 *       when a provider is published through a static {@code provider()} factory, so the
 *       name it hands back cannot be trusted to identify the implementation. A denylist
 *       built on those names would miss; an allowlist merely refuses to recognise them,
 *       which lands on off.
 * </ul>
 */
public final class ReaderCensus {
    /** Ships in fmlloader's own {@code META-INF/services} file (verified at 21.1.247). */
    public static final String BUILT_IN_JAR_MODS_TOML =
            "net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader";
    /** The other half of that same services file. */
    public static final String BUILT_IN_NESTED_LIBRARY =
            "net.neoforged.fml.loading.moddiscovery.readers.NestedLibraryModReader";
    /**
     * Us. Written as a literal so this class stays free of loader types and can be unit
     * tested; {@code ReaderCensusTest} pins it against the shipped services declaration.
     */
    public static final String OURS = "com.kuronami.kapokcore.KapokCoreReader";

    private static final Set<String> ALLOWED =
            Set.of(BUILT_IN_JAR_MODS_TOML, BUILT_IN_NESTED_LIBRARY, OURS);

    private ReaderCensus() {
    }

    /**
     * Sanity check on the census itself.
     *
     * <p>Both enumeration routes are indirect: one reads module descriptors, the other
     * asks a {@code ServiceLoader} built from our own class loader. Neither is the list
     * the loader actually iterates. If a route cannot even find this jar - which is
     * certainly registered, because something just called its reader - then it is not
     * seeing what the loader sees, and its silence about everyone else means nothing.
     *
     * @return true when the census found us, and can therefore be believed about others
     */
    public static boolean recognisesUs(Collection<String> names) {
        if (names == null) {
            return false;
        }
        for (String raw : names) {
            if (raw != null && OURS.equals(raw.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param names provider class names, in any order, possibly with repeats - the loader's
     *              own {@code .distinct()} in {@code LaunchContext#loadServices} does not
     *              deduplicate, because {@code ServiceLoader.Provider} does not override
     *              {@code equals}, so repeats reach us and we fold them here
     * @return every name that is not one of the three we expect, sorted and deduplicated;
     *         empty means we are alone and may stay on
     */
    public static List<String> foreign(Collection<String> names) {
        if (names == null) {
            return List.of();
        }
        TreeSet<String> foreign = new TreeSet<>();
        for (String raw : names) {
            if (raw == null) {
                continue;
            }
            String name = raw.trim();
            if (name.isEmpty() || ALLOWED.contains(name)) {
                continue;
            }
            foreign.add(name);
        }
        return List.copyOf(foreign);
    }
}
