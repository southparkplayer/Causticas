package dev.upscaler.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtDebugLabels;
import dev.upscaler.rt.accel.RtBuffer;
import dev.upscaler.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/** Owns the display exposure value shared by the RT compositor's display-mapping passes. */
public final class RtExposure {
    private RtImage image;
    private RtBuffer histogram;
    private RtBuffer state;
    private RtExposurePipeline pipeline;
    private boolean logged;
    private long lastFrameNanos;

    public RtImage image() {
        return image;
    }

    public boolean ready() {
        return image != null;
    }

    public void ensureResources(RtContext ctx) {
        if (image == null) {
            image = ctx.createStorageImage(1, 1, VK10.VK_FORMAT_R32_SFLOAT, "display exposure");
        }
        if (mode() == Mode.AUTO) {
            if (histogram == null) {
                histogram = ctx.createBuffer(256L * Integer.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false,
                        "exposure histogram");
            }
            if (state == null) {
                state = ctx.createBuffer(16, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "exposure state");
                resetAutoHistory();
            }
            if (pipeline == null) {
                pipeline = RtExposurePipeline.create(ctx);
            }
        }
        logOnce();
    }

    public void record(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, RtImage traceColor) {
        if (image == null) {
            throw new IllegalStateException("RT exposure image not created");
        }
        if (mode() == Mode.AUTO) {
            recordAuto(ctx, cmd, stack, traceColor);
            return;
        }
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure manual write")) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, exposureScale());
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
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
        if (image != null) {
            image.destroy();
            image = null;
        }
    }

    private float exposureScale() {
        return switch (mode()) {
            case MANUAL -> UpscalerConfig.Rt.Exposure.clampScale((float) Math.pow(2.0, manualEv()));
            case AUTO -> UpscalerConfig.Rt.Exposure.clampScale((float) Math.pow(2.0, manualEv()));
        };
    }

    private void recordAuto(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, RtImage traceColor) {
        if (pipeline == null || histogram == null || state == null) {
            throw new IllegalStateException("RT auto exposure resources not created");
        }
        pipeline.setResources(traceColor.view, histogram, image.view, state);
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure histogram clear")) {
            VK10.vkCmdFillBuffer(cmd, histogram.handle, 0, histogram.size, 0);
        }
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.dispatchHistogram(cmd, traceColor.width, traceColor.height);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.dispatchResolve(cmd, Math.max(1, traceColor.width * traceColor.height), autoConfig(), frameTimeSeconds());
    }

    private float frameTimeSeconds() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1.0f / 60.0f
                : Math.clamp((now - lastFrameNanos) / 1_000_000_000.0f, 1.0f / 240.0f, 0.25f);
        lastFrameNanos = now;
        return dt;
    }

    private void resetAutoHistory() {
        if (state == null || state.mapped == 0L) {
            return;
        }
        MemoryUtil.memPutFloat(state.mapped, exposureScale());
        MemoryUtil.memPutInt(state.mapped + 4, 0);
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
                : Float.toString(exposureScale());
        UpscalerMod.LOGGER.info("RT display exposure: mode={}, exposure={}, tonemap=agx, DLSS-RR exposure=NGX auto",
                mode.configName, exposureText);
    }

    private static Mode mode() {
        return Mode.parse(UpscalerConfig.Rt.Exposure.MODE.get());
    }

    private static float manualEv() {
        return UpscalerConfig.Rt.Exposure.MANUAL_EV.value();
    }

    private static AutoConfig autoConfig() {
        return new AutoConfig(
                UpscalerConfig.Rt.Exposure.KEY.value(),
                UpscalerConfig.Rt.Exposure.minEv(),
                UpscalerConfig.Rt.Exposure.maxEv(),
                UpscalerConfig.Rt.Exposure.ADAPT_UP.value(),
                UpscalerConfig.Rt.Exposure.ADAPT_DOWN.value(),
                manualEv());
    }

    record AutoConfig(float key, float minEv, float maxEv, float adaptUp, float adaptDown, float evBias) {
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
