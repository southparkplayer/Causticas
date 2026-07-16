package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WaterReflectionMotionContractTest {
    @Test
    void animatedWaterReprojectsWithPreviousWaveNormal() throws IOException {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(raygen.contains("waterWaveGradPair(waterDomain, pc.waterParams.w, pc.waterAnchor.z"));
        assertTrue(raygen.contains("gv_specPreviousNormal = previousWaterNormal"));
        assertTrue(raygen.contains("specSurfacePreviousNormal"));
        assertTrue(raygen.contains("previousReflectionNdc(surfacePos, prevN"));
        assertTrue(raygen.contains("gv_albedo = float3(0.0, 0.0, 0.0)"));
        assertTrue(raygen.contains("gv_motionHitCamRel = gv_hitCamRel"));
        assertTrue(raygen.contains("gv_motionUseRefracted = false"));
        assertTrue(raygen.contains("gv_specAlb = float3(F, F, F)"));
        assertTrue(raygen.contains("captureRefractedGuides && dot(transmittedDir, transmittedDir) > 0.0"));
        assertFalse(raygen.contains("gv_opticalFallbackAlbedo"));
        assertTrue(raygen.contains("if (waterWaves) gv_animatedGuide = 1.0"));
        assertTrue(raygen.indexOf("gv_specAlb = float3(F, F, F)")
                < raygen.indexOf("if (primaryOnly || bounce >= maxBounces)"));
        assertTrue(composite.contains("previousWaterWaveTimeValid ? previousWaterWaveTime : waterWaveTime"));
        assertTrue(composite.contains("terrain.blockZ & WATER_ANCHOR_MASK, previousWaveTime, 0f"));
        assertTrue(composite.contains("gAnimatedGuide.view"));
        assertFalse(composite.contains("System.nanoTime() / 1.0e9 % 3600.0"));
    }
}
