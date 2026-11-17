package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Type;

class ScanCacheTest {
    private static final String TAG = "neoforge 21.1.247 format 1";

    @TempDir
    Path dir;

    private final RecordingNotes notes = new RecordingNotes();

    @Test
    void scansOnceAndThenNeverAgain() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();

        ModFileScanData first = cache(store).compile(jar, () -> null, scanning(scans));
        assertEquals(1, scans.get());

        ScanCache second = cache(store);
        ModFileScanData restored = second.compile(jar, () -> null, scanning(scans));

        assertEquals(1, scans.get(), "the second launch must not read the classes again");
        assertEquals(1, second.reusedCount());
        assertEquals(List.of(), ScanDataComparison.differences(first, restored));
    }

    @Test
    void scansAgainWhenAClassChanges() throws IOException {
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        LinkedHashMap<String, byte[]> entries = JarFixtures.entries();
        Path jar = JarFixtures.jarOf(dir, "mod.jar", entries);
        cache(store).compile(jar, () -> null, scanning(scans));

        entries.put("com/example/B.class", "class B rebuilt".getBytes(StandardCharsets.UTF_8));
        Path rebuilt = JarFixtures.jarOf(dir, "mod-rebuilt.jar", entries);
        cache(store).compile(rebuilt, () -> null, scanning(scans));

        assertEquals(2, scans.get());
    }

    @Test
    void scansAgainWhenTheLoaderChanges() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();

        new ScanCache(store, "neoforge 21.1.247 format 1", notes, false)
                .compile(jar, () -> null, scanning(scans));
        new ScanCache(store, "neoforge 21.1.248 format 1", notes, false)
                .compile(jar, () -> null, scanning(scans));

        assertEquals(2, scans.get());
    }

    @Test
    void leavesJarsItCannotKeyAlone() throws IOException {
        Path exploded = Files.createDirectory(dir.resolve("exploded"));
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();

        cache(store).compile(exploded, () -> null, scanning(scans));
        cache(store).compile(exploded, () -> null, scanning(scans));

        assertEquals(2, scans.get());
        assertFalse(Files.exists(store), "nothing to store means nothing written");
    }

    @Test
    void doesNothingWhenThereIsNowhereToKeepAnything() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        AtomicInteger scans = new AtomicInteger();
        ScanCache nowhere = ScanCache.under(null, "21.1.247", notes);

        nowhere.compile(jar, () -> null, scanning(scans));

        assertEquals(1, scans.get());
        assertFalse(nowhere.usable());
        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("not cacheable (no cache directory for this launch)")
                        && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void logsWhyAJarThatIsNotARegularFileIsNotCacheable() throws IOException {
        Path exploded = Files.createDirectory(dir.resolve("exploded"));
        Path store = dir.resolve("store");

        cache(store).compile(exploded, () -> null, ScanCacheTest::scanned);

        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("not cacheable (not a regular file on the default filesystem)")
                        && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void logsWhyNothingIsCachedOnceTheCacheHasTurnedItselfOff() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        ScanCache cache = cache(store);
        cache.compile(jar, () -> null, ScanCacheTest::scanned);

        // Fails to announce the reuse below, which is what turns the cache off.
        notes.infoLands = false;
        cache.compile(jar, () -> null, ScanCacheTest::scanned);
        assertFalse(cache.usable(), "the failed announcement must have turned it off already");

        notes.infoLands = true;
        notes.details.clear();
        cache.compile(jar, () -> null, ScanCacheTest::scanned);

        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("not cacheable (the cache turned itself off earlier this launch)")
                        && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void logsWhyAFileThatIsNotAZipIsNotCacheable() throws IOException {
        Path notAZip = Files.write(dir.resolve("broken.jar"),
                "not a zip file".getBytes(StandardCharsets.UTF_8));
        Path store = dir.resolve("store");

        cache(store).compile(notAZip, () -> null, ScanCacheTest::scanned);

        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("not cacheable (could not be keyed (") && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void logsHowManyClassesWereStoredAndRestored() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");

        cache(store).compile(jar, () -> null, ScanCacheTest::scanned);
        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("stored as") && line.contains("classes=1")),
                notes.details.toString());

        notes.details.clear();
        cache(store).compile(jar, () -> null, ScanCacheTest::scanned);
        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("restored from") && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void scansAndReplacesAnEntryItCannotRead() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        cache(store).compile(jar, () -> null, scanning(scans));

        Path entry = onlyEntry(store);
        Files.write(entry, "shredded".getBytes(StandardCharsets.UTF_8));

        ScanCache after = cache(store);
        after.compile(jar, () -> null, scanning(scans));

        assertEquals(2, scans.get());
        assertEquals(1, notes.troubles.size(), notes.troubles.toString());
        assertTrue(notes.troubles.getFirst().contains("could not read"), notes.troubles.toString());
        assertEquals(1, after.storedCount(), "the unreadable entry is replaced, not left to rot");
    }

    @Test
    void keepsScanningWhenItCannotWrite() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        // A file where the directory would go: creating the directory fails, and so does
        // everything downstream of it.
        Path store = Files.writeString(dir.resolve("store"), "not a directory");
        AtomicInteger scans = new AtomicInteger();

        ScanCache cache = cache(store);
        cache.compile(jar, () -> null, scanning(scans));
        cache.compile(jar, () -> null, scanning(scans));

        assertEquals(2, scans.get());
        assertEquals(0, cache.storedCount());
        assertEquals(1, notes.troubles.size(), "one line per failure, not one per jar");
        assertTrue(notes.troubles.getFirst().contains("could not store"), notes.troubles.toString());
    }

    @Test
    void stopsUsingStoredScansWhenItCannotSaySoInTheLog() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        cache(store).compile(jar, () -> null, scanning(scans));

        ScanCache silenced = cache(store);
        notes.infoLands = false;
        silenced.compile(jar, () -> null, scanning(scans));

        assertEquals(2, scans.get(), "a jar nobody can see us skip is a jar we scan");
        assertFalse(silenced.usable());
    }

    @Test
    void verifyModeScansAnywayAndReportsAMatch() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        cache(store).compile(jar, () -> null, scanning(scans));

        ScanCache verifying = new ScanCache(store, TAG, notes, true);
        ModFileScanData answer = verifying.compile(jar, () -> null, scanning(scans));

        assertEquals(2, scans.get(), "verification is worth nothing unless it scans for real");
        assertTrue(notes.info.stream().anyMatch(line -> line.contains("restored scan matches")),
                notes.info.toString());
        assertEquals(1, verifying.reusedCount());
        assertEquals(List.of(), ScanDataComparison.differences(scanned(), answer));
    }

    @Test
    void verifyModeSaysSoOnALaunchWithNothingStoredYet() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        AtomicInteger scans = new AtomicInteger();

        // The launch that fills an empty directory restores nothing and so compares
        // nothing. Silence here would look exactly like a flag that never took effect,
        // which is how somebody concludes the feature is not implemented.
        new ScanCache(dir.resolve("store"), TAG, notes, true)
                .compile(jar, () -> null, scanning(scans));

        assertEquals(1, scans.get());
        assertTrue(notes.info.stream().anyMatch(line -> line.contains("is verifying its stored scans")),
                notes.info.toString());
        assertTrue(notes.info.stream().anyMatch(line -> line.contains("nothing to verify")),
                notes.info.toString());
    }

    @Test
    void verifyModeNamesWhatDisagrees() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        cache(store).compile(jar, () -> null, ScanCacheTest::scanned);

        ScanCache verifying = new ScanCache(store, TAG, notes, true);
        verifying.compile(jar, () -> null, ScanCacheTest::scannedDifferently);

        assertTrue(notes.info.stream().anyMatch(line -> line.contains("difference(s)")),
                notes.info.toString());
        assertTrue(notes.info.stream().anyMatch(line -> line.contains("com/example/Gamma")),
                notes.info.toString());
    }

    @Test
    void verifyModeAnswersFromTheStoredScan() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        cache(store).compile(jar, () -> null, ScanCacheTest::scanned);

        ModFileScanData sentinel = scanned();
        ScanCache verifying = new ScanCache(store, TAG, notes, true);
        ModFileScanData answer = verifying.compile(jar, () -> null, () -> sentinel);

        // Verification that hands back the fresh scan would prove nothing about what the
        // rest of the launch is given.
        assertNotSame(sentinel, answer);
    }

    @Test
    void restoresTheLiveModFileInfo() throws IOException {
        Path jar = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path store = dir.resolve("store");
        cache(store).compile(jar, () -> null, ScanCacheTest::scanned);

        ModFileScanData restored = cache(store).compile(jar, () -> null, ScanCacheTest::scanned);

        assertEquals(1, restored.getIModInfoData().size());
    }

    @Test
    void scansANestedModOnceAndThenNeverAgain() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar");
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();

        ModFileScanData first = cache(store).compile(parent, "lib.jar", () -> null, scanning(scans));
        assertEquals(1, scans.get());

        ScanCache second = cache(store);
        ModFileScanData restored = second.compile(parent, "lib.jar", () -> null, scanning(scans));

        assertEquals(1, scans.get(), "the second launch must not read the nested classes again");
        assertEquals(1, second.reusedCount());
        assertEquals(List.of(), ScanDataComparison.differences(first, restored));
    }

    @Test
    void twoModsCarryingTheSameLibraryStoreOneScan() throws IOException {
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        Path lib = JarFixtures.jarOf(dir, "lib-source.jar", JarFixtures.entries());

        cache(store).compile(JarFixtures.carrying(dir, "one.jar", at("META-INF/jarjar/lib.jar", lib)),
                "lib.jar", () -> null, scanning(scans));
        cache(store).compile(JarFixtures.carrying(dir, "two.jar", at("META-INF/jarjar/lib.jar", lib)),
                "lib.jar", () -> null, scanning(scans));

        // The key is a fingerprint of the nested jar rather than of where it came from, so
        // the same library shipped inside a dozen mods costs one scan, not a dozen.
        assertEquals(1, scans.get());
        assertEquals(1, storedEntries(store));
    }

    @Test
    void scansAgainWhenTheNestedModChanges() throws IOException {
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        LinkedHashMap<String, byte[]> entries = JarFixtures.entries();

        cache(store).compile(
                JarFixtures.carrying(dir, "before.jar", at("META-INF/jarjar/lib.jar",
                        JarFixtures.jarOf(dir, "lib-before.jar", entries))),
                "lib.jar", () -> null, scanning(scans));

        entries.put("com/example/B.class", "class B rebuilt".getBytes(StandardCharsets.UTF_8));
        cache(store).compile(
                JarFixtures.carrying(dir, "after.jar", at("META-INF/jarjar/lib.jar",
                        JarFixtures.jarOf(dir, "lib-after.jar", entries))),
                "lib.jar", () -> null, scanning(scans));

        assertEquals(2, scans.get());
    }

    @Test
    void aNestedModTheEnclosingJarCannotNameIsScanned() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar");
        AtomicInteger scans = new AtomicInteger();
        Path store = dir.resolve("store");

        cache(store).compile(parent, "absent.jar", () -> null, scanning(scans));
        cache(store).compile(parent, "absent.jar", () -> null, scanning(scans));

        assertEquals(2, scans.get(), "a jar we cannot name is a jar we scan");
        assertFalse(Files.exists(store), "and nothing is stored under a name we could not build");
        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("not cacheable (could not be keyed (the enclosing jar's central"
                                + " directory has no entry named absent.jar))")
                        && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void logsWhyANestedModWithADuplicateNameIsNotCacheable() throws IOException {
        Path store = dir.resolve("store");
        LinkedHashMap<String, Path> twice = new LinkedHashMap<>();
        twice.put("META-INF/jarjar/lib.jar", JarFixtures.jarOf(dir, "lib.jar", JarFixtures.entries()));
        twice.put("META-INF/jars/lib.jar", JarFixtures.jarOf(dir, "other-lib.jar",
                JarFixtures.reversed(JarFixtures.entries())));
        Path parent = JarFixtures.carrying(dir, "ambiguous.jar", twice);

        cache(store).compile(parent, "lib.jar", () -> null, ScanCacheTest::scanned);

        assertTrue(notes.details.stream().anyMatch(line ->
                line.contains("not cacheable (could not be keyed (the enclosing jar's central"
                                + " directory has more than one entry named lib.jar))")
                        && line.contains("classes=1")),
                notes.details.toString());
    }

    @Test
    void verifyModeReportsANestedModByItsOwnName() throws IOException {
        Path parent = carrying("META-INF/jarjar/lib.jar");
        Path store = dir.resolve("store");
        AtomicInteger scans = new AtomicInteger();
        cache(store).compile(parent, "lib.jar", () -> null, scanning(scans));

        ScanCache verifying = new ScanCache(store, TAG, notes, true);
        verifying.compile(parent, "lib.jar", () -> null, scanning(scans));

        assertEquals(2, scans.get(), "verification is worth nothing unless it scans for real");
        // Naming the jar it arrived in would say the enclosing mod was verified, which is
        // the one thing this launch did not do.
        assertTrue(notes.info.stream().anyMatch(
                line -> line.contains("verified lib.jar") && line.contains("restored scan matches")),
                notes.info.toString());
    }

    @Test
    void aLaunchThatReusesANestedScanSaysSoEvenAfterAWholeJarDid() throws IOException {
        Path store = dir.resolve("store");
        Path standalone = JarFixtures.jarOf(dir, "mod.jar", JarFixtures.entries());
        Path parent = carrying("META-INF/jarjar/lib.jar");
        cache(store).compile(standalone, () -> null, ScanCacheTest::scanned);
        cache(store).compile(parent, "lib.jar", () -> null, ScanCacheTest::scanned);

        ScanCache second = cache(store);
        second.compile(standalone, () -> null, ScanCacheTest::scanned);
        second.compile(parent, "lib.jar", () -> null, ScanCacheTest::scanned);

        // Mods in files of their own are read first and would take the only announcement,
        // which would leave a launch where nothing nested was restored reading exactly
        // like one where all of it was.
        assertTrue(notes.info.stream().anyMatch(line -> line.contains("shipped inside another")
                && line.contains("lib.jar") && line.contains("carrier.jar")),
                notes.info.toString());
    }

    @Test
    void housekeepingRemovesTemporaryCopiesOfALaunchThatWasKilled() throws IOException {
        Path store = Files.createDirectory(dir.resolve("store"));
        Path killed = Files.createTempFile(store, "partial", ".agb");
        Path running = Files.createTempFile(store, "partial", ".agb");
        Path stored = Files.write(store.resolve("kept.agb"), new byte[] {1, 2, 3});
        Files.setLastModifiedTime(killed, FileTime.fromMillis(System.currentTimeMillis() - 2 * 60 * 60 * 1000));

        ScanCache.housekeeping(store, notes);

        // A process killed outright never reaches the finally that deletes its own copy.
        assertFalse(Files.exists(killed), "a copy from two hours ago is a leftover");
        assertTrue(Files.exists(running), "one being written right now belongs to a running game");
        assertTrue(Files.exists(stored), "a stored scan is not a temporary copy");
        assertTrue(notes.details.stream().anyMatch(line -> line.contains("removed 1 temporary file(s)")),
                notes.details.toString());
    }

    @Test
    void housekeepingLeavesANestedJarWhoseOwnNameStartsWithPartial() throws IOException {
        Path store = Files.createDirectory(dir.resolve("store"));
        Path entryDir = Files.createDirectory(store.resolve("nested-key"));
        Path realCopy = Files.write(entryDir.resolve("partialfoo-1.0.jar"), new byte[] {1});
        Path leftover = Files.createTempFile(entryDir, "partial", ".jar");
        long old = System.currentTimeMillis() - 2 * 60 * 60 * 1000;
        Files.setLastModifiedTime(realCopy, FileTime.fromMillis(old));
        Files.setLastModifiedTime(leftover, FileTime.fromMillis(old));

        ScanCache.housekeeping(store, notes);

        assertTrue(Files.exists(realCopy), "a bundled jar named partial... is a stored copy, not a temporary file");
        assertFalse(Files.exists(leftover), "the temporary copy next to it is a leftover");
    }

    @Test
    void housekeepingDropsOnlyScansNoLaunchHasReadInAMonth() throws IOException {
        Path store = Files.createDirectory(dir.resolve("store"));
        long now = System.currentTimeMillis();
        Path unused = Files.write(store.resolve("unused.agb"), new byte[] {1});
        Files.setLastModifiedTime(unused, FileTime.fromMillis(now - ScanCache.UNUSED_MILLIS - 60_000));
        Path recent = Files.write(store.resolve("recent.agb"), new byte[] {1});
        Files.setLastModifiedTime(recent, FileTime.fromMillis(now - 24L * 60 * 60 * 1000));

        ScanCache.housekeeping(store, notes);

        assertFalse(Files.exists(unused), "an entry no launch has read in a month is a leftover");
        assertTrue(Files.exists(recent), "an entry read yesterday belongs to an installed mod");
        assertTrue(notes.details.stream().anyMatch(line -> line.contains("removed 1 stored scan(s)")),
                notes.details.toString());
    }

    @Test
    void housekeepingKeepsEveryRecentScanOfABigPack() throws IOException {
        // ATM10 stores about 520 entries and 64 MiB. A count or size cap that a big pack
        // reaches would drop entries the same launch reads and rewrite them every launch.
        Path store = Files.createDirectory(dir.resolve("store"));
        int entries = 2000;
        for (int i = 0; i < entries; i++) {
            Files.write(store.resolve("scan-" + i + ".agb"), new byte[] {1});
        }

        ScanCache.housekeeping(store, notes);

        assertEquals(entries, storedEntries(store));
    }

    /** An enclosing jar holding one nested jar at {@code at}. */
    private Path carrying(String at) throws IOException {
        return JarFixtures.carrying(dir, "carrier.jar", at(at,
                JarFixtures.jarOf(dir, "nested-source.jar", JarFixtures.entries())));
    }

    private static LinkedHashMap<String, Path> at(String entryName, Path jar) {
        LinkedHashMap<String, Path> one = new LinkedHashMap<>();
        one.put(entryName, jar);
        return one;
    }

    private static long storedEntries(Path store) throws IOException {
        try (var listing = Files.list(store)) {
            return listing.count();
        }
    }

    private ScanCache cache(Path store) {
        return new ScanCache(store, TAG, notes, false);
    }

    private static Supplier<ModFileScanData> scanning(AtomicInteger count) {
        return () -> {
            count.incrementAndGet();
            return scanned();
        };
    }

    private static ModFileScanData scanned() {
        ModFileScanData data = new ModFileScanData();
        data.addModFileInfo(null);
        data.getClasses().add(new ModFileScanData.ClassData(
                Type.getObjectType("com/example/Alpha"),
                Type.getObjectType("java/lang/Object"),
                Set.of()));
        data.getAnnotations().add(new ModFileScanData.AnnotationData(
                Type.getObjectType("com/example/Mod"), ElementType.TYPE,
                Type.getObjectType("com/example/Alpha"), "com.example.Alpha",
                Map.of("value", "alpha")));
        return data;
    }

    private static ModFileScanData scannedDifferently() {
        ModFileScanData data = scanned();
        data.getClasses().add(new ModFileScanData.ClassData(
                Type.getObjectType("com/example/Gamma"), null, Set.of()));
        return data;
    }

    private static Path onlyEntry(Path store) throws IOException {
        try (var listing = Files.list(store)) {
            List<Path> found = listing.toList();
            assertEquals(1, found.size(), found.toString());
            return found.getFirst();
        }
    }
}
