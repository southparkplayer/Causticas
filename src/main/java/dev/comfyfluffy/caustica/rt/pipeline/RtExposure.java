package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/** Owns the display exposure value shared by the RT compositor's display-mapping passes. */
public final class RtExposure {
    private static final long TILE_HISTORY_UINTS_PER_TILE = 20L;
    private RtImage image;
    private RtBuffer histogram;
    private RtBuffer state;
    private RtBuffer tileHistory;
    private RtExposurePipeline pipeline;
    private RtContext context;
    private boolean logged;
    private long lastFrameNanos;
    private float offlineFixedScale = Float.NaN;
    private AutoConfig lastAutoConfig;
    private Mode lastMode;
    private volatile boolean resetRequested;

    public RtImage image() {
        return image;
    }

    public boolean ready() {
        return image != null;
    }

    public void ensureResources(RtContext ctx) {
        context = ctx;
        if (image == null) {
            image = ctx.createStorageImage(1, 1, VK10.VK_FORMAT_R32_SFLOAT, "display exposure");
        }
        if (mode() == Mode.AUTO) {
            if (histogram == null) {
                histogram = ctx.createBuffer(260L * Integer.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false,
                        "exposure histogram");
            }
            if (state == null) {
                state = ctx.createBuffer(32, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "exposure state");
                resetAutoHistory();
            }
            if (tileHistory == null) {
                // 65,536 32x32 tiles covers displays up through 8K with ample headroom.
                tileHistory = ctx.createBuffer(65_536L * TILE_HISTORY_UINTS_PER_TILE * Integer.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false,
                        "exposure tile history");
                resetAutoHistory();
            }
            if (pipeline == null) {
                pipeline = RtExposurePipeline.create(ctx);
            }
        }
        logOnce();
    }

