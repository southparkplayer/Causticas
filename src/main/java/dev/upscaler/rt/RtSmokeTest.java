package dev.upscaler.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.mixin.CommandEncoderAccessor;
import dev.upscaler.mixin.GpuDeviceAccessor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR;

/**
 * P0 trace — step 1: build a one-triangle BLAS + TLAS on the live (RT-enabled) device and
 * log the sizes. Proves the raw-VK acceleration-structure path (build-size query, scratch,
 * buffer device addresses, build-command submission) before the RT pipeline / SBT / dispatch
 * are added on top. One-shot, render thread, device idle between frames.
 *
 * <p>Geometry is placed so it already matches {@code triangle.rgen}: vertices in [-1, 1] on
 * the z = 0 plane, identity TLAS instance — orthographic +Z rays from z = -1 will hit it.
 *
 * <p>Resources are intentionally leaked for the spike; lifecycle comes with P2.
 */
public final class RtSmokeTest {
    private static boolean done;

    private RtSmokeTest() {
    }

    public static void run() {
        if (done) {
            return;
        }
        if (!RtDeviceBringup.rtRequested()) {
            done = true;
            return;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device)) {
            return; // device not ready yet; try again next tick
        }
        done = true;
        try {
            build(device);
        } catch (Throwable t) {
            UpscalerMod.LOGGER.error("RT smoke test (AS build) failed", t);
        }
    }

    // Our own VMA allocator, created WITH VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT —
    // vanilla's (VulkanBackend.createVma) lacks it, so it rejects SHADER_DEVICE_ADDRESS buffers.
    private static long rtVma;

    private static long rtVma(VulkanDevice device) {
        if (rtVma != 0L) {
            return rtVma;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vk = device.vkDevice();
            VkPhysicalDevice phys = vk.getPhysicalDevice();
            VkInstance instance = phys.getInstance();
            VmaVulkanFunctions fns = VmaVulkanFunctions.calloc(stack).set(instance, vk);
            VmaAllocatorCreateInfo ci = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .instance(instance)
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                    .device(vk)
                    .physicalDevice(phys)
                    .pVulkanFunctions(fns);
            PointerBuffer p = stack.mallocPointer(1);
            int rc = Vma.vmaCreateAllocator(ci, p);
            if (rc != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateAllocator(RT) failed: " + rc);
            }
            rtVma = p.get(0);
        }
        return rtVma;
    }

    private record GpuBuffer(long buffer, long allocation, long deviceAddress, long mapped) {
    }

    private static GpuBuffer createBuffer(VkDevice vk, long vma, long size, int usage, boolean hostVisible) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                aci.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            int rc = Vma.vmaCreateBuffer(vma, bci, aci, pBuf, pAlloc, allocInfo);
            if (rc != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer failed: " + rc);
            }
            long buffer = pBuf.get(0);
            long mapped = hostVisible ? allocInfo.pMappedData() : 0L;
            VkBufferDeviceAddressInfo bdai = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
            long addr = VK12.vkGetBufferDeviceAddress(vk, bdai);
            return new GpuBuffer(buffer, pAlloc.get(0), addr, mapped);
        }
    }

    private static long createAccelerationStructure(VkDevice vk, long vma, long size, int type, GpuBuffer[] outBacking) {
        GpuBuffer backing = createBuffer(vk, vma, size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
        outBacking[0] = backing;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(backing.buffer())
                    .offset(0)
                    .size(size)
                    .type(type);
            LongBuffer pAs = stack.mallocLong(1);
            int rc = vkCreateAccelerationStructureKHR(vk, ci, null, pAs);
            if (rc != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateAccelerationStructureKHR failed: " + rc);
            }
            return pAs.get(0);
        }
    }

    private static long asDeviceAddress(VkDevice vk, long as) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureDeviceAddressInfoKHR info =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(as);
            return vkGetAccelerationStructureDeviceAddressKHR(vk, info);
        }
    }

    private static void submitAndWait(VulkanDevice device, java.util.function.Consumer<VkCommandBuffer> record) {
        // Real synchronous submit (Blaze3D's encoder.execute() only defers to frame end).
        RtCommands.submitSync(device, record);
    }

    private static void build(VulkanDevice device) {
        VkDevice vk = device.vkDevice();
        long vma = rtVma(device);

        // --- triangle geometry (z = 0 plane, matches triangle.rgen ortho rays) ---
        float[] verts = {
                0.0f, 0.6f, 0.0f,
                -0.6f, -0.5f, 0.0f,
                0.6f, -0.5f, 0.0f,
        };
        int[] indices = {0, 1, 2};

        int geomInput = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        GpuBuffer vb = createBuffer(vk, vma, (long) verts.length * Float.BYTES, geomInput, true);
        GpuBuffer ib = createBuffer(vk, vma, (long) indices.length * Integer.BYTES, geomInput, true);
        FloatBuffer vbMap = MemoryUtil.memFloatBuffer(vb.mapped(), verts.length);
        vbMap.put(verts);
        IntBuffer ibMap = MemoryUtil.memIntBuffer(ib.mapped(), indices.length);
        ibMap.put(indices);

        long blas;
        long blasAddress;
        long blasSize;
        long tlasSize;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ---------------- BLAS ----------------
            VkAccelerationStructureGeometryKHR.Buffer blasGeom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            blasGeom.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            var tri = blasGeom.geometry().triangles();
            tri.sType$Default()
                    .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(3L * Float.BYTES)
                    .maxVertex(verts.length / 3 - 1)
                    .indexType(VK10.VK_INDEX_TYPE_UINT32);
            tri.vertexData().deviceAddress(vb.deviceAddress());
            tri.indexData().deviceAddress(ib.deviceAddress());

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer blasBuild =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            blasBuild.sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1)
                    .pGeometries(blasGeom);

            VkAccelerationStructureBuildSizesInfoKHR blasSizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    blasBuild.get(0), stack.ints(1), blasSizes);
            blasSize = blasSizes.accelerationStructureSize();

            GpuBuffer[] blasBacking = new GpuBuffer[1];
            blas = createAccelerationStructure(vk, vma, blasSize, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, blasBacking);
            GpuBuffer blasScratch = createBuffer(vk, vma, blasSizes.buildScratchSize(),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);

            blasBuild.get(0).dstAccelerationStructure(blas);
            blasBuild.get(0).scratchData().deviceAddress(blasScratch.deviceAddress());

            VkAccelerationStructureBuildRangeInfoKHR.Buffer blasRange =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            blasRange.get(0).primitiveCount(indices.length / 3).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer ppBlasRange = stack.mallocPointer(1).put(0, blasRange.address());

            submitAndWait(device, cmd -> vkCmdBuildAccelerationStructuresKHR(cmd, blasBuild, ppBlasRange));
            blasAddress = asDeviceAddress(vk, blas);

            // ---------------- TLAS ----------------
            GpuBuffer instances = createBuffer(vk, vma, VkAccelerationStructureInstanceKHR.SIZEOF, geomInput, true);
            VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.calloc(stack);
            FloatBuffer xform = stack.floats(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f);
            instance.transform().matrix(xform);
            instance.instanceCustomIndex(0)
                    .mask(0xFF)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(0x00000001) // VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR
                    .accelerationStructureReference(blasAddress);
            MemoryUtil.memCopy(instance.address(), instances.mapped(), VkAccelerationStructureInstanceKHR.SIZEOF);

            VkAccelerationStructureGeometryKHR.Buffer tlasGeom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            tlasGeom.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            tlasGeom.geometry().instances().sType$Default().arrayOfPointers(false);
            tlasGeom.geometry().instances().data().deviceAddress(instances.deviceAddress());

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasBuild =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            tlasBuild.sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1)
                    .pGeometries(tlasGeom);

            VkAccelerationStructureBuildSizesInfoKHR tlasSizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    tlasBuild.get(0), stack.ints(1), tlasSizes);
            tlasSize = tlasSizes.accelerationStructureSize();

            GpuBuffer[] tlasBacking = new GpuBuffer[1];
            long tlas = createAccelerationStructure(vk, vma, tlasSize, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, tlasBacking);
            GpuBuffer tlasScratch = createBuffer(vk, vma, tlasSizes.buildScratchSize(),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);

            tlasBuild.get(0).dstAccelerationStructure(tlas);
            tlasBuild.get(0).scratchData().deviceAddress(tlasScratch.deviceAddress());

            VkAccelerationStructureBuildRangeInfoKHR.Buffer tlasRange =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            tlasRange.get(0).primitiveCount(1).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer ppTlasRange = stack.mallocPointer(1).put(0, tlasRange.address());

            submitAndWait(device, cmd -> vkCmdBuildAccelerationStructuresKHR(cmd, tlasBuild, ppTlasRange));

            RtTriangleScene.tlas = tlas; // hand off to the dispatch step (P0 trace step 2)
        }

        UpscalerMod.LOGGER.info("RT smoke test OK — triangle BLAS {} bytes (addr 0x{}), TLAS {} bytes built & traced-ready",
                blasSize, Long.toHexString(blasAddress), tlasSize);

        RtTriangleTrace.run(device, vma, RtTriangleScene.tlas);
    }

    /** Minimal handoff for the upcoming dispatch step; holds the built TLAS handle. */
    public static final class RtTriangleScene {
        public static long tlas;

        private RtTriangleScene() {
        }
    }
}
