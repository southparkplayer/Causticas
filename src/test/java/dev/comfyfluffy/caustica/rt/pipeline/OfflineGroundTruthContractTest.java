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
        assertTrue(mode.contains("OfflineBatchController"));
        assertTrue(mode.contains("completedOfflineGpuFrameSerial"));
        assertTrue(shader.contains("throughput /= q"));
        assertTrue(composite.contains("boolean rrOperational = !offlineGroundTruth"));
        assertTrue(composite.contains("boolean fgGuidesRequired = !offlineGroundTruth"));
        assertTrue(composite.contains("VK10.VK_FORMAT_R32G32B32A32_SFLOAT"));
        assertTrue(shader.contains("[format(\"rgba32f\")] RWTexture2D<float4> groundTruthAccum"));
        assertTrue(shader.contains("float3 batchSum = float3(0.0)"));
        assertTrue(shader.contains("offlineSampleCount[pix]"));
        assertTrue(!shader.contains("offlineMaxSamples"));
        assertTrue(!shader.contains("InterlockedAdd(groundTruthConverged"));
        assertTrue(shader.contains("offlinePilotRead"));
        assertTrue(shader.contains("pilotSample"));
        assertTrue(shader.contains("uint pathCount = workItem.pathCount"));
        assertTrue(shader.contains("uint pathCount = spp"));
        assertTrue(!shader.contains("uint(predictedError"));
        assertTrue(shader.contains("groundTruthAccum[pix] = float4(runningMean, 1.0)"));
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
        String ultra = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/UltraScreenshot.java"));
        String pause = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CapturePause.java"));
        String gui = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/GuiMixin.java"));
        String textureAtlas = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/TextureAtlasMixin.java"));
        String client = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java"));
        String mixins = Files.readString(Path.of("src/main/resources/caustica.mixins.json"));

        assertTrue(!mode.contains("tickRateManager().setFrozen"));
        assertTrue(!ultra.contains("tickRateManager().setFrozen"));
        assertTrue(mouse.contains("OfflineGroundTruth.INSTANCE.engaged()"));
        assertTrue(pause.contains("UltraScreenshot.INSTANCE.active()"));
        assertTrue(pause.contains("OfflineGroundTruth.INSTANCE.engaged()"));
        assertTrue(pause.contains("minecraft.hasSingleplayerServer()"));
        assertTrue(pause.contains("!server.isPublished()"));
        assertTrue(gui.contains("method = \"isPausing\""));
        assertTrue(gui.contains("CapturePause.shouldPause"));
        assertTrue(mixins.contains("\"GuiMixin\""));
        assertTrue(pause.contains("sceneFreezeRequested()"));
        assertTrue(textureAtlas.contains("method = \"tick\""));
        assertTrue(textureAtlas.contains("CapturePause.sceneFreezeRequested()"));
        assertTrue(mixins.contains("\"TextureAtlasMixin\""));
        assertTrue(composite.contains("frozenWaterWaveTime = waterWaveTime"));
        assertTrue(composite.contains("frozenSkyPush = liveSky"));
        assertTrue(!composite.contains("frozenChunkFadeClockSeconds"));
        assertTrue(composite.contains("groundTruthAccumulationFrames = 0"));
        assertTrue(client.contains("!CapturePause.sceneFreezeRequested()"));
        assertTrue(mode.contains("RtTerrain.hasPublishedSnapshot()"));
        assertTrue(composite.contains("frozenCameraCaptured"));
        assertTrue(composite.contains("if (!CapturePause.sceneFreezeRequested())"));
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
        assertTrue(workstation.contains("addDisplayHdr()"));
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
        String skyLut = Files.readString(Path.of("shaders/world/world_sky_lut.slang"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(composite.contains("BASE_GUIDE_COUNT = 11"));
        assertTrue(composite.contains("NRD_GUIDE_COUNT = 17"));
        assertTrue(raygen.contains("vk::binding(12, 0)"));
        assertTrue(raygen.contains("vk::binding(10, 0)"));
        assertTrue(composite.contains("VK10.VK_FORMAT_R32_UINT"));
        assertTrue(composite.contains("setExtraStorageImage(7, offlineSampleCount.view)"));
        assertTrue(composite.contains("zeroCount.uint32(0, 0)"));
        assertTrue(hit.contains("vk::binding(1, 1)"));
        assertTrue(hit.contains("vk::binding(2, 1)"));
        assertTrue(hit.contains("vk::binding(3, 1)"));
        assertTrue(miss.contains("vk::binding(20, 0)"));
        assertTrue(skyLut.contains("vk::binding(21, 0)"));
    }
}
