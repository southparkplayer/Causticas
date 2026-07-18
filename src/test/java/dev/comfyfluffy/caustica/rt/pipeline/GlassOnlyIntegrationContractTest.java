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
        assertTrue(terrain.contains("q.opticalClass = q.translucent ? opticalClass(state) : 0"));
        assertTrue(terrain.contains("Float.intBitsToFloat(q.opticalClass)"));
        assertTrue(terrain.contains("OPTICAL_EXTERIOR_WATER = 1 << 4"));
        assertTrue(terrain.contains("view.getFluidState(cullPos.setWithOffset(pos, opticalFace)).is(FluidTags.WATER)"));
        assertTrue(terrain.contains("Geom g = water ? cur.water() : cur.opaque()"));
    }

    @Test
    void glassIsCompactAndWaterKeepsTheCoreTransport() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String closestHit = source("shaders/world/world.rchit.slang");
        String common = source("shaders/world/world_common.slang");
        String registry = source("src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterialRegistry.java");

        assertTrue(raygen.contains("F = 2.0 * F / (1.0 + F)"));
        assertTrue(raygen.contains("float3 transmittedDir = thinPane ? rd : refract"));
        assertTrue(raygen.contains("float3 interfaceNormal = normalize(geometricNormal)"));
        assertTrue(raygen.contains("static const float ICE_GUIDE_ROUGH = 0.02"));
        assertTrue(raygen.contains("gv_normal = interfaceNormal"));
        assertTrue(raygen.contains("gv_rough = solidIce ? ICE_GUIDE_ROUGH : GLASS_GUIDE_ROUGH"));
        assertTrue(raygen.contains("refract(rd, interfaceNormal, etaI / etaT)"));
        assertTrue(raygen.contains("reflect(rd, interfaceNormal)"));
        assertTrue(raygen.contains("crossGlassExit ? normalize(payload.geometricNormal) : payload.normal"));
        assertTrue(raygen.contains("payloadInterfaceExteriorWater() ? WATER_IOR : 1.0"));
        assertTrue(raygen.contains("inWater = !entering && exteriorWater"));
        assertTrue(closestHit.contains("pr.aux0 & 0x10u"));
        assertTrue(common.contains("PAYLOAD_INTERFACE_EXTERIOR_WATER"));
        assertTrue(raygen.contains("bool inWater = (pc.flags & 1u) != 0u;"));
        assertTrue(raygen.contains("throughput *= exp(-waterExt * payload.hitT);"));
        assertTrue(raygen.contains("waterExt = waterExtinction(surfaceWaterTint);"));
        assertTrue(raygen.contains("inWater = surfaceWaterEntering;"));
        assertTrue(closestHit.contains("materialHeader.model == MATERIAL_WATER"));
        assertTrue(closestHit.contains("(pr.aux0 & 0xfu) << PAYLOAD_OPTICAL_CLASS_SHIFT"));
        assertEquals(2, occurrences(closestHit, "payload.flags |= PAYLOAD_INTERFACE_ENTERING"));
        assertTrue(common.contains("PRIM_FIRST_PERSON_THIN_GLASS = 1u << 1u"));
        assertFalse(registry.contains("normalizeForTransport"));

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
        assertTrue(raygen.contains("gv_opticalExitEta = thinPane ? 1.0 : materialIor"));
        assertTrue(raygen.contains("eta = exitEta / exteriorIor"));
        assertTrue(raygen.contains("eta = entering ? (1.0 / WATER_IOR) : WATER_IOR"));
        assertTrue(raygen.contains("Crossing-budget exhaustion means no trustworthy diffuse destination"));
        assertTrue(raygen.contains("gv_hitCamRel = destinationHitCamRel"));
        assertTrue(raygen.contains("if (!glassGuide)"));
        assertTrue(raygen.contains("gv_normal = destinationNormal"));
        assertTrue(raygen.contains("gv_rough = destinationRoughness"));
        assertTrue(raygen.contains("gv_albedo = destinationDiffuseAlbedo"));
        assertTrue(raygen.contains("float3 specSurfaceCamRel = gv_hitCamRel"));
        assertTrue(raygen.contains("specSurfaceAlbedo, dir, jndc, size"));
        assertTrue(raygen.contains("gv_opticalGuideMode == 3u, true, gv_opticalExitEta"));
        assertTrue(raygen.contains("Favg * Favg * Eavg"));
        assertTrue(closestHit.contains("float3 texAlbedo = srgbToLinear(blockAlbedoAtlas.SampleLevel(uv, blockLod).rgb)"));
        assertTrue(closestHit.contains("float3 tintLinear = srgbToLinear(tint)"));
        assertTrue(closestHit.contains("float3 albedo709 = texAlbedo * tintLinear"));
        assertTrue(closestHit.contains("payload.albedo = bt709ToBt2020(albedo709)"));
        assertFalse(closestHit.contains("payload.albedo = (pr.tint.w > 0.5) ? tint"));
        assertEquals(2, occurrences(raygen, "opticalGuideHit(")); // definition plus one shared call site
        assertFalse(raygen.contains("world_dlssd_guides"));
        assertFalse(closestHit.contains("PAYLOAD_SHADOW_QUERY"));
    }

    @Test
    void onlyFirstPersonHeldTranslucentItemsUsePresentationGlass() throws IOException {
        String capture = source("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCapture.java");
        String collector = source("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java");
        String closestHit = source("shaders/world/world.rchit.slang");

        assertTrue(capture.contains("PRIM_FIRST_PERSON_THIN_GLASS = 1 << 1"));
        assertTrue(capture.contains("prim.add(Float.intBitsToFloat(currentPrimFlags))"));
        assertTrue(collector.contains("firstPersonHeldItem = captureMode == CaptureMode.FIRST_PERSON_BODY"));
        assertTrue(collector.contains("firstPersonHeldItem && transmissive"));
        assertTrue(closestHit.contains("(pr.flags & PRIM_FIRST_PERSON_THIN_GLASS) != 0u ? 2u : 3u"));
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
