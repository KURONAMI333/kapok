package com.kuronami.kapokcore.jij;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kuronami.kapokcore.Notes;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the one replaced method hands the loader, and what it does when it cannot.
 *
 * <p>The nested jar is a real one and the copy is a real file, so what is under test is
 * the same code an install runs. The stock method is not: when this class stands down,
 * the test says that it stood down and lets whatever the loader does next happen, because
 * that behaviour belongs to the loader.
 */
class KapokJarInJarLocatorTest {
    private static final String ENTRY = "META-INF/jarjar/library-4.5.6.jar";

    @TempDir
    Path dir;

    private final Lines lines = new Lines();
    private final List<NestedJarStore> stores = new ArrayList<>();

    @AfterEach
    void letGoOfTheJars() {
        stores.forEach(NestedJarStore::closeEnclosingJars);
    }

    private NestedJarStore store() {
        NestedJarStore made = new NestedJarStore(dir.resolve("nested"), lines);
        stores.add(made);
        return made;
    }

    /** A jar carrying one real jar inside it, the way jar-in-jar ships a dependency. */
    private Path modJar(String name) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            manifest(out);
            out.putNextEntry(new ZipEntry(ENTRY));
            out.write(Files.readAllBytes(libraryJar()));
            out.closeEntry();
        }
        return jar;
    }

    private Path libraryJar() throws IOException {
        Path jar = dir.resolve("library-4.5.6.jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            manifest(out);
            out.putNextEntry(new ZipEntry("com/example/library/marker.txt"));
            out.write("a library".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static void manifest(ZipOutputStream out) throws IOException {
        out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        out.write("Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8));
        out.write(new byte[] {13, 10, 13, 10});
        out.closeEntry();
    }

    @Test
    void theLoaderIsHandedTheCopyOnDisk() throws IOException {
        Path mod = modJar("some-mod.jar");
        Pipeline pipeline = new Pipeline();
        ModFile parent = new ModFile(mod);

        Optional<IModFile> loaded = new KapokJarInJarLocator(store(), lines)
                .loadModFileFrom(parent, Path.of(ENTRY), pipeline);

        assertSame(pipeline.answer, loaded.orElse(null));
        assertNotNull(pipeline.contents);
        Path handed = pipeline.contents.getPrimaryPath();
        assertTrue(Files.isRegularFile(handed), handed + " is an ordinary file");
        assertEquals(dir.resolve("nested"), handed.getParent().getParent());
        assertEquals("library-4.5.6.jar", handed.getFileName().toString());
    }

    @Test
    void theNestedJarIsStillSaidToHaveComeOutOfItsMod() throws IOException {
        Path mod = modJar("some-mod.jar");
        Pipeline pipeline = new Pipeline();
        ModFile parent = new ModFile(mod);

        new KapokJarInJarLocator(store(), lines)
                .loadModFileFrom(parent, Path.of(ENTRY), pipeline);

        // What the stock method passes. Everything downstream that asks a mod file where
        // it came from - the mod list, the dependency messages - reads this.
        assertEquals(ModFileDiscoveryAttributes.DEFAULT.withParent(parent), pipeline.attributes);
        assertSame(parent, pipeline.attributes.parent());
    }

    @Test
    void aModThatIsNotAPlainJarFileIsLeftToTheLoader() {
        NestedJarStore store = store();
        Pipeline pipeline = new Pipeline();
        // A development launch loads mods out of directories, so there is no jar to read
        // an entry out of.
        standDownOn(store, new ModFile(null), Path.of(ENTRY), pipeline);

        assertNull(pipeline.contents, "nothing of ours was handed over");
        assertEquals(1, lines.details.size());
        assertTrue(lines.details.get(0).contains("in place (its mod is not a plain jar file)"));
        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 0 written, 0 reused, 1 mounted in place"), lines.info);
    }

    @Test
    void aNestedJarThatCannotBeWrittenOutIsLeftToTheLoader() throws IOException {
        NestedJarStore store = store();
        Pipeline pipeline = new Pipeline();
        // The mod is a jar, and does not hold the entry its own metadata named.
        Path mod = modJar("some-mod.jar");

        standDownOn(store, new ModFile(mod), Path.of("META-INF/jarjar/gone.jar"), pipeline);

        assertNull(pipeline.contents);
        assertEquals(1, lines.details.size());
        assertTrue(lines.details.get(0).contains("gone.jar"));
        assertEquals(List.of("Kapok Core: nested jars on disk instead of in memory -"
                + " 0 written, 0 reused, 1 mounted in place"), lines.info);
    }

    /**
     * Runs the override on a nested jar it has to hand back, then says what was done.
     *
     * <p>The stock method takes over from there and is allowed to do whatever it does with
     * a stand-in parent, including throw. That it was reached is the point.
     */
    private void standDownOn(NestedJarStore store, ModFile parent, Path path, Pipeline pipeline) {
        try {
            new KapokJarInJarLocator(store, lines).loadModFileFrom(parent, path, pipeline);
        } catch (RuntimeException theLoadersOwnPath) {
            // Not ours to assert on.
        }
        store.finish(true);
    }

    /** Records what the locator asked the pipeline to read. */
    private static final class Pipeline implements IDiscoveryPipeline {
        JarContents contents;
        ModFileDiscoveryAttributes attributes;
        final IModFile answer = new ModFile(null);

        @Override
        public Optional<IModFile> addPath(List<Path> paths, ModFileDiscoveryAttributes attributes,
                IncompatibleFileReporting reporting) {
            return Optional.empty();
        }

        @Override
        public Optional<IModFile> addJarContent(JarContents contents,
                ModFileDiscoveryAttributes attributes, IncompatibleFileReporting reporting) {
            return Optional.empty();
        }

        @Override
        public boolean addModFile(IModFile modFile) {
            return false;
        }

        @Override
        public IModFile readModFile(JarContents contents, ModFileDiscoveryAttributes attributes) {
            this.contents = contents;
            this.attributes = attributes;
            return answer;
        }

        @Override
        public void addIssue(ModLoadingIssue issue) {
        }
    }

    /** A mod file that knows where it is, and nothing else. */
    private static final class ModFile implements IModFile {
        private final Path filePath;

        ModFile(Path filePath) {
            this.filePath = filePath;
        }

        @Override
        public Path getFilePath() {
            return filePath;
        }

        @Override
        public String getFileName() {
            return filePath == null ? "some-mod" : filePath.getFileName().toString();
        }

        @Override
        public Path findResource(String... pieces) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Supplier<Map<String, Object>> getSubstitutionMap() {
            return LinkedHashMap::new;
        }

        @Override
        public Type getType() {
            return Type.MOD;
        }

        @Override
        public SecureJar getSecureJar() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSecurityStatus(SecureJar.Status status) {
        }

        @Override
        public List<IModInfo> getModInfos() {
            return List.of();
        }

        @Override
        public ModFileScanData getScanResult() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModFileDiscoveryAttributes getDiscoveryAttributes() {
            return ModFileDiscoveryAttributes.DEFAULT;
        }

        @Override
        public IModFileInfo getModFileInfo() {
            throw new UnsupportedOperationException();
        }
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
