package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtRuntimeStatus;
import dev.comfyfluffy.caustica.rt.RtSharcSupport;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** File-backed localhost debug bridge for repeatable renderer benchmarks without UI automation. */
final class CausticaDebugBridge {
    static final String ENABLE_PROPERTY = "caustica.debugBridge";
    private static final Path DIRECTORY = FabricLoader.getInstance().getGameDir().resolve("caustica-debug-bridge");
    private static final Path COMMAND = DIRECTORY.resolve("command.properties");
    private static final Path STATE = DIRECTORY.resolve("state.properties");
    private static final long PROCESS_START_MILLIS = System.currentTimeMillis();
    private static long lastCommandSequence = -1L;
    private static String lastCommandResult = "none";
    private static int ticks;
    private static int screenshotDelayTicks;

    private CausticaDebugBridge() {}

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static void tick(Minecraft client) {
        if (!enabled()) return;
        if (++ticks % 2 == 0) {
            applyCommand(client);
        }
        if (screenshotDelayTicks > 0 && --screenshotDelayTicks == 0) {
            net.minecraft.client.Screenshot.grab(client, false);
        }
        if (ticks % 10 == 0) {
            publishState(client);
        }
    }

    private static void applyCommand(Minecraft client) {
        if (!Files.isRegularFile(COMMAND)) return;
        Properties command = load(COMMAND);
        long sequence = parseLong(command.getProperty("sequence"), -1L);
        if (sequence <= lastCommandSequence) return;
        lastCommandSequence = sequence;
        if (sequence < PROCESS_START_MILLIS) {
            lastCommandResult = "ignored:command-predates-process";
            return;
        }
        try {
            setBoolean(command, "sharc", CausticaConfig.Rt.Sharc.ENABLED);
            setBoolean(command, "diffusePathGuide", CausticaConfig.Rt.DlssRr.DIFFUSE_PATH_GUIDE);
            setBoolean(command, "frameStats", CausticaConfig.Rt.FrameStats.ENABLED);
            boolean terrainSettingsChanged = command.containsKey("terrainDispatch")
                    || command.containsKey("terrainResults") || command.containsKey("terrainInflight")
                    || command.containsKey("terrainBuildBatch") || command.containsKey("omm")
                    || command.containsKey("ommSubdivision");
            setInt(command, "terrainDispatch", CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS);
            setInt(command, "terrainResults", CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS);
            setInt(command, "terrainInflight", CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS);
            setInt(command, "terrainBuildBatch", CausticaConfig.Rt.Terrain.GPU_BUILD_BATCH_SIZE);
            setBoolean(command, "omm", CausticaConfig.Rt.Omm.ENABLED);
            setInt(command, "ommSubdivision", CausticaConfig.Rt.Omm.SUBDIVISION);
            if (terrainSettingsChanged) RtTerrain.requestFullClear();
            String terrainBenchmark = command.getProperty("terrainBenchmark");
            if (terrainBenchmark != null) {
                RtTerrain.setBenchmarkTelemetryEnabled(Boolean.parseBoolean(terrainBenchmark));
            }
            setInt(command, "debugView", CausticaConfig.Rt.Composite.DEBUG_VIEW);
            setFloat(command, "ambientLightEv", CausticaConfig.Rt.Composite.AMBIENT_LIGHT_EV);
            setFloat(command, "sunlightIntensityEv", CausticaConfig.Rt.Composite.SUNLIGHT_INTENSITY_EV);
            setFloat(command, "moonlightIntensityEv", CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV);
            setFloat(command, "nightAirglowEv", CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV);
            setFloat(command, "sceneScale", CausticaConfig.Rt.Sharc.SCENE_SCALE);
            setFloat(command, "radianceScale", CausticaConfig.Rt.Sharc.RADIANCE_SCALE);
            setInt(command, "accumulationFrames", CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES);
            setInt(command, "staleFrames", CausticaConfig.Rt.Sharc.STALE_FRAMES);
            setBoolean(command, "antiFirefly", CausticaConfig.Rt.Sharc.ANTI_FIREFLY);
            setInt(command, "cacheExponent", CausticaConfig.Rt.Sharc.CACHE_EXPONENT);
            setInt(command, "updateTileSize", CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE);
            setInt(command, "updateMaxBounces", CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES);
            setFloat(command, "minSegmentRatio", CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO);
            setBoolean(command, "glossyQuery", CausticaConfig.Rt.Sharc.GLOSSY_QUERY);
            setBoolean(command, "liveSecondaryDirect", CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT);
            setBoolean(command, "primaryDiffuseReuse", CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE);
            setBoolean(command, "sharcDetailedStats", CausticaConfig.Rt.Sharc.DETAILED_STATS);
            if (Boolean.parseBoolean(command.getProperty("closeScreen", "false"))) {
                client.setScreenAndShow(null);
            }
            if (Boolean.parseBoolean(command.getProperty("openCausticaSettings", "false"))) {
                client.setScreenAndShow(new CausticaOptionsScreen(client.gui.screen(), client.options,
                        command.getProperty("causticaCategory")));
            }
            if (Boolean.parseBoolean(command.getProperty("openSharcSettings", "false"))) {
                client.setScreenAndShow(new RtSharcOptionsScreen(client.gui.screen(), client.options));
            }
            if (Boolean.parseBoolean(command.getProperty("screenshot", "false"))) {
                screenshotDelayTicks = 5;
            }
            if (Boolean.parseBoolean(command.getProperty("resetCache", "false"))) {
                RtComposite.INSTANCE.requestSharcReset("debug bridge reset");
            }
            String gameCommand = command.getProperty("gameCommand", "").trim();
            if (!gameCommand.isEmpty()) {
                if (client.player == null || client.player.connection == null) {
                    throw new IllegalStateException("gameCommand requires an active world");
                }
                client.player.connection.sendCommand(gameCommand);
            }
            String cameraYaw = command.getProperty("cameraYaw");
            String cameraPitch = command.getProperty("cameraPitch");
            if (cameraYaw != null || cameraPitch != null) {
                if (client.player == null) {
                    throw new IllegalStateException("camera orientation requires an active world");
                }
                if (cameraYaw != null) client.player.setYRot(Float.parseFloat(cameraYaw));
                if (cameraPitch != null) client.player.setXRot(Float.parseFloat(cameraPitch));
            }
            String fullscreen = command.getProperty("fullscreen");
            if (fullscreen != null && Boolean.parseBoolean(fullscreen) != client.getWindow().isFullscreen()) {
                client.getWindow().toggleFullScreen();
            }
            CausticaConfig.save();
            lastCommandResult = "applied";
            CausticaMod.LOGGER.info("Debug bridge applied command sequence {}", sequence);
            if (Boolean.parseBoolean(command.getProperty("shutdown", "false"))) {
                publishState(client);
                client.stop();
            }
        } catch (RuntimeException e) {
            lastCommandResult = "error:" + e.getClass().getSimpleName() + ":" + e.getMessage();
            CausticaMod.LOGGER.warn("Debug bridge rejected command sequence {}: {}", sequence, e.toString());
        }
    }

