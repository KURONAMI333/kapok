package com.kuronami.kapokcore;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * A {@link JarContents} that reports a manifest and whether a mods.toml is present, which
 * is all the reader looks at before it decides whether to claim a jar.
 */
final class FakeJarContents implements JarContents {
    private final Path primaryPath;
    private final Manifest manifest = new Manifest();
    private final boolean hasModsToml;

    private FakeJarContents(Path primaryPath, String modType, boolean hasModsToml) {
        this.primaryPath = primaryPath;
        this.hasModsToml = hasModsToml;
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (modType != null) {
            manifest.getMainAttributes().putValue("FMLModType", modType);
        }
    }

    /** No FMLModType and no mods.toml: the built-in readers decline this too. */
    static FakeJarContents plain(Path path) {
        return new FakeJarContents(path, null, false);
    }

    static FakeJarContents withModType(Path path, String modType) {
        return new FakeJarContents(path, modType, false);
    }

    static FakeJarContents withModsToml(Path path, String modType) {
        return new FakeJarContents(path, modType, true);
    }

    @Override
    public Path getPrimaryPath() {
        return primaryPath;
    }

    @Override
    public Optional<URI> findFile(String name) {
        boolean present = hasModsToml && JarModsDotTomlModFileReader.MODS_TOML.equals(name);
        return present ? Optional.of(URI.create("file:///fake/" + name)) : Optional.empty();
    }

    @Override
    public Manifest getManifest() {
        return manifest;
    }

    @Override
    public Set<String> getPackages() {
        return Set.of();
    }

    @Override
    public Set<String> getPackagesExcluding(String... excluded) {
        return Set.of();
    }

    @Override
    public List<SecureJar.Provider> getMetaInfServices() {
        return List.of();
    }

    @Override
    public void close() {
    }
}
