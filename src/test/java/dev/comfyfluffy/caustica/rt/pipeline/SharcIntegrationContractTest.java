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
        assertTrue(bridge.contains("setBoolean(command, \"sharc\""));
        assertTrue(bridge.contains("state.setProperty(\"fps\""));
        assertTrue(bridge.contains("state.setProperty(\"sharcActive\""));
        assertTrue(script.contains("[ValidateSet('get','set','wait','reset','shutdown')]"));
        assertTrue(bridge.contains("state.setProperty(\"backend\""));
        assertTrue(bridge.contains("state.setProperty(\"artifactSha256\""));
        assertTrue(bridge.contains("sequence < PROCESS_START_MILLIS"));
        assertTrue(bridge.contains("client.getWindow().toggleFullScreen()"));
    }

    @Test
    void biasedCacheIsDisabledForOfflineAndHasRealGpuTimestamps() throws Exception {
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        String profiler = read("src/main/java/dev/comfyfluffy/caustica/rt/RtTraceGpuProfiler.java");
        assertTrue(composite.contains("syncSharcResources(ctx, !OfflineGroundTruth.INSTANCE.active())"));
        assertTrue(profiler.contains("vkCmdWriteTimestamp"));
        assertTrue(profiler.contains("VK_QUERY_RESULT_64_BIT"));
        assertFalse(profiler.contains("VK_QUERY_RESULT_WAIT_BIT"));
    }

    @Test
    void causticaMenuExposesStatusMemoryAndDebugViews() throws Exception {
        String options = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
        String lang = read("src/main/resources/assets/caustica/lang/en_us.json");
        assertTrue(options.contains("public static OptionInstance<?>[] sharcOptions()"));
        assertTrue(options.contains("sharcSceneScale()"));
        assertTrue(options.contains("sharcRadianceScale()"));
        assertTrue(options.contains("sharcAccumulationFrames()"));
        assertTrue(options.contains("sharcStaleFrames()"));
        assertTrue(options.contains("RtComposite.INSTANCE.sharcActive()"));
        assertTrue(options.contains("DEBUG_VIEW_TONEMAP_COMPARISON, 9, 10, 11, 12, 13, 14, 15, 16"));
        assertTrue(options.contains("sharcUpdateTileSize()"));
        assertTrue(options.contains("sharcMinSegmentRatio()"));
        assertTrue(lang.contains("SHaRC Query Hit / Miss"));
        assertTrue(lang.contains("SHaRC Termination Depth"));
        assertTrue(lang.contains("SHaRC Occupancy"));
        assertTrue(lang.contains("SHaRC Voxel Hash"));
        assertTrue(lang.contains("SHaRC Cached Radiance"));
        assertTrue(lang.contains("SHaRC Query Eligibility"));
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
    void sdkSourceStaysExternalAndArtifactAvailabilityIsExplicit() throws Exception {
        String build = read("build.gradle");
        String support = read("src/main/java/dev/comfyfluffy/caustica/rt/RtSharcSupport.java");
        assertTrue(build.contains("SHARC_SDK"));
        assertTrue(build.contains("sharcHeaderHashes"));
        assertTrue(build.contains("NVIDIA-SHARC-SDK.txt"));
        assertTrue(support.contains("artifacts"));
        assertTrue(support.contains("shaderBufferInt64Atomics"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
