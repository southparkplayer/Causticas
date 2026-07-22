package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RisForkAbiContractTest {
    @Test
    void risFlagsAndEmissionPackingDoNotOverlapForkMetadata() throws IOException {
        String common = source("shaders/world/world_common.slang");
        String registry = source("src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterialRegistry.java");
        String collector = source("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtLightCollector.java");

        assertTrue(common.contains("TERRAIN_PRIM_IN_LIGHT_BUFFER = 1u << 2u"));
        assertTrue(common.contains("PAYLOAD_EMITTER_IN_LIST = 1u << 19u"));
        assertTrue(common.contains("MATERIAL_EMISSION_STRENGTH_MASK = 65535u"));
        assertTrue(common.contains("MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH = 32.0"));
        assertTrue(registry.contains("EMISSION_STRENGTH_MASK = 65535"));
        assertTrue(registry.contains("MAX_EMISSION_STRENGTH = 32.0f"));
        assertTrue(collector.contains("PRIM_FLAG_IN_LIGHT_BUFFER = 1 << 2"));
        assertTrue(collector.contains("| PRIM_FLAG_IN_LIGHT_BUFFER"));
    }

    @Test
    void risKeepsForkTorchCalibrationAndUsesBasePowerForProposalPdf() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");
        String collector = source("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtLightCollector.java");

        assertTrue(raygen.contains("EMISSIVE_MAX_MULTIPLIER * clamp(pc.torchIntensity"));
        assertTrue(raygen.contains("proposalPdf(lg, le"));
        assertTrue(raygen.contains("EMISSIVE_BASE_RADIANCE = 0.1953125"));
        assertTrue(collector.contains("Float.floatToRawIntBits(p[pb + PRIM_FLAGS_LANE]) | PRIM_FLAG_IN_LIGHT_BUFFER"));
    }

    @Test
    void hierarchyIsRequestedPublishedAndExposedInTheLiveMenu() throws IOException {
        String terrain = source("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java");
        String menu = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");

        assertTrue(terrain.contains("lightGrid.request(ctx, lightSections.values()"));
        assertTrue(terrain.contains("lightGrid.publishReady(ctx)"));
        assertTrue(terrain.contains("lightGrid.invalidate(ctx, lastGraphicsUse)"));
        assertTrue(menu.contains("risCandidatesSlider(CausticaConfig.Rt.Lights.RIS_CANDIDATES)"));
        assertTrue(menu.contains("RtTerrain.requiresLightListRebuild"));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n");
    }
}
