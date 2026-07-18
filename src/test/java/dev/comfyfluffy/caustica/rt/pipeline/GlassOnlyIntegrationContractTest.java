package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Prevents glass work from silently restoring the rejected low-light or global hybrid pipelines. */
final class GlassOnlyIntegrationContractTest {
    @Test
    void terrainClassifiesGlassWithoutChangingTheWaterBucket() throws IOException {
        String terrain = source("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrainMesher.java");
        assertTrue(terrain.contains("OPTICAL_THIN_GLASS = 2"));
        assertTrue(terrain.contains("OPTICAL_SOLID_GLASS = 3"));
        assertTrue(terrain.contains("block == Blocks.GLASS_PANE"));
        assertTrue(terrain.contains("block == Blocks.GLASS"));
        assertTrue(terrain.contains("q.translucent ? q.opticalClass : 0f"));
        assertTrue(terrain.contains("Geom g = water ? cur.water() : cur.opaque()"));
    }

    @Test
    void glassIsCompactAndWaterKeepsTheCoreTransport() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String closestHit = source("shaders/world/world.rchit.slang");

        assertTrue(raygen.contains("F = 2.0 * F / (1.0 + F)"));
        assertTrue(raygen.contains("float3 transmittedDir = thinPane ? rd : refract"));
        assertTrue(raygen.contains("bool inWater = (pc.flags & 1u) != 0u;"));
        assertTrue(raygen.contains("throughput *= exp(-waterExt * payload.hitT);"));
        assertTrue(raygen.contains("waterExt = waterExtinction(surfaceWaterTint);"));
        assertTrue(raygen.contains("inWater = surfaceWaterEntering;"));
        assertTrue(closestHit.contains("bool plainWater = pr.tint.w > 0.5 && pr.tint.w < 1.5"));

        assertFalse(raygen.contains("MAX_MEDIUM_DEPTH"));
        assertFalse(raygen.contains("pathMedia"));
        assertTrue(raygen.contains("void opticalGuideHit"));
        assertTrue(raygen.contains("crossing < uint(MAX_OPTICAL_INTERFACE_DEPTH)"));
        assertTrue(raygen.contains("bool crossGlassExit = glassExitPending && interfaceMaterial == MATERIAL_GLASS"));
        assertTrue(raygen.contains("bool crossWater = continueThroughWater && !glassExitPending"));
        assertTrue(raygen.contains("glassExitPending = false"));
        assertFalse(raygen.contains("!continueThroughWater && continueThroughExit"));
        assertTrue(raygen.contains("surfaceWaterEntering ? WATER_IOR : (1.0 / WATER_IOR)"));
        assertTrue(raygen.contains("diffuseAlbedo = SKY_DIFF_ALBEDO"));
        assertTrue(raygen.contains("gv_opticalGuideDir = transmittedDir"));
        assertTrue(raygen.contains("gv_opticalExitEta = thinPane ? 1.0 : materialIor / outsideIor"));
        assertTrue(raygen.contains("eta = entering ? (1.0 / WATER_IOR) : WATER_IOR"));
        assertTrue(raygen.contains("Crossing-budget exhaustion means no trustworthy diffuse destination"));
        assertTrue(raygen.contains("gv_hitCamRel = destinationHitCamRel"));
        assertTrue(raygen.contains("gv_normal = destinationNormal"));
        assertTrue(raygen.contains("gv_rough = destinationRoughness"));
        assertTrue(raygen.contains("gv_albedo = destinationDiffuseAlbedo"));
        assertTrue(raygen.contains("float3 specSurfaceCamRel = gv_hitCamRel"));
        assertTrue(raygen.contains("specSurfaceAlbedo, dir, jndc, size"));
        assertTrue(raygen.contains("gv_opticalGuideMode == 3u, true, gv_opticalExitEta"));
        assertTrue(raygen.contains("Favg * Favg * Eavg"));
        assertTrue(closestHit.contains("float3 texAlbedo = srgbToLinear(blockAtlas.SampleLevel(uv, blockLod).rgb)"));
        assertTrue(closestHit.contains("float3 tintLinear = srgbToLinear(tint)"));
        assertTrue(closestHit.contains("albedo709 = texAlbedo * tintLinear"));
        assertTrue(closestHit.contains("payload.albedo = bt709ToBt2020(albedo709)"));
        assertFalse(closestHit.contains("payload.albedo = (pr.tint.w > 0.5) ? tint"));
        assertEquals(2, occurrences(raygen, "opticalGuideHit(")); // definition plus one shared call site
        assertFalse(raygen.contains("world_dlssd_guides"));
        assertFalse(closestHit.contains("PAYLOAD_SHADOW_QUERY"));
    }

    @Test
    void rejectedLowLightAndSparseOpticalInfrastructureStayAbsent() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String common = source("shaders/world/world_common.slang");
        String pipeline = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java");
        String composite = source("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

        for (String text : new String[] {raygen, common, pipeline, composite}) {
            assertFalse(text.contains("emissiveTableAddr"));
            assertFalse(text.contains("EMITTER_NEE_PHASES"));
            assertFalse(text.contains("opticalMask"));
            assertFalse(text.contains("world_optical"));
        }
    }

    private static String source(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate).replace("\r\n", "\n");
            }
            cursor = cursor.getParent();
        }
        throw new IOException("Could not locate " + relative);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }
}
