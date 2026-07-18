package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OfflineGroundTruthContractTest {
    @Test
    void f7UsesNativeUncappedFp32AccumulationWithoutDenoising() throws Exception {
        String mode = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineGroundTruth.java"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String shader = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String exposure = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtExposure.java"));

        assertTrue(mode.contains("GLFW.GLFW_KEY_F7"));
        assertTrue(mode.contains("beginBenchmark(System.nanoTime())"));
        assertTrue(shader.contains("throughput /= q"));
        assertTrue(composite.contains("boolean rrOperational = !offlineGroundTruth"));
        assertTrue(composite.contains("boolean fgGuidesRequired = !offlineGroundTruth"));
        assertTrue(composite.contains("VK10.VK_FORMAT_R32G32B32A32_SFLOAT"));
        assertTrue(shader.contains("[format(\"rgba32f\")] RWTexture2D<float4> groundTruthAccum"));
        assertTrue(shader.contains("runningMean += (sampleRadiance - runningMean) / float(runningCount)"));
        assertTrue(!shader.contains("offlineMaxSamples"));
        assertTrue(!shader.contains("InterlockedAdd(groundTruthConverged"));
        assertTrue(shader.contains("offlinePilotRead"));
        assertTrue(shader.contains("pilotSample"));
        assertTrue(shader.contains("max(1u, uint(predictedError"));
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
        assertTrue(composite.contains("offlineCameraCaptured"));
        assertTrue(composite.contains("if (!OfflineGroundTruth.INSTANCE.active())"));
        assertTrue(composite.contains("OfflineGroundTruth.INSTANCE.sessionSignature()"));
        assertTrue(composite.contains("offlineTlas"));
        assertTrue(composite.contains("RtEntities.INSTANCE.beginOfflineSession()"));
    }

    @Test
    void f7RunsDirectlyBehindATransparentHudWithoutAutomaticExport() throws Exception {
        String menu = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaOptionsScreen.java"));
        String workstation = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java"));
        String options = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        String videoMixin = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/VideoSettingsScreenMixin.java"));
        String client = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java"));
        String mode = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineGroundTruth.java"));
        String hud = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineRendererHud.java"));

        assertTrue(!options.contains("offlineOptions()"));
        assertTrue(!menu.contains("offlineRendererButton"));
        assertTrue(menu.contains("extends CausticaSettingsScreen"));
        assertTrue(workstation.contains("addExposure()"));
        assertTrue(workstation.contains("addOutput()"));
        assertTrue(workstation.contains("addLighting()"));
        assertTrue(workstation.contains("addView()"));
        assertTrue(videoMixin.contains("RtVideoOptions.causticaButton"));
        assertTrue(client.contains("OfflineGroundTruth.INSTANCE.handleHotkey(client)"));
        assertTrue(client.contains("HudElementRegistry.addLast"));
        assertTrue(mode.contains("F7 toggles the renderer"));
        assertTrue(!mode.contains("exportOfflineSnapshotAsync"));
        assertTrue(!mode.contains("queryOfflineConvergedPixels"));
        assertTrue(hud.contains("F2 screenshot   F7 stop"));
        assertTrue(!hud.contains("graphics.fill("));
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

        assertTrue(composite.contains("GUIDE_COUNT = 11"));
        assertTrue(raygen.contains("vk::binding(12, 0)"));
        assertTrue(hit.contains("vk::binding(14, 0)"));
        assertTrue(hit.contains("vk::binding(15, 0)"));
        assertTrue(miss.contains("vk::binding(16, 0)"));
    }
}
