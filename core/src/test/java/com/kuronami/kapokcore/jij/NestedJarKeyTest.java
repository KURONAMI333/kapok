package com.kuronami.kapokcore.jij;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NestedJarKeyTest {
    private static final Path MOD = Path.of("C:", "games", "mods", "some-mod-1.2.3.jar");
    private static final String ENTRY = "META-INF/jarjar/library-4.5.6.jar";

    @Test
    void theSameEntryOfTheSameJarKeepsTheSameKey() {
        assertEquals(NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L),
                NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L));
    }

    @Test
    void aChangedLengthIsADifferentKey() {
        assertNotEquals(NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L),
                NestedJarKey.of(MOD, ENTRY, 1025, 0xABCDEF01L));
    }

    @Test
    void aChangedChecksumIsADifferentKey() {
        assertNotEquals(NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L),
                NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF02L));
    }

    @Test
    void theSameLibraryInsideTwoModsIsKeptApart() {
        Path other = Path.of("C:", "games", "mods", "other-mod-9.9.9.jar");
        assertNotEquals(NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L),
                NestedJarKey.of(other, ENTRY, 1024, 0xABCDEF01L));
    }

    @Test
    void theSameLibraryUnderTwoEntryNamesIsKeptApart() {
        // META-INF/jarjar/ and META-INF/jars/ are both in use in the wild.
        assertNotEquals(NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L),
                NestedJarKey.of(MOD, "META-INF/jars/library-4.5.6.jar", 1024, 0xABCDEF01L));
    }

    @Test
    void fieldsCannotBeShuffledIntoEachOther() {
        assertNotEquals(NestedJarKey.of(MOD, "a/b.jar", 12, 34),
                NestedJarKey.of(MOD, "a/b.jar1", 2, 34));
    }

    @Test
    void aKeyIsThirtyTwoHexCharacters() {
        String key = NestedJarKey.of(MOD, ENTRY, 1024, 0xABCDEF01L);
        assertEquals(32, key.length());
        assertTrue(NestedJarKey.looksLikeKey(key));
    }

    @Test
    void anythingElseInTheCacheDirectoryIsNotMistakenForAKey() {
        assertFalse(NestedJarKey.looksLikeKey("bundle"));
        assertFalse(NestedJarKey.looksLikeKey("0123456789abcdef0123456789abcde"));
        assertFalse(NestedJarKey.looksLikeKey("0123456789ABCDEF0123456789abcdef"));
        assertFalse(NestedJarKey.looksLikeKey("0123456789abcdef0123456789abcdeg"));
    }

    @Test
    void theSelectorsPathBecomesAZipEntryName() {
        // JarSelector turns the metadata's path into a default-filesystem Path, which
        // spells itself with backslashes on Windows; a zip entry name never does.
        assertEquals("META-INF/jarjar/library.jar",
                NestedJarKey.entryName(Path.of("META-INF", "jarjar", "library.jar")));
    }
}
