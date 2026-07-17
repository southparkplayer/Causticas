package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DlssdWaterGuideContractTest {
    @Test
    void animatedWaterReachesStreamlineBiasHintEndToEnd() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        String mask = read("shaders/display/dlssd_disocclusion.comp");
        String pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssdDisocclusionPipeline.java");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String rr = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java");

        assertTrue(raygen.contains("gAnimatedGuide[pix] = gv_animatedGuide"));
        assertTrue(raygen.contains("interfacePos.xz + pc.waterAnchor.xy"));
        assertTrue(raygen.contains("interfaceNormal = applyWaterWaves(interfaceNormal, waterDomain, pc.waterParams.w)"));
        assertTrue(raygen.contains("encounteredAnimatedWater = true"));
        assertTrue(raygen.contains("if (encounteredAnimatedWater) gv_animatedGuide = 1.0"));
        assertTrue(raygen.indexOf("if (encounteredAnimatedWater) gv_animatedGuide = 1.0")
                < raygen.indexOf("if (destinationValid)"));
        assertTrue(mask.contains("imageLoad(animatedGuideImage, pixel)"));
        assertTrue(mask.contains("max(disocclusion, animated)"));
        assertTrue(pipeline.contains("long biasCurrent, long animatedGuide"));
        assertTrue(composite.contains("gAnimatedGuide.view"));
        assertTrue(rr.contains("BUFFER_BIAS_CURRENT_COLOR_HINT"));
        assertTrue(rr.contains("writeResource(resources, 8, biasCurrentColor"));
    }

    @Test
    void unresolvedWaterNeverExportsAbsorptionTintAsDiffuseAlbedo() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        assertTrue(raygen.contains("crossing < uint(MAX_OPTICAL_INTERFACE_DEPTH)"));
        assertTrue(raygen.contains("gv_albedo = float3(0.0, 0.0, 0.0)"));
        assertTrue(raygen.contains("Total internal reflection has no transmitted diffuse destination"));
        assertTrue(raygen.contains("transmitted = false"));
        assertTrue(raygen.contains("if (dot(nextDirection, nextDirection) <= 0.0) return"));
        assertTrue(raygen.contains("Crossing-budget exhaustion means no trustworthy diffuse destination"));
        assertFalse(raygen.contains("gv_opticalFallbackAlbedo"));
        assertFalse(raygen.contains("unresolvedDiffuseAlbedo"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n");
    }
}
