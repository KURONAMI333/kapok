package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionGateTest {
    @Test
    void acceptsTheLineItWasReadAgainst() {
        assertTrue(VersionGate.supports("21.1.247"));
        assertTrue(VersionGate.supports("21.1.0"));
        assertTrue(VersionGate.supports("21.1.9"));
        assertTrue(VersionGate.supports(" 21.1.247 "));
    }

    @Test
    void acceptsPrereleaseSuffixesOnTheSameLine() {
        assertTrue(VersionGate.supports("21.1.0-beta"));
        assertTrue(VersionGate.supports("21.1.180-beta.3"));
    }

    @Test
    void refusesEveryOtherLine() {
        assertFalse(VersionGate.supports("21.2.1"));
        assertFalse(VersionGate.supports("21.10.5"));
        assertFalse(VersionGate.supports("20.4.100"));
        assertFalse(VersionGate.supports("22.1.1"));
        assertFalse(VersionGate.supports("26.1.2"));
    }

    @Test
    void refusesAnythingItCannotRead() {
        assertFalse(VersionGate.supports(null));
        assertFalse(VersionGate.supports(""));
        assertFalse(VersionGate.supports("   "));
        assertFalse(VersionGate.supports("21.1"));
        assertFalse(VersionGate.supports("21"));
        assertFalse(VersionGate.supports("21.1.x"));
        assertFalse(VersionGate.supports("not-a-version"));
    }

    @Test
    void describesAnAbsentVersionWithoutPrintingNull() {
        assertEquals("<unknown>", VersionGate.describe(null));
        assertEquals("<unknown>", VersionGate.describe("  "));
        assertEquals("21.1.247", VersionGate.describe(" 21.1.247 "));
    }
}
