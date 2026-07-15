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
        assertTrue(composite.contains("previousWaterWaveTimeValid ? previousWaterWaveTime : waterWaveTime"));
        assertTrue(composite.contains("terrain.blockZ & WATER_ANCHOR_MASK, previousWaveTime, 0f"));
        assertFalse(composite.contains("System.nanoTime() / 1.0e9 % 3600.0"));
    }
}
