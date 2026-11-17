package com.kuronami.kapokcore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Names a jar by what the class scanner would find in it, without reading a single class.
 *
 * <p>The whole point of the cache is to stop reading 200 MB of class files at every
 * launch, so the key cannot be a digest of those bytes - that costs exactly what it
 * saves. A zip already carries a per-entry checksum in its central directory, which sits
 * at the end of the file and is the only part {@link ZipFile} reads when it opens one.
 * Name, uncompressed size and CRC-32 of every {@code .class} entry is therefore a
 * content-sensitive fingerprint that costs one seek per jar.
 *
 * <p>Entries are sorted before they are digested. A rebuild of the same sources can emit
 * the same files in a different order, and re-scanning because a build tool shuffled its
 * output would be a miss for no reason.
 */
final class ScanCacheKey {
    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_SUFFIX = ".jar";

    /**
     * Written into a nested jar's digest so that no nested key can ever be read as the
     * key of a whole file, whatever the two sets of inputs happen to contain.
     */
    private static final String NESTED = "nested";

    /**
     * Separator between the fields of one entry. NUL cannot appear in a zip entry name,
     * so no combination of name, size and CRC can be re-read as a different triple.
     */
    private static final char FIELD = '\0';

    private ScanCacheKey() {
    }

    /**
     * Whether {@link #of} can be asked about this path at all.
     *
     * <p>Two things are excluded, and both are exclusions of correctness rather than of
     * convenience:
     *
     * <ul>
     *   <li>Anything that is not on the default filesystem. A jar nested inside another
     *       jar reaches the readers as a path inside a union filesystem, and its central
     *       directory cannot be read without inflating the whole nested jar first.
     *   <li>Anything that is not a regular file. A mod loaded from directories - which is
     *       what a development launch does - has no central directory, and is also the
     *       only shape in this loader that reaches a reader as several paths at once
     *       ({@code UserdevLocator} is the sole caller of the multi-path
     *       {@code addPath}; every production locator uses the single-path form). A key
     *       built from one path would silently under-describe such a mod.
     * </ul>
     */
    static boolean readable(Path jar) {
        return jar != null
                && jar.getFileSystem() == FileSystems.getDefault()
                && Files.isRegularFile(jar);
    }

    /**
     * Whether a jar arriving at a reader is one nested a single level inside
     * {@code parentFile}, and what it is called in there.
     *
     * <p>A jar shipped inside another one is opened over a filesystem of its own and
     * reaches the reader as that filesystem's root, so it has no central directory anyone
     * can read and {@link #readable} refuses it. What names it instead lives in the
     * enclosing jar's central directory - which is why the enclosing jar has to be a plain
     * file. That requirement is also what confines this to one level: a jar nested two
     * deep has a parent that is itself inside a filesystem, and falls out here.
     *
     * <p>The name is taken from the path rather than from the manifest or the discovery
     * attributes, because it is the only thing that ties the jar to an entry of the file
     * around it. It is returned only when it looks like a jar entry's name; the caller
     * still has to find exactly one such entry, and finds none when this is wrong.
     *
     * @return the nested jar's file name, or null when this is not that shape
     */
    static String nestedIn(Path parentFile, Path own) {
        if (!readable(parentFile)
                || own == null
                || own.getFileSystem() == FileSystems.getDefault()) {
            return null;
        }
        Path name = own.getFileName();
        if (name == null) {
            return null;
        }
        String text = name.toString();
        return text.endsWith(JAR_SUFFIX) ? text : null;
    }