    public void record(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, RtImage traceColor, RtImage depth,
                       boolean forceFixedExposure) {
        if (image == null) {
            throw new IllegalStateException("RT exposure image not created");
        }
        Mode currentMode = mode();
        if (lastMode != null && lastMode != currentMode) resetAutoHistory();
        lastMode = currentMode;
        if (!forceFixedExposure && currentMode == Mode.AUTO) {
            recordAuto(ctx, cmd, stack, traceColor, depth);
            return;
        }
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure manual write")) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, forceFixedExposure && Float.isFinite(offlineFixedScale)
                    ? offlineFixedScale : manualExposureScale());
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
    }

    /** Capture the exposure that completed the last realtime frame after the caller has idled the queue. */
    public void beginOfflineSession(RtContext ctx) {
        float captured = manualExposureScale();
        if (mode() == Mode.AUTO && state != null && state.mapped != 0L) {
            Vma.vmaInvalidateAllocation(ctx.vma(), state.allocation, 0, 16);
            float previous = MemoryUtil.memGetFloat(state.mapped);
            int initialized = MemoryUtil.memGetInt(state.mapped + 4);
            if (initialized != 0 && Float.isFinite(previous) && previous > 0.0f) {
                captured = CausticaConfig.Rt.Exposure.clampScale(previous);
            }
        }
        offlineFixedScale = captured;
        CausticaMod.LOGGER.info("Offline renderer captured display exposure scale {}", offlineFixedScale);
    }

    public void endOfflineSession() {
        offlineFixedScale = Float.NaN;
    }

    public float offlineFixedScale() {
        return offlineFixedScale;
    }

    public void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        if (histogram != null) {
            histogram.destroy();
            histogram = null;
        }
        if (state != null) {
            state.destroy();
            state = null;
        }
        if (tileHistory != null) {
            tileHistory.destroy();
            tileHistory = null;
        }
        if (image != null) {
            image.destroy();
            image = null;
        }
        offlineFixedScale = Float.NaN;
    }

    private float manualExposureScale() {
        return CausticaConfig.Rt.Exposure.clampScale((float) Math.pow(2.0, manualExposureEv()));
    }

    private void recordAuto(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, RtImage traceColor, RtImage depth) {
        if (pipeline == null || histogram == null || state == null || tileHistory == null) {
            throw new IllegalStateException("RT auto exposure resources not created");
        }
        AutoConfig config = autoConfig();
        if (lastAutoConfig != null && !lastAutoConfig.equals(config)) resetAutoHistory();
        lastAutoConfig = config;
        pipeline.setResources(traceColor.view, depth.view, histogram, image.view, state, tileHistory);
        if (resetRequested) {
            VK10.vkCmdFillBuffer(cmd, state.handle, 0, state.size, 0);
            VK10.vkCmdFillBuffer(cmd, tileHistory.handle, 0, tileHistory.size, 0);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            resetRequested = false;
        }
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure histogram clear")) {
            VK10.vkCmdFillBuffer(cmd, histogram.handle, 0, histogram.size, 0);
        }
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.dispatchHistogram(cmd, traceColor.width, traceColor.height, config);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.dispatchResolve(cmd, config, frameTimeSeconds(), traceColor.width, traceColor.height);
    }

    private float frameTimeSeconds() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1.0f / 60.0f
                : Math.clamp((now - lastFrameNanos) / 1_000_000_000.0f, 1.0f / 240.0f, 0.25f);
        lastFrameNanos = now;
        return dt;
    }

    public void resetAutoHistory() {
        resetRequested = true;
        lastFrameNanos = 0L;
    }

    private void logOnce() {
        if (logged) {
            return;
        }
        logged = true;
        Mode mode = mode();
        AutoConfig autoConfig = autoConfig();
        String exposureText = mode == Mode.AUTO
                ? "auto(key=" + autoConfig.key + ", minEv=" + autoConfig.minEv + ", maxEv=" + autoConfig.maxEv
                + ", adaptUp=" + autoConfig.adaptUp + ", adaptDown=" + autoConfig.adaptDown
                + ", evBias=" + autoConfig.evBias + ")"
                : Float.toString(manualExposureScale());
        CausticaMod.LOGGER.info("RT display exposure: mode={}, exposure={}, sdrTonemap={}, hdrTonemap={}, metering=pre-reconstruction scene-linear, DLSS-RR contract=neutral",
                mode.configName, exposureText, CausticaConfig.Rt.Sdr.TONEMAP_MODE.get(),
                CausticaConfig.Rt.Hdr.TONEMAP_MODE.get());
    }

    private static Mode mode() {
        return Mode.parse(CausticaConfig.Rt.Exposure.MODE.get());
    }

    private static float manualExposureEv() {
        return CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV.value();
    }

    private static AutoConfig autoConfig() {
        return new AutoConfig(
                CausticaConfig.Rt.Exposure.KEY.value(),
                CausticaConfig.Rt.Exposure.minEv(),
                CausticaConfig.Rt.Exposure.maxEv(),
                CausticaConfig.Rt.Exposure.ADAPT_UP.value(),
                CausticaConfig.Rt.Exposure.ADAPT_DOWN.value(),
                CausticaConfig.Rt.Exposure.COMPENSATION_EV.value(),
                CausticaConfig.Rt.Exposure.lowPercentile(),
                CausticaConfig.Rt.Exposure.highPercentile(),
                CausticaConfig.Rt.Exposure.highlightPercentile(),
                effectiveHighlightHeadroom(),
                CausticaConfig.Rt.Exposure.CENTER_WEIGHT.value(),
                CausticaConfig.Rt.Exposure.logMin(),
                CausticaConfig.Rt.Exposure.logMax());
    }

    private static float effectiveHighlightHeadroom() {
        float configured = CausticaConfig.Rt.Exposure.HIGHLIGHT_HEADROOM.value();
        if (!CausticaConfig.Rt.Hdr.ENABLED.get()) {
            return configured;
        }
        float displayHeadroom = CausticaConfig.Rt.Hdr.PEAK_NITS.value()
                / Math.max(CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS.value(), 1.0f);
        return Math.min(configured, Math.max(displayHeadroom, CausticaConfig.Rt.Exposure.KEY.value()));
    }

    record AutoConfig(float key, float minEv, float maxEv, float adaptUp, float adaptDown, float evBias,
                      float lowPercentile, float highPercentile, float highlightPercentile,
                      float highlightHeadroom, float centerWeight, float logMin, float logMax) {
    }

    public float actualEv() {
        return mode() == Mode.MANUAL ? manualExposureEv() : readStateFloat(0, 0.0f, true);
    }
    public float targetEv() { return readStateFloat(8, 0.0f, false); }
    public float confidence() { return readStateFloat(12, 0.0f, false); }
    public float trustedCoverage() { return readStateFloat(16, 0.0f, false); }
    public float activeCeilingEv() { return readStateFloat(20, 4.0f, false); }
    public float averageLogLuminance() { return readStateFloat(24, -20.0f, false); }

    private float readStateFloat(int offset, float fallback, boolean scaleToEv) {
        if (state == null || state.mapped == 0L) return fallback;
        if (context != null) Vma.vmaInvalidateAllocation(context.vma(), state.allocation, 0, 32);
        float value = MemoryUtil.memGetFloat(state.mapped + offset);
        if (!Float.isFinite(value)) return fallback;
        return scaleToEv ? (float)(Math.log(Math.max(value, 1.0e-8f)) / Math.log(2.0)) : value;
    }

    private enum Mode {
        MANUAL("manual"),
        AUTO("auto");

        private final String configName;

        Mode(String configName) {
            this.configName = configName;
        }

        static Mode parse(String value) {
            if (value != null) {
                for (Mode mode : values()) {
                    if (mode.configName.equalsIgnoreCase(value)) {
                        return mode;
                    }
                }
            }
            return AUTO;
        }
    }
}
