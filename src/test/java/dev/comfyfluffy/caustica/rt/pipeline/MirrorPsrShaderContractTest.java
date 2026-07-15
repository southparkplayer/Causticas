package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MirrorPsrShaderContractTest {
    @Test
    void exactMetalMirrorsUseDeltaContinuationAndRecursivePrimarySurfaceReplacement() throws Exception {
        String source = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(source.contains("perceptualRough <= PSR_MIRROR_ROUGHNESS_MAX && metal >= 0.999"));
        assertTrue(source.contains("if (exactMirror)"));
        assertTrue(source.contains("rd = reflect(rd, n);"));
        assertTrue(source.contains("PSR_MAX_MIRRORS = 3u"));
        assertTrue(source.contains("recursivePrimarySurfaceReplacement("));
        assertTrue(source.contains("for (int i = int(mirrorCount) - 1; i >= 0; --i)"));
        assertTrue(source.contains("finalHit -= 2.0 * n * dot(finalHit - mirrorPos[i], n)"));
    }

    @Test
    void glossySurfacesRetainTheFiniteGgxFloor() throws Exception {
        String source = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(source.contains("float rough = max(perceptualRough, MIN_ROUGH);"));
        assertTrue(source.contains("static const float MIN_ROUGH = 0.045;"));
    }
}
