package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DlssdParticleGuideContractTest {
    @Test
    void particlesRejectCurrentAndVacatedFootprintHistory() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        String mask = read("shaders/display/dlssd_disocclusion.comp");
        String pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssdDisocclusionPipeline.java");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

        int particleBranch = raygen.indexOf("if (material == MATERIAL_PARTICLE)");
        int particleResponsivity = raygen.indexOf("gv_animatedGuide = 1.0;", particleBranch);
        int nextMaterialBranch = raygen.indexOf("if (material == MATERIAL_WATER)", particleBranch);
        assertTrue(particleBranch >= 0 && particleResponsivity > particleBranch);
        assertTrue(nextMaterialBranch < 0 || particleResponsivity < nextMaterialBranch);

        assertTrue(mask.contains("imageStore(currentTemporalGuideHistory, pixel, vec4(animated))"));
        assertTrue(mask.contains("if (pc.resetHistory == 0u)"));
        assertTrue(mask.contains("imageLoad(previousTemporalGuideHistory, pixel)"));
        assertTrue(mask.contains("max(currentBias, previousAnimated)"));
        assertTrue(mask.contains("imageStore(disocclusionImage, pixel, vec4(disocclusion))"));

        assertTrue(pipeline.contains("IMAGE_BINDINGS = 9"));
        assertTrue(pipeline.contains("temporalGuideHistoryA, long temporalGuideHistoryB"));
        assertTrue(composite.contains("gTemporalGuideHistoryA"));
        assertTrue(composite.contains("gTemporalGuideHistoryB"));
        assertTrue(raygen.contains("if (encounteredAnimatedWater) gv_animatedGuide = 1.0"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
