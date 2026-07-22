package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SharcIntegrationContractTest {
    @Test
    void offPathRetainsTheOriginalPipelineAndAbi() throws Exception {
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String common = read("shaders/world/world_common.slang");
        assertTrue(composite.contains("private RtPipeline worldPipeline;"));
        assertTrue(composite.contains("if (sharcCache != null && !offlineGroundTruth)"));
        assertTrue(composite.contains("active.trace(cmd, renderW, renderH, pushAddr)"));
        assertTrue(common.contains("#define CAUSTICA_SHARC 0"));
        assertTrue(common.contains("public float3 geometricNormal;"));
    }

    @Test
    void runtimeRecordsSparseResolveQueryWithExactBarriers() throws Exception {
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String cache = read("src/main/java/dev/comfyfluffy/caustica/rt/RtSharcCache.java");
        String raygen = read("shaders/world/world.rgen.slang");
        assertTrue(composite.indexOf("updatePipeline.trace") < composite.indexOf("sharcResolvePipeline.dispatch"));
        assertTrue(composite.indexOf("sharcResolvePipeline.dispatch") < composite.indexOf("queryPipeline.trace"));
        assertTrue(composite.contains("? sharcQueryPipeline : sharcDiffuseQueryPipeline"));
        assertTrue(raygen.contains("#if CAUSTICA_SHARC_GLOSSY"));
        assertTrue(raygen.contains("#if CAUSTICA_SHARC_LIVE_SECONDARY_DIRECT"));
        String build = read("build.gradle");
        assertTrue(build.contains("\"-DCAUSTICA_SHARC_LIVE_SECONDARY_DIRECT=1\""));
        assertTrue(build.contains("\"-O2\", \"-fp-mode\", \"precise\""));
        assertTrue(composite.contains("!CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.value()"));
        assertTrue(composite.contains("(renderW + updateTileSize - 1) / updateTileSize"));
        assertTrue(raygen.contains("tile * tileSize"));
        assertTrue(raygen.contains("pc.maxBounces = min(pc.maxBounces, sharcFrame.updateMaxBounces)"));
        assertTrue(raygen.contains("sharcPreviousLobeDiffuse"));
        assertTrue(raygen.contains("payload.hitT >= minSegment"));
        assertTrue(raygen.contains("glossyCone >= minSegment"));
        assertTrue(raygen.contains("causticaSharcDiscardCurrentVertex(sharcState)"));
        assertTrue(cache.contains("VK_BUFFER_USAGE_TRANSFER_DST_BIT"));
        assertTrue(cache.contains("VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR"));
        assertTrue(cache.contains("VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"));
        String frameStats = read("src/main/java/dev/comfyfluffy/caustica/rt/RtFrameStats.java");
        assertTrue(frameStats.contains("sharcNumericRisks"));
        assertTrue(frameStats.contains("sharcResolvedSaturations"));
        assertFalse(cache.contains("lockAddress"));
    }

    @Test
    void debugBridgeControlsAndReportsLiveSharcState() throws Exception {
        String client = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java");
        String bridge = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaDebugBridge.java");
        String script = read("tools/caustica-debug-bridge.ps1");
        assertTrue(client.contains("CausticaDebugBridge.tick(client)"));
        assertTrue(bridge.contains("Boolean.getBoolean(ENABLE_PROPERTY)"));
        assertTrue(bridge.contains("if (!enabled()) return;"));
        assertTrue(bridge.contains("setBoolean(command, \"sharc\""));
        assertTrue(bridge.contains("state.setProperty(\"fps\""));
        assertTrue(bridge.contains("state.setProperty(\"sharcActive\""));
        assertTrue(bridge.contains("RtComposite.INSTANCE.sharcStatus()"));
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        assertTrue(composite.contains("Inactive - NRD reconstruction selected"));
        assertTrue(composite.contains("Inactive - offline ground truth uses unbiased baseline"));
        assertTrue(script.contains("[ValidateSet('get','set','wait','benchmark','reset','shutdown')]"));
        assertTrue(bridge.contains("state.setProperty(\"backend\""));
        assertTrue(bridge.contains("state.setProperty(\"artifactSha256\""));
        assertTrue(bridge.contains("state.setProperty(\"renderWidth\""));
        assertTrue(bridge.contains("sequence < PROCESS_START_MILLIS"));
        assertTrue(bridge.contains("client.getWindow().toggleFullScreen()"));
        assertTrue(bridge.contains("command.getProperty(\"causticaCategory\")"));
        assertTrue(bridge.contains("new RtSharcOptionsScreen(client.gui.screen(), client.options)"));
        assertTrue(bridge.contains("Screenshot.grab(client, false)"));
        assertTrue(script.contains("[bool]$OpenCausticaSettings"));
        assertTrue(script.contains("[string]$CausticaCategory"));
        assertTrue(script.contains("[bool]$OpenSharcSettings"));
        assertTrue(script.contains("[bool]$CloseScreen"));
        assertTrue(bridge.contains("command.getProperty(\"closeScreen\""));
        assertTrue(script.contains("[bool]$Screenshot"));
    }

    @Test
    void rootSettingsUseResponsiveCategoryWorkstation() throws Exception {
        String entry = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaOptionsScreen.java");
        String screen = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");
        String categoryLayout = read("src/main/java/dev/comfyfluffy/caustica/client/ui/CategoryLayout.java");
        String gridLayout = read("src/main/java/dev/comfyfluffy/caustica/client/ui/WidgetGridLayout.java");
        assertTrue(entry.contains("extends CausticaSettingsScreen"));
        assertTrue(screen.contains("case SHARC -> addSharc()"));
        assertTrue(screen.contains("new ScrollableLayout"));
        assertTrue(screen.contains("ReserveStrategy.RIGHT"));
        assertTrue(screen.contains("restoreSharcParityDefaults"));
        assertFalse(screen.contains("new RtSharcOptionsScreen(this, options)"));
        assertTrue(categoryLayout.contains("Deterministic vertical layout"));
        assertTrue(gridLayout.contains("compact responsive grid"));
    }

    @Test
    void biasedCacheIsDisabledForOfflineAndHasRealGpuTimestamps() throws Exception {
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String profiler = read("src/main/java/dev/comfyfluffy/caustica/rt/RtTraceGpuProfiler.java");
        assertTrue(composite.contains("syncSharcResources(ctx, !offlineGroundTruth)"));
        assertTrue(profiler.contains("vkCmdWriteTimestamp"));
        assertTrue(read("src/main/java/dev/comfyfluffy/caustica/rt/RtFrameStats.java")
                .contains("\"disocclusionGpuNanos\", \"dlssRrGpuNanos\""));
        assertTrue(profiler.contains("VK_QUERY_RESULT_64_BIT"));
        assertFalse(profiler.contains("VK_QUERY_RESULT_WAIT_BIT"));
    }

    @Test
    void causticaMenuExposesStatusMemoryAndDebugViews() throws Exception {
        String options = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
        String screen = read("src/main/java/dev/comfyfluffy/caustica/client/RtSharcOptionsScreen.java");
        String widgets = read("src/main/java/dev/comfyfluffy/caustica/client/ui/CausticaWidgets.java");
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
        String lang = read("src/main/resources/assets/caustica/lang/en_us.json");
        assertTrue(options.contains("public static OptionInstance<?>[] sharcOptions()"));
        assertTrue(options.contains("sharcSceneScale()"));
        assertTrue(options.contains("sharcRadianceScale()"));
        assertTrue(options.contains("sharcAccumulationFrames()"));
        assertTrue(options.contains("sharcStaleFrames()"));
        assertTrue(options.contains("RtComposite.INSTANCE.sharcStatus()"));
        assertTrue(options.contains("DEBUG_VIEW_TONEMAP_COMPARISON, 9, 10, 11, 12, 13, 14, 15, 16"));
        assertTrue(options.contains("sharcUpdateTileSize()"));
        assertTrue(options.contains("sharcMinSegmentRatio()"));
        assertTrue(lang.contains("SHaRC Query Hit / Miss"));
        assertTrue(lang.contains("SHaRC Termination Depth"));
        assertTrue(lang.contains("SHaRC Occupancy"));
        assertTrue(lang.contains("SHaRC Voxel Hash"));
        assertTrue(lang.contains("SHaRC Cached Radiance"));
        assertTrue(lang.contains("SHaRC Query Eligibility"));
        assertTrue(screen.contains("extends Screen"));
        assertTrue(screen.contains("restoreParityDefaults"));
        assertTrue(screen.contains("CausticaConfig.Rt.Sharc.ENABLED.set(true)"));
        assertTrue(widgets.contains("public static final int PANEL = 0x50000000"));
        assertTrue(widgets.contains("public static final int PANEL_2 = 0x30000000"));
        assertTrue(config.contains("\"sharc.enabled\", true"));
        assertTrue(config.contains("\"sharc.cache-exponent\", 24, 16, 28"));
        assertTrue(screen.contains("27, 28)"));
        assertTrue(lang.contains("RTX 5090 Maximum (10 GiB)"));
        assertTrue(config.contains("\"sharc.scene-scale\", 32.0f, 1.0f, 100.0f"));
        assertTrue(config.contains("\"sharc.accumulation-frames\", 128, 1, 1024"));
        assertTrue(config.contains("\"sharc.stale-frames\", 1024, 8, 1024"));
        assertTrue(config.contains("\"sharc.update-tile-size\", 2, 2, 64"));
        assertTrue(config.contains("\"sharc.update-max-bounces\", 8, 1, 8"));
        assertTrue(config.contains("\"sharc.min-segment-ratio\", 0.2f, 0.25f, 4.0f"));
        assertTrue(config.contains("\"sharc.glossy-query\", false"));
        assertTrue(config.contains("\"sharc.live-secondary-direct\", true"));
    }

    @Test
    void viewDependentDirectLightingIsNotStoredInTheDirectionlessCache() throws Exception {
        String raygen = read("shaders/world/world.rgen.slang");
        String bridge = read("shaders/sharc/sharc_bridge.slang");
        assertTrue(raygen.contains("float3 cacheableDirectLighting"));
        assertTrue(raygen.contains("float3 liveDirectLighting"));
        assertTrue(raygen.contains("cacheableDirectLighting, cacheableDirectLighting + liveDirectLighting"));
        assertTrue(raygen.contains("cachedRadiance + liveDirectLighting"));
        assertTrue(bridge.contains("cacheableDirectLighting / materialDemodulation"));
        assertFalse(bridge.contains("propagatedDirectLighting / materialDemodulation"));
    }

    @Test
    void rawPrimaryCacheDebugIsAnIndependentLiveAbcVariant() throws Exception {
        String raygen = read("shaders/world/world.rgen.slang");
        String build = read("build.gradle");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
        String bridge = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaDebugBridge.java");

        assertTrue(config.contains("\"sharc.primary-diffuse-reuse\", false"));
        assertTrue(build.contains("-DCAUSTICA_SHARC_PRIMARY_DIFFUSE=1"));
        assertTrue(build.contains("world_sharc_primary.rgen.spv"));
        assertTrue(raygen.contains("bool primaryEligible = bounce == 0 && primaryDiffuseEligible"));
        assertTrue(raygen.contains("metal <= 0.1 && perceptualRough >= 0.5"));
        assertTrue(raygen.contains("cachedRadiance + liveDirectLighting"));
        assertTrue(composite.contains("syncSharcPrimaryQueryPipeline"));
        assertTrue(composite.contains("sharcPrimaryQueryPipeline != null"));
        assertTrue(composite.contains("RtReconstruction.requestHistoryReset()"));
        assertTrue(bridge.contains("setBoolean(command, \"primaryDiffuseReuse\""));
        assertTrue(bridge.contains("state.setProperty(\"sharcPrimaryDiffuseActive\""));
        String lang = read("src/main/resources/assets/caustica/lang/en_us.json");
        assertTrue(lang.contains("Raw Primary Cache Debug"));
        assertTrue(lang.contains("no spatial interpolation or confidence filter"));
    }

    @Test
    void sdkSourceStaysExternalAndArtifactAvailabilityIsExplicit() throws Exception {
        String build = read("build.gradle");
        String support = read("src/main/java/dev/comfyfluffy/caustica/rt/RtSharcSupport.java");
        assertTrue(build.contains("SHARC_SDK"));
        assertTrue(build.contains("sharcHeaderHashes"));
        assertTrue(build.contains("NVIDIA-SHARC-SDK.txt"));
        assertTrue(support.contains("artifacts"));
        assertTrue(support.contains("shaderBufferInt64Atomics"));
        String helper = read("tools/build-fast.ps1");
        assertTrue(helper.contains("[switch]$WithoutSharc"));
        assertTrue(helper.contains("$artifactMode = $Mode -in @('Jar', 'Deploy', 'Full')"));
        assertTrue(helper.contains("$includeSharc = $WithSharc -or ($artifactMode -and -not $WithoutSharc)"));
        assertTrue(helper.contains("-PwithoutSharc=true"));
        assertTrue(build.contains("Production JARs include SHaRC by default"));
        assertTrue(build.contains("explicitOptOut=${sharcOptOut}"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
