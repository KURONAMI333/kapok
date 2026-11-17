package com.kuronami.kapokcore.jij;

import com.kuronami.kapokcore.Notes;
import com.kuronami.kapokcore.ZipEntryFile;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the jars that mods carry inside themselves as ordinary files on disk.
 *
 * <p>A jar mounted from inside another jar is not a file the operating system knows
 * about, so nothing can read a part of it: {@code jdk.nio.zipfs} inflates the whole
 * nested jar into a {@code byte[]} and holds it for as long as the filesystem is open,
 * which is the rest of the session. On a 198-mod pack that is 141 nested jars and 237.8
 * MiB of heap that exists only because the bytes had nowhere else to live. Written out
 * once under the game directory, the same jars are read through a file channel: the
 * operating system pages in what is asked for and nothing is held.
 *
 * <p>Deliberately free of loader types, the same way {@code ScanCache} is: everything
 * that needs a live mod file stays in {@link KapokJarInJarLocator}, and what is here is
 * driven from a test with real zip files and no game classpath.
 *
 * <p>Copies are keyed by {@link NestedJarKey} and each sits in a directory of its own, so
 * the file keeps the name it had inside the mod. That is not cosmetic: the loader's mod
 * list and its error messages name a mod file by {@code getFileName()}, and a launch with
 * this installed has to read the same as a launch without it.
 */
final class NestedJarStore {
    /**
     * Opens an enclosing jar for reading.
     *
     * <p>A seam, because "how often was this file opened" is the whole of what the
     * bookkeeping below is for and there is no other way to see it from outside.
     */
    interface JarOpener {
        ZipFile open(Path realPath) throws IOException;
    }

    private final Path root;
    private final Notes notes;
    private final JarOpener opener;

    /**
     * The enclosing jars opened so far, by real path.
     *
     * <p>A mod carrying ten libraries is asked about each of them separately, and
     * Jar-in-Jar asks a second time for every one it selects, so a launch makes far more
     * requests than there are jars to open. Each stays open until {@link #finish(boolean)}
     * and is read by whichever thread wants it - {@link ZipFile} is safe to share.
     */
    private final ConcurrentHashMap<Path, ZipFile> opened = new ConcurrentHashMap<>();

    private final Set<String> used = ConcurrentHashMap.newKeySet();
    private final AtomicLong extracted = new AtomicLong();
    private final AtomicLong reused = new AtomicLong();
    private final AtomicLong mountedInPlace = new AtomicLong();

    NestedJarStore(Path root, Notes notes) {
        this(root, notes, real -> new ZipFile(real.toFile(), StandardCharsets.UTF_8));
    }

    NestedJarStore(Path root, Notes notes, JarOpener opener) {
        this.root = root;
        this.notes = notes;
        this.opener = opener;
        // Each entry gets a directory of its own and the copy is written through a
        // temporary file in that directory, so a launch killed mid-write leaves one a
        // level down from here, where sweep() does not look. Only files an hour old are
        // touched, so a copy another game is writing right now stays.
        ZipEntryFile.deleteStalePartials(root);
    }

