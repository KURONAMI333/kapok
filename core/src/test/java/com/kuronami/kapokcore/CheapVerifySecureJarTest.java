package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cpw.mods.jarhandling.SecureJar;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheapVerifySecureJarTest {

    @Test
    void anUnsignedJarIsAnsweredWithoutReadingTheFile(@TempDir Path dir) {
        FakeSecureJar delegate = new FakeSecureJar(dir, false);
        CheapVerifySecureJar jar = new CheapVerifySecureJar(delegate, FakeSecureJar.METADATA);

        assertEquals(SecureJar.Status.UNVERIFIED, jar.verifyPath(dir.resolve("a/B.class")));
        assertEquals(0, delegate.verifyPathCalls,
                "skipping the second read of every class is the entire point");
    }

    @Test
    void aSignedJarStillGoesThroughTheRealVerifier(@TempDir Path dir) {
        FakeSecureJar delegate = new FakeSecureJar(dir, true);
        CheapVerifySecureJar jar = new CheapVerifySecureJar(delegate, FakeSecureJar.METADATA);

        assertEquals(SecureJar.Status.VERIFIED, jar.verifyPath(dir.resolve("a/B.class")));
        assertEquals(1, delegate.verifyPathCalls);
    }

    @Test
    void aPathFromAnotherFilesystemIsHandedBackAndTheExceptionSurvives(@TempDir Path dir) throws IOException {
        Path zipFile = dir.resolve("other.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            out.putNextEntry(new ZipEntry("a.txt"));
            out.closeEntry();
        }
        IllegalArgumentException wrongFilesystem = new IllegalArgumentException("Wrong filesystem");
        FakeSecureJar delegate = new FakeSecureJar(dir, false, wrongFilesystem);
        CheapVerifySecureJar jar = new CheapVerifySecureJar(delegate, FakeSecureJar.METADATA);

        try (FileSystem zip = FileSystems.newFileSystem(zipFile, Map.of())) {
            Path foreign = zip.getPath("a.txt");
            // The jar is unsigned, so the cheap answer would be tempting. The delegate's
            // precondition has to win, or a caller's genuine mistake vanishes.
            assertSame(wrongFilesystem, assertThrows(IllegalArgumentException.class,
                    () -> jar.verifyPath(foreign)));
            assertEquals(1, delegate.verifyPathCalls);
        }
    }

    @Test
    void theModuleDataProviderIsTheDelegatesOwnInstance(@TempDir Path dir) {
        FakeSecureJar delegate = new FakeSecureJar(dir, false);

        // Non-negotiable: class loading verifies signatures through this object, and it
        // must be the delegate's, not a wrapper. Identity, not equality.
        assertSame(delegate.provider, new CheapVerifySecureJar(delegate, FakeSecureJar.METADATA).moduleDataProvider());
    }

    @Test
    void everythingElseIsPlainDelegation(@TempDir Path dir) throws IOException {
        FakeSecureJar delegate = new FakeSecureJar(dir, false);
        CheapVerifySecureJar jar = new CheapVerifySecureJar(delegate, FakeSecureJar.METADATA);

        assertSame(delegate.getPrimaryPath(), jar.getPrimaryPath());
        assertSame(delegate.getRootPath(), jar.getRootPath());
        assertEquals("fake", jar.name());
        assertEquals(false, jar.hasSecurityData());
        assertEquals(SecureJar.Status.NONE, jar.getFileStatus("x"));
        assertEquals(dir.resolve("x"), jar.getPath("x"));
        assertNull(jar.getManifestSigners());
        assertNull(jar.getTrustedManifestEntries("x"));

        jar.close();
        assertEquals(1, delegate.closeCalls, "close must reach the real jar or handles leak");
    }
}
