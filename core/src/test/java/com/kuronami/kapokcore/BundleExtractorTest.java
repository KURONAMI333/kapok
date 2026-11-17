package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleExtractorTest {
    @TempDir
    Path dir;

    private static final byte[] MOD_A = "mod jar A".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MOD_A2 = "mod jar A, next version".getBytes(StandardCharsets.UTF_8);

    private Path serviceJar(String bundleName, byte[] bundleBytes) throws IOException {
        Path jar = dir.resolve("kapok-" + Integer.toHexString(bundleBytes.length) + ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(out, "META-INF/services/some.Service", "x".getBytes(StandardCharsets.UTF_8));
            put(out, "com/kuronami/kapokcore/Foo.class", new byte[] {1, 2, 3});
            put(out, BundleExtractor.BUNDLE_PREFIX + bundleName, bundleBytes);
            put(out, BundleExtractor.BUNDLE_PREFIX + "notes.txt", "not a jar".getBytes(StandardCharsets.UTF_8));
            put(out, BundleExtractor.BUNDLE_PREFIX + "deeper/other.jar", new byte[] {9});
        }
        return jar;
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    @Test
    void bundleEntriesListsOnlyTopLevelJarsUnderThePrefix() throws IOException {
        Path jar = serviceJar("kapok-mod-0.1.0.jar", MOD_A);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertEquals(List.of(BundleExtractor.BUNDLE_PREFIX + "kapok-mod-0.1.0.jar"),
                    BundleExtractor.bundleEntries(zip));
        }
    }

    @Test
    void firstLaunchWritesTheBundle() throws IOException {
        Path jar = serviceJar("kapok-mod-0.1.0.jar", MOD_A);
        Path target = dir.resolve("out");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<Path> jars = BundleExtractor.materialise(zip, BundleExtractor.bundleEntries(zip), target);
            assertEquals(List.of(target.resolve("kapok-mod-0.1.0.jar")), jars);
            assertArrayEquals(MOD_A, Files.readAllBytes(jars.get(0)));
        }
        try (var siblings = Files.list(target)) {
            assertEquals(1, siblings.count(), "no partial files left behind");
        }
    }

    @Test
    void unchangedBundleIsNotRewritten() throws IOException {
        Path jar = serviceJar("kapok-mod-0.1.0.jar", MOD_A);
        Path target = dir.resolve("out");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<String> entries = BundleExtractor.bundleEntries(zip);
            Path written = BundleExtractor.materialise(zip, entries, target).get(0);
            FileTime old = FileTime.fromMillis(1_000_000_000_000L);
            Files.setLastModifiedTime(written, old);
            BundleExtractor.materialise(zip, entries, target);
            assertEquals(old, Files.getLastModifiedTime(written), "second launch left the file untouched");
        }
    }

    @Test
    void changedBundleIsRewrittenAndStaleSiblingsRemoved() throws IOException {
        Path target = dir.resolve("out");
        Path v1 = serviceJar("kapok-mod-0.1.0.jar", MOD_A);
        try (ZipFile zip = new ZipFile(v1.toFile())) {
            BundleExtractor.materialise(zip, BundleExtractor.bundleEntries(zip), target);
        }
        Path v2 = serviceJar("kapok-mod-0.2.0.jar", MOD_A2);
        try (ZipFile zip = new ZipFile(v2.toFile())) {
            List<Path> jars = BundleExtractor.materialise(zip, BundleExtractor.bundleEntries(zip), target);
            assertArrayEquals(MOD_A2, Files.readAllBytes(jars.get(0)));
        }
        assertFalse(Files.exists(target.resolve("kapok-mod-0.1.0.jar")), "previous version removed");
        assertTrue(Files.exists(target.resolve("kapok-mod-0.2.0.jar")));
    }

    @Test
    void sameNameDifferentBytesIsRewritten() throws IOException {
        Path target = dir.resolve("out");
        Files.createDirectories(target);
        Path stale = target.resolve("kapok-mod-0.1.0.jar");
        try (OutputStream out = Files.newOutputStream(stale)) {
            out.write("corrupted or half-written".getBytes(StandardCharsets.UTF_8));
        }
        Path jar = serviceJar("kapok-mod-0.1.0.jar", MOD_A);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            BundleExtractor.materialise(zip, BundleExtractor.bundleEntries(zip), target);
        }
        assertArrayEquals(MOD_A, Files.readAllBytes(stale));
    }

    @Test
    void matchesComparesSizeAndCrc() throws IOException {
        Path jar = serviceJar("kapok-mod-0.1.0.jar", MOD_A);
        Path same = dir.resolve("same.jar");
        Files.write(same, MOD_A);
        Path sameSize = dir.resolve("sameSize.jar");
        Files.write(sameSize, "mod jar B".getBytes(StandardCharsets.UTF_8));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(BundleExtractor.BUNDLE_PREFIX + "kapok-mod-0.1.0.jar");
            assertTrue(ZipEntryFile.matches(entry, same));
            assertFalse(ZipEntryFile.matches(entry, sameSize), "same length, different bytes");
            assertFalse(ZipEntryFile.matches(entry, dir.resolve("missing.jar")));
        }
    }
}
