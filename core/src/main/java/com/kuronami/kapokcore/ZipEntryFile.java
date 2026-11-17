package com.kuronami.kapokcore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts one zip entry on the default filesystem as a regular file, and answers whether
 * that has already been done.
 *
 * <p>Two places in this jar need the same thing for different reasons - the mod half
 * carried inside the service jar ({@link BundleExtractor}) and every dependency carried
 * inside a mod jar ({@code jij.NestedJarStore}) - and both need it to be free on a launch
 * where nothing changed. The zip's own bookkeeping decides that: uncompressed size and
 * CRC-32 are in the central directory, which is the only part {@link ZipFile} reads when
 * it opens a file, so an unchanged entry costs one CRC over the copy already on disk and
 * no write at all.
 *
 * <p>Bytes are copied verbatim. Nothing here inflates and re-deflates, so a jar written
 * out by this class is byte-for-byte the entry that was in the enclosing jar, and its own
 * signature - if it has one - verifies exactly as it did inside.
 *
 * <p>Public only so that the {@code jij} package can reach it. Pure {@code java.nio} and
 * {@code java.util.zip}, no loader types.
 */
public final class ZipEntryFile {
    /** What a temporary copy is called, both while it is written and when it is found later. */
    static final String PARTIAL_PREFIX = "partial";

    /**
     * The exact shape {@link Files#createTempFile} gives a temporary copy: the prefix, an
     * unsigned decimal number, the suffix. A nested jar is stored under its own entry name,
     * which may also start with "partial", so the prefix alone would sweep real copies.
     */
    private static final java.util.regex.Pattern PARTIAL_NAME =
            java.util.regex.Pattern.compile("partial[0-9]+\\.(jar|agb)");

    /** Whether {@code fileName} is a temporary copy this class or {@code ScanCache} wrote. */
    static boolean isPartial(String fileName) {
        return PARTIAL_NAME.matcher(fileName).matches();
    }

    /** A temporary copy not touched for this long is a leftover of a launch that was killed. */
    static final long STALE_MILLIS = 60L * 60 * 1000;

    private ZipEntryFile() {
    }

    /** True when {@code target} already holds exactly the bytes {@code entry} describes. */
    public static boolean matches(ZipEntry entry, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (entry.getSize() >= 0 && Files.size(target) != entry.getSize()) {
            return false;
        }
        if (entry.getCrc() < 0) {
            // A zip written without CRCs in the central directory. Nothing to compare
            // against, so rewrite rather than trust a size match alone.
            return false;
        }
        return crc32(target) == entry.getCrc();
    }

    /**
     * Writes {@code entry} to {@code target}, replacing whatever is there.
     *
     * <p>Through a temporary file and a move, so that a launch interrupted mid-copy
     * leaves either the old file or the new one and never a half-written jar that the
     * next launch would accept on a size match.
     */
    public static void write(ZipFile jar, ZipEntry entry, Path target) throws IOException {
        Path partial = Files.createTempFile(target.getParent(), PARTIAL_PREFIX, ".jar");
        try {
            try (InputStream in = jar.getInputStream(entry)) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * Removes temporary copies left behind by a launch that was killed mid-write.
     *
     * <p>{@link #write} removes its own temporary file on the way out, and the move onto the
     * target means a half copy is never taken for the entry it was replacing. A process
     * killed outright still leaves one behind, and nothing else ever looks at it, so
     * without this they would collect one per interrupted launch.
     *
     * <p>{@code directory} may be where the copies are written or a parent of those
     * directories, one level down - which is how the jar-in-jar store keeps one directory
     * per entry.
     *
     * <p>Only files untouched for an hour are removed. Another game started from the same
     * installation may be copying right now, and a copy in progress is seconds old.
     *
     * @return how many files were removed
     */
    public static int deleteStalePartials(Path directory) {
        return deleteStalePartials(directory, STALE_MILLIS);
    }

    /** As {@link #deleteStalePartials(Path)}, for files not touched in {@code olderThanMillis}. */
    static int deleteStalePartials(Path directory, long olderThanMillis) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        int removed = deleteStaleIn(directory, cutoff);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    removed += deleteStaleIn(child, cutoff);
                }
            }
        } catch (IOException | RuntimeException notListed) {
            // A temporary file only costs space, so a directory that cannot be listed is
            // passed over rather than allowed to fail a launch.
        }
        return removed;
    }

    private static int deleteStaleIn(Path directory, long cutoff) {
        int removed = 0;
        try (DirectoryStream<Path> files =
                Files.newDirectoryStream(directory, PARTIAL_PREFIX + "*")) {
            for (Path file : files) {
                if (!isPartial(file.getFileName().toString())) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException | RuntimeException gone) {
                    // Another process may have taken it; either way it is not there.
                }
            }
        } catch (IOException | RuntimeException notListed) {
            // See above.
        }
        return removed;
    }

    private static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }
}
