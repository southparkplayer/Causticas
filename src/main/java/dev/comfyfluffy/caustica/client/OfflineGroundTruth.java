package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Persistent, uncapped native-resolution accumulation controlled by F7 and a transparent HUD. */
public final class OfflineGroundTruth {
    private static final int DEFAULT_BATCH = 8;
    private static final int[] BENCHMARK_BATCHES = {1, 2, 4, 8};
    private static final long BENCHMARK_WARMUP_NANOS = 350_000_000L;
    private static final long BENCHMARK_MEASURE_NANOS = 1_250_000_000L;
    private static final long RATE_WINDOW_NANOS = 500_000_000L;

    public static final OfflineGroundTruth INSTANCE = new OfflineGroundTruth();
    public static final KeyMapping KEY = new KeyMapping(
            "key.caustica.offline_ground_truth", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC);

    private boolean active;
    private boolean startPending;
    private boolean previousFrozen;
    private long submittedSamples;
    private long startedNanos;
    private int samplesPerBatch = DEFAULT_BATCH;
    private int sessionSignature;

    private long rateWindowNanos;
    private long rateWindowSamples;
    private double samplesPerSecond;

    private boolean benchmarking;
    private boolean benchmarkMeasuring;
    private int benchmarkIndex;
    private long benchmarkPhaseNanos;
    private long benchmarkPhaseSamples;
    private double benchmarkBestRate;
    private int benchmarkBestBatch = DEFAULT_BATCH;

    private OfflineGroundTruth() {
    }

    public boolean active() { return active; }
    public boolean engaged() { return startPending || active; }
    public long submittedSamples() { return submittedSamples; }
    public int samplesPerBatch() { return samplesPerBatch; }
    public int maxBounces() { return 64; }
    public int sessionSignature() { return sessionSignature; }
    public double samplesPerSecond() { return samplesPerSecond; }
    public boolean benchmarking() { return benchmarking; }
    public int benchmarkStage() { return benchmarkIndex + 1; }
    public int benchmarkStageCount() { return BENCHMARK_BATCHES.length; }
    public boolean benchmarkMeasuring() { return benchmarkMeasuring; }
    public String phaseLabel() {
        if (startPending) return "PREPARING";
        if (benchmarking) return "CALIBRATING";
        return active ? "RENDERING" : "IDLE";
    }

    public double elapsedSeconds() {
        return startedNanos == 0L ? 0.0 : (System.nanoTime() - startedNanos) / 1.0e9;
    }

    /** F7 toggles the renderer. Starting automatically calibrates the work batch. */
    public void handleHotkey(Minecraft minecraft) {
        if (engaged()) {
            deactivate(minecraft, "Offline renderer stopped");
            return;
        }
        start(minecraft);
    }

