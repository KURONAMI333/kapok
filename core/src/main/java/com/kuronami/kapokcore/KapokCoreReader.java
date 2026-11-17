package com.kuronami.kapokcore;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.jarhandling.impl.Jar;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the same {@code ModFile} the loader would have built, over a {@link SecureJar}
 * that does not read every class a second time.
 *
 * <p>There is no other seam. {@code SecureJar.from} hard-casts to {@code JarContentsImpl},
 * {@code JarContents} is {@code @NonExtendable}, {@code Jar}'s constructor reads its
 * collaborator's fields directly, and no locator hook ever sees a {@code SecureJar}. To
 * wrap the jar you have to be the reader that creates it, and to be that reader you have
 * to claim the jar. Hence priority 1, and hence the self-seal in {@link Activation}.
 */
public final class KapokCoreReader implements IModFileReader {
    private final Activation activation;
    private final LoaderEnvironment environment;
    private final Notes notes;
    private final AtomicLong wrapped = new AtomicLong();
    private final AtomicLong passedThrough = new AtomicLong();
    private final AtomicBoolean announcedFirstWrap = new AtomicBoolean();

    /**
     * Built on the first claim rather than in the constructor, for the same reason
     * everything else here is: {@code ServiceLoaderUtil} catches only
     * {@code ServiceConfigurationError} while it builds providers.
     */
    private volatile ScanCache cache;

    /**
     * False once {@link CachingModFile} has proved it cannot be used. That the loader's
     * {@code ModFile} can be subclassed from this jar's module is the one thing about
     * this design that no test can settle - the build compiles against a flat classpath
     * while the game resolves a module graph - so a failure here has to degrade to the
     * plain {@code ModFile} rather than take the rest of the feature with it.
     */
    private final AtomicBoolean subclassUsable = new AtomicBoolean(true);

    /** Cleared by the first mod file that will not say which jar it came out of. */
    private final AtomicBoolean askedWhichJar = new AtomicBoolean(true);

    /**
     * Called by {@code ServiceLoaderUtil}, which catches only
     * {@code ServiceConfigurationError}. Everything expensive or fallible is deferred to
     * the first {@code read}.
     */
    public KapokCoreReader() {
        this(new FmlLoaderEnvironment(), new Slf4jNotes());
    }

    KapokCoreReader(LoaderEnvironment environment, Notes notes) {
        this.notes = notes;
        this.environment = environment;
        this.activation = new Activation(environment, notes);
    }

    /**
     * One above {@code JarModsDotTomlModFileReader}, which does not override the default
     * of 0. {@code ServiceLoaderUtil} sorts descending, so this is what puts us first;
     * without it the feature is silently inert while still announcing itself.
     */
    @Override
    public int getPriority() {
        return 1;
    }

