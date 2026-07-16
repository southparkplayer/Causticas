package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.concurrent.CompletableFuture;
import dev.comfyfluffy.caustica.rt.pipeline.RtOfflineExporter;

/** Progressive, native-resolution, un-denoised path-tracing reference mode. */
public final class OfflineGroundTruth {
    private static final int QUERY_SAMPLE_INTERVAL = 32;
    public static final OfflineGroundTruth INSTANCE = new OfflineGroundTruth();
    public static final KeyMapping KEY = new KeyMapping(
            "key.caustica.offline_ground_truth", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC);

    private boolean active;
    private boolean startPending;
    private boolean previousFrozen;
    private boolean complete;
    private boolean exportPending;
    private int submittedSamples;
    private int convergedPixels;
    private int samplesSinceQuery;
    private boolean progressQueryPending;
    private CompletableFuture<RtOfflineExporter.ExportResult> exportFuture;
    private long startedNanos;
    private long lastProgressNanos;

    private OfflineGroundTruth() {
    }

    public boolean active() {
        return active;
    }

    /** True while preparing or rendering; used by UI actions so a pending start can be cancelled. */
    public boolean engaged() {
        return startPending || active;
    }

    public boolean complete() {
        return complete;
    }

    public int submittedSamples() {
        return submittedSamples;
    }

    public int convergedPixels() {
        return convergedPixels;
    }

    public double elapsedSeconds() {
        return startedNanos == 0L ? 0.0 : (System.nanoTime() - startedNanos) / 1.0e9;
    }

    public String status() {
        if (!active) {
            return startPending ? "Preparing first terrain snapshot" : "Idle";
        }
        int target = CausticaConfig.Rt.Offline.MAX_SAMPLES.value();
        int pixels = RtComposite.INSTANCE.offlinePixelCount();
        String convergence = pixels > 0 ? " — " + convergedPixels + " / " + pixels + " px" : "";
        return complete ? "Complete" + convergence
                : "Rendering — up to " + Math.min(submittedSamples, target) + " / " + target + " spp" + convergence;
    }

    public void toggle(Minecraft minecraft) {
        if (engaged()) {
            deactivate(minecraft, "Offline ground truth disabled");
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            notify(minecraft, "Offline ground truth requires an active world");
            return;
        }
        if (!CausticaConfig.Rt.ENABLED.value()) {
            notify(minecraft, "Offline ground truth requires ray tracing");
            return;
        }
        if (UltraScreenshot.INSTANCE.active()) {
            notify(minecraft, "Cancel the ultra screenshot before enabling offline ground truth");
            return;
        }
        if (!RtTerrain.hasPublishedSnapshot()) {
            startPending = true;
            notify(minecraft, "Offline renderer preparing the first terrain snapshot (F7 opens controls)");
            return;
        }

        activate(minecraft);
    }

    private void activate(Minecraft minecraft) {
        startPending = false;
        previousFrozen = minecraft.level.tickRateManager().isFrozen();
        active = true;
        complete = false;
        exportPending = false;
        submittedSamples = 0;
        convergedPixels = 0;
        samplesSinceQuery = 0;
        progressQueryPending = false;
        exportFuture = null;
        startedNanos = System.nanoTime();
        lastProgressNanos = startedNanos;
        minecraft.level.tickRateManager().setFrozen(true);
        RtComposite.INSTANCE.requestTemporalReset();
        notify(minecraft, "Offline renderer started: native adaptive accumulation (F7 opens controls)");
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
            RtComposite.INSTANCE.requestTemporalReset();
            return;
        }
        minecraft.level.tickRateManager().setFrozen(true);
        if (progressQueryPending && !complete) {
            progressQueryPending = false;
            convergedPixels = RtComposite.INSTANCE.queryOfflineConvergedPixels();
            int pixelCount = RtComposite.INSTANCE.offlinePixelCount();
            if (pixelCount > 0 && convergedPixels >= pixelCount) {
                complete = true;
                exportPending = true;
                notify(minecraft, String.format(java.util.Locale.ROOT,
                        "Offline render converged: %d pixels, up to %d spp in %.1fs; saving...",
                        pixelCount, submittedSamples, elapsedSeconds()));
            }
        }
        if (complete && exportPending) {
            exportPending = false;
            try {
                exportFuture = RtComposite.INSTANCE.exportOfflineSnapshotAsync();
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Offline renderer export failed", t);
                notify(minecraft, "Offline render export failed; see latest.log");
            }
        }
        if (exportFuture != null && exportFuture.isDone()) {
            try {
                var result = exportFuture.join();
                notify(minecraft, "Offline render saved: " + result.manifest().getFileName());
            } catch (Throwable t) {
                CausticaMod.LOGGER.error("Offline renderer export failed", t);
                notify(minecraft, "Offline render export failed; see latest.log");
            } finally {
                exportFuture = null;
            }
        }
    }

    /** Called at frame tail after a fresh native offline batch reached the display image. */
    public void frameRendered(Minecraft minecraft) {
        if (!active || complete || !RtComposite.INSTANCE.producedFreshOfflineFrame()) {
            return;
        }
        int target = CausticaConfig.Rt.Offline.MAX_SAMPLES.value();
        submittedSamples = Math.min(target,
                submittedSamples + CausticaConfig.Rt.Offline.SAMPLES_PER_BATCH.value());
        long now = System.nanoTime();
        samplesSinceQuery += CausticaConfig.Rt.Offline.SAMPLES_PER_BATCH.value();
        if (submittedSamples >= CausticaConfig.Rt.Offline.MIN_SAMPLES.value()
                && (samplesSinceQuery >= QUERY_SAMPLE_INTERVAL || submittedSamples >= target)) {
            samplesSinceQuery = 0;
            progressQueryPending = true;
        }
        if (now - lastProgressNanos >= 1_000_000_000L) {
            lastProgressNanos = now;
            notify(minecraft, "Offline renderer: " + submittedSamples + " / " + target + " spp");
        }
    }

    /** Keeps UI progress honest whenever camera, scene, settings, or resources invalidate accumulation. */
    public void onRendererReset() {
        if (!active) {
            return;
        }
        complete = false;
        exportPending = false;
        submittedSamples = 0;
        convergedPixels = 0;
        samplesSinceQuery = 0;
        progressQueryPending = false;
        exportFuture = null;
        startedNanos = System.nanoTime();
        lastProgressNanos = startedNanos;
    }

    private void deactivate(Minecraft minecraft, String message) {
        if (!engaged()) {
            return;
        }
        boolean wasActive = active;
        startPending = false;
        active = false;
        complete = false;
        exportPending = false;
        if (wasActive && minecraft.level != null) {
            minecraft.level.tickRateManager().setFrozen(previousFrozen);
        }
        if (wasActive) {
            RtComposite.INSTANCE.requestTemporalReset();
        }
        submittedSamples = 0;
        convergedPixels = 0;
        samplesSinceQuery = 0;
        progressQueryPending = false;
        exportFuture = null;
        notify(minecraft, message);
    }

    private static void notify(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal(message));
        }
    }
}
