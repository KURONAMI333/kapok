package com.kuronami.kapokcore.jij;

import com.kuronami.kapokcore.Notes;
import cpw.mods.jarhandling.JarContents;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import net.neoforged.neoforgespi.locating.ModFileLoadingException;

/**
 * Jar-in-Jar, with the nested jars read from disk instead of from memory.
 *
 * <p>The loader's own locator mounts a nested jar where it lies, by opening a filesystem
 * over a path inside another filesystem. Nothing can seek within such a path, so
 * {@code jdk.nio.zipfs} inflates the entire nested jar into a {@code byte[]} and keeps it
 * for the rest of the session. This subclass writes the same bytes out as a file first
 * (see {@link NestedJarStore}) and hands the loader that, which it opens through a file
 * channel and reads a page at a time.
 *
 * <p>One method is replaced and nothing else. Which nested jars exist, which version of a
 * duplicated library wins, and what is said when a dependency cannot be satisfied are all
 * decided in {@code scanMods} by {@code JarSelector}, inherited untouched; this only
 * changes where the winner's bytes are read from. {@code scanMods} itself is wrapped, but
 * only to delegate and then say what was done - the moment it returns is the first at
 * which every mod has been asked what it carries, and so the first at which a copy can be
 * known to belong to no installed mod any more, and whether it returned at all is half of
 * whether that question has an answer.
 *
 * <p>Every failure is per jar and falls back to the loader's own path for that one jar, so
 * a nested jar this class cannot write out is mounted the way it always was rather than
 * being missing.
 */
public final class KapokJarInJarLocator extends JarInJarDependencyLocator {
    private final NestedJarStore store;
    private final Notes notes;

    KapokJarInJarLocator(NestedJarStore store, Notes notes) {
        this.store = store;
        this.notes = notes;
    }

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        boolean completed = false;
        try {
            super.scanMods(loadedMods, pipeline);
            completed = true;
        } finally {
            try {
                store.finish(completed);
            } catch (Throwable t) {
                // Housekeeping, after the work that mattered is already done. Whatever
                // went wrong here, the mods are loaded and the launch continues.
                notes.trouble("Kapok Core: could not tidy the nested jar cache", t);
            }
        }
    }

    @Override
    protected Optional<IModFile> loadModFileFrom(IModFile parent, Path path, IDiscoveryPipeline pipeline) {
        Path copy = copyOf(parent, path);
        if (copy == null) {
            return super.loadModFileFrom(parent, path, pipeline);
        }
        try {
            return Optional.ofNullable(pipeline.readModFile(
                    JarContents.of(copy),
                    ModFileDiscoveryAttributes.DEFAULT.withParent(parent)));
        } catch (Exception e) {
            // The same failure the loader's own method reports, in the same shape: the
            // file was found and could not be read, which is a mod loading error and not
            // something to retry down another path.
            ModFileLoadingException failure = new ModFileLoadingException(
                    "Failed to load mod file " + parent.getFileName());
            failure.initCause(e);
            throw failure;
        }
    }

    /**
     * @return the nested jar as a file on disk, or null when this one has to go the
     *         loader's own way - the enclosing mod is not a plain jar file (a development
     *         launch loads mods from directories), or writing the copy failed
     */
    private Path copyOf(IModFile parent, Path path) {
        String entryName = NestedJarKey.entryName(path);
        Path enclosing = parent.getFilePath();
        if (enclosing == null
                || enclosing.getFileSystem() != FileSystems.getDefault()
                || !Files.isRegularFile(enclosing)) {
            // Said out loud rather than passed over in silence. Every reason to fall back
            // has to leave a line, because the only other symptom is a count of zero at
            // the end of startup with nothing to say which of them applied.
            store.mountedInPlace();
            notes.detail("Kapok Core: mounting " + entryName + " inside "
                    + parent.getFileName() + " in place (its mod is not a plain jar file)");
            return null;
        }
        try {
            return store.materialise(enclosing, entryName);
        } catch (Throwable t) {
            store.mountedInPlace();
            notes.detail("Kapok Core: mounting " + entryName + " inside "
                    + parent.getFileName() + " in place (" + t + ")");
            return null;
        }
    }
}
