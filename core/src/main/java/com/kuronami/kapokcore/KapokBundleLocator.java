package com.kuronami.kapokcore;

import com.kuronami.kapokcore.jij.JarInJarSwap;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Hands the Kapok mod jar carried inside this service jar to mod discovery.
 *
 * <p>Why a locator exists at all: a jar that declares any loader SPI in
 * {@code META-INF/services/} is placed in FML's SERVICE layer by
 * {@code TransformerDiscovererConstants#shouldLoadInServiceLayer} and, by the same
 * decision, excluded from the mod scan. So the file that provides
 * {@link KapokCoreReader} can never itself be a mod. The only way to ship the reader
 * and the mod as one download is for the reader's jar to carry the mod's jar and to
 * introduce it here, the way Sinytra Connector does.
 *
 * <p>Two properties of this class are deliberate:
 *
 * <ul>
 *   <li><b>Independent of {@link Activation}.</b> If the reader seals itself - another
 *       reader is present, the loader version is unfamiliar, an unexpected error - the
 *       mod half still loads. "Core off, Kapok on" is the state a split installation
 *       already had; "both silently gone" would be a regression in how failure looks.
 *   <li><b>Never throws.</b> {@code ServiceLoaderUtil} only catches
 *       {@code ServiceConfigurationError} while constructing providers, and the
 *       discovery pipeline attributes an exception from a locator to nothing useful.
 *       Every failure lands as one INFO line and an absent mod.
 * </ul>
 *
 * <p>The bundle is materialised under {@code <game dir>/.cache/kapok-core/bundle/}
 * (see {@link BundleExtractor}) so that the loader sees a regular file, the same shape
 * as anything in {@code mods/}. Deleting {@code .cache/kapok-core} - the documented
 * way to reset the scan cache - simply causes a re-extraction next launch. If the game
 * directory is unknown or the write fails, the nested path inside this jar is offered
 * instead, which is what Jar-in-Jar does for every nested mod.
 *
 * <p>{@code -Dkapok.core.bundle=off} stands the locator down. That is for development
 * launches where the mod already comes from the classpath and a second copy would be
 * rejected as a duplicate mod id; it is not a user setting and is not documented as one.
 *
 * <p>It also carries a second, unrelated job: {@link JarInJarSwap}, which replaces the
 * loader's Jar-in-Jar locator so that the jars mods carry inside themselves are written
 * out to disk rather than held in the heap. It lives here because a candidate locator is
 * handed the live discovery pipeline and is the last thing to run before the list of
 * dependency locators is read, and nothing else in this jar is either.
 */
public final class KapokBundleLocator implements IModFileCandidateLocator {
    static final String PROPERTY = "kapok.core.bundle";
    static final String BUNDLE_DIRECTORY = "bundle";

    private final LoaderEnvironment environment;
    private final Notes notes;
    private final JarInJarSwap jarInJar;

    /** Called by {@code ServiceLoaderUtil}; must not throw. Everything fallible is in {@link #findCandidates}. */
    public KapokBundleLocator() {
        this(new FmlLoaderEnvironment(), new Slf4jNotes());
    }

    KapokBundleLocator(LoaderEnvironment environment, Notes notes) {
        this.environment = environment;
        this.notes = notes;
        this.jarInJar = new JarInJarSwap(notes);
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        // Two unrelated things, in one method because this is the only moment the loader
        // hands anyone a live discovery pipeline. Kept apart so that a launch which is not
        // carrying a bundle - a development launch, or a version this jar declines to
        // touch - still gets the nested jars taken out of the heap, and the other way
        // round.
        replaceJarInJar(pipeline);
        try {
            locate(pipeline);
        } catch (Throwable t) {
            notes.trouble("Kapok bundle not loaded", t);
        }
    }

    /**
     * Stands the loader's Jar-in-Jar locator down in favour of one that writes nested jars
     * out to disk. Has to happen here: the list of dependency locators is read once, after
     * every candidate locator has run, so a candidate locator is the last thing to run
     * before it is too late.
     */
    private void replaceJarInJar(IDiscoveryPipeline pipeline) {
        try {
            String version = environment.neoForgeVersion();
            if (!VersionGate.supports(version)) {
                notes.info("Kapok Core: nested jars left in memory (built for NeoForge "
                        + VersionGate.SUPPORTED_LINE + ".x, found "
                        + VersionGate.describe(version) + ")");
                return;
            }
            Path gameDirectory = environment.gameDirectory();
            jarInJar.installOnce(pipeline, gameDirectory == null
                    ? null
                    : gameDirectory.resolve(".cache").resolve(ScanCache.DIRECTORY));
        } catch (Throwable t) {
            notes.trouble("Kapok Core: nested jars left in memory", t);
        }
    }

    private void locate(IDiscoveryPipeline pipeline) throws Exception {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY))) {
            notes.info("Kapok bundle: standing down (-D" + PROPERTY + "=off)");
            return;
        }
        String version = environment.neoForgeVersion();
        if (!VersionGate.supports(version)) {
            notes.info("Kapok bundle: not loaded on " + VersionGate.describe(version));
            return;
        }
        Optional<Path> own = OwnJar.of(KapokBundleLocator.class, environment.serviceLayerJars());
        if (own.isEmpty()) {
            notes.info("Kapok bundle: not loaded, this jar's own location is not a file ("
                    + OwnJar.describeModuleLocation(KapokBundleLocator.class) + "; development launch?)");
            return;
        }
        Path ownJar = own.get();
        try (ZipFile zip = new ZipFile(ownJar.toFile())) {
            List<String> entries = BundleExtractor.bundleEntries(zip);
            if (entries.isEmpty()) {
                notes.info("Kapok bundle: " + ownJar.getFileName() + " carries no mod jar");
                return;
            }
            List<Path> jars = materialise(zip, entries, ownJar);
            for (Path jar : jars) {
                pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT.withLocator(this), IncompatibleFileReporting.ERROR);
                notes.info("Kapok bundle: loaded " + jar.getFileName() + " from " + ownJar.getFileName());
            }
        }
    }

    private List<Path> materialise(ZipFile zip, List<String> entries, Path ownJar) throws Exception {
        Path gameDirectory = environment.gameDirectory();
        if (gameDirectory != null) {
            Path target = gameDirectory.resolve(".cache").resolve(ScanCache.DIRECTORY).resolve(BUNDLE_DIRECTORY);
            try {
                return BundleExtractor.materialise(zip, entries, target);
            } catch (Exception e) {
                notes.trouble("Kapok bundle: could not be written under " + target + ", mounting it in place", e);
            }
        }
        // Left open on purpose: the loader keeps reading the mounted jar for the whole
        // session, and a closed filesystem would fail every later read.
        FileSystem inPlace = FileSystems.newFileSystem(ownJar);
        return entries.stream().map(inPlace::getPath).toList();
    }
}
