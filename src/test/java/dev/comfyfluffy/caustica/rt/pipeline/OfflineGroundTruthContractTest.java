package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OfflineGroundTruthContractTest {
    @Test
    void f7UsesNativeAdaptiveFp32AccumulationWithoutDenoising() throws Exception {
        String mode = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineGroundTruth.java"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String shader = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String exposure = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtExposure.java"));

        assertTrue(mode.contains("GLFW.GLFW_KEY_F7"));
        assertTrue(composite.contains("boolean rrOperational = !offlineGroundTruth"));
        assertTrue(composite.contains("boolean fgGuidesRequired = !offlineGroundTruth"));
        assertTrue(composite.contains("VK10.VK_FORMAT_R32G32B32A32_SFLOAT"));
        assertTrue(shader.contains("[format(\"rgba32f\")] RWTexture2D<float4> groundTruthAccum"));
        assertTrue(shader.contains("[format(\"rgba32f\")] RWTexture2D<float4> groundTruthVariance"));
        assertTrue(shader.contains("[format(\"r32ui\")] RWTexture2D<uint> groundTruthConverged"));
        assertTrue(shader.contains("runningMean += (sampleRadiance - runningMean) / float(runningCount)"));
        assertTrue(shader.contains("statisticallyConverged = all(standardError <= threshold)"));
        assertTrue(shader.contains("groundTruthAccum[pix] = float4(runningMean, float(runningCount))"));
        assertTrue(composite.contains("clearOfflineAccumulation(cmd, stack)"));
        assertTrue(composite.contains("exposure.beginOfflineSession(ctx)"));
        assertTrue(exposure.contains("offlineFixedScale"));
        assertTrue(exposure.contains("MemoryUtil.memGetFloat(state.mapped)"));
    }

    @Test
    void animatedSceneAndCameraAreFrozenForConvergence() throws Exception {
        String mode = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineGroundTruth.java"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String mouse = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/MouseHandlerMixin.java"));

        assertTrue(mode.contains("tickRateManager().setFrozen(true)"));
        assertTrue(mouse.contains("OfflineGroundTruth.INSTANCE.active()"));
        assertTrue(composite.contains("groundTruthWaterWaveTime = waterWaveTime"));
        assertTrue(composite.contains("groundTruthAccumulationFrames = 0"));
        assertTrue(composite.contains("!OfflineGroundTruth.INSTANCE.active()"));
        assertTrue(mode.contains("RtTerrain.hasPublishedSnapshot()"));
        assertTrue(mode.contains("Preparing first terrain snapshot"));
        assertTrue(composite.contains("CausticaConfig.offlineRenderSignature()"));
    }

    @Test
    void productionModeExportsRawAndDisplayArtifactsAndOwnsAUnifiedMenu() throws Exception {
        String exporter = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtOfflineExporter.java"));
        String menu = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaOptionsScreen.java"));
        String videoMixin = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/VideoSettingsScreenMixin.java"));
        String client = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java"));

        assertTrue(exporter.contains("writeExr(exr"));
        assertTrue(exporter.contains("writePng(png"));
        assertTrue(exporter.contains("writeManifest(manifest"));
        assertTrue(menu.contains("offlineRendererButton"));
        assertTrue(menu.contains("tonemappingButton"));
        assertTrue(menu.contains("frameGenerationButton"));
        assertTrue(menu.contains("runtimeOptions"));
        assertTrue(menu.contains("firstPersonOptions"));
        assertTrue(videoMixin.contains("RtVideoOptions.causticaButton"));
        assertTrue(client.contains("new OfflineRendererOptionsScreen(null, client.options)"));
        String pauseMixin = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/PauseScreenMixin.java"));
        assertTrue(pauseMixin.contains("new CausticaOptionsScreen(this, minecraft.options)"));
        String optionsMixin = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/OptionsScreenMixin.java"));
        assertTrue(optionsMixin.contains("new CausticaOptionsScreen(this, minecraft.options)"));
    }

    @Test
    void descriptorBindingsRemainAlignedAfterOfflineImages() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String hit = Files.readString(Path.of("shaders/world/world.rchit.slang"));
        String miss = Files.readString(Path.of("shaders/world/world.rmiss.slang"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(composite.contains("GUIDE_COUNT = 10"));
        assertTrue(raygen.contains("vk::binding(12, 0)"));
        assertTrue(hit.contains("vk::binding(13, 0)"));
        assertTrue(hit.contains("vk::binding(14, 0)"));
        assertTrue(miss.contains("vk::binding(15, 0)"));
    }
}
