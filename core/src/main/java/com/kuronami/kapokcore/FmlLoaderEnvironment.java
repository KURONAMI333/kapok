package com.kuronami.kapokcore;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.ModDirTransformerDiscoverer;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.neoforgespi.locating.IModFileReader;

/**
 * Reads what this jar needs out of the running loader.
 *
 * <p>Nothing here constructs a third party's reader. That is the whole point: the census
 * runs while the loader is already iterating readers, and building somebody else's
 * provider a second time would run their constructor, their static initialiser and their
 * provider factory again.
 */
public final class FmlLoaderEnvironment implements LoaderEnvironment {
    private static final String READER_SERVICE = "net.neoforged.neoforgespi.locating.IModFileReader";

    /**
     * {@code FMLServiceProvider#initialize} calls {@code FMLLoader.setupLaunchHandler},
     * which assigns the version, before {@code beginModScan} builds the
     * {@code ModDiscoverer}. Discovery therefore always runs with a version in place.
     */
    @Override
    public String neoForgeVersion() {
        VersionInfo info = FMLLoader.versionInfo();
        return info == null ? null : info.neoForgeVersion();
    }

    /**
     * {@code FMLLoader.setupLaunchHandler} assigns the game path in the same method that
     * assigns the version, so a discovery that can read one can read the other.
     */
    @Override
    public Path gameDirectory() {
        return FMLLoader.getGamePath();
    }

    /**
     * {@code ModDirTransformerDiscoverer} is the discovery service that lifted this jar
     * out of {@code mods/} into the SERVICE layer; it keeps the list of what it lifted so
     * that {@code ModsFolderLocator} can skip those files. That list is the only place
     * the loader records where a service jar came from as a plain file path.
     */
    @Override
    public List<Path> serviceLayerJars() {
        return List.copyOf(ModDirTransformerDiscoverer.allExcluded());
    }

    @Override
    public Collection<String> modFileReaderClassNames() {
        ModuleLayer own = getClass().getModule().getLayer();
        return own != null ? fromModuleGraph(own) : fromServiceLoader();
    }

    /**
     * Production path. A jar that declares an {@code IModFileReader} is forced into the
     * SERVICE layer by {@code TransformerDiscovererConstants#shouldLoadInServiceLayer},
     * and automatic modules carry their {@code META-INF/services} entries in their
     * descriptor ({@code SimpleJarMetadata#computeDescriptor} copies them through
     * {@code ModuleDescriptor.Builder#provides}). So the names are readable straight off
     * the module graph, with no class loading at all.
     *
     * <p>Known limitation: this walks our own layer and its ancestors, which covers
     * SERVICE and its parent BOOT. PLUGIN is a sibling of SERVICE rather than an
     * ancestor, so a reader published from the PLUGIN layer would not be seen here.
     */
    private static Collection<String> fromModuleGraph(ModuleLayer start) {
        Set<String> names = new LinkedHashSet<>();
        Set<ModuleLayer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ModuleLayer> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            ModuleLayer layer = pending.removeFirst();
            if (!seen.add(layer)) {
                continue;
            }
            pending.addAll(layer.parents());
            for (ResolvedModule module : layer.configuration().modules()) {
                for (ModuleDescriptor.Provides provides : module.reference().descriptor().provides()) {
                    if (READER_SERVICE.equals(provides.service())) {
                        names.addAll(provides.providers());
                    }
                }
            }
        }
        return names;
    }

    /**
     * Development / classpath path, used when this class sits in an unnamed module and so
     * has no layer to walk. {@code ServiceLoader.Provider#type()} hands back a cached
     * {@code Class} object; only {@code get()} reaches {@code newInstance}, and the class
     * itself is loaded with {@code initialize = false}. Nothing of the provider runs.
     *
     * <p>Deduplication is ours because {@code ServiceLoader.Provider} does not override
     * {@code equals}, which is why the loader's own {@code .distinct()} is a no-op.
     */
    private Collection<String> fromServiceLoader() {
        List<String> names = new ArrayList<>();
        ServiceLoader<IModFileReader> loader =
                ServiceLoader.load(IModFileReader.class, getClass().getClassLoader());
        for (ServiceLoader.Provider<IModFileReader> provider : loader.stream().toList()) {
            names.add(provider.type().getName());
        }
        return names;
    }
}
