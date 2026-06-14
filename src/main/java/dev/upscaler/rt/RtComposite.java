package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.upscaler.UpscalerMod;
import dev.upscaler.client.SodiumCompat;
import dev.upscaler.mixin.CommandEncoderAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/**
 * P0 on-screen composite: each frame, ray-trace the triangle TLAS into a screen-sized storage
 * image and {@code vkCmdCopyImage} it over the world color target at the end-of-world seam,
 * recorded into the frame's command stream (deferred submit is correct for per-frame work).
 * Gated by {@code -Dupscaler.rt.composite=true}; when on, RT is the sole writer of the world
 * target and the upscaler path is skipped.
 *
 * <p>Pipeline/SBT/descriptor are built once; the output image is rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.composite", "false"));

    private RtPipeline pipeline;
    private RtImage output;
    private long boundTlas;
    private boolean failed;
    private boolean loggedActive;

    private RtComposite() {
    }

    /** Called at the end-of-world seam when {@code ENABLED}. Returns true if it took over the frame. */
    public boolean composite(GpuTexture nativeColor, int width, int height) {
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        RtTriangleScene scene = RtTriangleScene.currentOrNull();
        if (scene == null) {
            return false; // scene not built yet (first tick hasn't run)
        }
        try {
            ensureSetup(ctx, scene.tlas());
            ensureOutput(ctx, width, height);
            recordFrame(nativeColor, width, height);
            if (!loggedActive) {
                loggedActive = true;
                UpscalerMod.LOGGER.info("RT composite active: tracing {}x{} into the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT composite failed; reverting to vanilla/upscaler path", t);
            return false;
        }
    }

    private void ensureSetup(RtContext ctx, long tlas) {
        if (pipeline == null) {
            pipeline = RtPipeline.create(ctx, "triangle.rgen.spv", "triangle.rmiss.spv", "triangle.rchit.spv");
        }
        if (boundTlas != tlas) {
            pipeline.setTlas(tlas);
            boundTlas = tlas;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        if (output != null && output.width == width && output.height == height) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (output != null) {
            output.destroy();
        }
        output = ctx.createStorageImage(width, height);
        pipeline.setStorageImage(output.view);
    }

    private void recordFrame(GpuTexture nativeColor, int width, int height) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            pipeline.trace(cmd, width, height);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).extent().set(width, height, 1);
            VK10.vkCmdCopyImage(cmd, output.image, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
    }

    public void destroy() {
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        boundTlas = 0L;
    }

    private static long vkImage(GpuTexture texture) {
        Long sodiumHandle = SodiumCompat.vkImage(texture);
        if (sodiumHandle != null) {
            return sodiumHandle;
        }
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }
}