    /**
     * @param generatorTag identifies everything outside the jar that changes what a scan
     *                     produces - see {@code ScanCache}
     * @return a hex SHA-256 over the {@code .class} entries of {@code jar} and the tag
     */
    static String of(Path jar, String generatorTag) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> remaining = zip.entries();
            while (remaining.hasMoreElements()) {
                ZipEntry entry = remaining.nextElement();
                String name = entry.getName();
                // Every .class entry counts, including the ones under
                // META-INF/versions: the scanner walks the jar root and filters on the
                // file name alone, so a multi-release jar's alternate classes are read
                // like any other.
                if (entry.isDirectory() || !name.endsWith(CLASS_SUFFIX)) {
                    continue;
                }
                entries.add(name + FIELD + entry.getSize() + FIELD + entry.getCrc());
            }
        }
        Collections.sort(entries);

        MessageDigest digest = sha256();
        feed(digest, generatorTag);
        feed(digest, Integer.toString(entries.size()));
        for (String entry : entries) {
            feed(digest, entry);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Names a jar carried inside {@code parentJar} by what the enclosing jar's central
     * directory records about it.
     *
     * <p>Nested jars are stored deflated, so the cheap trick that names a whole file -
     * reading its own central directory - cannot be played on one without inflating it
     * first, which is the work being avoided. The enclosing directory already holds the
     * nested jar's uncompressed size and CRC-32, and those name it more tightly than the
     * per-class digest used for a whole file: they cover every byte of it, not only the
     * classes. They also cost nothing extra, being in the same one seek.
     *
     * <p>Two enclosing jars shipping the same dependency at the same place therefore
     * produce the same key and share one stored scan, which is the point - a modern pack
     * carries the same handful of libraries inside dozens of mods. Where the entry sits is
     * part of the key, so a library moved between {@code META-INF/jarjar/} and
     * {@code META-INF/jars/} is renamed rather than confused with itself. Nothing here
     * assumes either directory: both are in use, and the entry is found by its file name
     * wherever it is.
     *
     * <p>What sharing costs is that a CRC-32 collision hands one nested jar another's
     * scan. Two jars have to agree on all three fields to collide, and the first of them
     * is a path carrying the library's name and version, so the pairs at risk are not any
     * two of a pack's ~150 nested jars but two differing builds published under one
     * version - and they must then also match in length and in 32 bits of checksum. The
     * whole-file key makes the same bet per class; this one makes it once for a jar.
     *
     * @param nestedName the nested jar's file name, from {@link #nestedIn}
     * @return the key, or null when the enclosing jar does not hold exactly one entry of
     *         that name - two of them cannot be told apart from here, and a jar that has
     *         to be guessed at is one to scan rather than to remember
     */
    static String ofNested(Path parentJar, String nestedName, String generatorTag)
            throws IOException {
        return ofNestedWithReason(parentJar, nestedName, generatorTag).key();
    }

    /**
     * What {@link #ofNested} returns, or - when it would return {@code null} - which of
     * its three refusals applied. Nothing here changes what {@code ofNested} answers for
     * any input; this only keeps the reason around long enough for {@code ScanCache} to
     * put it in its own log line, without asking either caller to change how it reads a
     * result it already has.
     */
    record NestedKeyResult(String key, String reason) {
    }

    /** Same computation as {@link #ofNested}, with the refusal reason kept alongside it. */
    static NestedKeyResult ofNestedWithReason(Path parentJar, String nestedName, String generatorTag)
            throws IOException {
        String entryName = null;
        long size = 0;
        long crc = 0;
        try (ZipFile zip = new ZipFile(parentJar.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> remaining = zip.entries();
            while (remaining.hasMoreElements()) {
                ZipEntry entry = remaining.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                        || !name.endsWith(JAR_SUFFIX)
                        || !fileName(name).equals(nestedName)) {
                    continue;
                }
                if (entryName != null) {
                    // Two of them cannot be told apart from here, and a jar that has to be
                    // guessed at is one to scan rather than to remember.
                    return new NestedKeyResult(null,
                            "the enclosing jar's central directory has more than one entry named "
                                    + nestedName);
                }
                entryName = name;
                size = entry.getSize();
                crc = entry.getCrc();
            }
        }
        if (entryName == null) {
            return new NestedKeyResult(null,
                    "the enclosing jar's central directory has no entry named " + nestedName);
        }
        if (size < 0 || crc < 0) {
            // A central directory always carries both for an entry that is really there,
            // so this is the same refusal as the one above: we were not told enough to
            // name the jar, and naming it wrong is the one outcome that must not happen.
            return new NestedKeyResult(null,
                    "the enclosing jar's central directory recorded a negative size or CRC for "
                            + nestedName);
        }

        MessageDigest digest = sha256();
        feed(digest, generatorTag);
        feed(digest, NESTED);
        feed(digest, entryName);
        feed(digest, Long.toString(size));
        feed(digest, Long.toString(crc));
        return new NestedKeyResult(HexFormat.of().formatHex(digest.digest()), null);
    }

    /** The last segment of a zip entry name, which always uses {@code /}. */
    private static String fileName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private static void feed(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every conforming JRE has it. Reported as IOException so the caller's single
            // "we could not key this jar, scan it" branch covers this too.
            throw new IOException("SHA-256 is not available", impossible);
        }
    }
}
