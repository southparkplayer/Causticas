package dev.upscaler.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.upscaler.UpscalerMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;

import java.nio.ByteBuffer;

/**
 * One-shot launch-time verification that the RT path still produces a correct image: trace the
 * triangle into an offscreen image, copy to a host buffer, and check the center pixel is the
 * barycentric color and a corner is the miss background. A cheap regression guard over the whole
 * AS + pipeline + SBT + dispatch chain. Gated by {@code -Dupscaler.rt.selftest} (default on).
 */
public final class RtSelfTest {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.selftest", "true"));
    private static final int DIM = 512;

    private RtSelfTest() {
    }

    public static void run(RtContext ctx, long tlas) {
        RtPipeline pipeline = null;
        RtImage out = null;
        RtBuffer readback = null;
        try {
            pipeline = RtPipeline.create(ctx, "triangle.rgen.spv", "triangle.rmiss.spv", "triangle.rchit.spv");
            pipeline.setTlas(tlas);
            out = ctx.createStorageImage(DIM, DIM);
            pipeline.setStorageImage(out.view);
            readback = ctx.createBuffer((long) DIM * DIM * 4, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);

            long outImage = out.image;
            long readbackBuffer = readback.handle;
            RtPipeline pipe = pipeline;
            ctx.submitSync(cmd -> {
                pipe.trace(cmd, DIM, DIM);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                    copy.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    copy.get(0).imageExtent().set(DIM, DIM, 1);
                    VK10.vkCmdCopyImageToBuffer(cmd, outImage, VK10.VK_IMAGE_LAYOUT_GENERAL, readbackBuffer, copy);
                }
            });

            Vma.vmaInvalidateAllocation(ctx.vma(), readback.allocation, 0, VK10.VK_WHOLE_SIZE);
            ByteBuffer px = MemoryUtil.memByteBuffer(readback.mapped, DIM * DIM * 4);
            UpscalerMod.LOGGER.info("RT self-test OK — center px {} (expect barycentric), corner px {} (expect ~5,5,13)",
                    pixel(px, DIM / 2, DIM / 2), pixel(px, 4, 4));
        } catch (Throwable t) {
            UpscalerMod.LOGGER.error("RT self-test failed", t);
        } finally {
            if (readback != null) {
                readback.destroy();
            }
            if (out != null) {
                out.destroy();
            }
            if (pipeline != null) {
                pipeline.destroy();
            }
        }
    }

    private static String pixel(ByteBuffer px, int x, int y) {
        int o = (y * DIM + x) * 4;
        return (px.get(o) & 0xFF) + "," + (px.get(o + 1) & 0xFF) + "," + (px.get(o + 2) & 0xFF) + "," + (px.get(o + 3) & 0xFF);
    }
}
