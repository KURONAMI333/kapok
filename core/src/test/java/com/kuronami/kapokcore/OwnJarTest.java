package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnJarTest {
    @TempDir
    Path dir;

    private Path jar(String name, String... entries) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(entry.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void picksTheCandidateThatCarriesThisClass() throws IOException {
        Path essential = jar("essential.jar", "META-INF/services/cpw.mods.modlauncher.api.ITransformationService", "gg/essential/Loader.class");
        Path ours = jar("kapok-0.1.0.jar", "META-INF/services/x", OwnJar.MARKER_ENTRY);
        Path notAZip = dir.resolve("garbage.jar");
        Files.writeString(notAZip, "not a zip at all");
        Path missing = dir.resolve("missing.jar");

        Optional<Path> found = OwnJar.fromCandidates(List.of(essential, notAZip, missing, ours));

        assertTrue(found.isPresent());
        assertEquals(ours, found.get());
    }

    @Test
    void emptyWhenNoCandidateCarriesIt() throws IOException {
        Path other = jar("other.jar", "com/example/Thing.class");
        assertTrue(OwnJar.fromCandidates(List.of(other)).isEmpty());
        assertTrue(OwnJar.fromCandidates(List.of()).isEmpty());
    }

    @Test
    void markerNamesThisClass() {
        assertEquals("com/kuronami/kapokcore/OwnJar.class", OwnJar.MARKER_ENTRY);
    }
}
