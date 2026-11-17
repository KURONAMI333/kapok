package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanCacheKeyTest {
    private static final String TAG = "neoforge 21.1.247 format 1";

    @TempDir
    Path dir;

    @Test
    void namesTheSameJarTheSameWayTwice() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "same.jar", JarFixtures.entries());

        assertEquals(ScanCacheKey.of(jar, TAG), ScanCacheKey.of(jar, TAG));
    }

    @Test
    void ignoresTheOrderTheEntriesWereWrittenIn() throws IOException {
        LinkedHashMap<String, byte[]> entries = JarFixtures.entries();
        Path forwards = JarFixtures.jarOf(dir, "forwards.jar", entries);
        Path backwards = JarFixtures.jarOf(dir, "backwards.jar", JarFixtures.reversed(entries));

        assertEquals(ScanCacheKey.of(forwards, TAG), ScanCacheKey.of(backwards, TAG));
    }

    @Test
    void changesWhenOneByteOfOneClassChanges() throws IOException {
        LinkedHashMap<String, byte[]> before = JarFixtures.entries();
        Path original = JarFixtures.jarOf(dir, "before.jar", before);

        LinkedHashMap<String, byte[]> after = JarFixtures.entries();
        byte[] body = after.get("com/example/B.class").clone();
        body[0] ^= 0x01;
        after.put("com/example/B.class", body);
        Path edited = JarFixtures.jarOf(dir, "after.jar", after);

        // Without this the test could pass while proving nothing: the key is built from
        // the central directory, so an edit the CRC does not see is an edit the key
        // cannot see either.
        assertNotEquals(
                JarFixtures.crcOf(original, "com/example/B.class"),
                JarFixtures.crcOf(edited, "com/example/B.class"),
                "the edit has to be one the central directory records");

        assertNotEquals(ScanCacheKey.of(original, TAG), ScanCacheKey.of(edited, TAG));
    }

    @Test
    void ignoresEntriesTheScannerWouldNotRead() throws IOException {
        LinkedHashMap<String, byte[]> before = JarFixtures.entries();
        Path original = JarFixtures.jarOf(dir, "plain.jar", before);

        LinkedHashMap<String, byte[]> after = JarFixtures.entries();
        after.put("assets/example/lang/en_us.json",
                "{\"a\":\"b\"}".getBytes(StandardCharsets.UTF_8));
        after.put("assets/example/textures/new.png", new byte[] {1, 2, 3});
        Path retextured = JarFixtures.jarOf(dir, "retextured.jar", after);

        assertEquals(ScanCacheKey.of(original, TAG), ScanCacheKey.of(retextured, TAG));
    }

    @Test
    void countsClassesUnderMetaInfVersions() throws IOException {
        LinkedHashMap<String, byte[]> before = JarFixtures.entries();
        Path original = JarFixtures.jarOf(dir, "single.jar", before);

        LinkedHashMap<String, byte[]> after = JarFixtures.entries();
        after.put("META-INF/versions/17/com/example/A.class",
                "class A17 rebuilt".getBytes(StandardCharsets.UTF_8));
        Path multiRelease = JarFixtures.jarOf(dir, "multirelease.jar", after);

        // The scanner walks the jar root and keeps anything whose file name ends in
        // .class, so a multi-release jar's alternate classes are scanned like any other
        // and have to be part of what names the jar.
        assertNotEquals(ScanCacheKey.of(original, TAG), ScanCacheKey.of(multiRelease, TAG));
    }

    @Test
    void changesWhenTheGeneratorChanges() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "generator.jar", JarFixtures.entries());

        assertNotEquals(
                ScanCacheKey.of(jar, "neoforge 21.1.247 format 1"),
                ScanCacheKey.of(jar, "neoforge 21.1.248 format 1"));
    }

    @Test
    void namesTheSameNestedJarTheSameWayTwice() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "parent.jar");

        assertEquals(ScanCacheKey.ofNested(parent, "lib.jar", TAG),
                ScanCacheKey.ofNested(parent, "lib.jar", TAG));
    }

    @Test
    void changesWhenTheNestedJarChanges() throws IOException {
        Path before = carrying("META-INF/jarjar/lib.jar", "before.jar");

        LinkedHashMap<String, byte[]> rebuilt = JarFixtures.entries();
        rebuilt.put("com/example/B.class", "class B rebuilt".getBytes(StandardCharsets.UTF_8));
        Path after = JarFixtures.carrying(dir, "after.jar", nested(
                "META-INF/jarjar/lib.jar", JarFixtures.jarOf(dir, "rebuilt-source.jar", rebuilt)));

        // Without these the test could pass while proving nothing. The key is built from
        // what the enclosing jar's central directory says about the entry, so the size and
        // the CRC have to be recorded there and have to have moved.
        assertTrue(JarFixtures.sizeOf(before, "META-INF/jarjar/lib.jar") >= 0,
                "a central directory records the uncompressed size of a deflated entry");
        assertNotEquals(
                JarFixtures.crcOf(before, "META-INF/jarjar/lib.jar"),
                JarFixtures.crcOf(after, "META-INF/jarjar/lib.jar"),
                "the edit has to be one the enclosing central directory records");

        assertNotEquals(ScanCacheKey.ofNested(before, "lib.jar", TAG),
                ScanCacheKey.ofNested(after, "lib.jar", TAG));
    }

    @Test
    void findsANestedJarWhereverItIsPut() throws IOException {
        // Both directories are in use, and a jar that only looked in one of them would
        // quietly cache nothing for a quarter of what ships nested.
        Path underJars = carrying("META-INF/jars/lib.jar", "elsewhere.jar");

        assertNotNull(ScanCacheKey.ofNested(underJars, "lib.jar", TAG));
    }

    @Test
    void refusesAJarThatCarriesTwoOfTheSameName() throws IOException {
        LinkedHashMap<String, Path> twice = new LinkedHashMap<>();
        twice.put("META-INF/jarjar/lib.jar", JarFixtures.jarOf(dir, "lib.jar", JarFixtures.entries()));
        twice.put("META-INF/jars/lib.jar", JarFixtures.jarOf(dir, "other-lib.jar",
                JarFixtures.reversed(JarFixtures.entries())));
        Path parent = JarFixtures.carrying(dir, "ambiguous.jar", twice);

        // Nothing is wrong with either jar; there is simply no way from here to tell which
        // one is being read, and answering with the wrong stored scan is the one outcome
        // that has to be impossible.
        assertNull(ScanCacheKey.ofNested(parent, "lib.jar", TAG));
    }

    @Test
    void refusesANestedNameTheEnclosingJarDoesNotCarry() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");

        assertNull(ScanCacheKey.ofNested(parent, "absent.jar", TAG));
    }

    @Test
    void reasonTellsADuplicateNameApartFromAnAbsentOne() throws IOException {
        LinkedHashMap<String, Path> twice = new LinkedHashMap<>();
        twice.put("META-INF/jarjar/lib.jar", JarFixtures.jarOf(dir, "lib.jar", JarFixtures.entries()));
        twice.put("META-INF/jars/lib.jar", JarFixtures.jarOf(dir, "other-lib.jar",
                JarFixtures.reversed(JarFixtures.entries())));
        Path duplicateParent = JarFixtures.carrying(dir, "ambiguous.jar", twice);
        Path absentParent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");

        ScanCacheKey.NestedKeyResult duplicate =
                ScanCacheKey.ofNestedWithReason(duplicateParent, "lib.jar", TAG);
        ScanCacheKey.NestedKeyResult absent =
                ScanCacheKey.ofNestedWithReason(absentParent, "absent.jar", TAG);

        assertNull(duplicate.key());
        assertNull(absent.key());
        assertNotEquals(duplicate.reason(), absent.reason(),
                "two different refusals must not read as the same one in a log");
        assertTrue(duplicate.reason().contains("more than one entry"), duplicate.reason());
        assertTrue(absent.reason().contains("no entry"), absent.reason());
    }

    @Test
    void reasonIsNullWhenAKeyIsProduced() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");

        ScanCacheKey.NestedKeyResult result = ScanCacheKey.ofNestedWithReason(parent, "lib.jar", TAG);

        assertNotNull(result.key());
        assertNull(result.reason());
    }

    @Test
    void tellsANestedJarApartFromAWholeOne() throws IOException {
        Path lib = JarFixtures.jarOf(dir, "lib.jar", JarFixtures.entries());
        Path parent = JarFixtures.carrying(dir, "parent.jar",
                nested("META-INF/jarjar/lib.jar", lib));

        assertNotEquals(ScanCacheKey.of(lib, TAG), ScanCacheKey.ofNested(parent, "lib.jar", TAG));
    }

    @Test
    void recognisesAJarNestedOneLevelDown() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");

        try (FileSystem inside = FileSystems.newFileSystem(parent)) {
            assertEquals("lib.jar",
                    ScanCacheKey.nestedIn(parent, inside.getPath("/META-INF/jarjar/lib.jar")));
        }
    }

    @Test
    void doesNotTreatAWholeJarAsANestedOne() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");
        Path onDisk = JarFixtures.jarOf(dir, "standalone.jar", JarFixtures.entries());

        // A mod in a file of its own is named from its own central directory. Taking the
        // nested branch for one would name it from an enclosing jar it has nothing to do
        // with.
        assertNull(ScanCacheKey.nestedIn(parent, onDisk));
    }

    @Test
    void doesNotReachPastOneLevelOfNesting() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");
        Path exploded = Files.createDirectory(dir.resolve("not-a-file"));

        try (FileSystem inside = FileSystems.newFileSystem(parent)) {
            Path deeper = inside.getPath("/META-INF/jarjar/lib.jar");

            // A jar two levels down has an enclosing jar that is itself inside a
            // filesystem, so it has no central directory anyone here can read. That is the
            // whole of what keeps this to one level.
            assertNull(ScanCacheKey.nestedIn(deeper, deeper));
            assertNull(ScanCacheKey.nestedIn(exploded, deeper));
            assertNull(ScanCacheKey.nestedIn(null, deeper));
        }
    }

    @Test
    void refusesAPathThatDoesNotNameAJar() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar", "carrier.jar");

        try (FileSystem inside = FileSystems.newFileSystem(parent)) {
            // The name comes from the path, and the shape that carries it is a special
            // case of one filesystem implementation. If that ever stops holding, what
            // arrives is a root with no file name - and the answer has to be "do not
            // cache this", never "cache it under some other name".
            assertNull(ScanCacheKey.nestedIn(parent, inside.getPath("/")));
            assertNull(ScanCacheKey.nestedIn(parent, inside.getPath("/com/example/A.class")));
        }
    }

    /** An enclosing jar holding one nested jar at {@code at}. */
    private Path carrying(String at, String outerName) throws IOException {
        return JarFixtures.carrying(dir, outerName, nested(at,
                JarFixtures.jarOf(dir, "nested-source.jar", JarFixtures.entries())));
    }

    private static LinkedHashMap<String, Path> nested(String at, Path jar) {
        LinkedHashMap<String, Path> one = new LinkedHashMap<>();
        one.put(at, jar);
        return one;
    }

    @Test
    void readsOnlyRegularFilesOnTheDefaultFilesystem() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "readable.jar", JarFixtures.entries());
        assertTrue(ScanCacheKey.readable(jar));

        Path directory = Files.createDirectory(dir.resolve("exploded"));
        assertFalse(ScanCacheKey.readable(directory), "a mod loaded from directories has no"
                + " central directory, and is the shape that arrives as several paths at once");

        assertFalse(ScanCacheKey.readable(null));

        try (FileSystem inside = FileSystems.newFileSystem(jar)) {
            assertFalse(ScanCacheKey.readable(inside.getPath("/com/example/A.class")),
                    "a nested jar cannot be read without inflating the one around it");
        }
    }
}