    @Nullable
    @Override
    public IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
        try {
            if (!activation.active()) {
                return null;
            }
            return claim(jar, attributes);
        } catch (Throwable t) {
            // Returning null is how the loader behaves when this jar is absent, so the
            // built-in readers pick the file up on the next iteration and any genuine
            // problem with it is reported by them, against the right path.
            activation.seal("an unexpected error while reading a mod file", t);
            return null;
        }
    }

    @Nullable
    IModFile claim(JarContents contents, ModFileDiscoveryAttributes attributes) {
        String declaredType = contents.getManifest().getMainAttributes().getValue(ModFile.TYPE);
        IModFile.Type type = null;
        if (declaredType != null) {
            try {
                type = IModFile.Type.valueOf(declaredType);
            } catch (IllegalArgumentException unknown) {
                type = null;
            }
        }

        ClaimPlan plan = ClaimPlan.of(declaredType, type != null,
                contents.findFile(JarModsDotTomlModFileReader.MODS_TOML).isPresent());
        if (plan == ClaimPlan.DECLINE_UNKNOWN_TYPE || plan == ClaimPlan.DECLINE_NOT_A_MOD) {
            return null;
        }

        ModFileDiscoveryAttributes mine = attributes.withReader(this);

        // What the cache will be asked about. A mod in a file of its own is that file; a
        // mod shipped inside another one has no readable directory of its own, so it is
        // the file around it plus the name it has in there. Both are settled now, while
        // the attributes are still a local: what they say about a parent is read once,
        // here, rather than from a field the loader is free to replace later.
        Path path = contents.getPrimaryPath();
        String nestedName = null;
        Path around = jarAround(attributes);
        if (around != null) {
            nestedName = ScanCacheKey.nestedIn(around, path);
            if (nestedName != null) {
                path = around;
            }
        }

        if (plan == ClaimPlan.CLAIM_WITH_MODS_TOML) {
            ModJarMetadata metadata = new ModJarMetadata(contents);
            SecureJar jar = cheapen(contents, SecureJar.from(contents, metadata), metadata);
            IModFile mod = withModsToml(jar, mine, path, nestedName);
            metadata.setModFile(mod);
            return mod;
        }

        JarMetadata metadata = JarMetadata.from(contents);
        return withDeclaredType(
                cheapen(contents, SecureJar.from(contents, metadata), metadata), type, mine, path, nestedName);
    }

    /**
     * The file the mod that carries this one was read from, when another one carries it.
     *
     * <p>Only {@code JarInJarDependencyLocator} sets a parent in this loader, so a parent
     * here means a nested mod and nothing else. The failure is caught separately from
     * {@link #read}'s blanket handler on purpose: that one seals the feature for the whole
     * launch, and a mod file from elsewhere that will not say where it came from should
     * cost the cache that one jar rather than every jar after it.
     */
    @Nullable
    private Path jarAround(ModFileDiscoveryAttributes attributes) {
        try {
            IModFile parent = attributes.parent();
            return parent == null ? null : parent.getFilePath();
        } catch (Throwable t) {
            if (askedWhichJar.compareAndSet(true, false)) {
                notes.trouble("Kapok Core cannot tell which jar a mod nested inside another"
                        + " came from, so nested mod classes are read as usual on this launch", t);
            }
            return null;
        }
    }

    private IModFile withModsToml(SecureJar jar, ModFileDiscoveryAttributes attributes, Path path,
            String nestedName) {
        ScanCache scans = scans();
        if (scans != null && subclassUsable.get()) {
            try {
                return new CachingModFile(jar, ModFileParser::modsTomlParser, attributes, scans,
                        path, nestedName);
            } catch (LinkageError unreachable) {
                subclassNotUsable(unreachable);
            }
        }
        return IModFile.create(jar, ModFileParser::modsTomlParser, attributes);
    }

    private IModFile withDeclaredType(SecureJar jar, IModFile.Type type,
            ModFileDiscoveryAttributes attributes, Path path, String nestedName) {
        ScanCache scans = scans();
        if (scans != null && subclassUsable.get()) {
            try {
                return new CachingModFile(jar, JarModsDotTomlModFileReader::manifestParser,
                        type, attributes, scans, path, nestedName);
            } catch (LinkageError unreachable) {
                subclassNotUsable(unreachable);
            }
        }
        return IModFile.create(jar, JarModsDotTomlModFileReader::manifestParser, type, attributes);
    }

    /**
     * Only a linkage failure is caught, and only here. Anything else thrown while
     * building a mod file is the loader's own complaint about that jar and belongs to the
     * blanket handler in {@link #read}, which seals the whole feature.
     */
    private void subclassNotUsable(LinkageError cause) {
        if (subclassUsable.compareAndSet(true, false)) {
            notes.trouble("Kapok Core cannot store class scans on this launch: its ModFile"
                    + " subclass is not reachable from the module it was loaded into."
                    + " Mod classes will still be read only once each", cause);
        }
    }

    /** @return the cache, or null when this launch has nowhere to keep one */
    private ScanCache scans() {
        ScanCache current = this.cache;
        if (current == null) {
            synchronized (this) {
                current = this.cache;
                if (current == null) {
                    current = build();
                    this.cache = current;
                }
            }
        }
        return current.usable() ? current : null;
    }

    private ScanCache build() {
        Path gameDirectory;
        String version;
        try {
            gameDirectory = environment.gameDirectory();
            version = environment.neoForgeVersion();
        } catch (Throwable t) {
            // Activation read both of these a moment ago, so arriving here means the
            // loader changed its mind mid-discovery.
            notes.trouble("Kapok Core will not store class scans on this launch: the loader"
                    + " stopped answering where the game directory is", t);
            return ScanCache.under(null, null, notes);
        }
        if (gameDirectory == null || version == null) {
            notes.info("Kapok Core will not store class scans on this launch: the loader has"
                    + " not published a game directory. Mod classes will still be read only once"
                    + " each.");
            return ScanCache.under(null, null, notes);
        }
        return ScanCache.under(gameDirectory, version, notes);
    }

    /**
     * Decides once per jar whether the cheap {@code verifyPath} is sound for it.
     *
     * <p>A signed jar is handed back untouched, so signed mods run none of this code at
     * all. The decision is safe to take once because the signing sets are populated only
     * while {@code JarContents} is being built: {@code verifyAndGetSigners} returns before
     * it can reach {@code ManifestVerifier} whenever both sets are empty, so "unsigned"
     * cannot later become "signed".
     */
    SecureJar cheapen(JarContents contents, SecureJar built, JarMetadata metadata) {
        return cheapen(contents, built, metadata, built instanceof Jar);
    }

    /**
     * @param familiar whether {@code built} is the {@code cpw.mods.jarhandling.impl.Jar}
     *                 the equivalence argument was written against. Split out from the
     *                 {@code instanceof} so tests can reach all three branches - building
     *                 a real {@code Jar} needs the union filesystem, which needs an
     *                 --add-opens on the test JVM.
     */
    SecureJar cheapen(JarContents contents, SecureJar built, JarMetadata metadata, boolean familiar) {
        if (!familiar) {
            // SecureJar.from is documented to produce impl.Jar; if that ever stops being
            // true, the equivalence argument above no longer describes this object.
            passedThrough.incrementAndGet();
            detail("left alone (unfamiliar SecureJar " + built.getClass().getName() + ")", contents);
            return built;
        }
        if (built.hasSecurityData()) {
            passedThrough.incrementAndGet();
            detail("left alone (signed)", contents);
            return built;
        }
        wrapped.incrementAndGet();
        detail("second read skipped", contents);
        if (announcedFirstWrap.compareAndSet(false, true)) {
            // The activation line proves the reader loaded and the guards passed; this one
            // proves a mod jar actually went through the wrapper. Both are INFO because
            // between them they are the only evidence this jar did anything - there is no
            // getter for the securityStatus we influence, and no hook that fires when
            // discovery ends, so a running total cannot be published anywhere.
            if (!notes.info("Kapok Core wrapped its first mod file: " + contents.getPrimaryPath())) {
                // Same rule as the activation line: if we cannot be seen, we stop. The
                // jars already wrapped keep their wrapper, which is correct behaviour on
                // its own; we simply claim no more of them.
                activation.seal("its own log output could not be written", null);
            }
        }
        return new CheapVerifySecureJar(built, metadata);
    }

    private void detail(String what, JarContents contents) {
        if (!notes.detailed()) {
            return;
        }
        notes.detail(what + " [" + wrapped.get() + " wrapped, " + passedThrough.get()
                + " passed through]: " + contents.getPrimaryPath());
    }

    /** Test seam: lets a test see whether a failure sealed the feature. */
    Activation activation() {
        return activation;
    }

    /** Shown in the loader's own "Found N implementations of IModFileReader" debug list. */
    @Override
    public String toString() {
        return "kapok core single-pass mod file reader";
    }
}
