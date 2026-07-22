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
        assertTrue(terrain.contains("BiomeColors.getAverageWaterColor(view, cullPos) & 0x00ff_ffff"));
        assertTrue(terrain.contains("Float.intBitsToFloat(q.opticalWaterTint)"));
        assertTrue(terrain.contains("Geom g = water ? cur.water() : cur.opaque()"));
    }

    @Test
    void standardGlassStaysCompactAndHqWaterDoesNotAlterItsTransport() throws IOException {
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
        assertTrue(raygen.contains("interfaceNormal = normalize(payload.geometricNormal)"));
        assertTrue(raygen.contains("interfaceNormal = payload.normal"));
        assertTrue(raygen.contains("payloadInterfaceExteriorWater() ? WATER_IOR : 1.0"));
        assertTrue(raygen.contains("inWater = !entering && exteriorWater"));
        assertTrue(closestHit.contains("pr.aux0 & 0x10u"));
        assertTrue(closestHit.contains("payload.iorTransmission = pr.aux1"));
        assertTrue(common.contains("PAYLOAD_INTERFACE_EXTERIOR_WATER"));
        assertTrue(common.contains("adjacent water body's packed sRGB8 tint"));
        assertTrue(raygen.contains("float3 exteriorWaterTint = exteriorWater ? payloadInterfaceWaterTint()"));
        assertTrue(raygen.contains("waterExt = waterExtinction(exteriorWaterTint)"));
        assertFalse(raygen.contains("if (inWater) waterExt = waterExtinction(pc.waterParams.xyz)"));
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
        assertTrue(raygen.contains("#define CAUSTICA_NESTED_MEDIA 0"));
        int glassStart = raygen.indexOf("if (material == MATERIAL_GLASS)");
        int waterStart = raygen.indexOf("if (material == MATERIAL_WATER)", glassStart);
        String glassTransport = raygen.substring(glassStart, waterStart);
        assertFalse(glassTransport.contains("PRIMARY_WATER_REFLECTION"));
        assertFalse(glassTransport.contains("primaryWaterHit = true"));
        assertTrue(glassTransport.contains("Glass and ice retain one unbiased Fresnel continuation"));
        assertTrue(glassTransport.contains("rndf(sampler) < F"));
        assertFalse(glassTransport.contains("standardPrimaryTransmission"));
        assertTrue(raygen.contains("solidDielectricExtinction(glassTint) * payload.hitT"));
        assertEquals(2, occurrences(raygen, "if (entering) throughput *= glassTint"));
        assertFalse(raygen.contains("if (!entering) throughput *= glassTint"));
        assertTrue(raygen.contains("model the authored glass texture as an"));
        assertTrue(raygen.contains("entrance coating"));
        assertTrue(raygen.contains("void opticalGuideHit"));
        assertTrue(raygen.contains("crossing < uint(MAX_OPTICAL_INTERFACE_DEPTH)"));
        assertTrue(raygen.contains("float targetIor = currentIor"));
        assertTrue(raygen.contains("targetIor = entering ? WATER_IOR : 1.0"));
        assertTrue(raygen.contains("payloadInterfaceExteriorWater() ? WATER_IOR : 1.0"));
        assertTrue(raygen.contains("currentIor / max(targetIor, 1.0)"));
        assertFalse(raygen.contains("glassExitPending"));
        assertTrue(raygen.contains("diffuseAlbedo = SKY_DIFF_ALBEDO"));
        assertTrue(raygen.contains("gv_opticalGuideDir = transmittedDir"));
        assertTrue(raygen.contains("static const uint OPTICAL_GUIDE_TRANSMISSION = 1u"));
        assertTrue(raygen.contains("gv_opticalGuideMode = OPTICAL_GUIDE_TRANSMISSION"));
        assertTrue(raygen.contains("gv_opticalGuideIor = thinPane ? outsideIor"));
        assertTrue(raygen.contains(": (entering ? materialIor : outsideIor)"));
        assertTrue(raygen.contains("A dielectric interface has no diffuse reflectance"));
        assertFalse(raygen.contains("gv_albedo = glassTint"));
        assertTrue(raygen.contains("Crossing-budget exhaustion means no trustworthy diffuse destination"));
        assertTrue(raygen.contains("failure = OPTICAL_GUIDE_FAILURE_BUDGET"));
        assertTrue(raygen.contains("gv_hitCamRel = destinationHitCamRel"));
        int destination = raygen.indexOf("if (destinationValid) {");
        int failure = raygen.indexOf("} else {", destination);
        String validPath = raygen.substring(destination, failure);
        assertTrue(validPath.contains("Standard is refraction-priority"));
        assertTrue(validPath.contains("gv_normal = destinationNormal"));
        assertTrue(validPath.contains("gv_rough = destinationRoughness"));
        assertFalse(raygen.contains(
                "gv_albedo = destinationDiffuseAlbedo;"));
        assertTrue(raygen.contains(
                "gv_albedo = destinationDiffuseAlbedo * destinationTransmissionFilter;"));
        assertTrue(raygen.contains("float3 specSurfaceCamRel = gv_hitCamRel"));
        assertTrue(raygen.contains("specSurfaceAlbedo, dir, jndc, size"));
        assertTrue(raygen.contains(
                "destinationDiffuseAlbedo, destinationTransmissionFilter,"));
        assertTrue(raygen.contains("Favg * Favg * Eavg"));
        assertTrue(closestHit.contains(
                "float3 texAlbedo = sampleSrgbLinearTrilinear(blockAlbedoAtlas, uv, blockLod"));
        assertTrue(closestHit.contains("float3 tintLinear = srgbToLinear(tint)"));
        assertTrue(closestHit.contains("float3 albedo709 = texAlbedo * tintLinear"));
        assertTrue(closestHit.contains("payload.albedo = bt709ToBt2020(albedo709)"));
        assertFalse(closestHit.contains("payload.albedo = (pr.tint.w > 0.5) ? tint"));
        assertEquals(2, occurrences(raygen, "opticalGuideHit(")); // definition and one current-frame guide walk
        assertFalse(raygen.contains("world_dlssd_guides"));
        assertFalse(closestHit.contains("PAYLOAD_SHADOW_QUERY"));
    }

    @Test
    void dlssdOpticalGuideAccumulatesEnteredGlassTint() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");

        assertTrue(raygen.contains("static float3 gv_opticalGuideTransmissionFilter;"));

        assertTrue(raygen.contains(
                "gv_opticalGuideTransmissionFilter = float3(1.0, 1.0, 1.0);"));

        assertTrue(raygen.contains(
                "float3 initialTransmissionFilter"));

        assertTrue(raygen.contains(
                "out float3 transmissionFilter"));

        assertTrue(raygen.contains(
                "transmissionFilter = initialTransmissionFilter;"));

        assertTrue(raygen.contains(
                "bool crossedGlassEntry = false;"));

        assertTrue(raygen.contains(
                "crossedGlassEntry = interfaceEntering;"));

        assertTrue(raygen.contains(
                "if (crossedGlassEntry)"));

        assertTrue(raygen.contains(
                "transmissionFilter *= payload.albedo;"));

        assertTrue(raygen.contains(
                "destinationDiffuseAlbedo * destinationTransmissionFilter"));

        // Reflection remains a neutral dielectric Fresnel lobe.
        assertTrue(raygen.contains(
                "gv_specAlb = float3(F, F, F);"));

        // Ordinary glass must not become PSR.
        int glassStart = raygen.indexOf("if (material == MATERIAL_GLASS)");
        int waterStart = raygen.indexOf(
                "if (material == MATERIAL_WATER)", glassStart);
        String glassTransport = raygen.substring(glassStart, waterStart);

        assertFalse(glassTransport.contains("gv_psrMirror = true"));
    }

    @Test
    void guideTraversalHandlesNestedDielectricsWithoutPublishingSentinelBlack() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        int helper = raygen.indexOf("void opticalGuideHit");
        int helperEnd = raygen.indexOf("#if CAUSTICA_NRD", helper);
        String walk = raygen.substring(helper, helperEnd);

        assertTrue(walk.contains("interfaceMaterial == MATERIAL_WATER"));
        assertTrue(walk.contains("interfaceMaterial == MATERIAL_GLASS"));
        assertTrue(walk.contains("opticalClass == OPTICAL_THIN_GLASS"));
        assertTrue(walk.contains("opticalClass == OPTICAL_SOLID_GLASS || opticalClass == OPTICAL_SOLID_ICE"));
        assertTrue(walk.contains("straightThrough = true"));
        assertTrue(walk.contains("currentIor = targetIor"));
        assertTrue(walk.contains("transmissionFilter *= payload.albedo"));
        assertTrue(walk.contains("failure = OPTICAL_GUIDE_FAILURE_NONE"));
        assertFalse(walk.contains(
                "if (!interfaceEntering) {\n            transmissionFilter *= payload.albedo;"));
        assertFalse(walk.contains("diffuseAlbedo = payload.albedo;\n            transmitted = true;\n            return;\n        }\n\n        float3 interfacePos"));
    }

    @Test
    void onlyFirstPersonHeldTranslucentItemsUsePresentationGlass() throws IOException {
        String capture = source("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCapture.java");
        String collector = source("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java");
        String closestHit = source("shaders/world/world.rchit.slang");

        assertTrue(capture.contains("PRIM_FIRST_PERSON_THIN_GLASS = 1 << 1"));
        assertTrue(capture.contains("primitives[lane + 9] = Float.intBitsToFloat(currentPrimFlags)"));
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