    private void start(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            notify(minecraft, "Offline renderer requires an active world");
            return;
        }
        if (!CausticaConfig.Rt.ENABLED.value()) {
            notify(minecraft, "Offline renderer requires ray tracing");
            return;
        }
        if (UltraScreenshot.INSTANCE.active()) {
            notify(minecraft, "Cancel Ultra Screenshot before starting the offline renderer");
            return;
        }
        if (!RtTerrain.hasPublishedSnapshot()) {
            startPending = true;
            notify(minecraft, "Offline renderer is freezing the terrain snapshot");
            return;
        }
        activate(minecraft);
    }

    private void activate(Minecraft minecraft) {
        startPending = false;
        previousFrozen = minecraft.level.tickRateManager().isFrozen();
        sessionSignature = CausticaConfig.renderSettingsSignature();
        active = true;
        resetProgress();
        beginBenchmark(System.nanoTime());
        minecraft.level.tickRateManager().setFrozen(true);
        RtComposite.INSTANCE.beginOfflineSession();
        RtComposite.INSTANCE.requestTemporalReset();
        notify(minecraft, "Offline renderer started");
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
            benchmarking = false;
            RtComposite.INSTANCE.endOfflineSession();
            RtComposite.INSTANCE.requestTemporalReset();
            return;
        }
        minecraft.level.tickRateManager().setFrozen(true);
        if (CausticaConfig.renderSettingsSignature() != sessionSignature) {
            deactivate(minecraft, "Offline renderer stopped: render settings changed");
        }
    }

    /** Called at frame tail after a fresh native offline batch reached the display image. */
    public void frameRendered() {
        if (!active || !RtComposite.INSTANCE.producedFreshOfflineFrame()) {
            return;
        }
        submittedSamples += samplesPerBatch;
        long now = System.nanoTime();
        updateRate(now);
        if (benchmarking) {
            advanceBenchmark(now);
        }
    }

    /** Used only when GPU images must actually be recreated; ordinary temporal resets preserve progress. */
    public void onRendererReset() {
        if (!active) {
            return;
        }
        resetProgress();
        beginBenchmark(System.nanoTime());
    }

    private void resetProgress() {
        submittedSamples = 0L;
        startedNanos = System.nanoTime();
        rateWindowNanos = startedNanos;
        rateWindowSamples = 0L;
        samplesPerSecond = 0.0;
        samplesPerBatch = DEFAULT_BATCH;
    }

    private void updateRate(long now) {
        long elapsed = now - rateWindowNanos;
        if (elapsed < RATE_WINDOW_NANOS) {
            return;
        }
        samplesPerSecond = (submittedSamples - rateWindowSamples) * 1.0e9 / elapsed;
        rateWindowNanos = now;
        rateWindowSamples = submittedSamples;
    }

    private void beginBenchmark(long now) {
        benchmarking = true;
        benchmarkMeasuring = false;
        benchmarkIndex = 0;
        benchmarkPhaseNanos = now;
        benchmarkPhaseSamples = submittedSamples;
        benchmarkBestRate = 0.0;
        benchmarkBestBatch = DEFAULT_BATCH;
        samplesPerBatch = BENCHMARK_BATCHES[0];
    }

    private void advanceBenchmark(long now) {
        long elapsed = now - benchmarkPhaseNanos;
        if (!benchmarkMeasuring) {
            if (elapsed >= BENCHMARK_WARMUP_NANOS) {
                benchmarkMeasuring = true;
                benchmarkPhaseNanos = now;
                benchmarkPhaseSamples = submittedSamples;
            }
            return;
        }
        if (elapsed < BENCHMARK_MEASURE_NANOS) {
            return;
        }

        double rate = (submittedSamples - benchmarkPhaseSamples) * 1.0e9 / elapsed;
        int testedBatch = BENCHMARK_BATCHES[benchmarkIndex];
        CausticaMod.LOGGER.info("Offline calibration: batch={} max spp, throughput={} max spp/s",
                testedBatch, String.format(java.util.Locale.ROOT, "%.2f", rate));
        if (rate > benchmarkBestRate) {
            benchmarkBestRate = rate;
            benchmarkBestBatch = testedBatch;
        }

        benchmarkIndex++;
        if (benchmarkIndex >= BENCHMARK_BATCHES.length) {
            benchmarking = false;
            benchmarkMeasuring = false;
            samplesPerBatch = benchmarkBestBatch;
            CausticaMod.LOGGER.info("Offline calibration selected batch={} at {} max spp/s",
                    benchmarkBestBatch, String.format(java.util.Locale.ROOT, "%.2f", benchmarkBestRate));
            return;
        }
        samplesPerBatch = BENCHMARK_BATCHES[benchmarkIndex];
        benchmarkMeasuring = false;
        benchmarkPhaseNanos = now;
        benchmarkPhaseSamples = submittedSamples;
    }

    private void deactivate(Minecraft minecraft, String message) {
        if (!engaged()) {
            return;
        }
        boolean wasActive = active;
        startPending = false;
        active = false;
        benchmarking = false;
        if (wasActive && minecraft.level != null) {
            minecraft.level.tickRateManager().setFrozen(previousFrozen);
        }
        RtComposite.INSTANCE.endOfflineSession();
        RtComposite.INSTANCE.requestTemporalReset();
        submittedSamples = 0L;
        startedNanos = 0L;
        notify(minecraft, message);
    }

    /** Renderer-owned terminal condition such as resize or resource reload. */
    public void abort(String reason) {
        deactivate(Minecraft.getInstance(), reason);
    }

    private static void notify(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal(message));
        }
    }
}
