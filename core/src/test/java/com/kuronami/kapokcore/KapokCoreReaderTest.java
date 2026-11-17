package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cpw.mods.jarhandling.SecureJar;
import java.nio.file.Path;
import net.neoforged.neoforgespi.locating.IOrderedProvider;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KapokCoreReaderTest {

    @Test
    void priorityIsAboveTheBuiltInReaders() {
        // ServiceLoaderUtil sorts descending and JarModsDotTomlModFileReader does not
        // override the default of 0. Losing this override leaves the feature inert while
        // it still announces itself, which is the exact failure this jar must not have.
        assertEquals(1, new KapokCoreReader(CountingEnvironment.healthy(), new RecordingNotes())
                .getPriority());
        assertEquals(0, IOrderedProvider.DEFAULT_PRIORITY);
    }

    @Test
    void anUnsignedFamiliarJarComesBackWrapped(@TempDir Path dir) {
        // The first version of cheapen() returned the bare jar from its wrapping branch.
        // Everything compiled and every other test passed while the feature did nothing.
        KapokCoreReader reader = healthyReader();
        FakeSecureJar unsigned = new FakeSecureJar(dir, false);

        SecureJar result = reader.cheapen(FakeJarContents.plain(dir), unsigned, FakeSecureJar.METADATA, true);

        assertInstanceOf(CheapVerifySecureJar.class, result);
        assertSame(unsigned, ((CheapVerifySecureJar) result).delegate());
    }

    @Test
    void aSignedJarIsHandedBackUntouched(@TempDir Path dir) {
        KapokCoreReader reader = healthyReader();
        FakeSecureJar signed = new FakeSecureJar(dir, true);

        assertSame(signed, reader.cheapen(FakeJarContents.plain(dir), signed, FakeSecureJar.METADATA, true));
    }

    @Test
    void anUnfamiliarSecureJarIsHandedBackUntouched(@TempDir Path dir) {
        KapokCoreReader reader = healthyReader();
        FakeSecureJar stranger = new FakeSecureJar(dir, false);

        assertSame(stranger, reader.cheapen(FakeJarContents.plain(dir), stranger, FakeSecureJar.METADATA, false));
    }

    @Test
    void theFirstWrapIsAnnouncedExactlyOnce(@TempDir Path dir) {
        RecordingNotes notes = new RecordingNotes();
        KapokCoreReader reader = new KapokCoreReader(CountingEnvironment.healthy(), notes);

        reader.cheapen(FakeJarContents.plain(dir), new FakeSecureJar(dir, false), FakeSecureJar.METADATA, true);
        reader.cheapen(FakeJarContents.plain(dir), new FakeSecureJar(dir, false), FakeSecureJar.METADATA, true);
        reader.cheapen(FakeJarContents.plain(dir), new FakeSecureJar(dir, false), FakeSecureJar.METADATA, true);

        assertEquals(1, notes.info.stream()
                .filter(m -> m.contains("wrapped its first mod file")).count());
    }

    @Test
    void wrappingSealsItselfWhenItCannotBeAnnounced(@TempDir Path dir) {
        // Same invariant as the activation line: no INFO in the log has to keep meaning
        // "this jar is not doing anything".
        RecordingNotes notes = new RecordingNotes();
        KapokCoreReader reader = new KapokCoreReader(CountingEnvironment.healthy(), notes);
        notes.infoLands = false;

        reader.cheapen(FakeJarContents.plain(dir), new FakeSecureJar(dir, false), FakeSecureJar.METADATA, true);

        assertEquals(Activation.State.SEALED, reader.activation().state());
    }

    @Test
    void anActiveReaderSealsMidRunWhenTheWrapAnnouncementFails(@TempDir Path dir) {
        // The production ordering: read() activates, claim() reaches cheapen(), and only
        // then does the announcement fail. Activation goes ACTIVE -> SEALED while jars are
        // already wrapped, so this is the mixed state the comment in cheapen() describes.
        RecordingNotes notes = new RecordingNotes();
        KapokCoreReader reader = new KapokCoreReader(CountingEnvironment.healthy(), notes);

        reader.read(FakeJarContents.plain(dir), ModFileDiscoveryAttributes.DEFAULT);
        assertEquals(Activation.State.ACTIVE, reader.activation().state());

        notes.infoLands = false;
        reader.cheapen(FakeJarContents.plain(dir), new FakeSecureJar(dir, false), FakeSecureJar.METADATA, true);

        assertEquals(Activation.State.SEALED, reader.activation().state());
        assertNull(reader.read(FakeJarContents.withModType(dir, "LIBRARY"),
                ModFileDiscoveryAttributes.DEFAULT), "a sealed reader claims nothing further");
    }

    @Test
    void aJarWithNeitherMarkerIsDeclined(@TempDir Path dir) {
        assertNull(healthyReader().claim(FakeJarContents.plain(dir), ModFileDiscoveryAttributes.DEFAULT));
    }

    @Test
    void aJarNamingAnUnknownModTypeIsDeclinedRatherThanClaimed(@TempDir Path dir) {
        // The built-in reader raises "unknown fml mod type" against this jar. Claiming it
        // would make that error disappear.
        assertNull(healthyReader().claim(
                FakeJarContents.withModType(dir, "NONSENSE"), ModFileDiscoveryAttributes.DEFAULT));
        assertNull(healthyReader().claim(
                FakeJarContents.withModsToml(dir, "NONSENSE"), ModFileDiscoveryAttributes.DEFAULT),
                "an unknown type must win over the mods.toml branch");
    }

    @Test
    void aSealedReaderReturnsNullAndNeverThrows(@TempDir Path dir) {
        RecordingNotes notes = new RecordingNotes();
        KapokCoreReader reader = new KapokCoreReader(
                CountingEnvironment.of("21.2.1", ReaderCensus.OURS), notes);

        assertNull(reader.read(FakeJarContents.withModType(dir, "LIBRARY"),
                ModFileDiscoveryAttributes.DEFAULT));
        assertEquals(Activation.State.SEALED, reader.activation().state());
    }

    @Test
    void anExceptionDuringReadSealsInsteadOfEscaping(@TempDir Path dir) {
        // addJarContent turns a throw from a reader into a "broken file" issue reported
        // against the jar being read, and readModFile does not catch at all. Either way an
        // unrelated mod gets blamed for our bug, so nothing may escape read().
        RecordingNotes notes = new RecordingNotes();
        KapokCoreReader reader = new KapokCoreReader(CountingEnvironment.healthy(), notes);

        JarContentsThatThrows exploding = new JarContentsThatThrows(dir);
        assertNull(reader.read(exploding, ModFileDiscoveryAttributes.DEFAULT));
        assertEquals(Activation.State.SEALED, reader.activation().state());
        assertEquals(1, notes.troubles.size());
    }

    private static KapokCoreReader healthyReader() {
        return new KapokCoreReader(CountingEnvironment.healthy(), new RecordingNotes());
    }

    /** Stands in for a jar whose contents blow up while the reader inspects them. */
    private static final class JarContentsThatThrows implements cpw.mods.jarhandling.JarContents {
        private final Path path;

        JarContentsThatThrows(Path path) {
            this.path = path;
        }

        @Override
        public Path getPrimaryPath() {
            return path;
        }

        @Override
        public java.util.Optional<java.net.URI> findFile(String name) {
            throw new IllegalStateException("corrupt");
        }

        @Override
        public java.util.jar.Manifest getManifest() {
            throw new IllegalStateException("corrupt");
        }

        @Override
        public java.util.Set<String> getPackages() {
            return java.util.Set.of();
        }

        @Override
        public java.util.Set<String> getPackagesExcluding(String... excluded) {
            return java.util.Set.of();
        }

        @Override
        public java.util.List<SecureJar.Provider> getMetaInfServices() {
            return java.util.List.of();
        }

        @Override
        public void close() {
        }
    }
}
