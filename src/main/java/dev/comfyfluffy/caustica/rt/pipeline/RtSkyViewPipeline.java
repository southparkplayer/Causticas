package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Pre-integrates the four-wavelength atmosphere when its celestial inputs change. */
public final class RtSkyViewPipeline {
    private static final String SHADER = "/caustica/rt/sky_view.comp.spv";
    private final RtContext ctx;
    private final long layout;
    private final long pool;
    private final long set;
    private final long pipelineLayout;
    private final long pipeline;
    private long boundView;
    private long boundTransmittanceView;

    private RtSkyViewPipeline(RtContext ctx, long layout, long pool, long set,
                              long pipelineLayout, long pipeline) {
        this.ctx = ctx; this.layout = layout; this.pool = pool; this.set = set;
        this.pipelineLayout = pipelineLayout; this.pipeline = pipeline;
    }

    public static RtSkyViewPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) binding.get(i).binding(i)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dsl = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binding);
            check(VK10.vkCreateDescriptorSetLayout(vk, dsl, null, out), "vkCreateDescriptorSetLayout(sky view)");
            long layout = out.get(0);
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            VkDescriptorPoolCreateInfo dp = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(size);
            check(VK10.vkCreateDescriptorPool(vk, dp, null, out), "vkCreateDescriptorPool(sky view)");
            long pool = out.get(0);
            VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(layout));
            check(VK10.vkAllocateDescriptorSets(vk, alloc, out), "vkAllocateDescriptorSets(sky view)");
            long set = out.get(0);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(40);
            VkPipelineLayoutCreateInfo pl = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(layout)).pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(vk, pl, null, out), "vkCreatePipelineLayout(sky view)");
            long pipelineLayout = out.get(0);
            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cp = VkComputePipelineCreateInfo.calloc(1, stack);
            cp.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cp, null, out),
                    "vkCreateComputePipelines(sky view)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "spectral sky-view LUT");
            return new RtSkyViewPipeline(ctx, layout, pool, set, pipelineLayout, pipeline);
        }
    }

    public void setImages(long view, long transmittanceView) {
        if (view == boundView && transmittanceView == boundTransmittanceView) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(2, stack);
            image.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            image.get(1).imageView(transmittanceView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(2, stack);
            for (int i = 0; i < 2; i++) write.get(i).sType$Default().dstSet(set).dstBinding(i)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(image.address(i), 1));
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
        boundView = view;
        boundTransmittanceView = transmittanceView;
    }

    public void dispatchTransmittance(VkCommandBuffer cmd, int width, int height) {
        dispatch(cmd, width, height, 0, 0, 0, 0, 0, 0, 0, 0, true, 0);
    }

    public void dispatchSky(VkCommandBuffer cmd, int width, int height,
                         float sunX, float sunY, float sunZ, float sunSource,
                         float moonX, float moonY, float moonZ, float moonSource,
                         boolean enabled) {
        dispatch(cmd, width, height, sunX, sunY, sunZ, sunSource,
                moonX, moonY, moonZ, moonSource, enabled, 1);
    }

    private void dispatch(VkCommandBuffer cmd, int width, int height,
                          float sunX, float sunY, float sunZ, float sunSource,
                          float moonX, float moonY, float moonZ, float moonSource,
                          boolean enabled, int pass) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "spectral sky-view LUT")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(set), null);
            ByteBuffer push = stack.malloc(40);
            push.putFloat(0, sunX); push.putFloat(4, sunY); push.putFloat(8, sunZ); push.putFloat(12, sunSource);
            push.putFloat(16, moonX); push.putFloat(20, moonY); push.putFloat(24, moonZ); push.putFloat(28, moonSource);
            push.putInt(32, enabled ? 1 : 0);
            push.putInt(36, pass);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    public void destroy() {
        VK10.vkDestroyPipeline(ctx.vk(), pipeline, null);
        VK10.vkDestroyPipelineLayout(ctx.vk(), pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(ctx.vk(), pool, null);
        VK10.vkDestroyDescriptorSetLayout(ctx.vk(), layout, null);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RtSkyViewPipeline.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource: " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + SHADER, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, out), "vkCreateShaderModule(sky view)");
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
