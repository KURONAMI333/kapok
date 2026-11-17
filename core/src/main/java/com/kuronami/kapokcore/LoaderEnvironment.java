package com.kuronami.kapokcore;

import java.nio.file.Path;
import java.util.List;
import java.util.Collection;

/**
 * The facts this jar needs from the running mod loader.
 *
 * <p>Split out so the decision logic can be tested without a loader on the classpath,
 * and so that "the loader answered something we did not expect" and "the loader threw"
 * are both handled in one place.
 */
public interface LoaderEnvironment {
    /** The running NeoForge version, or {@code null} if the loader has not published one. */
    String neoForgeVersion();

    /**
     * Class names of every registered {@code IModFileReader}, without constructing any
     * of them. May throw; the caller treats a throw as "turn off".
     */
    Collection<String> modFileReaderClassNames();

    /**
     * The directory the game was launched against, or {@code null} when the loader has
     * not settled on one. Anything this jar keeps between launches goes underneath it,
     * so that it travels with the instance and can be deleted with it.
     */
    Path gameDirectory();

    /**
     * The jars the loader placed in its SERVICE layer from the mods folder, or an empty
     * list when the loader does not expose them. This jar is one of them in production.
     */
    default List<Path> serviceLayerJars() {
        return List.of();
    }
}
