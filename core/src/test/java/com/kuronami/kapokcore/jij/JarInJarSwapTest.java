package com.kuronami.kapokcore.jij;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kuronami.kapokcore.Notes;
import cpw.mods.jarhandling.JarContents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reflection walk, against a stand-in shaped like {@code ModDiscoverer}: a private
 * final list of dependency locators and an inner class handed out as the pipeline, which
 * is the only handle the real thing gives anybody.
 */
class JarInJarSwapTest {
    @TempDir
    Path cache;

    private final Lines lines = new Lines();

    private static final class OtherLocator implements IDependencyLocator {
        @Override
        public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        }
    }

    /** Stands in for {@code ModDiscoverer}: same field, same inner-class pipeline. */
    private static final class Discoverer {
        private final List<IDependencyLocator> dependencyLocators;

        Discoverer(List<IDependencyLocator> locators) {
            this.dependencyLocators = List.copyOf(locators);
        }

        /**
         * Not static, and using the enclosing instance, so that it carries a
         * {@code this$0} the way the loader's does - javac elides the field in an inner
         * class that never reaches for its enclosing object.
         */
        final class Pipeline implements IDiscoveryPipeline {
            /** The list as it stands now, read the way the swap has to read it. */
            List<IDependencyLocator> locators() {
                return Discoverer.this.dependencyLocators;
            }

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
                return null;
            }

            @Override
            public void addIssue(ModLoadingIssue issue) {
            }
        }
    }

    /**
     * A discoverer whose {@code dependencyLocators} is not a list of them.
     *
     * <p>Stands for a NeoForge line that kept the name and changed what is behind it,
     * which the swap has to notice before it writes anything.
     */
    private static final class OddDiscoverer {
        private final Object dependencyLocators = "not a list of locators";

        final class Pipeline implements IDiscoveryPipeline {
            @Override
            public Optional<IModFile> addPath(List<Path> paths, ModFileDiscoveryAttributes attributes,
                    IncompatibleFileReporting reporting) {
                return Optional.of(nothing());
            }

            @Override
            public Optional<IModFile> addJarContent(JarContents contents,
                    ModFileDiscoveryAttributes attributes, IncompatibleFileReporting reporting) {
                return Optional.empty();
            }

            @Override
            public boolean addModFile(IModFile modFile) {
                return OddDiscoverer.this.dependencyLocators == null;
            }

            @Override
            public IModFile readModFile(JarContents contents, ModFileDiscoveryAttributes attributes) {
                return null;
            }

            @Override
            public void addIssue(ModLoadingIssue issue) {
            }

            private IModFile nothing() {
                throw new UnsupportedOperationException();
            }
        }
    }

    /** A pipeline that is not the loader's: no enclosing instance to find. */
    private static final class LoosePipeline implements IDiscoveryPipeline {
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
            return null;
        }

        @Override
        public void addIssue(ModLoadingIssue issue) {
        }
    }

    private List<IDependencyLocator> locatorsOf(Discoverer discoverer) throws Exception {
        var field = Discoverer.class.getDeclaredField("dependencyLocators");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<IDependencyLocator> value = (List<IDependencyLocator>) field.get(discoverer);
        return value;
    }

    @Test
    void theStockLocatorIsExchangedAndTheRestAreLeftWhereTheyAre() throws Exception {
        OtherLocator before = new OtherLocator();
        OtherLocator after = new OtherLocator();
        Discoverer discoverer = new Discoverer(
                List.of(before, new JarInJarDependencyLocator(), after));

        new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), cache);

        List<IDependencyLocator> now = locatorsOf(discoverer);
        assertEquals(3, now.size());
        assertSame(before, now.get(0));
        assertInstanceOf(KapokJarInJarLocator.class, now.get(1));
        assertSame(after, now.get(2));
        assertEquals(List.of("Kapok Core: jar-in-jar locator replaced (nested jars are"
                + " written to .cache/kapok-core/nested and read from there)"), lines.info);
    }

    @Test
    void theSecondCallDoesNothing() throws Exception {
        Discoverer discoverer = new Discoverer(List.of(new JarInJarDependencyLocator()));
        JarInJarSwap swap = new JarInJarSwap(lines);

        swap.installOnce(discoverer.new Pipeline(), cache);
        IDependencyLocator installed = locatorsOf(discoverer).get(0);
        swap.installOnce(discoverer.new Pipeline(), cache);

        assertSame(installed, locatorsOf(discoverer).get(0));
        assertEquals(1, lines.info.size());
    }

    @Test
    void aLoaderWithoutTheLocatorIsLeftAlone() throws Exception {
        OtherLocator only = new OtherLocator();
        Discoverer discoverer = new Discoverer(List.of(only));

        new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), cache);

        assertSame(only, locatorsOf(discoverer).get(0));
        assertEquals(List.of("Kapok Core: nested jars left in memory (0 jar-in-jar locators"
                + " in mod discovery, expected 1)"), lines.info);
    }

    @Test
    void aLoaderCarryingTwoOfThemIsLeftAlone() throws Exception {
        JarInJarDependencyLocator first = new JarInJarDependencyLocator();
        JarInJarDependencyLocator second = new JarInJarDependencyLocator();
        Discoverer discoverer = new Discoverer(List.of(first, second));

        new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), cache);

        // Two of them is a shape nobody has seen. Replacing one and leaving the other
        // would have both walk the same nested jars and the loader call every one a
        // duplicate, so nothing is touched.
        assertSame(first, locatorsOf(discoverer).get(0));
        assertSame(second, locatorsOf(discoverer).get(1));
        assertEquals(List.of("Kapok Core: nested jars left in memory (2 jar-in-jar locators"
                + " in mod discovery, expected 1)"), lines.info);
    }

    @Test
    void anEntryThatIsNotADependencyLocatorIsLeftAlone() throws Exception {
        Discoverer discoverer = new Discoverer(List.of(new JarInJarDependencyLocator()));
        replaceListWith(discoverer, List.of("not a locator at all"));

        new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), cache);

        assertEquals(List.of("Kapok Core: nested jars left in memory (an unexpected entry"
                + " in the dependency locator list)"), lines.info);
    }

    @Test
    void aFieldHoldingSomethingOtherThanAListIsLeftAlone() {
        new JarInJarSwap(lines).installOnce(new OddDiscoverer().new Pipeline(), cache);

        assertEquals(List.of("Kapok Core: nested jars left in memory (mod discovery does"
                + " not hold a list of dependency locators)"), lines.info);
    }

    /** Puts something the swap does not expect where the locator list lives. */
    private void replaceListWith(Discoverer discoverer, Object value) throws Exception {
        var field = Discoverer.class.getDeclaredField("dependencyLocators");
        field.setAccessible(true);
        field.set(discoverer, value);
    }

    @Test
    void aPipelineThatIsNotTheLoadersIsLeftAlone() {
        new JarInJarSwap(lines).installOnce(new LoosePipeline(), cache);

        assertEquals(1, lines.info.size());
        assertTrue(lines.info.get(0).contains("nested jars left in memory"));
    }

    @Test
    void noGameDirectoryMeansNowhereToWriteAndNothingToInstall() throws Exception {
        Discoverer discoverer = new Discoverer(List.of(new JarInJarDependencyLocator()));

        new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), null);

        assertInstanceOf(JarInJarDependencyLocator.class, locatorsOf(discoverer).get(0));
        assertEquals(List.of("Kapok Core: nested jars left in memory (the game directory is"
                + " not known)"), lines.info);
    }

    @Test
    void aSwapThatCannotBeAnnouncedIsPutBack() throws Exception {
        JarInJarDependencyLocator stock = new JarInJarDependencyLocator();
        Discoverer discoverer = new Discoverer(List.of(stock));
        lines.infoLands = false;

        new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), cache);

        // The only evidence this jar rearranged discovery is its own line in the log.
        assertSame(stock, locatorsOf(discoverer).get(0));
    }

    @Test
    void standingDownIsRequestableForADevelopmentLaunch() throws Exception {
        JarInJarDependencyLocator stock = new JarInJarDependencyLocator();
        Discoverer discoverer = new Discoverer(List.of(stock));
        System.setProperty(JarInJarSwap.PROPERTY, "off");
        try {
            new JarInJarSwap(lines).installOnce(discoverer.new Pipeline(), cache);
        } finally {
            System.clearProperty(JarInJarSwap.PROPERTY);
        }

        assertSame(stock, locatorsOf(discoverer).get(0));
        assertFalse(lines.info.isEmpty());
    }

    /** Captures what would have been written to the log. */
    private static final class Lines implements Notes {
        final List<String> info = new ArrayList<>();
        boolean infoLands = true;

        @Override
        public boolean info(String message) {
            if (!infoLands) {
                return false;
            }
            info.add(message);
            return true;
        }

        @Override
        public boolean detailed() {
            return true;
        }

        @Override
        public void detail(String message) {
        }

        @Override
        public void trouble(String message, Throwable cause) {
            info.add(message);
        }
    }
}
