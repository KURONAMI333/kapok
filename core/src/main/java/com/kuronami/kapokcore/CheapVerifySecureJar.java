package com.kuronami.kapokcore;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import java.io.IOException;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.util.Objects;
import java.util.jar.Attributes;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link SecureJar} that answers {@code verifyPath} without reading the file.
 *
 * <p>{@code ModFile#scanFile} walks every {@code .class} entry twice: once through
 * {@code peek}, which the ASM scan genuinely needs, and once through
 * {@code JarSigningData#verifyPath}, which calls {@code Files.readAllBytes} and then
 * hands the bytes to {@code verifyAndGetSigners}. On an unsigned jar that method returns
 * {@code null} on its first line without recording anything, and {@code getFileStatus}
 * falls through to {@code UNVERIFIED} because {@code hasSecurityData()} is false. The
 * second read is therefore a constant function with no side effects, and this class
 * returns its constant.
 *
 * <p>Three things this deliberately does not do:
 *
 * <ul>
 *   <li><b>No try/catch anywhere.</b> The {@code UncheckedIOException} that
 *       {@code JarSigningData} raises on an unreadable entry, and the
 *       {@code IllegalArgumentException} that {@code Jar} raises for a path from the
 *       wrong filesystem, are both part of the contract the loader relies on. Swallowing
 *       either would turn a loud failure into a quiet wrong answer.
 *   <li><b>No wrapping of {@code moduleDataProvider()}.</b> Class loading verifies
 *       signatures through that object ({@code ModuleClassLoader} to
 *       {@code JarModuleFinder} to {@code ModuleDataProvider#verifyAndGetSigners}, which
 *       goes straight to {@code JarSigningData} and never through {@code verifyPath}).
 *       The delegate's own instance is handed back untouched.
 *   <li><b>No use of {@code hasSecurityData()} as the sole gate.</b> The wrong-filesystem
 *       precondition of the delegate is reproduced first, so a caller that passes a
 *       foreign path still gets the exception it would have got.
 * </ul>
 */
public final class CheapVerifySecureJar implements SecureJar {
    private final SecureJar delegate;

    // Essential's SortedJarOrPathList reads this exact field using Unsafe to sort
    // library versions. Keep the delegate's original, potentially lazy metadata.
    // This cannot be a record: Unsafe.objectFieldOffset rejects record fields.
    private final JarMetadata metadata;

    public CheapVerifySecureJar(SecureJar delegate, JarMetadata metadata) {
        this.delegate = Objects.requireNonNull(delegate);
        this.metadata = Objects.requireNonNull(metadata);
    }

    public SecureJar delegate() {
        return delegate;
    }

    @Override
    public SecureJar.Status verifyPath(Path path) {
        // Delegate's precondition, reproduced rather than assumed: getRootPath() is
        // this.filesystem.getPath(""), so its FileSystem is the very object Jar compares
        // against. Handing the call back lets the delegate raise "Wrong filesystem".
        if (path.getFileSystem() != delegate.getRootPath().getFileSystem()) {
            return delegate.verifyPath(path);
        }
        // Belt and braces: the reader already refuses to wrap a signed jar, and the
        // signing sets can never become non-empty once empty (verifyAndGetSigners returns
        // before touching ManifestVerifier when hasSecurityData() is false). Re-checking
        // costs a field read and removes the need to trust that argument at runtime.
        if (delegate.hasSecurityData()) {
            return delegate.verifyPath(path);
        }
        return SecureJar.Status.UNVERIFIED;
    }

    @Override
    public SecureJar.ModuleDataProvider moduleDataProvider() {
        return delegate.moduleDataProvider();
    }

    @Override
    public Path getPrimaryPath() {
        return delegate.getPrimaryPath();
    }

    @Nullable
    @Override
    public CodeSigner[] getManifestSigners() {
        return delegate.getManifestSigners();
    }

    @Override
    public SecureJar.Status getFileStatus(String name) {
        return delegate.getFileStatus(name);
    }

    @Nullable
    @Override
    public Attributes getTrustedManifestEntries(String name) {
        return delegate.getTrustedManifestEntries(name);
    }

    @Override
    public boolean hasSecurityData() {
        return delegate.hasSecurityData();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Path getPath(String first, String... rest) {
        return delegate.getPath(first, rest);
    }

    @Override
    public Path getRootPath() {
        return delegate.getRootPath();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
