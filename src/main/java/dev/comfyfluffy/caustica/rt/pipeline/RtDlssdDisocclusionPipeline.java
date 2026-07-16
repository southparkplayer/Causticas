package dev.comfyfluffy.caustica.rt.pipeline;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Produces DLSSD disocclusion and current-color-bias masks from depth history and motion. */
public final class RtDlssdDisocclusionPipeline {
    private static final String SHADER = "/caustica/rt/dlssd_disocclusion.comp.spv";
    private static final int SET_COUNT = 2;
    private static final int IMAGE_BINDINGS = 7;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private RtDlssdDisocclusionPipeline(RtContext ctx, long descriptorSetLayout,
            long descriptorPool, long[] descriptorSets, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RtDlssdDisocclusionPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(IMAGE_BINDINGS, stack);
            for (int i = 0; i < IMAGE_BINDINGS; i++) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer output = stack.mallocLong(SET_COUNT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, layoutInfo, null, output),
                    "vkCreateDescriptorSetLayout(DLSSD disocclusion)");
            long descriptorLayout = output.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(IMAGE_BINDINGS * SET_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SET_COUNT).pPoolSizes(poolSize);
            output.position(0).limit(1);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, output),
                    "vkCreateDescriptorPool(DLSSD disocclusion)");
            long pool = output.get(0);

            LongBuffer layouts = stack.mallocLong(SET_COUNT);
            for (int i = 0; i < SET_COUNT; i++) layouts.put(i, descriptorLayout);
            output = stack.mallocLong(SET_COUNT);
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(layouts);
            check(VK10.vkAllocateDescriptorSets(vk, allocateInfo, output),
                    "vkAllocateDescriptorSets(DLSSD disocclusion)");
            long[] sets = new long[SET_COUNT];
            output.get(sets);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(12);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorLayout))
                    .pPushConstantRanges(pushRange);
            output = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, output),
                    "vkCreatePipelineLayout(DLSSD disocclusion)");
            long pipelineLayout = output.get(0);

            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo, null, output),
                    "vkCreateComputePipelines(DLSSD disocclusion)");
            long pipeline = output.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "DLSSD disocclusion");
            return new RtDlssdDisocclusionPipeline(ctx, descriptorLayout, pool, sets, pipelineLayout, pipeline);
        }
    }

    public void setImages(long depth, long motion, long historyA, long historyB,
            long disocclusion, long biasCurrent, long animatedGuide) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(IMAGE_BINDINGS * SET_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_BINDINGS * SET_COUNT, stack);
            int write = 0;
            for (int parity = 0; parity < SET_COUNT; parity++) {
                long previousHistory = parity == 0 ? historyA : historyB;
                long currentHistory = parity == 0 ? historyB : historyA;
                long[] images = {depth, motion, previousHistory, currentHistory, disocclusion, biasCurrent,
                        animatedGuide};
                for (int binding = 0; binding < IMAGE_BINDINGS; binding++) {
                    infos.get(write).imageView(images[binding]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(write).sType$Default().dstSet(descriptorSets[parity]).dstBinding(binding)
                            .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(infos.get(write).address(), 1));
                    write++;
                }
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    public void dispatch(VkCommandBuffer commandBuffer, int width, int height,
            boolean resetHistory, long frameIndex) {
        try (MemoryStack stack = MemoryStack.stackPush();
                RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, commandBuffer, "DLSSD disocclusion")) {
            long descriptorSet = descriptorSets[(int) (frameIndex & 1L)];
            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(12);
            push.putInt(0, width).putInt(4, height).putInt(8, resetHistory ? 1 : 0);
            VK10.vkCmdPushConstants(commandBuffer, pipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(commandBuffer, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) return;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RtDlssdDisocclusionPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER, exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer output = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, output),
                    "vkCreateShaderModule(DLSSD disocclusion)");
            return output.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
