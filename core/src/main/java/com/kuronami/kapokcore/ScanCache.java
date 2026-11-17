package com.kuronami.kapokcore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * Remembers what scanning a mod jar produced, so the next launch does not do it again.
 *
 * <p>Deliberately not a class the loader can see. Everything that needs a live
 * {@code ModFile} stays in {@link CachingModFile}; this holds the decision, takes the
 * real scan as a {@link Supplier}, and so can be driven from a test with no game
 * classpath, no union filesystem and no mod jars.
 *
 * <p>The escape hatch is the directory. There is no configuration file and no toggle:
 * delete {@code .cache/kapok-core} under the game directory and the next launch scans
 * everything exactly as an unmodded loader would, then starts again from nothing.
 *
 * <p>Every failure is a fall-through to the real scan, and every distinct failure says so
 * once. A cache that quietly stops working must not look the same as one that is working.
 */
final class ScanCache {
    /** Set {@code -Dkapok.core.verifyScan=true} to scan anyway and compare. */
    static final String VERIFY_PROPERTY = "kapok.core.verifyScan";

    private static final String SUFFIX = ".agb";
    static final String DIRECTORY = "kapok-core";

    /**
     * How long a stored scan may go unused before housekeeping removes it.
     *
     * <p>Age of last use, not a count or size limit. A limit sized for one pack is
     * passed by a bigger one: ATM10 alone stores about 520 entries and 64 MiB, so any
     * cap a large pack reaches would drop entries that very launch reads, rescan them
     * and write them back, every launch. Entries a launch reads are touched (see
     * {@link #markUsed}), so what ages out is only what no launch has asked for in this
     * long: mods that were removed or updated, and keys of NeoForge builds the game has
     * moved off.
     */
    static final long UNUSED_MILLIS = 30L * 24 * 60 * 60 * 1000;

    /** A hit on an entry touched more recently than this is not touched again. */
    private static final long TOUCH_AFTER_MILLIS = 24L * 60 * 60 * 1000;

    /** {@link #housekeeping} runs once, though {@link #under} is asked for a cache per reader. */
    private static final AtomicBoolean housekept = new AtomicBoolean();

    private final Path directory;
    private final String generatorTag;
    private final Notes notes;
    private final boolean verify;

    private final AtomicBoolean off = new AtomicBoolean();
    private final AtomicBoolean announcedReuse = new AtomicBoolean();

    /**
     * Counted apart from {@link #announcedReuse} because the two can fail separately.
     * Mods in files of their own are read first and would always take a single
     * announcement, leaving a launch where nothing nested was ever restored looking
     * exactly like one where all of it was.
     */
    private final AtomicBoolean announcedNestedReuse = new AtomicBoolean();
    private final AtomicBoolean announcedVerify = new AtomicBoolean();
    private final AtomicBoolean announcedNothingToVerify = new AtomicBoolean();
    private final AtomicBoolean warnedUnreadable = new AtomicBoolean();
    private final AtomicBoolean warnedUnwritable = new AtomicBoolean();
    private final AtomicLong reused = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    /**
     * @param gameDirectory where the cache directory is put, or {@code null} when the
     *                      loader has not published one yet - in which case nothing is
     *                      cached, and the launch is exactly the one without this jar
     * @param neoForgeVersion stands in for the whole scan generator. The version gate
     *                        pins this jar to one NeoForge line, and within a NeoForge
     *                        build the loader and ASM versions are fixed, so a change to
     *                        either arrives as a change to this string.
     */
    static ScanCache under(Path gameDirectory, String neoForgeVersion, Notes notes) {
        if (gameDirectory == null) {
            return new ScanCache(null, "neoforge " + neoForgeVersion + " format " + ScanDataCodec.FORMAT,
                    notes, Boolean.getBoolean(VERIFY_PROPERTY));
        }
        Path directory = gameDirectory.resolve(".cache").resolve(DIRECTORY);
        if (housekept.compareAndSet(false, true)) {
            housekeeping(directory, notes);
        }
        return new ScanCache(directory, "neoforge " + neoForgeVersion + " format " + ScanDataCodec.FORMAT,
                notes, Boolean.getBoolean(VERIFY_PROPERTY));
    }

