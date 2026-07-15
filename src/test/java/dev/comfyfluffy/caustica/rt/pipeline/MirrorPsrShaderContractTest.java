package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MirrorPsrShaderContractTest {
    @Test
    void exactMetalMirrorsUseDeltaContinuationAndPrimarySurfaceReplacement() throws Exception {
        String source = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(source.contains("perceptualRough <= PSR_MIRROR_ROUGHNESS_MAX && metal >= 0.999"));
        assertTrue(source.contains("if (exactMirror)"));
        assertTrue(source.contains("rd = reflect(rd, n);"));
        assertTrue(source.contains("if (gv_psrMirror)"));
        assertTrue(source.contains("float3 virtualHit = reflectedHit - 2.0 * n"));
    }

    @Test
    void glossySurfacesRetainTheFiniteGgxFloor() throws Exception {
        String source = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(source.contains("float rough = max(perceptualRough, MIN_ROUGH);"));
        assertTrue(source.contains("static const float MIN_ROUGH = 0.045;"));
    }
}