    private static void publishState(Minecraft client) {
        Properties state = new Properties();
        state.setProperty("commandSequence", Long.toString(lastCommandSequence));
        state.setProperty("commandResult", lastCommandResult);
        state.setProperty("fps", Integer.toString(client.getFps()));
        var screen = client.gui.screen();
        state.setProperty("screen", screen == null ? "none" : screen.getClass().getSimpleName());
        state.setProperty("inWorld", Boolean.toString(client.level != null));
        if (client.player != null) {
            state.setProperty("playerX", Double.toString(client.player.getX()));
            state.setProperty("playerY", Double.toString(client.player.getY()));
            state.setProperty("playerZ", Double.toString(client.player.getZ()));
            state.setProperty("playerYaw", Float.toString(client.player.getYRot()));
            state.setProperty("playerPitch", Float.toString(client.player.getXRot()));
        }
        state.setProperty("fullscreen", Boolean.toString(client.getWindow().isFullscreen()));
        state.setProperty("windowWidth", Integer.toString(client.getWindow().getWidth()));
        state.setProperty("windowHeight", Integer.toString(client.getWindow().getHeight()));
        state.setProperty("screenWidth", Integer.toString(client.getWindow().getScreenWidth()));
        state.setProperty("screenHeight", Integer.toString(client.getWindow().getScreenHeight()));
        state.setProperty("sharcConfigured", CausticaConfig.Rt.Sharc.ENABLED.configuredValue().toString());
        state.setProperty("sharcActive", Boolean.toString(RtComposite.INSTANCE.sharcActive()));
        state.setProperty("sharcPrimaryDiffuseActive", Boolean.toString(
                RtComposite.INSTANCE.sharcPrimaryDiffuseActive()));
        state.setProperty("sharcPackaged", Boolean.toString(RtSharcSupport.packaged()));
        state.setProperty("sharcVersion", RtSharcSupport.version());
        state.setProperty("sharcStatus", RtComposite.INSTANCE.sharcStatus());
        state.setProperty("sharcCapacity", Integer.toString(RtComposite.INSTANCE.sharcCapacity()));
        state.setProperty("sharcAllocatedBytes", Long.toString(RtComposite.INSTANCE.sharcBytes()));
        state.setProperty("sharcResetCount", Long.toString(RtComposite.INSTANCE.sharcResetCountValue()));
        state.setProperty("sharcLastResetReason", RtComposite.INSTANCE.sharcLastResetReason());
        state.setProperty("baselineTraceGpuNanos", Long.toString(RtComposite.INSTANCE.baselineTraceGpuNanos()));
        state.setProperty("sharcUpdateGpuNanos", Long.toString(RtComposite.INSTANCE.sharcUpdateGpuNanos()));
        state.setProperty("sharcResolveGpuNanos", Long.toString(RtComposite.INSTANCE.sharcResolveGpuNanos()));
        state.setProperty("sharcQueryGpuNanos", Long.toString(RtComposite.INSTANCE.sharcQueryGpuNanos()));
        state.setProperty("sharcQueryPassGpuNanos", Long.toString(RtComposite.INSTANCE.sharcQueryGpuNanos()));
        state.setProperty("blasGpuNanos", Long.toString(RtComposite.INSTANCE.blasGpuNanos()));
        state.setProperty("entityBlasGpuNanos", Long.toString(RtComposite.INSTANCE.blasGpuNanos()));
        state.setProperty("tlasGpuNanos", Long.toString(RtComposite.INSTANCE.tlasGpuNanos()));
        state.setProperty("reconstructionGpuNanos", Long.toString(RtComposite.INSTANCE.reconstructionGpuNanos()));
        state.setProperty("disocclusionGpuNanos", Long.toString(RtComposite.INSTANCE.disocclusionGpuNanos()));
        state.setProperty("dlssRrGpuNanos", Long.toString(RtComposite.INSTANCE.dlssRrGpuNanos()));
        state.setProperty("diffusePathGuide", Boolean.toString(
                CausticaConfig.Rt.DlssRr.DIFFUSE_PATH_GUIDE.value()));
        state.setProperty("exposureGpuNanos", Long.toString(RtComposite.INSTANCE.exposureGpuNanos()));
        state.setProperty("displayGpuNanos", Long.toString(RtComposite.INSTANCE.displayGpuNanos()));
        state.setProperty("copyGpuNanos", Long.toString(RtComposite.INSTANCE.copyGpuNanos()));
        state.setProperty("pilotGpuNanos", Long.toString(RtComposite.INSTANCE.pilotGpuNanos()));
        state.setProperty("mainTraceGpuNanos", Long.toString(RtComposite.INSTANCE.mainTraceGpuNanos()));
        state.setProperty("scheduleGpuNanos", Long.toString(RtComposite.INSTANCE.scheduleGpuNanos()));
        state.setProperty("offlineActive", Boolean.toString(OfflineGroundTruth.INSTANCE.active()));
        state.setProperty("offlinePhase", OfflineGroundTruth.INSTANCE.phaseLabel());
        state.setProperty("offlineConfiguredBatchLimit", Integer.toString(
                OfflineGroundTruth.INSTANCE.samplesPerBatch()));
        state.setProperty("offlineSubmittedMainPaths", Long.toString(
                OfflineGroundTruth.INSTANCE.submittedSamples()));
        state.setProperty("offlineScheduledSpp", Double.toString(
                OfflineGroundTruth.INSTANCE.scheduledSamplesPerPixel()));
        state.setProperty("offlineScheduledSppPerSecond", Double.toString(
                OfflineGroundTruth.INSTANCE.scheduledSamplesPerPixelPerSecond()));
        var offlineMetadata = RtComposite.INSTANCE.completedOfflineDispatchMetadata();
        state.setProperty("offlineGpuFrameSerial", Integer.toString(
                RtComposite.INSTANCE.completedOfflineGpuFrameSerial()));
        state.setProperty("offlineMainPaths", Long.toString(offlineMetadata.mainPaths()));
        state.setProperty("offlinePilotPaths", Long.toString(offlineMetadata.pilotPaths()));
        state.setProperty("offlineActiveTiles", Integer.toString(offlineMetadata.activeTiles()));
        state.setProperty("offlineTotalTiles", Integer.toString(offlineMetadata.totalTiles()));
        state.setProperty("offlineIndirectInvocations", Integer.toString(
                offlineMetadata.indirectInvocations()));
        state.setProperty("offlineIndirect", Boolean.toString(offlineMetadata.indirect()));
        state.setProperty("dlssgBeforePresentCpuNanos",
                Long.toString(dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.beforePresentCpuNanos()));
        state.setProperty("dlssgAfterPresentCpuNanos",
                Long.toString(dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.afterPresentCpuNanos()));
        state.setProperty("reflexGpuFrameTimeUs",
                Integer.toString(dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.reflexGpuFrameTimeUs()));
        state.setProperty("reflexGpuActiveRenderTimeUs",
                Integer.toString(dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.reflexGpuActiveRenderTimeUs()));
        state.setProperty("renderWidth", Integer.toString(RtComposite.INSTANCE.renderWidth()));
        state.setProperty("renderHeight", Integer.toString(RtComposite.INSTANCE.renderHeight()));
        state.setProperty("outputScalePercent", Integer.toString(RtComposite.INSTANCE.outputScalePercent()));
        state.setProperty("traceWidth", Integer.toString(RtComposite.INSTANCE.renderWidth()));
        state.setProperty("traceHeight", Integer.toString(RtComposite.INSTANCE.renderHeight()));
        state.setProperty("finalOutputWidth", Integer.toString(RtComposite.INSTANCE.outputWidth()));
        state.setProperty("finalOutputHeight", Integer.toString(RtComposite.INSTANCE.outputHeight()));
        state.setProperty("inputRatioTenths", Integer.toString(RtComposite.INSTANCE.inputScalePercent()));
        state.setProperty("inputUpscaleRatio",
                Float.toString(RtComposite.INSTANCE.inputScalePercent() / 10.0f));
        state.setProperty("inputScalePercent", Integer.toString(RtComposite.INSTANCE.inputScalePercent()));
        state.setProperty("requestedInputRatioTenths",
                Integer.toString(RtComposite.INSTANCE.inputScalePercent()));
        state.setProperty("appliedInputRatioTenths",
                Integer.toString(RtComposite.INSTANCE.appliedInputScalePercent()));
        state.setProperty("requestedInputScalePercent",
                Integer.toString(RtComposite.INSTANCE.inputScalePercent()));
        state.setProperty("appliedInputScalePercent",
                Integer.toString(RtComposite.INSTANCE.appliedInputScalePercent()));
        state.setProperty("inputScaleDebounceState", RtComposite.INSTANCE.inputScaleDebounceState());
        state.setProperty("dlssdResolutionPath", RtComposite.INSTANCE.dlssdResolutionPath());
        var dlssdPlan = dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.resolutionPlan();
        if (dlssdPlan != null) {
            state.setProperty("dlssdQuality", Integer.toString(dlssdPlan.quality()));
            state.setProperty("dlssdOutputWidth", Integer.toString(dlssdPlan.dlssdOutputWidth()));
            state.setProperty("dlssdOutputHeight", Integer.toString(dlssdPlan.dlssdOutputHeight()));
            state.setProperty("dlssdIntermediateWidth", Integer.toString(dlssdPlan.dlssdOutputWidth()));
            state.setProperty("dlssdIntermediateHeight", Integer.toString(dlssdPlan.dlssdOutputHeight()));
            state.setProperty("dlssdRenderWidthMin", Integer.toString(dlssdPlan.renderWidthMin()));
            state.setProperty("dlssdRenderHeightMin", Integer.toString(dlssdPlan.renderHeightMin()));
            state.setProperty("dlssdRenderWidthMax", Integer.toString(dlssdPlan.renderWidthMax()));
            state.setProperty("dlssdRenderHeightMax", Integer.toString(dlssdPlan.renderHeightMax()));
        } else {
            state.setProperty("dlssdIntermediateWidth", Integer.toString(RtComposite.INSTANCE.renderWidth()));
            state.setProperty("dlssdIntermediateHeight", Integer.toString(RtComposite.INSTANCE.renderHeight()));
        }
        state.setProperty("dlssdLastEvaluateResult", Integer.toString(
                dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.lastEvaluateResult()));
        state.setProperty("dlssdFrameSucceeded", Boolean.toString(
                dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.lastEvaluateResult() == 0
                        && !dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr.INSTANCE.fallbackActive()));
        state.setProperty("outputWidth", Integer.toString(RtComposite.INSTANCE.outputWidth()));
        state.setProperty("outputHeight", Integer.toString(RtComposite.INSTANCE.outputHeight()));
        state.setProperty("outputScalePath", RtComposite.INSTANCE.outputScalePath());
        state.setProperty("outputScaleFailure", RtComposite.INSTANCE.outputScaleFailure());
        state.setProperty("backend", RtRuntimeStatus.backend());
        state.setProperty("rtRequested", Boolean.toString(RtDeviceBringup.rtRequested()));
        state.setProperty("rtContextReady", Boolean.toString(RtRuntimeStatus.rtContextReady()));
        state.setProperty("rtFailureLatched", Boolean.toString(RtComposite.INSTANCE.hasFailed()));
        state.setProperty("rtStatus", RtRuntimeStatus.unavailableReason());
        state.setProperty("artifactSha256", RtRuntimeStatus.artifactSha256());
        RtTerrain.Status terrain = RtTerrain.status();
        state.setProperty("terrainOutstandingTasks", Integer.toString(terrain.outstandingTasks()));
        state.setProperty("terrainOutstandingLimit", Integer.toString(terrain.outstandingLimit()));
        state.setProperty("terrainQueuedMissing", Integer.toString(terrain.queuedMissing()));
        state.setProperty("terrainQueuedReextract", Integer.toString(terrain.queuedReextract()));
        state.setProperty("terrainResidentSections", Integer.toString(terrain.residentSections()));
        state.setProperty("terrainCancelledTasks", Long.toString(terrain.cancelledTasks()));
        state.setProperty("terrainDiscardedBuilds", Long.toString(terrain.discardedBuilds()));
        state.setProperty("terrainGpuQueueDepth", Integer.toString(terrain.gpuQueueDepth()));
        state.setProperty("terrainBuildsSubmitted", Long.toString(terrain.buildsSubmitted()));
        state.setProperty("terrainBuildsPublished", Long.toString(terrain.buildsPublished()));
        state.setProperty("terrainBuildLatencyNanos", Long.toString(terrain.lastBuildLatencyNanos()));
        state.setProperty("terrainBuildLatencyKind", "host-submit-to-timeline-completion");
        state.setProperty("terrainActiveCompactionQueries", Integer.toString(terrain.activeCompactionQueries()));
        state.setProperty("frameStats", Boolean.toString(CausticaConfig.Rt.FrameStats.ENABLED.value()));
        RtTerrain.StreamingStats streaming = RtTerrain.streamingStats();
        state.setProperty("terrainBenchmark", Boolean.toString(streaming.enabled()));
        state.setProperty("terrainDispatch", Integer.toString(CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS.value()));
        state.setProperty("terrainResults", Integer.toString(CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS.value()));
        state.setProperty("terrainInflight", Integer.toString(CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.value()));
        state.setProperty("terrainBuildBatch", Integer.toString(CausticaConfig.Rt.Terrain.GPU_BUILD_BATCH_SIZE.value()));
        state.setProperty("terrainOmm", Boolean.toString(CausticaConfig.Rt.Omm.ENABLED.value()));
        state.setProperty("terrainOmmSubdivision", Integer.toString(CausticaConfig.Rt.Omm.SUBDIVISION.value()));
        state.setProperty("terrainBenchmarkStartedNanos", Long.toString(streaming.startedNanos()));
        state.setProperty("terrainDesired", Integer.toString(streaming.desired()));
        state.setProperty("terrainMissing", Integer.toString(streaming.missing()));
        state.setProperty("terrainReextract", Integer.toString(streaming.reextract()));
        state.setProperty("terrainInFlight", Integer.toString(streaming.inFlight()));
        state.setProperty("terrainWorkerActive", Integer.toString(streaming.workerActive()));
        state.setProperty("terrainGpuQueued", Integer.toString(streaming.gpuQueued()));
        state.setProperty("terrainCompletedQueued", Integer.toString(streaming.completedQueued()));
        state.setProperty("terrainPublished", Integer.toString(streaming.published()));
        state.setProperty("terrainEmpty", Integer.toString(streaming.empty()));
        state.setProperty("terrainResident", Integer.toString(streaming.resident()));
        state.setProperty("terrainActiveTasks", Integer.toString(streaming.activeTasks()));
        state.setProperty("terrainActionableBacklog", Integer.toString(streaming.actionableBacklog()));
        state.setProperty("terrainDispatchedTotal", Long.toString(streaming.dispatchedTotal()));
        state.setProperty("terrainCpuCompletedTotal", Long.toString(streaming.cpuCompletedTotal()));
        state.setProperty("terrainGpuCompletedTotal", Long.toString(streaming.gpuCompletedTotal()));
        state.setProperty("terrainPublishedTotal", Long.toString(streaming.publishedTotal()));
        state.setProperty("terrainEmptyCompletedTotal", Long.toString(streaming.emptyCompletedTotal()));
        state.setProperty("terrainNeighborBlockedTotal", Long.toString(streaming.neighborBlockedTotal()));
        state.setProperty("terrainSnapshotNanosTotal", Long.toString(streaming.snapshotNanosTotal()));
        state.setProperty("terrainCpuNanosTotal", Long.toString(streaming.cpuNanosTotal()));
        state.setProperty("terrainGpuNanosTotal", Long.toString(streaming.gpuNanosTotal()));
        state.setProperty("debugView", Integer.toString(CausticaConfig.Rt.Composite.DEBUG_VIEW.value()));
        state.setProperty("ambientLightEv", Float.toString(RtComposite.INSTANCE.skyAmbientEv()));
        state.setProperty("sunAngleRadians", Float.toString(RtComposite.INSTANCE.skySunAngle()));
        state.setProperty("moonAngleRadians", Float.toString(RtComposite.INSTANCE.skyMoonAngle()));
        state.setProperty("sunDirection", RtComposite.INSTANCE.skySunX() + ","
                + RtComposite.INSTANCE.skySunY() + "," + RtComposite.INSTANCE.skySunZ());
        state.setProperty("moonDirection", RtComposite.INSTANCE.skyMoonX() + ","
                + RtComposite.INSTANCE.skyMoonY() + "," + RtComposite.INSTANCE.skyMoonZ());
        state.setProperty("moonAltitudeRadians",
                Float.toString(RtComposite.INSTANCE.skyMoonAltitudeRadians()));
        state.setProperty("moonLitFraction",
                Float.toString(RtComposite.INSTANCE.skyMoonLitFraction()));
        state.setProperty("moonHorizonVisibility",
                Float.toString(RtComposite.INSTANCE.skyMoonHorizonVisibility()));
        state.setProperty("moonEffectiveIlluminanceLux",
                Float.toString(RtComposite.INSTANCE.skyMoonEffectiveIlluminanceLux()));
        state.setProperty("dayFactor", Float.toString(RtComposite.INSTANCE.skyDayFactor()));
        state.setProperty("twilightFactor", Float.toString(RtComposite.INSTANCE.skyTwilightFactor()));
        state.setProperty("exposureMode", CausticaConfig.Rt.Exposure.MODE.get());
        state.setProperty("exposureMeteringSource", "pre-reconstruction-scene-linear");
        state.setProperty("exposureManualEv", Float.toString(CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV.value()));
        state.setProperty("exposureCompensationEv", Float.toString(CausticaConfig.Rt.Exposure.COMPENSATION_EV.value()));
        state.setProperty("exposureMinEv", Float.toString(CausticaConfig.Rt.Exposure.MIN_EV.value()));
        state.setProperty("exposureMaxEv", Float.toString(CausticaConfig.Rt.Exposure.MAX_EV.value()));
        state.setProperty("exposureActualEv", Float.toString(RtComposite.INSTANCE.exposureActualEv()));
        state.setProperty("exposureTargetEv", Float.toString(RtComposite.INSTANCE.exposureTargetEv()));
        state.setProperty("exposureConfidence", Float.toString(RtComposite.INSTANCE.exposureConfidence()));
        state.setProperty("exposureTrustedCoverage", Float.toString(RtComposite.INSTANCE.exposureTrustedCoverage()));
        state.setProperty("exposureActiveCeilingEv", Float.toString(RtComposite.INSTANCE.exposureActiveCeilingEv()));
        state.setProperty("exposureAverageLogLuminance", Float.toString(RtComposite.INSTANCE.exposureAverageLogLuminance()));
        state.setProperty("sunlightIntensityEv", Float.toString(CausticaConfig.Rt.Composite.SUNLIGHT_INTENSITY_EV.value()));
        state.setProperty("moonlightIntensityEv", Float.toString(CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV.value()));
        state.setProperty("nightAirglowEv", Float.toString(CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV.value()));
        state.setProperty("sceneScale", Float.toString(CausticaConfig.Rt.Sharc.SCENE_SCALE.value()));
        state.setProperty("radianceScale", Float.toString(CausticaConfig.Rt.Sharc.RADIANCE_SCALE.value()));
        state.setProperty("accumulationFrames", Integer.toString(CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.value()));
        state.setProperty("staleFrames", Integer.toString(CausticaConfig.Rt.Sharc.STALE_FRAMES.value()));
        state.setProperty("antiFirefly", Boolean.toString(CausticaConfig.Rt.Sharc.ANTI_FIREFLY.value()));
        state.setProperty("cacheExponent", Integer.toString(CausticaConfig.Rt.Sharc.CACHE_EXPONENT.value()));
        state.setProperty("updateTileSize", Integer.toString(CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.value()));
        state.setProperty("updateMaxBounces", Integer.toString(CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES.value()));
        state.setProperty("minSegmentRatio", Float.toString(CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO.value()));
        state.setProperty("glossyQuery", Boolean.toString(CausticaConfig.Rt.Sharc.GLOSSY_QUERY.value()));
        state.setProperty("liveSecondaryDirect", Boolean.toString(
                CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.value()));
        state.setProperty("primaryDiffuseReuse", Boolean.toString(
                CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.value()));
        state.setProperty("sharcDetailedStats", Boolean.toString(CausticaConfig.Rt.Sharc.DETAILED_STATS.value()));
        state.setProperty("timestampMillis", Long.toString(System.currentTimeMillis()));
        try {
            Files.createDirectories(DIRECTORY);
            Path temporary = DIRECTORY.resolve("state.properties.tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                state.store(output, "Caustica debug bridge state");
            }
            Files.move(temporary, STATE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Debug bridge failed to publish state: {}", e.toString());
        }
    }

    private static Properties load(Path path) {
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            result.load(input);
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Debug bridge failed to read {}: {}", path, e.toString());
        }
        return result;
    }

    private static void setBoolean(Properties command, String name, CausticaConfig.BooleanSetting setting) {
        String value = command.getProperty(name);
        if (value != null) setting.set(Boolean.parseBoolean(value));
    }

    private static void setInt(Properties command, String name, CausticaConfig.IntSetting setting) {
        String value = command.getProperty(name);
        if (value != null) setting.set(Integer.parseInt(value));
    }

    private static void setFloat(Properties command, String name, CausticaConfig.FloatSetting setting) {
        String value = command.getProperty(name);
        if (value != null) setting.set(Float.parseFloat(value));
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
