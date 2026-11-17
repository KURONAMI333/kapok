package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

class JarMetadataCompatibilityTest {
    @Test
    void essentialCanReadTheOriginalMetadataThroughItsUnsafeFieldGetter(@TempDir Path dir)
            throws Exception {
        try (JarContents contents = JarContents.of(JarFixtures.unsigned(dir, "library-5.11.0-all.jar", 1))) {
            MutableMetadata metadata = new MutableMetadata();
            SecureJar delegate = SecureJar.from(contents, metadata);
            KapokCoreReader reader = new KapokCoreReader(CountingEnvironment.healthy(), new RecordingNotes());
            SecureJar wrapper = reader.cheapen(contents, delegate, metadata);

            // Essential stage2's SortedJarOrPathList uses getDeclaredField("metadata")
            // and UnsafeHacks.makeGetter. A record component also fails this boundary:
            // Unsafe.objectFieldOffset rejects record fields.
            assertSame(metadata, readLikeEssential(wrapper));
            metadata.version = "5.11.0";
            assertEquals("5.11.0", readLikeEssential(wrapper).version(),
                    "ModJarMetadata is populated after wrapping; preserve its identity, not a snapshot");
        }
    }

    private static JarMetadata readLikeEssential(SecureJar jar) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        Field metadata = jar.getClass().getDeclaredField("metadata");
        return (JarMetadata) unsafe.getObject(jar, unsafe.objectFieldOffset(metadata));
    }

    private static final class MutableMetadata implements JarMetadata {
        private String version = "4.3.0";

        public String name() { return "library"; }
        public String version() { return version; }
        public ModuleDescriptor descriptor() {
            return ModuleDescriptor.newAutomaticModule(name()).version(version).build();
        }
    }
}
