package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClaimPlanTest {
    @Test
    void aModsTomlJarIsClaimed() {
        assertEquals(ClaimPlan.CLAIM_WITH_MODS_TOML, ClaimPlan.of(null, false, true));
        assertEquals(ClaimPlan.CLAIM_WITH_MODS_TOML, ClaimPlan.of("LIBRARY", true, true));
    }

    @Test
    void aManifestOnlyJarWithAKnownTypeIsClaimed() {
        assertEquals(ClaimPlan.CLAIM_AS_MANIFEST_MOD, ClaimPlan.of("LIBRARY", true, false));
        assertEquals(ClaimPlan.CLAIM_AS_MANIFEST_MOD, ClaimPlan.of("GAMELIBRARY", true, false));
    }

    @Test
    void aJarWithNeitherMarkerIsDeclined() {
        // The built-in readers return null here too, which is what keeps
        // NestedLibraryModReader and the "no reader felt responsible" path alive.
        assertEquals(ClaimPlan.DECLINE_NOT_A_MOD, ClaimPlan.of(null, false, false));
    }

    @Test
    void anUnknownTypeBeatsTheModsTomlBranch() {
        // The order that matters. JarModsDotTomlModFileReader resolves FMLModType before
        // it looks for a mods.toml, so a jar naming a type the loader does not know is
        // rejected even when it also carries a mods.toml. Claiming such a jar would
        // swallow an error the loader raises.
        assertEquals(ClaimPlan.DECLINE_UNKNOWN_TYPE, ClaimPlan.of("NONSENSE", false, true));
        assertEquals(ClaimPlan.DECLINE_UNKNOWN_TYPE, ClaimPlan.of("NONSENSE", false, false));
    }
}
