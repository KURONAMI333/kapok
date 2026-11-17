package com.kuronami.kapokcore.jij;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kuronami.kapokcore.Notes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NestedJarStoreTest {
    @TempDir
    Path dir;

    private final Lines lines = new Lines();
    private final List<NestedJarStore> stores = new ArrayList<>();

    /**
     * Every store built here, so that the enclosing jars they opened are let go of at the
     * end of the test. A launch does that in {@code finish}; a test that is checking what
     * happens before {@code finish} would otherwise leave a mod jar open, and Windows will
     * not delete a file that something has open.
     */
    @AfterEach
    void letGoOfTheJars() {
        stores.forEach(NestedJarStore::closeEnclosingJars);
    }

    private NestedJarStore store() {
        return store(root());
    }

    private NestedJarStore store(Path root) {
        return store(root, real -> new java.util.zip.ZipFile(real.toFile(), StandardCharsets.UTF_8));
    }

    private NestedJarStore store(Path root, NestedJarStore.JarOpener opener) {
        NestedJarStore made = new NestedJarStore(root, lines, opener);
        stores.add(made);
        return made;
    }

    private static final byte[] LIBRARY = "a whole library jar, more or less"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWER = "a whole library jar, one version later"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAME_LENGTH = "B whole library jar, more or less"
            .getBytes(StandardCharsets.UTF_8);

    private Path root() {
        return dir.resolve("nested");
    }

    /** A jar carrying the given entries, the way jar-in-jar ships dependencies. */
    private Path modJar(String name, Map<String, byte[]> nested) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (var entry : nested.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static LinkedHashMap<String, byte[]> one(String entryName, byte[] bytes) {
        LinkedHashMap<String, byte[]> nested = new LinkedHashMap<>();
        nested.put(entryName, bytes);
        return nested;
    }

    @Test
    void theCopyKeepsTheNameItHadInsideTheMod() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store();

        Path copy = store.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        assertEquals("library-4.5.6.jar", copy.getFileName().toString());
        assertArrayEquals(LIBRARY, Files.readAllBytes(copy));
    }

    @Test
    void theCopyIsAnOrdinaryFileUnderTheStoresOwnDirectory() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store();

        Path copy = store.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        assertTrue(Files.isRegularFile(copy));
        assertEquals(root(), copy.getParent().getParent());
        assertTrue(NestedJarKey.looksLikeKey(copy.getParent().getFileName().toString()));
    }

    @Test
    void theSecondLaunchWritesNothing() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));

        Path first = store()
                .materialise(mod, "META-INF/jarjar/library-4.5.6.jar");
        FileTime written = Files.getLastModifiedTime(first);

        NestedJarStore next = store();
        Path again = next.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");
        next.finish(true);

        assertEquals(first, again);
        assertEquals(written, Files.getLastModifiedTime(again));
        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 0 written, 1 reused, 0 mounted in place"), lines.info);
    }

    @Test
    void anUpdatedModRewritesItsCopyUnderANewKey() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore launch = store();
        Path old = launch.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        // The mod is updated between one launch and the next, so the launch that read the
        // old one has ended and is no longer holding it open.
        launch.closeEnclosingJars();
        Files.delete(mod);
        modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", NEWER));
        NestedJarStore next = store();
        Path fresh = next.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        assertFalse(old.equals(fresh));
        assertArrayEquals(NEWER, Files.readAllBytes(fresh));
        assertTrue(Files.exists(old), "the old copy is still there until the sweep");
        assertEquals(1, next.sweep());
        assertFalse(Files.exists(old.getParent()));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void aLibraryOfTheSameLengthButDifferentBytesIsRewritten() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore launch = store();
        Path old = launch.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");
        launch.closeEnclosingJars();

        // The copy is checked by length rather than by reading it back, so this is the
        // case that check cannot see: same length, different bytes. The key is what keeps
        // them apart, because it carries the entry's CRC-32 as well as its size.
        assertEquals(LIBRARY.length, SAME_LENGTH.length);
        Files.delete(mod);
        modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", SAME_LENGTH));
        Path fresh = store().materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        assertFalse(old.equals(fresh));
        assertArrayEquals(SAME_LENGTH, Files.readAllBytes(fresh));
    }

    @Test
    void aLibraryStillInstalledSurvivesTheSweep() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store();
        Path copy = store.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        assertEquals(0, store.sweep());
        assertTrue(Files.exists(copy));
    }

    @Test
    void theSweepLeavesAnythingThatIsNotAKeyDirectoryAlone() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store();
        store.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");
        Path stranger = root().resolve("notes.txt");
        Files.writeString(stranger, "someone else's file");
        Path folder = root().resolve("bundle");
        Files.createDirectories(folder);

        assertEquals(0, store.sweep());
        assertTrue(Files.exists(stranger));
        assertTrue(Files.exists(folder));
    }

    @Test
    void aSweepBeforeAnythingWasWrittenIsNotAFailure() {
        assertEquals(0, store().sweep());
    }

    @Test
    void twoModsCarryingTheSameLibraryGetOneCopyEach() throws IOException {
        Path first = modJar("first-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        Path second = modJar("second-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store();

        Path fromFirst = store.materialise(first, "META-INF/jarjar/library-4.5.6.jar");
        Path fromSecond = store.materialise(second, "META-INF/jarjar/library-4.5.6.jar");

        // Not shared on purpose: the loader holds both open for the whole session, and one
        // of the two mods being updated must not disturb the file the other is reading.
        assertFalse(fromFirst.equals(fromSecond));
        assertArrayEquals(LIBRARY, Files.readAllBytes(fromFirst));
        assertArrayEquals(LIBRARY, Files.readAllBytes(fromSecond));
        assertEquals(0, store.sweep());
    }

    @Test
    void aLibraryUnderEitherDirectoryIsMaterialisedTheSameWay() throws IOException {
        LinkedHashMap<String, byte[]> nested = new LinkedHashMap<>();
        nested.put("META-INF/jarjar/one.jar", LIBRARY);
        nested.put("META-INF/jars/two.jar", NEWER);
        Path mod = modJar("some-mod.jar", nested);
        NestedJarStore store = store();

        assertEquals("one.jar",
                store.materialise(mod, "META-INF/jarjar/one.jar").getFileName().toString());
        assertEquals("two.jar",
                store.materialise(mod, "META-INF/jars/two.jar").getFileName().toString());
    }

    @Test
    void aGameDirectorySpeltWithADotStillWorks() throws IOException {
        // A development launch has "." as its game directory, so the store's root arrives
        // carrying a redundant segment. Nothing about deciding where a copy goes may look
        // at a normalised form of the path and compare it with the unnormalised one.
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store(dir.resolve(".").resolve("nested"));

        Path copy = store.materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        assertArrayEquals(LIBRARY, Files.readAllBytes(copy));
        assertEquals("library-4.5.6.jar", copy.getFileName().toString());
    }

    @Test
    void anEntryThatIsNotThereIsReportedRatherThanGuessedAt() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        NestedJarStore store = store();

        assertThrows(IOException.class,
                () -> store.materialise(mod, "META-INF/jarjar/gone.jar"));
    }

    @Test
    void anEntryNameThatWouldLandOutsideItsOwnDirectoryIsRefused() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/..", LIBRARY));
        NestedJarStore store = store();

        assertThrows(IOException.class, () -> store.materialise(mod, "META-INF/jarjar/.."));
    }

    @Test
    void whatWasDoneIsSaidOnceAndNamesEveryNumber() throws IOException {
        LinkedHashMap<String, byte[]> nested = new LinkedHashMap<>();
        nested.put("META-INF/jarjar/one.jar", LIBRARY);
        nested.put("META-INF/jarjar/two.jar", NEWER);
        Path mod = modJar("some-mod.jar", nested);

        NestedJarStore store = store();
        store.materialise(mod, "META-INF/jarjar/one.jar");
        store.materialise(mod, "META-INF/jarjar/two.jar");
        // Detection and selection each ask for the same jar, and the second ask is a reuse.
        store.materialise(mod, "META-INF/jarjar/one.jar");
        // One more that the locator could not hand over, mounted where it lies instead.
        store.mountedInPlace();
        store.finish(true);

        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 2 written, 1 reused, 1 mounted in place"), lines.info);
    }

    @Test
    void aRemovedModIsNamedInTheLineThatSweptIt() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        store().materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        NestedJarStore next = store();
        next.finish(true);

        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 0 written, 0 reused, 0 mounted in place,"
                + " 1 no longer installed and removed"), lines.info);
    }

    @Test
    void aLaunchThatFellBackOnEveryJarSweepsNothing() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        Path copy = store()
                .materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        // What the first launch of this feature actually did: every jar mounted where it
        // lay, so the store was asked for nothing and knows of no key that is in use.
        NestedJarStore next = store();
        next.mountedInPlace();
        next.finish(true);

        assertTrue(Files.exists(copy), "the copies are still installed, whatever this"
                + " launch managed to ask for");
        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 0 written, 0 reused, 1 mounted in place"), lines.info);
    }

    @Test
    void aLaunchWhereOneJarFellBackStillSweepsNothing() throws IOException {
        LinkedHashMap<String, byte[]> nested = new LinkedHashMap<>();
        nested.put("META-INF/jarjar/one.jar", LIBRARY);
        nested.put("META-INF/jarjar/two.jar", NEWER);
        Path mod = modJar("some-mod.jar", nested);
        NestedJarStore first = store();
        Path both = first.materialise(mod, "META-INF/jarjar/one.jar");
        first.materialise(mod, "META-INF/jarjar/two.jar");

        NestedJarStore next = store();
        next.materialise(mod, "META-INF/jarjar/two.jar");
        next.mountedInPlace();
        next.finish(true);

        // The one that fell back is missing from the set of keys in use, and there is
        // nothing to say which one it was.
        assertTrue(Files.exists(both));
    }

    @Test
    void aScanThatDidNotFinishSweepsNothing() throws IOException {
        Path mod = modJar("some-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        Path copy = store()
                .materialise(mod, "META-INF/jarjar/library-4.5.6.jar");

        NestedJarStore next = store();
        next.finish(false);

        assertTrue(Files.exists(copy), "a scan that threw stopped asking, so the keys it"
                + " never reached are not gone");
        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 0 written, 0 reused, 0 mounted in place"), lines.info);
    }

    @Test
    void theEnclosingJarIsOpenedOnceHoweverManyJarsItCarries() throws IOException {
        LinkedHashMap<String, byte[]> nested = new LinkedHashMap<>();
        nested.put("META-INF/jarjar/one.jar", LIBRARY);
        nested.put("META-INF/jarjar/two.jar", NEWER);
        Path mod = modJar("some-mod.jar", nested);
        List<Path> opens = new ArrayList<>();
        NestedJarStore store = store(root(), real -> {
            opens.add(real);
            return new java.util.zip.ZipFile(real.toFile(), StandardCharsets.UTF_8);
        });

        store.materialise(mod, "META-INF/jarjar/one.jar");
        store.materialise(mod, "META-INF/jarjar/two.jar");
        // Jar-in-Jar asks a second time for everything it selects.
        store.materialise(mod, "META-INF/jarjar/one.jar");

        assertEquals(List.of(mod.toRealPath()), opens);
    }

    @Test
    void twoModsAreTwoOpensAndBothAreClosedAtTheEnd() throws IOException {
        Path first = modJar("first-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        Path second = modJar("second-mod.jar", one("META-INF/jarjar/library-4.5.6.jar", LIBRARY));
        List<Path> opens = new ArrayList<>();
        List<Path> closes = new ArrayList<>();
        NestedJarStore store = store(root(), real -> {
            opens.add(real);
            return new java.util.zip.ZipFile(real.toFile(), StandardCharsets.UTF_8) {
                @Override
                public void close() throws IOException {
                    closes.add(real);
                    super.close();
                }
            };
        });

        store.materialise(first, "META-INF/jarjar/library-4.5.6.jar");
        store.materialise(second, "META-INF/jarjar/library-4.5.6.jar");
        assertEquals(List.of(first.toRealPath(), second.toRealPath()), opens);
        assertTrue(closes.isEmpty(), "nothing is closed while the scan may still ask");

        store.finish(true);

        // Both, in whatever order they come out of the map.
        assertEquals(Set.of(first.toRealPath(), second.toRealPath()), Set.copyOf(closes));
        assertEquals(2, closes.size());
    }

    /** Captures what would have been written to the log. */
    private static final class Lines implements Notes {
        final List<String> info = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        @Override
        public boolean info(String message) {
            info.add(message);
            return true;
        }

        @Override
        public boolean detailed() {
            return true;
        }

        @Override
        public void detail(String message) {
            details.add(message);
        }

        @Override
        public void trouble(String message, Throwable cause) {
            info.add(message);
        }
    }
}
