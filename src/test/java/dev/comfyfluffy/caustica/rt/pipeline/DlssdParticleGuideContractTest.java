package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
        String build = read("build.gradle");

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

        assertTrue(mask.contains("#if CAUSTICA_PARTICLE_TEMPORAL_HISTORY"));
        assertTrue(build.contains("-DCAUSTICA_PARTICLE_TEMPORAL_HISTORY=0"));
        assertTrue(build.contains("-DCAUSTICA_PARTICLE_TEMPORAL_HISTORY=1"));
        assertTrue(build.contains("dlssd_disocclusion_particle_history.comp.spv"));
        assertTrue(pipeline.contains("particleTemporalHistory ? 9 : BASE_IMAGE_BINDINGS"));
        assertTrue(pipeline.contains("PARTICLE_HISTORY_SHADER"));
        assertTrue(pipeline.contains("temporalGuideHistoryA, long temporalGuideHistoryB"));
        assertTrue(composite.contains("gTemporalGuideHistoryA"));
        assertTrue(composite.contains("gTemporalGuideHistoryB"));
        assertTrue(composite.contains("if (particleTemporalHistory)"));
        assertTrue(config.contains("dlss-rr.particle-temporal-history\", false"));
        assertFalse(config.contains("dlss-rr.particle-temporal-history\", true"));
        assertTrue(raygen.contains("if (encounteredAnimatedWater) gv_animatedGuide = 1.0"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