    /**
     * The file holding {@code entryName} out of {@code enclosingFile}, writing it first if
     * this is the first launch to ask - or the first since that entry changed.
     *
     * <p>The bytes are the entry's own, uncompressed but otherwise untouched, so the
     * result is the same jar the loader would have mounted in place.
     *
     * @param enclosingFile a regular file on the default filesystem. A nested jar two deep
     *                      satisfies this because its enclosing jar is itself a copy this
     *                      method already made.
     * @param entryName     as the central directory spells it, {@code /}-separated
     * @throws IOException when the enclosing jar cannot be opened, holds no such entry, or
     *                     the copy cannot be written. The caller falls back to mounting
     *                     the nested path for that one jar and carries on with the rest.
     */
    Path materialise(Path enclosingFile, String entryName) throws IOException {
        Path real = enclosingFile.toRealPath();
        ZipFile zip = openedJar(real);
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IOException("no entry " + entryName + " in " + real);
        }
        String fileName = fileName(entryName);
        if (!isPlainFileName(fileName)) {
            // The name of the copy comes out of a mod's own metadata, so it is checked
            // rather than trusted: it has to be a file name and not a way of walking out
            // of the directory it belongs in.
            throw new IOException("unusable entry name " + entryName + " in " + real);
        }
        String key = NestedJarKey.of(real, entryName, entry.getSize(), entry.getCrc());
        used.add(key);
        Path directory = root.resolve(key);
        Path target = directory.resolve(fileName);
        if (alreadyWritten(entry, target)) {
            reused.incrementAndGet();
            return target;
        }
        Files.createDirectories(directory);
        ZipEntryFile.write(zip, entry, target);
        extracted.incrementAndGet();
        return target;
    }

    /**
     * True when {@code target} already holds this entry, without reading the copy back.
     *
     * <p>The directory the copy lives in is named after the entry's own size and CRC-32
     * ({@link NestedJarKey}), so a file of that length under that name is this entry: an
     * entry whose bytes changed has a different CRC-32 and with it a different directory,
     * and the file it finds there is written rather than found. That leaves the length to
     * check, which is one stat, where {@link ZipEntryFile#matches} reads the whole jar -
     * the cost of writing it out a second time, paid on every launch of every nested jar.
     *
     * <p>A copy whose bytes were damaged in place without changing its length is taken as
     * good and stays until the entry itself changes. The damage that matters is the jar
     * the loader is mounting, and this way it is read as the copy rather than not at all.
     *
     * <p>An entry whose central directory carries no size or no CRC-32 is left to
     * {@link ZipEntryFile#matches}: its key is the same for every version of it, so a
     * length on its own would be all that stands between a stale copy and a launch.
     */
    private static boolean alreadyWritten(ZipEntry entry, Path target) throws IOException {
        if (entry.getSize() < 0 || entry.getCrc() < 0) {
            return ZipEntryFile.matches(entry, target);
        }
        return Files.isRegularFile(target) && Files.size(target) == entry.getSize();
    }

    private ZipFile openedJar(Path real) throws IOException {
        try {
            return opened.computeIfAbsent(real, path -> {
                try {
                    return opener.open(path);
                } catch (IOException cannotOpen) {
                    throw new UncheckedIOException(cannotOpen);
                }
            });
        } catch (UncheckedIOException cannotOpen) {
            throw cannotOpen.getCause();
        }
    }

    /** One nested jar was mounted where it lies, so this store never heard about it. */
    void mountedInPlace() {
        mountedInPlace.incrementAndGet();
    }

    /**
     * Says what was done and removes the copies of nested jars that are no longer in any
     * installed mod.
     *
     * <p>Called once, when the dependency locator has finished, because that is the first
     * moment at which "no longer in any installed mod" means anything: a key is only known
     * to be unused once every mod has been asked what it carries.
     *
     * <p>Removing a copy is worth no risk at all - a leftover costs disk space and a
     * missing one costs a launch - so a directory that will not delete is left alone and
     * mentioned rather than retried.
     *
     * @param scanCompleted whether the scan this store served ran to the end
     */
    void finish(boolean scanCompleted) {
        // Before the sweep, so that nothing this store is still holding open stands in
        // the way of deleting the directory it lives in.
        closeEnclosingJars();
        int removed = maySweep(scanCompleted) ? sweep() : 0;
        notes.info("Kapok Core: nested jars on disk instead of in memory - "
                + extracted.get() + " written, " + reused.get() + " reused, "
                + mountedInPlace.get() + " mounted in place"
                + (removed > 0 ? ", " + removed + " no longer installed and removed" : ""));
    }

    /**
     * True when the set of keys asked for is the whole set the installed mods carry, which
     * is what "no longer in any installed mod" is decided against.
     *
     * <p>Two launches produce a short set without anything having gone wrong enough to
     * notice, and either one would throw away copies that are still installed:
     *
     * <ul>
     *   <li>A nested jar that fell back to being mounted where it lies never reached this
     *       store, so its key is missing. One is enough - the first launch of the feature
     *       fell back on every jar it saw, and sweeping there would have emptied a cache
     *       that had just been filled.
     *   <li>A scan that threw partway stopped asking, so every key after the failure is
     *       missing. That happens while somebody is repairing a broken install, which is
     *       the worst moment to also make their next launch slow.
     * </ul>
     */
    private boolean maySweep(boolean scanCompleted) {
        return scanCompleted && mountedInPlace.get() == 0;
    }

    /**
     * Lets go of every enclosing jar this store opened. Nothing may ask it for a copy
     * afterwards without paying for the opens again, which is why {@link #finish(boolean)}
     * is the only caller in a launch.
     */
    void closeEnclosingJars() {
        for (var each : opened.entrySet()) {
            try {
                each.getValue().close();
            } catch (IOException wouldNotClose) {
                // One handle that will not let go is not a reason to keep the rest open,
                // nor to skip the line that says what this launch did.
                notes.detail("Kapok Core: " + each.getKey() + " stayed open ("
                        + wouldNotClose + ")");
            }
        }
        opened.clear();
    }

    /** @return how many stale key directories were removed */
    int sweep() {
        int removed = 0;
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(root)) {
            for (Path candidate : existing) {
                Path name = candidate.getFileName();
                if (name == null
                        || !NestedJarKey.looksLikeKey(name.toString())
                        || used.contains(name.toString())
                        || !Files.isDirectory(candidate)) {
                    continue;
                }
                if (deleteDirectory(candidate)) {
                    removed++;
                }
            }
        } catch (IOException noDirectory) {
            // Nothing was ever written, or the directory went away underneath us. Either
            // way there is nothing to clean up and nothing has gone wrong.
            return removed;
        }
        return removed;
    }

    private boolean deleteDirectory(Path directory) {
        try (DirectoryStream<Path> contents = Files.newDirectoryStream(directory)) {
            for (Path each : contents) {
                Files.deleteIfExists(each);
            }
        } catch (IOException stillInUse) {
            notes.detail("Kapok Core: leaving " + directory + " in place (" + stillInUse + ")");
            return false;
        }
        try {
            Files.deleteIfExists(directory);
            return true;
        } catch (IOException stillInUse) {
            notes.detail("Kapok Core: leaving " + directory + " in place (" + stillInUse + ")");
            return false;
        }
    }

    /**
     * True when {@code name} names a file and nothing else.
     *
     * <p>Checked on the name rather than on the resolved path. The game directory is a
     * relative path in a development launch, and comparing a resolved path against its own
     * {@code normalize()} throws away the leading {@code .} and makes every name look
     * wrong - which is exactly what happened the first time this was tried.
     */
    private static boolean isPlainFileName(String name) {
        return !name.isEmpty()
                && !".".equals(name)
                && !"..".equals(name)
                && name.indexOf(File.separatorChar) < 0
                && name.indexOf('/') < 0
                && name.indexOf(':') < 0;
    }

    /** The last segment of a zip entry name, which is what the copy is called. */
    static String fileName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }
}
