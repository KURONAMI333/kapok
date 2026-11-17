package com.kuronami.kapokcore;

import cpw.mods.jarhandling.SecureJar;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * A hand-rolled {@link SecureJar}, so {@link CheapVerifySecureJar} can be exercised
 * without a mod loader. It counts the calls that reach it, which is how the tests tell
 * "answered cheaply" apart from "delegated".
 */
final class FakeSecureJar implements SecureJar {
    static final cpw.mods.jarhandling.JarMetadata METADATA = new cpw.mods.jarhandling.JarMetadata() {
        public String name() { return "fake"; }
        public String version() { return "1.0"; }
        public java.lang.module.ModuleDescriptor descriptor() {
            return java.lang.module.ModuleDescriptor.newAutomaticModule(name()).version(version()).build();
        }
    };
    private final Path rootPath;
    private final boolean signed;
    private final RuntimeException verifyFailure;
    private final Manifest manifest = new Manifest();

    int verifyPathCalls;
    int getFileStatusCalls;
    int closeCalls;

    final SecureJar.ModuleDataProvider provider = new SecureJar.ModuleDataProvider() {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public java.lang.module.ModuleDescriptor descriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI uri() {
            return URI.create("file:///fake");
        }

        @Override
        public Optional<URI> findFile(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<InputStream> open(String name) {
            return Optional.empty();
        }

        @Override
        public Manifest getManifest() {
            return manifest;
        }

        @Override
        public CodeSigner[] verifyAndGetSigners(String name, byte[] bytes) {
            return null;
        }
    };

    FakeSecureJar(Path rootPath, boolean signed) {
        this(rootPath, signed, null);
    }

    FakeSecureJar(Path rootPath, boolean signed, RuntimeException verifyFailure) {
        this.rootPath = rootPath;
        this.signed = signed;
        this.verifyFailure = verifyFailure;
    }

    @Override
    public SecureJar.ModuleDataProvider moduleDataProvider() {
        return provider;
    }

    @Override
    public Path getPrimaryPath() {
        return rootPath;
    }

    @Override
    public CodeSigner[] getManifestSigners() {
        return null;
    }

    @Override
    public SecureJar.Status verifyPath(Path path) {
        verifyPathCalls++;
        if (verifyFailure != null) {
            throw verifyFailure;
        }
        return SecureJar.Status.VERIFIED;
    }

    @Override
    public SecureJar.Status getFileStatus(String name) {
        getFileStatusCalls++;
        return SecureJar.Status.NONE;
    }

    @Override
    public Attributes getTrustedManifestEntries(String name) {
        return null;
    }

    @Override
    public boolean hasSecurityData() {
        return signed;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Path getPath(String first, String... rest) {
        return rootPath.resolve(first);
    }

    @Override
    public Path getRootPath() {
        return rootPath;
    }

    @Override
    public void close() {
        closeCalls++;
    }
}
