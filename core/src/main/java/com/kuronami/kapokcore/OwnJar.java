package com.kuronami.kapokcore;

import java.io.IOException;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

/**
 * Finds the jar this code was loaded from.
 *
 * <p>Three routes, tried in order, all read-only:
 *
 * <ol>
 *   <li><b>The loader's own record.</b> {@code ModDirTransformerDiscoverer} lifted this
 *       jar out of {@code mods/} into the SERVICE layer and remembers the file it did that
 *       to. Among those few files, ours is the one that carries this class. This is the
 *       route that works in production (verified 2026-09-05 on NeoForge 21.1.247, where
 *       the module location below was <em>not</em> a {@code file:} URI).
 *   <li><b>The module reference.</b> Present when the class sits in a named module whose
 *       {@code ModuleReference} location is a real file.
 *   <li><b>The code source.</b> The classpath case of a development launch.
 * </ol>
 */
final class OwnJar {
    /** Entry whose presence identifies our jar among the SERVICE-layer candidates. */
    static final String MARKER_ENTRY = OwnJar.class.getName().replace('.', '/') + ".class";

    private OwnJar() {
    }

    /**
     * @param candidates jars the loader placed in its SERVICE layer (may be empty)
     * @return the jar file {@code cls} was loaded from, if it can be named as a regular file
     */
    static Optional<Path> of(Class<?> cls, List<Path> candidates) {
        Optional<Path> fromCandidates = fromCandidates(candidates);
        if (fromCandidates.isPresent()) {
            return fromCandidates;
        }
        Optional<Path> fromModule = fromModule(cls);
        if (fromModule.isPresent()) {
            return fromModule;
        }
        return fromCodeSource(cls);
    }

    /** What the module system says our location is; for the log line when nothing above worked. */
    static String describeModuleLocation(Class<?> cls) {
        Module module = cls.getModule();
        if (module == null || !module.isNamed() || module.getLayer() == null) {
            return "unnamed module";
        }
        return module.getLayer().configuration().findModule(module.getName())
                .flatMap(m -> m.reference().location())
                .map(URI::toString)
                .orElse("module " + module.getName() + " without a location");
    }

    static Optional<Path> fromCandidates(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            try (ZipFile zip = new ZipFile(candidate.toFile())) {
                if (zip.getEntry(MARKER_ENTRY) != null) {
                    return Optional.of(candidate);
                }
            } catch (IOException notAZip) {
                // Not ours, or not readable as a zip; the loader will complain about it
                // on its own if it matters.
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> fromModule(Class<?> cls) {
        Module module = cls.getModule();
        if (module == null || !module.isNamed() || module.getLayer() == null) {
            return Optional.empty();
        }
        Optional<ResolvedModule> resolved = module.getLayer().configuration().findModule(module.getName());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Optional<URI> location = resolved.get().reference().location();
        if (location.isEmpty()) {
            return Optional.empty();
        }
        return asRegularFile(location.get());
    }

    private static Optional<Path> fromCodeSource(Class<?> cls) {
        try {
            CodeSource source = cls.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Optional.empty();
            }
            return asRegularFile(source.getLocation().toURI());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> asRegularFile(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(uri);
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
