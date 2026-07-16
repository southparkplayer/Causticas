package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtRuntimeStatus;
import dev.comfyfluffy.caustica.rt.RtSharcSupport;
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
    private static final Path DIRECTORY = FabricLoader.getInstance().getGameDir().resolve("caustica-debug-bridge");
    private static final Path COMMAND = DIRECTORY.resolve("command.properties");
    private static final Path STATE = DIRECTORY.resolve("state.properties");
    private static final long PROCESS_START_MILLIS = System.currentTimeMillis();
    private static long lastCommandSequence = -1L;
    private static String lastCommandResult = "none";
    private static int ticks;

    private CausticaDebugBridge() {}

    static void tick(Minecraft client) {
        if (++ticks % 2 == 0) {
            applyCommand(client);
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
            setBoolean(command, "frameStats", CausticaConfig.Rt.FrameStats.ENABLED);
            setInt(command, "debugView", CausticaConfig.Rt.Composite.DEBUG_VIEW);
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
            setBoolean(command, "sharcDetailedStats", CausticaConfig.Rt.Sharc.DETAILED_STATS);
            if (Boolean.parseBoolean(command.getProperty("resetCache", "false"))) {
                RtComposite.INSTANCE.requestSharcReset("debug bridge reset");
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
        state.setProperty("inWorld", Boolean.toString(client.level != null));
        state.setProperty("fullscreen", Boolean.toString(client.getWindow().isFullscreen()));
        state.setProperty("windowWidth", Integer.toString(client.getWindow().getWidth()));
        state.setProperty("windowHeight", Integer.toString(client.getWindow().getHeight()));
        state.setProperty("screenWidth", Integer.toString(client.getWindow().getScreenWidth()));
        state.setProperty("screenHeight", Integer.toString(client.getWindow().getScreenHeight()));
        state.setProperty("sharcConfigured", CausticaConfig.Rt.Sharc.ENABLED.configuredValue().toString());
        state.setProperty("sharcActive", Boolean.toString(RtComposite.INSTANCE.sharcActive()));
        state.setProperty("sharcPackaged", Boolean.toString(RtSharcSupport.packaged()));
        state.setProperty("sharcVersion", RtSharcSupport.version());
        state.setProperty("sharcStatus", RtSharcSupport.status());
        state.setProperty("sharcCapacity", Integer.toString(RtComposite.INSTANCE.sharcCapacity()));
        state.setProperty("sharcAllocatedBytes", Long.toString(RtComposite.INSTANCE.sharcBytes()));
        state.setProperty("sharcResetCount", Long.toString(RtComposite.INSTANCE.sharcResetCountValue()));
        state.setProperty("sharcLastResetReason", RtComposite.INSTANCE.sharcLastResetReason());
        state.setProperty("baselineTraceGpuNanos", Long.toString(RtComposite.INSTANCE.baselineTraceGpuNanos()));
        state.setProperty("sharcUpdateGpuNanos", Long.toString(RtComposite.INSTANCE.sharcUpdateGpuNanos()));
        state.setProperty("sharcResolveGpuNanos", Long.toString(RtComposite.INSTANCE.sharcResolveGpuNanos()));
        state.setProperty("sharcQueryGpuNanos", Long.toString(RtComposite.INSTANCE.sharcQueryGpuNanos()));
        state.setProperty("blasGpuNanos", Long.toString(RtComposite.INSTANCE.blasGpuNanos()));
        state.setProperty("tlasGpuNanos", Long.toString(RtComposite.INSTANCE.tlasGpuNanos()));
        state.setProperty("reconstructionGpuNanos", Long.toString(RtComposite.INSTANCE.reconstructionGpuNanos()));
        state.setProperty("exposureGpuNanos", Long.toString(RtComposite.INSTANCE.exposureGpuNanos()));
        state.setProperty("displayGpuNanos", Long.toString(RtComposite.INSTANCE.displayGpuNanos()));
        state.setProperty("copyGpuNanos", Long.toString(RtComposite.INSTANCE.copyGpuNanos()));
        state.setProperty("backend", RtRuntimeStatus.backend());
        state.setProperty("rtRequested", Boolean.toString(RtDeviceBringup.rtRequested()));
        state.setProperty("rtContextReady", Boolean.toString(RtRuntimeStatus.rtContextReady()));
        state.setProperty("rtFailureLatched", Boolean.toString(RtComposite.INSTANCE.hasFailed()));
        state.setProperty("rtStatus", RtRuntimeStatus.unavailableReason());
        state.setProperty("artifactSha256", RtRuntimeStatus.artifactSha256());
        state.setProperty("frameStats", Boolean.toString(CausticaConfig.Rt.FrameStats.ENABLED.value()));
        state.setProperty("debugView", Integer.toString(CausticaConfig.Rt.Composite.DEBUG_VIEW.value()));
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
