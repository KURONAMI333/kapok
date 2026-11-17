package com.kuronami.kapokcore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts the mod jar carried inside this service jar onto the default filesystem, once.
 *
 * <p>The discovery pipeline can mount a jar that lives inside another jar - Jar-in-Jar
 * does exactly that - but a nested path is not a regular file on the default filesystem,
 * which is the one shape {@link ScanCacheKey#readable} refuses. Materialising the bundle
 * under the game directory gives the loader the most ordinary input it knows (a file in
 * a folder, same as {@code mods/}) and lets the scan cache cover Kapok's own classes.
 *
 * <p>"Once" is decided by {@link ZipEntryFile} from the zip's own bookkeeping, so an
 * unchanged bundle costs one CRC over a file of a few hundred kilobytes and no write.
 * Anything else in the target directory that is not the current bundle is a leftover
 * from a previous version and is removed, so two versions of the mod can never be
 * offered to the loader at the same time.
 *
 * <p>Pure {@code java.nio} and {@code java.util.zip}; no loader types, so it is unit
 * tested against real zip files without the game classpath.
 */
final class BundleExtractor {
    /** Directory inside the service jar that holds the mod jar(s). */
    static final String BUNDLE_PREFIX = "META-INF/kapok/";
    private static final String JAR_SUFFIX = ".jar";

    private BundleExtractor() {
    }

    /**
     * @return names of the bundle entries in {@code jar}, in zip order. Empty when the
     *         jar carries nothing under {@link #BUNDLE_PREFIX}.
     */
    static List<String> bundleEntries(ZipFile jar) {
        List<String> names = new ArrayList<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory()
                    && name.startsWith(BUNDLE_PREFIX)
                    && name.endsWith(JAR_SUFFIX)
                    && name.indexOf('/', BUNDLE_PREFIX.length()) < 0) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Materialises the named entries under {@code targetDir} and removes any other
     * {@code .jar} already there.
     *
     * @return the paths of the materialised jars, one per entry, in the same order
     * @throws IOException when the target directory cannot be created or written; the
     *         caller decides whether to fall back to mounting the nested path directly
     */
    static List<Path> materialise(ZipFile jar, List<String> entryNames, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        List<Path> out = new ArrayList<>(entryNames.size());
        List<String> keep = new ArrayList<>(entryNames.size());
        for (String entryName : entryNames) {
            ZipEntry entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IOException("bundle entry vanished: " + entryName);
            }
            String fileName = entryName.substring(BUNDLE_PREFIX.length());
            Path target = targetDir.resolve(fileName);
            if (!ZipEntryFile.matches(entry, target)) {
                ZipEntryFile.write(jar, entry, target);
            }
            out.add(target);
            keep.add(fileName);
        }
        removeStale(targetDir, keep);
        return out;
    }

    private static void removeStale(Path targetDir, List<String> keep) throws IOException {
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(targetDir, "*" + JAR_SUFFIX)) {
            for (Path sibling : siblings) {
                if (!keep.contains(sibling.getFileName().toString())) {
                    Files.deleteIfExists(sibling);
                }
            }
        }
    }
}
