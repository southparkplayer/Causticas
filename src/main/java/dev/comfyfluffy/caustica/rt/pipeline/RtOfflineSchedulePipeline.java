package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkCommandBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static org.lwjgl.vulkan.VK10.*;

/** GPU tile scheduler that produces a VkTraceRaysIndirectCommandKHR-compatible state record. */
public final class RtOfflineSchedulePipeline {
    private static final String SHADER = "/caustica/rt/offline_schedule.comp.spv";
    private static final int RING = 6;
    private static final int PUSH_SIZE = 48;

    private final RtContext ctx;
    private final long layout;
    private final long pipeline;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final RtBuffer state;
    private final RtBuffer work;
    private final int tileWidth;
    private final int tileHeight;
    private int currentSet = -1;
    private boolean destroyed;

    private RtOfflineSchedulePipeline(RtContext ctx, long layout, long pipeline, long descriptorPool,
                                      long[] descriptorSets, RtBuffer state, RtBuffer work,
                                      int tileWidth, int tileHeight) {
        this.ctx = ctx;
        this.layout = layout;
        this.pipeline = pipeline;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.state = state;
        this.work = work;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
    }

    public static RtOfflineSchedulePipeline create(RtContext ctx, int tileWidth, int tileHeight) {
        if (tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("offline scheduler dimensions must be positive");
        }
        long layout = 0L;
        long pipeline = 0L;
        long descriptorPool = 0L;
        long descriptorLayout = 0L;
        RtBuffer state = null;
        RtBuffer work = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.vk(), layoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(offline scheduler)");
            descriptorLayout = handle.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_SIZE);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushRange);
            check(vkCreatePipelineLayout(ctx.vk(), pipelineLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(offline scheduler)");
            layout = handle.get(0);

            byte[] bytes;
            try (InputStream in = RtOfflineSchedulePipeline.class.getResourceAsStream(SHADER)) {
                if (in == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("failed to read " + SHADER, e);
            }
            ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            long module;
            try {
                VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default().pCode(code);
                check(vkCreateShaderModule(ctx.vk(), moduleInfo, null, handle),
                        "vkCreateShaderModule(offline scheduler)");
                module = handle.get(0);
            } finally {
                MemoryUtil.memFree(code);
            }
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer compute = VkComputePipelineCreateInfo.calloc(1, stack);
            compute.get(0).sType$Default().stage(stage).layout(layout);
            check(vkCreateComputePipelines(ctx.vk(), VK_NULL_HANDLE, compute, null, handle),
                    "vkCreateComputePipelines(offline scheduler)");
            pipeline = handle.get(0);
            vkDestroyShaderModule(ctx.vk(), module, null);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(RING * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(RING).pPoolSizes(poolSizes);
            check(vkCreateDescriptorPool(ctx.vk(), poolInfo, null, handle),
                    "vkCreateDescriptorPool(offline scheduler)");
            descriptorPool = handle.get(0);
            LongBuffer layouts = stack.mallocLong(RING);
            for (int i = 0; i < RING; i++) layouts.put(i, descriptorLayout);
            VkDescriptorSetAllocateInfo allocate = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
            LongBuffer sets = stack.mallocLong(RING);
            check(vkAllocateDescriptorSets(ctx.vk(), allocate, sets),
                    "vkAllocateDescriptorSets(offline scheduler)");
            long[] descriptorSets = new long[RING];
            sets.get(descriptorSets);

            state = ctx.createBuffer(40L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false, "offline scheduler state");
            work = ctx.createBuffer((long) tileWidth * tileHeight * 8L,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, "offline tile worklist");
            vkDestroyDescriptorSetLayout(ctx.vk(), descriptorLayout, null);
            descriptorLayout = 0L;
            return new RtOfflineSchedulePipeline(ctx, layout, pipeline, descriptorPool, descriptorSets,
                    state, work, tileWidth, tileHeight);
        } catch (Throwable t) {
            if (work != null) work.destroy();
            if (state != null) state.destroy();
            if (pipeline != 0L) vkDestroyPipeline(ctx.vk(), pipeline, null);
            if (layout != 0L) vkDestroyPipelineLayout(ctx.vk(), layout, null);
            if (descriptorPool != 0L) vkDestroyDescriptorPool(ctx.vk(), descriptorPool, null);
            if (descriptorLayout != 0L) vkDestroyDescriptorSetLayout(ctx.vk(), descriptorLayout, null);
            throw t;
        }
    }

    public RtBuffer state() { return state; }
    public RtBuffer work() { return work; }

    public void dispatch(VkCommandBuffer cmd, MemoryStack stack, long pilotView, long sampleCountView,
                         int imageWidth, int imageHeight, int frameIndex, int batch, float targetError) {
        currentSet = (currentSet + 1) % RING;
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "offline tile scheduler")) {
            vkCmdFillBuffer(cmd, state.handle, 0L, state.size, 0);
            ByteBuffer initialWidth = stack.malloc(4).putInt(0, 1);
            vkCmdUpdateBuffer(cmd, state.handle, 0L, initialWidth);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VkDescriptorImageInfo.Buffer pilotImage = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(pilotView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer countImage = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(sampleCountView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(descriptorSets[currentSet]).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(pilotImage);
            writes.get(1).sType$Default().dstSet(descriptorSets[currentSet]).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(countImage);
            vkUpdateDescriptorSets(ctx.vk(), writes, null);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                    stack.longs(descriptorSets[currentSet]), null);
            ByteBuffer push = stack.calloc(PUSH_SIZE);
            push.putLong(0, work.deviceAddress).putLong(8, state.deviceAddress)
                    .putInt(16, tileWidth).putInt(20, tileHeight)
                    .putInt(24, imageWidth).putInt(28, imageHeight)
                    .putInt(32, tileWidth * tileHeight).putInt(36, frameIndex)
                    .putInt(40, batch).putFloat(44, targetError);
            vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(cmd, (tileWidth * tileHeight + 63) / 64, 1, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        work.destroy();
        state.destroy();
        vkDestroyPipeline(ctx.vk(), pipeline, null);
        vkDestroyPipelineLayout(ctx.vk(), layout, null);
        vkDestroyDescriptorPool(ctx.vk(), descriptorPool, null);
        destroyed = true;
    }
}
