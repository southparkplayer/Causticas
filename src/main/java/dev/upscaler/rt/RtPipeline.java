package dev.upscaler.rt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.upscaler.rt.RtContext.check;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR;

/**
 * An RT pipeline with a 3-group SBT (raygen + miss + one triangle hit group) and a descriptor
 * set of {binding 0 = TLAS, binding 1 = storage image}. Built from SPIR-V resources. Update the
 * bindings with {@link #setTlas}/{@link #setStorageImage}, then {@link #trace}. Reusable across
 * the triangle spike and P1 terrain (extend the descriptor layout there as needed).
 */
public final class RtPipeline {
    private static final String SHADER_DIR = "/upscaler/rt/";

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtBuffer sbt;
    private final long sbtStride;
    private boolean destroyed;

    private RtPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline, RtBuffer sbt, long stride) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
        this.sbt = sbt;
        this.sbtStride = stride;
    }

    public static RtPipeline create(RtContext ctx, String rgen, String rmiss, String rchit) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout");
            long dsl = p.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool");
            long pool = p.get(0);
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets");
            long set = pSet.get(0);

            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(dsl));
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout");
            long layout = p.get(0);

            long mGen = loadModule(vk, stack, rgen);
            long mMiss = loadModule(vk, stack, rmiss);
            long mHit = loadModule(vk, stack, rchit);
            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(3, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(mGen).pName(entry);
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_MISS_BIT_KHR).module(mMiss).pName(entry);
            stages.get(2).sType$Default().stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(mHit).pName(entry);

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
            groups.get(0).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(0).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            groups.get(1).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(1).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            groups.get(2).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(2).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);

            VkRayTracingPipelineCreateInfoKHR.Buffer rtpci = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            rtpci.get(0).sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateRayTracingPipelinesKHR(vk, VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE, rtpci, null, pPipeline),
                    "vkCreateRayTracingPipelinesKHR");
            long pipeline = pPipeline.get(0);

            VK10.vkDestroyShaderModule(vk, mGen, null);
            VK10.vkDestroyShaderModule(vk, mMiss, null);
            VK10.vkDestroyShaderModule(vk, mHit, null);

            // SBT: one record per group, each region 64-aligned (stride over-aligned to baseAlignment).
            int handleSize = ctx.shaderGroupHandleSize();
            int groupCount = 3;
            ByteBuffer handles = stack.malloc(groupCount * handleSize);
            check(vkGetRayTracingShaderGroupHandlesKHR(vk, pipeline, 0, groupCount, handles), "vkGetRayTracingShaderGroupHandlesKHR");
            long stride = align(handleSize, ctx.shaderGroupBaseAlignment());
            RtBuffer sbt = ctx.createBuffer(stride * groupCount, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true);
            for (int g = 0; g < groupCount; g++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(handles) + (long) g * handleSize, sbt.mapped + g * stride, handleSize);
            }
            return new RtPipeline(ctx, dsl, pool, set, layout, pipeline, sbt, stride);
        }
    }

    public void setTlas(long tlas) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR).pAccelerationStructures(stack.longs(tlas));
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().pNext(asWrite.address()).dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    public void setStorageImage(long imageView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
        }
    }

    /** Record bind + trace into the given command buffer. */
    public void trace(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(descriptorSet), null);
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + sbtStride).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.deviceAddress + 2L * sbtStride).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            vkCmdTraceRaysKHR(cmd, raygen, miss, hit, callable, width, height, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        sbt.destroy();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long align(long v, long a) {
        return (v + a - 1) & ~(a - 1);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
