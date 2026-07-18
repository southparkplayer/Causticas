package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the finite-world boundary split between camera presentation and path transport. */
final class BorderFogContractTest {
    @Test
    void primaryMissesAndDistantPrimaryHitsUseFogWithoutChangingTransportMisses() throws Exception {
        String common = read("shaders/world/world_common.slang");
        String skyLut = read("shaders/world/world_sky_lut.slang");
        String raygen = read("shaders/world/world.rgen.slang");
        String miss = read("shaders/world/world.rmiss.slang");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java");

        assertTrue(common.contains("PAYLOAD_PRIMARY_RAY = 1u << 11u"));
        assertTrue(common.contains("public float4 borderFogColor"));
        assertTrue(common.contains("public float4 borderFogParams"));
        assertTrue(skyLut.contains("public float3 atmosphericBoundaryFog"));
        assertTrue(skyLut.contains("sampleSkyView(fogDir)"));
        assertTrue(skyLut.contains("pc.skyLighting.z * (1.0 - pc.skyState.w)"));
        assertTrue(raygen.contains("primaryVisibilityRay = false;"));
        assertTrue(raygen.contains("if (primaryHitDistance > fogStart)"));
        assertTrue(raygen.contains("float opticalDepth = 6.0 * fogProgress * fogProgress"));
        assertTrue(raygen.contains("float fogWeight = 1.0 - exp(-opticalDepth)"));
        assertFalse(raygen.contains("smoothstep(fogStart, fogEnd, primaryHitDistance)"));
        assertTrue(raygen.contains("atmosphericBoundaryFog(rd, pc)"));
        assertTrue(miss.contains("if (primaryRay && !aboveHorizon && !earthAtmosphere)"));
        assertTrue(miss.contains("else if (!primaryRay && earthAtmosphere && !aboveHorizon)"));
        assertTrue(pipeline.contains("VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR"));
        assertTrue(composite.contains("EnvironmentAttributes.FOG_COLOR"));
        assertTrue(composite.contains("mc.options.getEffectiveRenderDistance()"));
        assertTrue(composite.contains("(renderDistanceChunks - 3.5f) * 16.0f"));
        assertTrue(raygen.contains("float chunkFadeThreshold = frac"));
        assertFalse(raygen.contains("chunkFadeThreshold = frac(float(pc.frameIndex"));
        assertTrue(raygen.contains("if (gv_primaryChunkFade <= chunkFadeThreshold)"));
        assertTrue(raygen.contains("frameRadiance = atmosphericBoundaryFog(dir, pc)"));
        assertTrue(raygen.contains("gv_hitCamRel = dir * 1.0e6"));
        assertTrue(raygen.contains("gv_animatedGuide = 1.0"));
        assertTrue(raygen.contains("gv_albedo = SKY_DIFF_ALBEDO"));
        assertTrue(raygen.contains("gv_specAlb = SKY_SPEC_ALBEDO"));
        assertFalse(raygen.contains("lerp(atmosphericBoundaryFog(rd, pc), L, primaryChunkFade)"));
        assertTrue(read("shaders/world/world.rchit.slang")
                .contains("world.chunkFade.x - sec.publishedTime"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }
}
