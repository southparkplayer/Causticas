package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.CommandEncoderAccessor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCmdTraceRaysKHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR;

/**
 * P0 trace — step 2: ray-trace the one-triangle TLAS into a storage image with an RT
 * pipeline + SBT, then read back the center vs. a corner pixel to verify. Center should be
 * the barycentric triangle color; corner should be the miss background {@code (0.02,0.02,0.05)}.
 * No screen composite yet — this isolates pipeline/SBT/dispatch from the world-target seam.
 */
public final class RtTriangleTrace {
    private static final int DIM = 512;
    private static final String SHADER_DIR = "/upscaler/rt/";

    private RtTriangleTrace() {
    }

    private static long align(long v, long a) {
        return (v + a - 1) & ~(a - 1);
    }

    public static void run(VulkanDevice device, long vma, long tlas) {
        VkDevice vk = device.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // --- RT pipeline properties (handle sizes/alignments for the SBT) ---
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR
                    .calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(rtProps.address());
            VK12.vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), props2);
            int handleSize = rtProps.shaderGroupHandleSize();
            int baseAlign = rtProps.shaderGroupBaseAlignment();

            // --- output storage image (R8G8B8A8_UNORM, STORAGE + TRANSFER_SRC) ---
            long image;
            long imageView;
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(DIM, DIM, 1);
            VmaAllocationCreateInfo iaci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            org.lwjgl.PointerBuffer pAlloc = stack.mallocPointer(1);
            java.nio.LongBuffer pImage = stack.mallocLong(1);
            int rc = Vma.vmaCreateImage(vma, ici, iaci, pImage, pAlloc, null);
            check(rc, "vmaCreateImage(rt output)");
            image = pImage.get(0);

            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(VK10.VK_FORMAT_R8G8B8A8_UNORM);
            vci.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            java.nio.LongBuffer pView = stack.mallocLong(1);
            check(VK10.vkCreateImageView(vk, vci, null, pView), "vkCreateImageView(rt output)");
            imageView = pView.get(0);

            // --- descriptor set layout (0 = TLAS, 1 = storage image) ---
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            binds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            java.nio.LongBuffer pSetLayout = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, pSetLayout), "vkCreateDescriptorSetLayout");
            long setLayout = pSetLayout.get(0);

            // --- descriptor pool + set ---
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            java.nio.LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, pPool), "vkCreateDescriptorPool");
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pPool.get(0)).pSetLayouts(stack.longs(setLayout));
            java.nio.LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets");
            long descriptorSet = pSet.get(0);

            // write TLAS (binding 0) + storage image (binding 1)
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                    .pAccelerationStructures(stack.longs(tlas));
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(imageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().pNext(asWrite.address()).dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imgInfo);
            VK10.vkUpdateDescriptorSets(vk, writes, null);

            // --- pipeline layout ---
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
            java.nio.LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, pLayout), "vkCreatePipelineLayout");
            long pipelineLayout = pLayout.get(0);

            // --- shader stages ---
            long rgen = loadModule(vk, stack, "triangle.rgen.spv");
            long rmiss = loadModule(vk, stack, "triangle.rmiss.spv");
            long rchit = loadModule(vk, stack, "triangle.rchit.spv");
            ByteBuffer entry = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(3, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(rgen).pName(entry);
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_MISS_BIT_KHR).module(rmiss).pName(entry);
            stages.get(2).sType$Default().stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(rchit).pName(entry);

            // --- shader groups: 0 raygen(general), 1 miss(general), 2 hit(triangles) ---
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
            groups.get(0).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(0).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            groups.get(1).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(1).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            groups.get(2).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(2).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);

            VkRayTracingPipelineCreateInfoKHR.Buffer rtpci = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            rtpci.get(0).sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(pipelineLayout);
            java.nio.LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateRayTracingPipelinesKHR(vk, VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE, rtpci, null, pPipeline),
                    "vkCreateRayTracingPipelinesKHR");
            long pipeline = pPipeline.get(0);

            // --- SBT: one record per group, each region 64-aligned, stride over-aligned to baseAlign ---
            int groupCount = 3;
            ByteBuffer handles = stack.malloc(groupCount * handleSize);
            check(vkGetRayTracingShaderGroupHandlesKHR(vk, pipeline, 0, groupCount, handles), "vkGetRayTracingShaderGroupHandlesKHR");
            long stride = align(handleSize, baseAlign); // 64
            long sbtSize = stride * groupCount;
            GpuBuffer sbt = createBuffer(vk, vma, sbtSize, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR);
            for (int g = 0; g < groupCount; g++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(handles) + (long) g * handleSize, sbt.mapped + g * stride, handleSize);
            }

            VkStridedDeviceAddressRegionKHR raygenRegion = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.address + 0L * stride).stride(stride).size(stride);
            VkStridedDeviceAddressRegionKHR missRegion = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.address + 1L * stride).stride(stride).size(stride);
            VkStridedDeviceAddressRegionKHR hitRegion = VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(sbt.address + 2L * stride).stride(stride).size(stride);
            VkStridedDeviceAddressRegionKHR callableRegion = VkStridedDeviceAddressRegionKHR.calloc(stack);

            // --- readback buffer ---
            GpuBuffer readback = createBufferHostVisible(vk, vma, (long) DIM * DIM * 4, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

            // --- record + synchronously submit: layout->GENERAL, clear, trace, copy ---
            RtCommands.submitSync(device, cmd -> {
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, stack);
            toGeneral.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image);
            toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, toGeneral);

            // Diagnostic sentinel: clear to (10,20,30) before tracing. If the readback shows
            // this, the trace's imageStore isn't landing; if 0, the copy/readback is broken;
            // if barycentric/background, the trace works.
            VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
            clearColor.float32(0, 10f / 255f).float32(1, 20f / 255f).float32(2, 30f / 255f).float32(3, 1f);
            VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack);
            clearRange.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image, VK10.VK_IMAGE_LAYOUT_GENERAL, clearColor, clearRange);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(descriptorSet), null);
            vkCmdTraceRaysKHR(cmd, raygenRegion, missRegion, hitRegion, callableRegion, DIM, DIM, 1);

            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.get(0).imageExtent().set(DIM, DIM, 1);
            VK10.vkCmdCopyImageToBuffer(cmd, image, VK10.VK_IMAGE_LAYOUT_GENERAL, readback.buffer, copy);
            });

            // --- read center vs corner pixel (invalidate first in case host memory isn't coherent) ---
            Vma.vmaInvalidateAllocation(vma, readback.allocation, 0, VK10.VK_WHOLE_SIZE);
            ByteBuffer px = MemoryUtil.memByteBuffer(readback.mapped, DIM * DIM * 4);
            String center = pixel(px, DIM / 2, DIM / 2);
            String corner = pixel(px, 4, 4);
            UpscalerMod.LOGGER.info("RT triangle trace OK — center px {} (expect barycentric), corner px {} (expect ~5,5,13)", center, corner);
        } catch (Throwable t) {
            UpscalerMod.LOGGER.error("RT triangle trace failed", t);
        }
    }

    private static String pixel(ByteBuffer px, int x, int y) {
        int o = (y * DIM + x) * 4;
        return (px.get(o) & 0xFF) + "," + (px.get(o + 1) & 0xFF) + "," + (px.get(o + 2) & 0xFF) + "," + (px.get(o + 3) & 0xFF);
    }

    private record GpuBuffer(long buffer, long allocation, long address, long mapped) {
    }

    private static GpuBuffer createBuffer(VkDevice vk, long vma, long size, int usage) {
        return makeBuffer(vk, vma, size, usage, true);
    }

    private static GpuBuffer createBufferHostVisible(VkDevice vk, long vma, long size, int usage) {
        return makeBuffer(vk, vma, size, usage, true);
    }

    private static GpuBuffer makeBuffer(VkDevice vk, long vma, long size, int usage, boolean hostVisible) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            // RANDOM (not SEQUENTIAL_WRITE) so the same helper serves both the write-once SBT
            // and the read-back buffer; the two host-access flags are mutually exclusive in VMA.
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            java.nio.LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(vma, bci, aci, pBuf, pAlloc, info), "vmaCreateBuffer(rt)");
            long buffer = pBuf.get(0);
            VkBufferDeviceAddressInfo bdai = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
            return new GpuBuffer(buffer, pAlloc.get(0), VK12.vkGetBufferDeviceAddress(vk, bdai), info.pMappedData());
        }
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) throws IOException {
        byte[] bytes;
        try (InputStream in = RtTriangleTrace.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IOException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            java.nio.LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void check(int rc, String what) {
        if (rc != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + rc);
        }
    }
}
