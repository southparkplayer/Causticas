package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Persistent native-resolution accumulation controlled by F7; single-player uses vanilla pause semantics. */
public final class OfflineGroundTruth {
    private static final int DEFAULT_BATCH = 8;
    private static final long BATCH_TARGET_GPU_NANOS = 16_000_000L;
    private static final long RATE_WINDOW_NANOS = 500_000_000L;

    public static final OfflineGroundTruth INSTANCE = new OfflineGroundTruth();
    public static final KeyMapping KEY = new KeyMapping(
            "key.caustica.offline_ground_truth", GLFW.GLFW_KEY_F7, CausticaKeyMappings.CATEGORY);

    private boolean active;
    private boolean startPending;
    private long actualMainPaths;
    private long actualPilotPaths;
    private long startedNanos;
    private int samplesPerBatch = DEFAULT_BATCH;
    private int sessionSignature;
    private int lastGpuFrameSerial;

    private long rateWindowNanos;
    private long rateWindowSamples;
    private double samplesPerSecond;

    private final OfflineBatchController batchController =
            new OfflineBatchController(BATCH_TARGET_GPU_NANOS, DEFAULT_BATCH);

    private OfflineGroundTruth() {
    }

    public boolean active() { return active; }
    public boolean engaged() { return startPending || active; }
    /** Main-path work submitted to the GPU; this is not a configured samples-per-pixel value. */
    public long submittedSamples() { return actualMainPaths; }
    public long actualMainPaths() { return actualMainPaths; }
    public long actualPilotPaths() { return actualPilotPaths; }
    public double scheduledSamplesPerPixel() {
        long pixels = (long) RtComposite.INSTANCE.renderWidth() * RtComposite.INSTANCE.renderHeight();
        return pixels > 0L ? actualMainPaths / (double) pixels : 0.0;
    }
    public double scheduledSamplesPerPixelPerSecond() {
        long pixels = (long) RtComposite.INSTANCE.renderWidth() * RtComposite.INSTANCE.renderHeight();
        return pixels > 0L ? samplesPerSecond / pixels : 0.0;
    }
    public int samplesPerBatch() { return samplesPerBatch; }
    public int maxBounces() { return 64; }
    public int sessionSignature() { return sessionSignature; }
    public double samplesPerSecond() { return samplesPerSecond; }
    public boolean benchmarking() { return false; }
    public int benchmarkStage() { return 0; }
    public int benchmarkStageCount() { return 0; }
    public boolean benchmarkMeasuring() { return false; }
    public String phaseLabel() {
        if (startPending) return "PREPARING";
        return active ? "RENDERING" : "IDLE";
    }

    public double elapsedSeconds() {
        return startedNanos == 0L ? 0.0 : (System.nanoTime() - startedNanos) / 1.0e9;
    }

    /** F7 toggles the renderer. The batch controller adapts from delayed GPU timestamps. */
    public void handleHotkey(Minecraft minecraft) {
        if (engaged()) {
            deactivate(minecraft, Component.translatable("caustica.status.offline.stopped"));
            return;
        }
        start(minecraft);
    }

    private void start(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            notify(minecraft, Component.translatable("caustica.status.offline.requiresWorld"));
            return;
        }
        if (!CausticaConfig.Rt.ENABLED.value()) {
            notify(minecraft, Component.translatable("caustica.status.offline.requiresRayTracing"));
            return;
        }
        if (UltraScreenshot.INSTANCE.active()) {
            notify(minecraft, Component.translatable("caustica.status.offline.ultraScreenshotActive"));
            return;
        }
        if (!RtTerrain.hasPublishedSnapshot()) {
            startPending = true;
            notify(minecraft, Component.translatable("caustica.status.offline.preparingTerrain"));
            return;
        }
        activate(minecraft);
    }

    private void activate(Minecraft minecraft) {
        startPending = false;
        sessionSignature = CausticaConfig.renderSettingsSignature();
        active = true;
        resetProgress();
        RtComposite.INSTANCE.beginOfflineSession();
        RtComposite.INSTANCE.requestTemporalReset();
        notify(minecraft, Component.translatable("caustica.status.offline.started"));
    }

    public void tick(Minecraft minecraft) {
        if (startPending) {
            if (minecraft.level == null || minecraft.player == null) {
                startPending = false;
                return;
            }
            if (RtTerrain.hasPublishedSnapshot()) {
                activate(minecraft);
            }
        }
        if (!active) {
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            active = false;
            RtComposite.INSTANCE.endOfflineSession();
            RtComposite.INSTANCE.requestTemporalReset();
            return;
        }
        if (CausticaConfig.renderSettingsSignature() != sessionSignature) {
            deactivate(minecraft, Component.translatable("caustica.status.offline.settingsChanged"));
        }
    }

    /** Called at frame tail after a fresh native offline batch reached the display image. */
    public void frameRendered() {
        if (!active || !RtComposite.INSTANCE.producedFreshOfflineFrame()) {
            return;
        }
        long now = System.nanoTime();
        int completedFrame = RtComposite.INSTANCE.completedOfflineGpuFrameSerial();
        if (completedFrame > lastGpuFrameSerial) {
            var metadata = RtComposite.INSTANCE.completedOfflineDispatchMetadata();
            if (metadata.configuredBatchLimit() > 0) {
                actualMainPaths += metadata.mainPaths();
                actualPilotPaths += metadata.pilotPaths();
                samplesPerBatch = batchController.observe(
                        RtComposite.INSTANCE.baselineTraceGpuNanos(),
                        metadata.configuredBatchLimit());
            }
            lastGpuFrameSerial = completedFrame;
        }
        updateRate(now);
    }

    /** Used only when GPU images must actually be recreated; ordinary temporal resets preserve progress. */
    public void onRendererReset() {
        if (!active) {
            return;
        }
        resetProgress();
    }

    private void resetProgress() {
        startedNanos = System.nanoTime();
        rateWindowNanos = startedNanos;
        rateWindowSamples = 0L;
        samplesPerSecond = 0.0;
        samplesPerBatch = DEFAULT_BATCH;
        actualMainPaths = 0L;
        actualPilotPaths = 0L;
        lastGpuFrameSerial = RtComposite.INSTANCE.completedOfflineGpuFrameSerial();
        batchController.reset(DEFAULT_BATCH);
    }

    private void updateRate(long now) {
        long elapsed = now - rateWindowNanos;
        if (elapsed < RATE_WINDOW_NANOS) {
            return;
        }
        samplesPerSecond = (actualMainPaths - rateWindowSamples) * 1.0e9 / elapsed;
        rateWindowNanos = now;
        rateWindowSamples = actualMainPaths;
    }

    private void deactivate(Minecraft minecraft, Component message) {
        if (!engaged()) {
            return;
        }
        startPending = false;
        active = false;
        RtComposite.INSTANCE.endOfflineSession();
        RtComposite.INSTANCE.requestTemporalReset();
        actualMainPaths = 0L;
        actualPilotPaths = 0L;
        samplesPerSecond = 0.0;
        startedNanos = 0L;
        notify(minecraft, message);
    }

    /** Renderer-owned terminal condition such as resize or resource reload. */
    public void abort(Component reason) {
        deactivate(Minecraft.getInstance(), reason);
    }

    private static void notify(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(message);
        }
    }
}
