package dev.upscaler.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.upscaler.UpscalerMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/** Owns the display exposure value shared by the RT compositor's display-mapping passes. */
final class RtExposure {
    private static final float DEFAULT_FIXED_EXPOSURE = 1.1f;

    private final Mode mode = Mode.parse(System.getProperty("upscaler.rt.exposure.mode", "auto"));
    private final float fixedExposure = positiveFloat("upscaler.rt.exposure.fixed", DEFAULT_FIXED_EXPOSURE);
    private final float manualEv = finiteFloat("upscaler.rt.exposure.manualEv", 0.0f);
    private final AutoConfig autoConfig = new AutoConfig(
            positiveFloat("upscaler.rt.exposure.key", 0.18f),
            finiteFloat("upscaler.rt.exposure.minEv", -0.5f),
            finiteFloat("upscaler.rt.exposure.maxEv", 2.5f),
            positiveFloat("upscaler.rt.exposure.adaptUp", 0.12f),
            positiveFloat("upscaler.rt.exposure.adaptDown", 0.35f),
            manualEv);

    private RtImage image;
    private RtBuffer histogram;
    private RtBuffer state;
    private RtExposurePipeline pipeline;
    private boolean logged;
    private long lastFrameNanos;

    RtImage image() {
        return image;
    }

    boolean ready() {
        return image != null;
    }

    void ensureResources(RtContext ctx) {
        if (image == null) {
            image = ctx.createStorageImage(1, 1, VK10.VK_FORMAT_R32_SFLOAT);
        }
        if (mode == Mode.AUTO) {
            if (histogram == null) {
                histogram = ctx.createBuffer(256L * Integer.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            }
            if (state == null) {
                state = ctx.createBuffer(16, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                resetAutoHistory();
            }
            if (pipeline == null) {
                pipeline = RtExposurePipeline.create(ctx);
            }
        }
        logOnce();
    }

    void record(VkCommandBuffer cmd, MemoryStack stack, RtImage traceColor) {
        if (image == null) {
            throw new IllegalStateException("RT exposure image not created");
        }
        if (mode == Mode.AUTO) {
            recordAuto(cmd, stack, traceColor);
            return;
        }
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        color.float32(0, exposureScale());
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, image.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
    }

    void destroy() {
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
        return switch (mode) {
            case MANUAL -> clampExposure((float) Math.pow(2.0, manualEv));
            case FIXED -> fixedExposure;
            case AUTO -> fixedExposure;
        };
    }

    private void recordAuto(VkCommandBuffer cmd, MemoryStack stack, RtImage traceColor) {
        if (pipeline == null || histogram == null || state == null) {
            throw new IllegalStateException("RT auto exposure resources not created");
        }
        pipeline.setResources(traceColor.view, histogram, image.view, state);
        VK10.vkCmdFillBuffer(cmd, histogram.handle, 0, histogram.size, 0);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.dispatchHistogram(cmd, traceColor.width, traceColor.height);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        pipeline.dispatchResolve(cmd, Math.max(1, traceColor.width * traceColor.height), autoConfig, frameTimeSeconds());
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
        MemoryUtil.memPutFloat(state.mapped, fixedExposure);
        MemoryUtil.memPutInt(state.mapped + 4, 0);
        lastFrameNanos = 0L;
    }

    private void logOnce() {
        if (logged) {
            return;
        }
        logged = true;
        String exposureText = mode == Mode.AUTO
                ? "auto(key=" + autoConfig.key + ", minEv=" + autoConfig.minEv + ", maxEv=" + autoConfig.maxEv
                + ", adaptUp=" + autoConfig.adaptUp + ", adaptDown=" + autoConfig.adaptDown
                + ", evBias=" + autoConfig.evBias + ")"
                : Float.toString(exposureScale());
        UpscalerMod.LOGGER.info("RT display exposure: mode={}, exposure={}, tonemap=aces, DLSS-RR exposure=NGX auto",
                mode.configName, exposureText);
    }

    private static float positiveFloat(String key, float fallback) {
        return clampExposure(finiteFloat(key, fallback));
    }

    private static float finiteFloat(String key, float fallback) {
        try {
            float value = Float.parseFloat(System.getProperty(key, Float.toString(fallback)));
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clampExposure(float value) {
        return Math.clamp(value, 1.0e-4f, 1.0e4f);
    }

    record AutoConfig(float key, float minEv, float maxEv, float adaptUp, float adaptDown, float evBias) {
        AutoConfig {
            if (maxEv < minEv) {
                float t = minEv;
                minEv = maxEv;
                maxEv = t;
            }
        }
    }

    private enum Mode {
        FIXED("fixed"),
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
            return FIXED;
        }
    }
}
