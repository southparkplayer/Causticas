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
        assertTrue(raygen.contains("interfaceNormal = applyWaterWaves(interfaceNormal, waterDomain, waterWaveTime)"));
        assertTrue(raygen.contains("encounteredAnimatedWater = true"));
        assertTrue(raygen.contains("if (encounteredAnimatedWater) gv_animatedGuide = 1.0"));
        assertTrue(raygen.indexOf("if (encounteredAnimatedWater) gv_animatedGuide = 1.0")
                < raygen.indexOf("if (destinationValid)"));
        assertTrue(mask.contains("imageLoad(animatedGuideImage, pixel)"));
        assertTrue(mask.contains("float currentBias = max(disocclusion, animated)"));
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

    @Test
    void refractedWaterKeepsInterfaceIdentityAndDestinationColorGuides() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        int destination = raygen.indexOf("if (destinationValid) {");
        int failure = raygen.indexOf("} else {\n            // No coherent transmitted layer", destination);
        String validPath = raygen.substring(destination, failure);

        assertTrue(validPath.contains("gv_motionHitCamRel = destinationHitCamRel"));
        assertTrue(validPath.contains("gv_albedo = destinationDiffuseAlbedo"));
        assertTrue(validPath.contains("if (glassGuide) {"));
        assertTrue(validPath.contains("gv_hitCamRel = destinationHitCamRel"));
        assertFalse(validPath.contains("gv_normal = destinationNormal"));
        assertFalse(validPath.contains("gv_rough = destinationRoughness"));
        assertTrue(validPath.indexOf("if (glassGuide) {") < validPath.indexOf("gv_hitCamRel = destinationHitCamRel"));
        assertTrue(raygen.contains("Water depth remains on the physical animated"));
        assertTrue(raygen.contains("gv_animatedGuide = 1.0;\n        }\n    }"));
        assertFalse(raygen.contains("Clear dielectric depth follows the transmitted destination"));
    }

    @Test
    void highQualityWaterUsesACompileTimeIsolatedPreviousRefractionProbe() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        String build = read("build.gradle");
        String bringup = read("src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java");
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");

        assertTrue(raygen.contains("#define CAUSTICA_TRANSPARENCY_HQ 0"));
        assertTrue(raygen.contains("#if CAUSTICA_TRANSPARENCY_HQ"));
        assertTrue(raygen.contains("previousIncidentDir = normalize((hitPos - pc.camOffset) + pc.camDelta)"));
        assertTrue(raygen.contains("gv_opticalGuidePreviousDir = previousTransmittedDir"));
        assertTrue(raygen.contains("rayConeSpread, pc.waterAnchor.z"));
        assertTrue(raygen.contains("gv_motionUseExplicitPrevious = true"));
        assertTrue(raygen.contains("if (waterWaves && !gv_opticalGuidePreviousValid) gv_animatedGuide = 1.0"));
        assertTrue(raygen.contains("if (previousDestinationValid) {"));
        assertTrue(raygen.contains("} else {\n                    // A previous refracted direction alone is not enough"));
        assertTrue(raygen.contains("exhaustion the second walk has no coherent previous destination"));
        assertTrue(build.contains("-DCAUSTICA_TRANSPARENCY_HQ=0"));
        assertTrue(build.contains("-DCAUSTICA_TRANSPARENCY_HQ=1"));
        assertTrue(build.contains("world_hq.rgen.spv"));
        assertTrue(build.contains("world_base_hq.rgen.spv"));
        assertTrue(build.contains("world_sharc_base_hq.rgen.spv"));
        assertTrue(bringup.contains("highQualityTransparencyShader"));
        assertTrue(bringup.contains("if (!RtReconstruction.usesDlss()"));
        assertTrue(config.contains("dlss-rr.high-quality-transparency\", false"));
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        assertTrue(composite.contains("refreshTransparencyPipelineIfNeeded(ctx)"));
        assertTrue(composite.contains("RT transparency quality active:"));
        assertTrue(composite.contains("RtTerrain.quiesceForResourceReload(ctx)"));
        assertTrue(composite.contains("RtTerrain.pauseForResourceReload()"));
        assertTrue(composite.contains("RtTerrain.resumeAfterResourceReload()"));
        assertTrue(composite.contains("if (ctx != null) {"));
        assertFalse(composite.contains("ctx != null && worldPipeline != null"));
        assertTrue(composite.contains("materialBindingsReady && !reloadRebindRequested"));
        assertFalse(composite.contains("ctx.waitIdle(\"resource reload\")"));
        String terrain = read("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java");
        assertTrue(terrain.contains("public static void quiesceForResourceReload(RtContext ctx)"));
        assertTrue(terrain.contains("if (INSTANCE.resourceReloadPaused) return"));
        assertTrue(terrain.contains("if (!INSTANCE.resourceReloadPaused && RtMaterialRegistry.INSTANCE.isReady())"));
    }

    @Test
    void highQualityTransparencyExactlySplitsOnlyThePrimaryDielectric() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");

        assertTrue(raygen.contains("#if CAUSTICA_TRANSPARENCY_HQ && !CAUSTICA_NRD"
                + " && !CAUSTICA_OFFLINE && !CAUSTICA_SHARC_UPDATE"));
        assertTrue(raygen.contains("#define CAUSTICA_PRIMARY_DIELECTRIC_SPLIT 1"));
        assertTrue(raygen.contains("#define CAUSTICA_PRIMARY_DIELECTRIC_SPLIT 0"));
        assertTrue(raygen.contains("if (segment == 0 && primaryOpticalLobe != 0u)"));
        assertTrue(raygen.contains("throughput *= F"));
        assertTrue(raygen.contains("throughput *= 1.0 - F"));
        assertTrue(raygen.contains("PRIMARY_OPTICAL_TRANSMISSION, primaryDielectricSplit"));
        assertTrue(raygen.contains("PRIMARY_OPTICAL_REFLECTION, reflectedPrimaryDielectricSplit"));
        assertTrue(raygen.contains("if (primaryDielectricSplit && !primaryOnly)"));
        assertTrue(raygen.contains("sampler.state = sampleHash(sampler.state ^ 0xa511e9b3u)"));
        assertFalse(raygen.contains("reflectionSampler.state = sampleHash"));
        assertTrue(raygen.contains("gv_specAlb = F >= 1.0 ? float3(1.0)"));
        assertTrue(raygen.contains("rrSpecularAlbedo(float3(f0), gv_rough * gv_rough, cosI)"));
        assertTrue(raygen.contains("static const float WATER_GUIDE_ROUGH = 0.0"));
        assertTrue(raygen.contains("static const float GLASS_GUIDE_ROUGH = 0.0"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n");
    }
}