    /**
     * Clears what earlier launches left in {@code directory}: temporary copies of a launch
     * that was killed mid-write, then the stored scans no launch has read in
     * {@link #UNUSED_MILLIS}.
     *
     * <p>Done before anything is restored, so it happens on a directory nothing here is
     * reading. Another game started from the same installation may be running, which is
     * why only a temporary copy untouched for an hour is removed - one being written right
     * now is seconds old. Stored scans need no such care: a launch reads one whole with
     * {@code Files.readAllBytes} and closes it before going on, so a file removed while
     * another game is running costs that game nothing, and the next launch scans the one
     * mod whose entry went away.
     *
     * <p>One directory for every NeoForge version, rather than one per version: the
     * generator tag is part of each key, so an entry stored under a version the game has
     * moved off never matches again, nothing touches it, and it ages out before the ones
     * this launch is reading, because entries a launch reads are touched.
     */
    static void housekeeping(Path directory, Notes notes) {
        int partials = ZipEntryFile.deleteStalePartials(directory);
        if (partials > 0) {
            notes.detail("removed " + partials + " temporary file(s) an interrupted launch left in "
                    + directory);
        }
        prune(directory, notes);
    }

    /**
     * Removes the stored scans no launch has read in {@link #UNUSED_MILLIS}.
     *
     * <p>Nothing here is required for a launch to be correct, only for it to be quick,
     * and an entry removed by mistake costs one rescan of one mod.
     */
    private static void prune(Path directory, Notes notes) {
        long cutoff = System.currentTimeMillis() - UNUSED_MILLIS;
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                // A temporary copy another process is writing is not ours to judge, and
                // deleting it would only make that process scan its mod again.
                if (ZipEntryFile.isPartial(file.getFileName().toString())) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException | RuntimeException kept) {
                    // Left for the next launch to try again.
                }
            }
        } catch (IOException | RuntimeException notListed) {
            return;
        }
        if (removed > 0) {
            notes.detail("removed " + removed + " stored scan(s) no launch has read in "
                    + (UNUSED_MILLIS / (24L * 60 * 60 * 1000)) + " days from " + directory);
        }
    }

    /**
     * Records that a launch read {@code entry}, so housekeeping keeps it. One write a
     * day at most per entry; a failure leaves the entry to age out, which costs a rescan.
     */
    private static void markUsed(Path entry) {
        try {
            long now = System.currentTimeMillis();
            if (now - Files.getLastModifiedTime(entry).toMillis() > TOUCH_AFTER_MILLIS) {
                Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(now));
            }
        } catch (IOException | RuntimeException ignored) {
            // Only the age changes; the entry is still read correctly.
        }
    }

    ScanCache(Path directory, String generatorTag, Notes notes, boolean verify) {
        this.directory = directory;
        this.generatorTag = generatorTag;
        this.notes = notes;
        this.verify = verify;
    }

    /**
     * @param jar the file the scan would read, used to name the entry
     * @param modFileInfo the live info the restored data has to carry, since the stored
     *                    form deliberately holds none
     * @param scan the real scan. Called when there is nothing usable to restore, and also
     *             in verify mode, where its result is compared against the restored one.
     */
    ModFileScanData compile(Path jar, Supplier<IModFileInfo> modFileInfo,
            Supplier<ModFileScanData> scan) {
        return compile(jar, null, modFileInfo, scan);
    }

    /**
     * @param jar the file the scan would read; for a nested mod, the file it is carried in
     * @param nestedName the mod's file name inside {@code jar}, or null when the scan
     *                   reads {@code jar} itself. A jar shipped inside another cannot be
     *                   named from its own central directory, so it is named from the
     *                   entry the enclosing jar records for it - see {@code ScanCacheKey}.
     */
    ModFileScanData compile(Path jar, String nestedName, Supplier<IModFileInfo> modFileInfo,
            Supplier<ModFileScanData> scan) {
        if (directory == null) {
            return notCacheable("no cache directory for this launch", jar, nestedName, scan);
        }
        if (off.get()) {
            return notCacheable("the cache turned itself off earlier this launch", jar, nestedName, scan);
        }
        if (!ScanCacheKey.readable(jar)) {
            return notCacheable("not a regular file on the default filesystem", jar, nestedName, scan);
        }

        String key;
        try {
            if (nestedName == null) {
                key = ScanCacheKey.of(jar, generatorTag);
            } else {
                ScanCacheKey.NestedKeyResult nested =
                        ScanCacheKey.ofNestedWithReason(jar, nestedName, generatorTag);
                if (nested.key() == null) {
                    // The enclosing jar holds no single entry of that name. Nothing is
                    // wrong with either jar; we simply have no way to tell this one from
                    // another.
                    return notCacheable(
                            "could not be keyed (" + nested.reason() + ")", jar, nestedName, scan);
                }
                key = nested.key();
            }
        } catch (Throwable t) {
            // A jar we cannot read the directory of is a jar the loader is about to have
            // its own opinion about. Not our place to report it as a problem.
            return notCacheable("could not be keyed (" + t + ")", jar, nestedName, scan);
        }

        if (verify) {
            // Said here rather than on the first match, because a launch with an empty
            // directory has nothing to restore and so nothing to compare. Announcing only
            // when something matches would make that launch indistinguishable from one
            // where the flag never took effect.
            announceVerify();
        }

        String named = name(jar, nestedName);
        Path entry = directory.resolve(key + SUFFIX);
        ModFileScanData restored = restore(entry, modFileInfo);
        if (restored == null) {
            ModFileScanData fresh = scan.get();
            save(entry, fresh, jar, nestedName);
            if (verify && announcedNothingToVerify.compareAndSet(false, true)) {
                notes.info("Kapok Core has nothing to verify for " + named
                        + ": there was no stored scan for it, so this launch is storing one."
                        + " Launch again to compare.");
            }
            return fresh;
        }

        reused.incrementAndGet();
        AtomicBoolean firstOfItsKind =
                nestedName == null ? announcedReuse : announcedNestedReuse;
        String subject = nestedName == null
                ? ": " + named
                : " for a mod shipped inside another: " + named + ", from " + jar.getFileName();
        if (firstOfItsKind.compareAndSet(false, true) && !notes.info(
                "Kapok Core reused a stored class scan for the first time" + subject
                        + " (delete " + directory + " to scan everything again)")) {
            // Same rule as the activation line. If nobody can see that we are answering
            // from a file instead of from the jar, we stop answering from the file.
            stop("its own log output could not be written", null);
            return scan.get();
        }

        if (verify) {
            report(named, ScanDataComparison.differences(scan.get(), restored));
        }
        detail("restored from " + entry.getFileName(), jar, nestedName, restored.getClasses().size());
        return restored;
    }

    /**
     * @param reason why {@code jar} (or the nested mod named by {@code nestedName}) is not
     *               being cached this time, said in a way distinct enough to tell one
     *               refusal from another in the log
     */
    private ModFileScanData notCacheable(String reason, Path jar, String nestedName,
            Supplier<ModFileScanData> scan) {
        skipped.incrementAndGet();
        ModFileScanData fresh = scan.get();
        detail("not cacheable (" + reason + ")", jar, nestedName, fresh.getClasses().size());
        return fresh;
    }

    private ModFileScanData restore(Path entry, Supplier<IModFileInfo> modFileInfo) {
        byte[] bytes;
        try {
            if (!Files.isRegularFile(entry)) {
                return null;
            }
            bytes = Files.readAllBytes(entry);
        } catch (Throwable t) {
            once(warnedUnreadable, "Kapok Core could not read its stored scans, so this launch"
                    + " scans mod classes as usual", t);
            return null;
        }
        try {
            ModFileScanData restored = ScanDataCodec.decode(bytes, modFileInfo);
            markUsed(entry);
            return restored;
        } catch (Throwable t) {
            // A truncated or foreign file. Drop it so the next launch is not slowed by
            // the same failed read, and scan for real now.
            once(warnedUnreadable, "Kapok Core discarded a stored scan it could not read ("
                    + entry.getFileName() + "); this launch scans mod classes as usual", t);
            discard(entry);
            return null;
        }
    }

    private void save(Path entry, ModFileScanData data, Path jar, String nestedName) {
        Path partial = null;
        try {
            Files.createDirectories(directory);
            byte[] encoded = ScanDataCodec.encode(data);
            // Written beside the entry and moved onto it, so a launch that dies mid-write
            // cannot leave a half file that the next one would try to read.
            partial = Files.createTempFile(directory, ZipEntryFile.PARTIAL_PREFIX, SUFFIX);
            Files.write(partial, encoded);
            move(partial, entry);
            partial = null;
            stored.incrementAndGet();
            detail("stored as " + entry.getFileName(), jar, nestedName, data.getClasses().size());
        } catch (Throwable t) {
            once(warnedUnwritable, "Kapok Core could not store a scan under " + directory
                    + ", so every launch will keep scanning mod classes", t);
        } finally {
            if (partial != null) {
                discard(partial);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException noAtomicMove) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void announceVerify() {
        if (announcedVerify.compareAndSet(false, true)) {
            notes.info("Kapok Core is verifying its stored scans (-D" + VERIFY_PROPERTY
                    + "=true): every scan restored from " + directory + " is being scanned"
                    + " again and compared, and every comparison is reported here. This is"
                    + " slower than not having this jar installed at all.");
        }
    }

    private void report(String named, List<String> differences) {
        if (differences.isEmpty()) {
            notes.info("Kapok Core verified " + named + ": restored scan matches");
            return;
        }
        notes.info("Kapok Core verified " + named + ": " + differences.size()
                + " difference(s) between the stored scan and a fresh one");
        for (String difference : differences) {
            notes.info("  " + difference);
        }
    }

    private void discard(Path entry) {
        try {
            Files.deleteIfExists(entry);
        } catch (Throwable ignored) {
            // Leaving it behind costs one failed read per launch, which the caller has
            // already been told about. Nothing further to do from here.
        }
    }

    /** Turns storing and restoring off for the rest of the launch. Scans still happen. */
    private void stop(String reason, Throwable cause) {
        if (off.compareAndSet(false, true)) {
            notes.trouble("Kapok Core stopped using its stored scans: " + reason, cause);
        }
    }

    private void once(AtomicBoolean guard, String message, Throwable cause) {
        if (guard.compareAndSet(false, true)) {
            notes.trouble(message, cause);
        }
    }

    private void detail(String what, Path jar, String nestedName, int classes) {
        if (!notes.detailed()) {
            return;
        }
        notes.detail("scan " + what + " [" + reused.get() + " reused, " + stored.get()
                + " stored, " + skipped.get() + " not cacheable]: " + where(jar, nestedName)
                + " classes=" + classes);
    }

    /** What to call this scan's subject in a line a person reads. */
    private static String name(Path jar, String nestedName) {
        return nestedName != null ? nestedName : String.valueOf(jar.getFileName());
    }

    /**
     * The same subject, said in full. A nested mod is written as the jar it came in, then
     * {@code !}, then its own name - the one form in which a line about a nested mod says
     * both which mod it is and where to find it.
     */
    private static String where(Path jar, String nestedName) {
        return nestedName != null ? jar + "!" + nestedName : jar.toString();
    }

    /** Test seam. */
    long reusedCount() {
        return reused.get();
    }

    /** Test seam. */
    long storedCount() {
        return stored.get();
    }

    /** Whether there is any point handing jars to this at all. */
    boolean usable() {
        return directory != null && !off.get();
    }
}
