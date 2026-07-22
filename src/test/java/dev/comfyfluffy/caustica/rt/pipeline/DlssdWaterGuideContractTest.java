package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DlssdWaterGuideContractTest {
    @Test
    void animatedWaterDoesNotAnnihilateTheWholePixelHistory() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        String mask = read("shaders/display/dlssd_disocclusion.comp");
        String pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssdDisocclusionPipeline.java");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String rr = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java");

        assertTrue(raygen.contains("gAnimatedGuide[pix] = gv_animatedGuide"));
        assertTrue(raygen.contains("interfacePos.xz + pc.waterAnchor.xy"));
        assertTrue(raygen.contains("interfaceNormal = applyWaterWaves(interfaceNormal, waterDomain,"
                + " waterWaveTime, rayConeWidth)"));
        assertTrue(raygen.contains("encounteredAnimatedWater = true"));
        assertFalse(raygen.contains("if (encounteredAnimatedWater) gv_animatedGuide = 1.0"));
        assertFalse(raygen.contains("if (waterWaves) gv_animatedGuide = 1.0"));
        assertTrue(raygen.contains("gv_animatedGuide = max(gv_animatedGuide, waveHistoryBias)"));
        assertTrue(raygen.contains("saturate((angularPixels - 0.25) * (1.0 / 1.75))"));
        assertTrue(raygen.contains("length(n - previousWaterNormal)"));
        assertTrue(mask.contains("imageLoad(animatedGuideImage, pixel)"));
        assertTrue(mask.contains("float currentBias = max(disocclusion, animated)"));
        assertTrue(pipeline.contains("long biasCurrent, long animatedGuide"));
        assertTrue(composite.contains("gAnimatedGuide.view"));
        assertTrue(rr.contains("BUFFER_BIAS_CURRENT_COLOR_HINT"));
        assertTrue(rr.contains("writeResource(resources, 8, biasCurrentColor"));
    }

    @Test
    void opticalGuideFollowsTirWithoutInventingDiffuseAlbedo() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        assertTrue(raygen.contains("crossing < uint(MAX_OPTICAL_INTERFACE_DEPTH)"));
        assertTrue(raygen.contains("gv_albedo = float3(0.0, 0.0, 0.0)"));
        assertTrue(raygen.contains("TIR is not a failed optical path"));
        assertTrue(raygen.contains("transmitted = false"));
        assertTrue(raygen.contains("direction = normalize(reflect(direction, interfaceNormal))"));
        assertFalse(raygen.contains("failure = OPTICAL_GUIDE_FAILURE_TIR;\n            return;"));
        assertTrue(raygen.contains("Crossing-budget exhaustion means no trustworthy diffuse destination"));
        assertFalse(raygen.contains("gv_opticalFallbackAlbedo"));
        assertFalse(raygen.contains("unresolvedDiffuseAlbedo"));
    }

    @Test
    void refractionOwnsOrdinaryGuidesWhileWaterRetainsSpecularGuides() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        int destination = raygen.indexOf("if (destinationValid) {");
        int failure = raygen.indexOf("} else {\n            // No coherent transmitted layer", destination);
        String validPath = raygen.substring(destination, failure);

        assertTrue(validPath.contains("gv_motionHitCamRel = destinationHitCamRel"));
        assertTrue(validPath.contains("gv_albedo = destinationDiffuseAlbedo"));
        assertTrue(validPath.contains("gv_hitCamRel = destinationHitCamRel"));
        assertTrue(validPath.contains("gv_normal = destinationNormal"));
        assertTrue(validPath.contains("gv_rough = destinationRoughness"));
        assertFalse(validPath.contains("gv_specAlb = destinationSpecularAlbedo"));
        assertTrue(raygen.contains("gv_opticalGuideMode = OPTICAL_GUIDE_TRANSMISSION"));
        assertTrue(raygen.contains("gv_opticalGuideIor = surfaceWaterEntering ? WATER_IOR : 1.0"));
        assertTrue(raygen.contains("gSpecAlbedo[pix] = float4(resolvedSpecularAlbedo, 1.0)"));
        assertTrue(raygen.contains("bool interfaceSpecMotionRequired = debugSpecMotion || writeRrGuides"));
        assertFalse(raygen.contains("if (writeRrGuides && gv_motionUseRefracted) specMotion = motion"));
        assertFalse(raygen.contains("if (encounteredAnimatedWater) gv_animatedGuide = 1.0"));
    }

    @Test
    void highQualityWaterIsASettingsGatedDlssOnlyShaderSpecialization() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        String build = read("build.gradle");
        String bringup = read("src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java");
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String settings = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");

        assertTrue(raygen.contains("#define CAUSTICA_TRANSPARENCY_HQ 0"));
        assertTrue(build.contains("-DCAUSTICA_TRANSPARENCY_HQ=0"));
        assertTrue(build.contains("-DCAUSTICA_TRANSPARENCY_HQ=1"));
        assertTrue(build.contains("world_hq.rgen.spv"));
        assertTrue(build.contains("world_nv_hq.rgen.spv"));
        assertTrue(build.contains("world_base_hq.rgen.spv"));
        assertFalse(build.contains("world_nrd_hq.rgen.spv"));
        assertFalse(build.contains("world_sharc_hq.rgen.spv"));
        assertTrue(bringup.contains("highQualityTransparencyShader"));
        assertTrue(bringup.contains("return shader.substring(0, suffix) + \"_hq\" + shader.substring(suffix)"));
        assertTrue(bringup.contains("!RtReconstruction.usesDlss() || !RtDlssRr.INSTANCE.isOperational()"));
        assertFalse(bringup.substring(bringup.indexOf("public static String sharcQueryRaygenShader()"),
                bringup.indexOf("public static String sharcUpdateRaygenShader()"))
                .contains("highQualityTransparencyShader"));
        assertTrue(config.contains("dlss-rr.high-quality-transparency\", true"));
        assertTrue(composite.contains("private static boolean highQualityTransparencyEnabled()"));
        assertTrue(composite.contains("ADVANCED_OPTICAL_TRANSPORT.value()"));
        assertTrue(composite.contains("RtReconstruction.usesDlss()"));
        assertTrue(composite.contains("RtDlssRr.INSTANCE.isOperational()"));
        assertFalse(composite.contains("renderSizeHighQualityTransparency"));
        assertFalse(composite.contains("DLSSD premultiplied optical reflection layer"));
        assertTrue(composite.contains("refreshTransparencyPipelineIfNeeded(ctx)"));
        assertTrue(composite.contains("RT water reconstruction active:"));
        assertTrue(composite.contains("requestTemporalReset()"));
        assertTrue(settings.contains(".activeWhen(this::highQualityTransparencyControlsActive)"));
        assertTrue(settings.contains("RtReconstruction.usesDlss() && RtReconstruction.enabled()"));
    }

    @Test
    void highQualitySplitsOnlySegmentZeroWaterAndKeepsNestedMediaDisabled() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        int glassStart = raygen.indexOf("if (material == MATERIAL_GLASS)");
        int waterStart = raygen.indexOf("if (material == MATERIAL_WATER)", glassStart);
        int ordinarySurfaceStart = raygen.indexOf("float3 albedo = payload.albedo;", waterStart);
        assertTrue(glassStart >= 0 && waterStart > glassStart && ordinarySurfaceStart > waterStart);
        String glass = raygen.substring(glassStart, waterStart);
        String water = raygen.substring(waterStart, ordinarySurfaceStart);

        assertTrue(raygen.contains("#define CAUSTICA_NESTED_MEDIA 0"));
        assertTrue(raygen.contains("#if CAUSTICA_TRANSPARENCY_HQ && !CAUSTICA_OFFLINE"));
        assertTrue(raygen.contains("#define CAUSTICA_DETERMINISTIC_WATER 1"));
        assertFalse(raygen.contains("CAUSTICA_DLSS_TRANSPARENCY_RESOURCES"));
        assertFalse(glass.contains("primaryWaterHit = true"));
        assertFalse(glass.contains("PRIMARY_WATER_REFLECTION"));
        assertTrue(water.contains("segment == 0 && primaryWaterSample != 0u"));
        assertTrue(water.contains("primaryWaterHit = true"));
        assertTrue(water.contains("chooseReflection = primaryWaterSample == PRIMARY_WATER_REFLECTION"));
        assertTrue(raygen.contains("false, false, false, reflectionSampler"));
        assertTrue(raygen.contains("sampleRadiance = F * reflectionRadiance + (1.0 - F) * transmissionRadiance"));
        assertFalse(raygen.contains("gTransparencyLayer"));
        assertFalse(composite.contains("gTransparencyLayer"));
    }

    @Test
    void properWaterRecombinesBothLobesExactly() {
        double[] fresnel = {0.10, 0.75, 1.00, 0.30};
        double[] transmission = {8.0, 2.0, 99.0, 5.0};
        double[] reflection = {1.0, 9.0, 4.0, 3.0};
        double finalRadiance = 0.0;
        for (int i = 0; i < fresnel.length; i++) {
            finalRadiance += (1.0 - fresnel[i]) * transmission[i] + fresnel[i] * reflection[i];
        }
        finalRadiance /= fresnel.length;
        assertEquals(5.7375, finalRadiance, 1.0e-12);
    }

    @Test
    void opticalContinuationOwnsGuidesThroughRefractionAndTir() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");
        assertTrue(raygen.contains("if (captureRefractedGuides && dot(transmittedDir, transmittedDir) > 0.0)"));
        assertTrue(raygen.contains("gv_motionHitCamRel = destinationHitCamRel"));
        assertTrue(raygen.contains("gv_hitCamRel = destinationHitCamRel"));
        assertTrue(raygen.contains("gv_normal = destinationNormal"));
        assertTrue(raygen.contains("gv_rough = destinationRoughness"));
        assertTrue(raygen.contains("gv_albedo = destinationDiffuseAlbedo"));
        assertFalse(raygen.contains("gv_specAlb = destinationSpecularAlbedo"));
        assertFalse(raygen.contains("if (writeRrGuides && gv_motionUseRefracted) specMotion = motion"));
        assertTrue(raygen.contains("direction = normalize(reflect(direction, interfaceNormal))"));
        assertTrue(raygen.contains("remain"));
        assertTrue(raygen.contains("dot(transmittedDir, transmittedDir) <= 0.0 || rndf(sampler) < F"));
    }

    @Test
    void standardDlssUsesOneUnbiasedStochasticDielectricContinuation() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");

        assertFalse(raygen.contains("standardPrimaryTransmission"));
        assertTrue(raygen.contains("chooseReflection = dot(transmittedDir, transmittedDir) <= 0.0"
                + " || rndf(sampler) < F"));
        assertTrue(raygen.contains("bool captureRefractedGuides = (writeMotionGuides && !writeNrdGuides)"));
        assertTrue(raygen.contains("forcing transmission here removed"));
    }

    @Test
    void analyticWaterWavesBandLimitUnresolvedOctaves() throws IOException {
        String raygen = read("shaders/world/world.rgen.slang");

        assertTrue(raygen.contains("float waterWaveBandWeight(float lambda, float footprint)"));
        assertTrue(raygen.contains("if (bandWeight <= 0.0) break"));
        assertTrue(raygen.contains("float waterFootprint = rayConeWidth"));
        assertFalse(raygen.contains("filteredSlopeRoughness"));
        assertTrue(raygen.contains("waterWaveGradPair(waterDomain, pc.waterParams.w, pc.waterAnchor.z,"
                + " waterFootprint,"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n");
    }
}
