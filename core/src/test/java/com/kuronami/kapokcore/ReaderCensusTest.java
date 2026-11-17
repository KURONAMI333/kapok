package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReaderCensusTest {
    @Test
    void theExpectedCrowdIsNotForeign() {
        assertTrue(ReaderCensus.foreign(List.of(
                ReaderCensus.BUILT_IN_JAR_MODS_TOML,
                ReaderCensus.BUILT_IN_NESTED_LIBRARY,
                ReaderCensus.OURS)).isEmpty());
    }

    @Test
    void nothingAtAllIsNotForeign() {
        assertTrue(ReaderCensus.foreign(List.of()).isEmpty());
        assertTrue(ReaderCensus.foreign(null).isEmpty());
    }

    @Test
    void oneStrangerIsEnough() {
        assertEquals(List.of("com.example.TheirReader"), ReaderCensus.foreign(List.of(
                ReaderCensus.BUILT_IN_JAR_MODS_TOML,
                "com.example.TheirReader",
                ReaderCensus.OURS)));
    }

    @Test
    void repeatsFoldIntoOneName() {
        // LaunchContext#loadServices ends in .distinct(), but ServiceLoader.Provider does
        // not override equals, so the same provider can arrive more than once.
        assertEquals(List.of("com.example.TheirReader"), ReaderCensus.foreign(Arrays.asList(
                "com.example.TheirReader",
                "com.example.TheirReader",
                " com.example.TheirReader ")));
    }

    @Test
    void blanksAndNullsAreNotStrangers() {
        assertTrue(ReaderCensus.foreign(Arrays.asList(null, "", "   ")).isEmpty());
    }

    @Test
    void severalStrangersComeBackSorted() {
        assertEquals(List.of("a.A", "b.B", "c.C"),
                ReaderCensus.foreign(List.of("c.C", "a.A", "b.B")));
    }

    @Test
    void theCensusMustBeAbleToSeeUs() {
        assertTrue(ReaderCensus.recognisesUs(List.of(ReaderCensus.OURS)));
        assertTrue(ReaderCensus.recognisesUs(List.of(" " + ReaderCensus.OURS + " ")));
        assertFalse(ReaderCensus.recognisesUs(List.of(ReaderCensus.BUILT_IN_JAR_MODS_TOML)));
        assertFalse(ReaderCensus.recognisesUs(List.of()));
        assertFalse(ReaderCensus.recognisesUs(null));
        assertFalse(ReaderCensus.recognisesUs(Arrays.asList((String) null)));
    }

    @Test
    void theShippedServiceDeclarationMatchesTheAllowlistEntry() throws IOException {
        // If the class is ever renamed or moved, the allowlist literal and the shipped
        // META-INF/services file drift apart and the reader seals itself against its own
        // presence. This is the cheapest place to notice.
        try (InputStream in = ReaderCensusTest.class.getClassLoader()
                .getResourceAsStream("META-INF/services/net.neoforged.neoforgespi.locating.IModFileReader")) {
            assertNotNull(in, "the service declaration must ship in src/main/resources");
            String declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(ReaderCensus.OURS, declared);
        }
    }
}
