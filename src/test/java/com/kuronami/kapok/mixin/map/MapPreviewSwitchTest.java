package com.kuronami.kapok.mixin.map;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The two switches that govern the map-preview null have to reach the copy
 * that survives with c2me installed.
 *
 * <p>{@code kapok-map.mixins.json} is the one chunk-related config that
 * applies when c2me is present, so it carries its own copy of the early return
 * that {@code ServerChunkCacheGuardMixin} also has. That copy is only run when
 * {@code ServerChunkCacheGuardMixin} is not, which means a switch missing from
 * it is invisible on the packs where c2me is absent and silently defeats the
 * A/N measurement on the packs where it is present.
 *
 * <p>This was a real gap: the first version checked
 * {@link com.kuronami.kapok.chunk.ChunkLoadGuard#NO_BIOME_LOAD} but not
 * {@link com.kuronami.kapok.chunk.ChunkLoadGuard#GUARD}, so
 * {@code -Dkapok.chunk.guard=false} still answered null for map biome
 * lookups and "against vanilla" was not vanilla. The question is about which
 * names appear in the source, not about an answer, so it is read off the
 * source.
 */
class MapPreviewSwitchTest {

    private static final Path MIXIN = Path.of(
            "src/main/java/com/kuronami/kapok/mixin/map/MapPreviewChunkGuardMixin.java");

    @Test
    void bothMeasurementSwitchesReachTheMapCopy() throws IOException {
        assertTrue(Files.isRegularFile(MIXIN),
                "expected the mixin at " + MIXIN.toAbsolutePath()
                        + "; if it moved, move this test with it");
        String source = Files.readString(MIXIN, StandardCharsets.UTF_8);

        assertTrue(source.contains("ChunkLoadGuard.GUARD"),
                "kapok.chunk.guard=false no longer turns the map branch off;"
                        + " the map copy must check GUARD like ServerChunkCacheGuardMixin does");
        assertTrue(source.contains("ChunkLoadGuard.NO_BIOME_LOAD"),
                "kapok.chunk.noBiomeLoad=false no longer turns the map branch off");
        assertTrue(source.contains("shouldReturnNullForMapPreview"),
                "the map branch is gone or no longer shares its decision with the chunk guard");
        assertTrue(source.contains("ChunkLoadGuard.inMapPreview()"),
                "the map branch no longer checks that a preview is running");
    }
}
