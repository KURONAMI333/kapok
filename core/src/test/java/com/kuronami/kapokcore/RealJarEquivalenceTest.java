package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.jarhandling.impl.Jar;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link CheapVerifySecureJar} against the loader's real
 * {@code cpw.mods.jarhandling.impl.Jar} over real jar files, and compares our answer to
 * the loader's for every class entry - the same walk {@code ModFile#scanFile} performs.
 *
 * <p>Each comparison mounts the jar <em>twice</em>. {@code JarSigningData#verifyPath}
 * caches into {@code statusData} on signed jars, so asking the same instance twice would
 * measure the cache rather than the two code paths.
 *
 * <p>The sweep classifies jars by {@code SecureJar#hasSecurityData()} rather than by the
 * presence of a {@code META-INF/*.RSA} block, because the two disagree in practice. A jar
 * whose first zip entry is not {@code META-INF/MANIFEST.MF} is read as unsigned by
 * {@code JarInputStream}, so {@code SecureJarVerifier#getJarVerifier} hands back nothing
 * and the loader itself never verifies it - signature block or no signature block. Of the
 * three signed jars in the reference instance, only ferritecore puts the manifest first;
 * framework and goblintraders lead with LICENSE / .cache entries. Keying the wrapper off
 * the loader's own predicate is what keeps us in step with it either way.
 */
class RealJarEquivalenceTest {

    @Test
    void secureJarFromProducesTheImplementationWeReasonedAbout(@TempDir Path dir) throws IOException {
        // cheapen() refuses to wrap anything that is not this class, and the whole
        // equivalence argument is about this class's fields. Checked, not assumed.
        try (JarContents contents = JarContents.of(JarFixtures.unsigned(dir, "m.jar", 4))) {
            assertInstanceOf(Jar.class, SecureJar.from(contents));
        }
    }

    @Test
    void everyClassEntryOfAnUnsignedJarGetsTheSameStatus(@TempDir Path dir) throws IOException {
        try (JarContents baselineContents = JarContents.of(JarFixtures.unsigned(dir, "a.jar", 120));
                JarContents wrappedContents = JarContents.of(JarFixtures.unsigned(dir, "b.jar", 120))) {

            SecureJar baseline = SecureJar.from(baselineContents);
            JarMetadata metadata = JarMetadata.from(wrappedContents);
            SecureJar inner = SecureJar.from(wrappedContents, metadata);
            assertFalse(baseline.hasSecurityData(), "the fixture must be unsigned");

            Comparison result = compareEveryClassEntry(baseline, inner, metadata);

            assertEquals(120, result.entries, "the walk must actually have visited the classes");
            assertEquals(0, result.mismatches, result.report);
            assertEquals(List.of(SecureJar.Status.UNVERIFIED), result.distinctStatuses);
        }
    }

    @Test
    void everyClassEntryOfEveryRealModJarGetsTheSameStatus(@TempDir Path dir) throws IOException {
        Path copies = Files.createDirectory(dir.resolve("signed"));
        List<Path> jars = JarFixtures.copySignedJars(copies);
        Assumptions.assumeFalse(jars.isEmpty(),
                "set " + JarFixtures.SIGNED_JAR_DIR_ENV + " to a directory of real mod jars to run this");

        int loaderSigned = 0;
        int loaderUnsigned = 0;
        int totalEntries = 0;
        for (Path jar : jars) {
            try (JarContents baselineContents = JarContents.of(jar);
                    JarContents wrappedContents = JarContents.of(jar)) {

                SecureJar baseline = SecureJar.from(baselineContents);
                JarMetadata metadata = JarMetadata.from(wrappedContents);
                SecureJar inner = SecureJar.from(wrappedContents, metadata);

                Comparison result = compareEveryClassEntry(baseline, inner, metadata);

                assertNotEquals(0, result.entries, jar.getFileName() + " has no class entries");
                assertEquals(0, result.mismatches, jar.getFileName() + ": " + result.report);
                totalEntries += result.entries;
                if (baseline.hasSecurityData()) {
                    loaderSigned++;
                } else {
                    loaderUnsigned++;
                }
                System.out.println("[equivalence] " + jar.getFileName()
                        + ": hasSecurityData=" + baseline.hasSecurityData()
                        + ", " + result.entries + " class entries agreed, statuses "
                        + result.distinctStatuses);
            }
        }
        System.out.println("[equivalence] " + totalEntries + " class entries over " + jars.size()
                + " jars (" + loaderSigned + " the loader treats as signed, "
                + loaderUnsigned + " it does not)");
        assertNotEquals(0, loaderSigned,
                "at least one jar must exercise the VERIFIED path or the sweep proves nothing");
    }

    @Test
    void cheapenWrapsExactlyThoseJarsTheLoaderTreatsAsUnsigned(@TempDir Path dir) throws IOException {
        Path copies = Files.createDirectory(dir.resolve("signed"));
        List<Path> jars = JarFixtures.copySignedJars(copies);
        Assumptions.assumeFalse(jars.isEmpty(),
                "set " + JarFixtures.SIGNED_JAR_DIR_ENV + " to a directory of real mod jars to run this");

        KapokCoreReader reader =
                new KapokCoreReader(CountingEnvironment.healthy(), new RecordingNotes());
        for (Path jar : jars) {
            try (JarContents contents = JarContents.of(jar)) {
                JarMetadata metadata = JarMetadata.from(contents);
                SecureJar built = SecureJar.from(contents, metadata);
                SecureJar result = reader.cheapen(contents, built, metadata);
                if (built.hasSecurityData()) {
                    assertSame(built, result,
                            jar.getFileName() + " is signed and must run none of our code");
                } else {
                    assertInstanceOf(CheapVerifySecureJar.class, result, jar.getFileName().toString());
                }
            }
        }
    }

    @Test
    void aRealUnsignedJarComesBackWrapped(@TempDir Path dir) throws IOException {
        // Reaches cheapen() through the production `built instanceof Jar` path rather than
        // the boolean seam the fake-based tests use.
        KapokCoreReader reader =
                new KapokCoreReader(CountingEnvironment.healthy(), new RecordingNotes());
        try (JarContents contents = JarContents.of(JarFixtures.unsigned(dir, "m.jar", 4))) {
            JarMetadata metadata = JarMetadata.from(contents);
            SecureJar built = SecureJar.from(contents, metadata);
            SecureJar result = reader.cheapen(contents, built, metadata);
            assertInstanceOf(CheapVerifySecureJar.class, result);
            assertSame(built, ((CheapVerifySecureJar) result).delegate());
        }
    }

    @Test
    void theRealWrongFilesystemExceptionIsNotSwallowed(@TempDir Path dir) throws IOException {
        Path zipFile = dir.resolve("other.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            out.putNextEntry(new ZipEntry("a.txt"));
            out.closeEntry();
        }
        try (JarContents contents = JarContents.of(JarFixtures.unsigned(dir, "m.jar", 2));
                FileSystem zip = FileSystems.newFileSystem(zipFile, Map.of())) {

            JarMetadata metadata = JarMetadata.from(contents);
            SecureJar inner = SecureJar.from(contents, metadata);
            Path foreign = zip.getPath("a.txt");

            assertEquals("Wrong filesystem",
                    assertThrows(IllegalArgumentException.class, () -> inner.verifyPath(foreign)).getMessage());
            assertEquals("Wrong filesystem",
                    assertThrows(IllegalArgumentException.class,
                            () -> new CheapVerifySecureJar(inner, metadata).verifyPath(foreign)).getMessage());
        }
    }

    @Test
    void theModuleDataProviderOfARealJarIsHandedThroughUntouched(@TempDir Path dir) throws IOException {
        try (JarContents contents = JarContents.of(JarFixtures.unsigned(dir, "m.jar", 2))) {
            JarMetadata metadata = JarMetadata.from(contents);
            SecureJar inner = SecureJar.from(contents, metadata);
            assertSame(inner.moduleDataProvider(), new CheapVerifySecureJar(inner, metadata).moduleDataProvider());
        }
    }

    /** Walks the jar the way {@code ModFile#scanFile} does and compares the two answers. */
    private static Comparison compareEveryClassEntry(SecureJar baseline, SecureJar wrappedInner, JarMetadata metadata) {
        CheapVerifySecureJar wrapper = new CheapVerifySecureJar(wrappedInner, metadata);
        Path baselineRoot = baseline.getRootPath();
        Path wrapperRoot = wrappedInner.getRootPath();

        List<String> mismatches = new ArrayList<>();
        List<SecureJar.Status> seen = new ArrayList<>();
        int entries = 0;

        try (Stream<Path> files = Files.find(baselineRoot, Integer.MAX_VALUE,
                (p, a) -> p.getNameCount() > 0 && p.getFileName().toString().endsWith(".class"))) {
            for (Path path : files.toList()) {
                entries++;
                String relative = baselineRoot.relativize(path).toString();
                SecureJar.Status loaderAnswer = baseline.verifyPath(path);
                SecureJar.Status ourAnswer = wrapper.verifyPath(wrapperRoot.resolve(relative));
                if (!seen.contains(loaderAnswer)) {
                    seen.add(loaderAnswer);
                }
                if (loaderAnswer != ourAnswer) {
                    mismatches.add(relative + ": loader=" + loaderAnswer + " ours=" + ourAnswer);
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new Comparison(entries, mismatches.size(), seen, String.join("; ", mismatches));
    }

    private record Comparison(int entries, int mismatches,
            List<SecureJar.Status> distinctStatuses, String report) {
    }
}
