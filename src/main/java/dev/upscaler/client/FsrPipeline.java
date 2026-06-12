package dev.upscaler.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.ffx.FfxLibrary;
import dev.upscaler.ffx.FfxUpscaleContext;
import dev.upscaler.mixin.CommandEncoderAccessor;
import dev.upscaler.mixin.GpuDeviceAccessor;
import dev.upscaler.mixin.VulkanGpuTextureAccessor;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M5 plumbing: replaces the bilinear upscale with a real FSR 3.1 dispatch.
 *
 * <p>Current limitations (intentional, next milestones):
 * <ul>
 *   <li>Motion vectors are a zero-cleared texture — camera motion will ghost/smear
 *       until the M4 reprojection pass lands.</li>
 *   <li>No projection jitter yet (M3), so FSR adds little detail beyond its
 *       spatial/edge reconstruction.</li>
 * </ul>
 *
 * <p>Interop notes: Blaze3D keeps all textures in VK_IMAGE_LAYOUT_GENERAL
 * permanently, and FFX's COMMON/UNORDERED_ACCESS states both map to GENERAL in
 * its VK backend, so no layout juggling is needed. FSR's output must be
 * storage-capable, which Blaze3D textures never are, so the output is a raw VMA
 * image copied into the main target color afterwards. The dispatch records into
 * a transient command buffer obtained from (and re-queued into) vanilla's
 * VulkanCommandEncoder, bracketed by Mojang's own coarse memory barriers.
 */
public final class FsrPipeline {
	public static final FsrPipeline INSTANCE = new FsrPipeline();
	private static final String DLL_NAME = "amd_fidelityfx_vk.dll";

	private final boolean enabledByProperty = !"false".equalsIgnoreCase(System.getProperty("upscaler.fsr", "true"));
	private boolean failed;

	private FfxLibrary lib;
	private FfxUpscaleContext context;
	private int contextRenderWidth = -1;
	private int contextRenderHeight = -1;
	private int contextUpscaleWidth = -1;
	private int contextUpscaleHeight = -1;

	// motion vector texture (Blaze3D, sampled input; zero for now)
	private GpuTexture mvTexture;
	private GpuTextureView mvTextureView;

	// FSR output (raw VMA image, storage-capable)
	private long outputImage;
	private long outputAllocation;
	private boolean outputNeedsLayoutInit;

	private long lastFrameNanos;
	private boolean resetNextDispatch;
	private boolean loggedActive;

	private FsrPipeline() {
	}

	/**
	 * Records the FSR upscale + copy to the main color texture.
	 *
	 * @return true if dispatched; false if the caller should fall back to the bilinear blit
	 */
	public boolean dispatch(GpuTexture lowResColor, GpuTexture lowResDepth, int renderWidth, int renderHeight,
	                        GpuTexture nativeColor, int upscaleWidth, int upscaleHeight) {
		if (!this.enabledByProperty || this.failed) {
			return false;
		}
		if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).upscaler$getBackend() instanceof VulkanDevice device)) {
			return false;
		}

		try {
			ensureLibrary();
			ensureResources(device, renderWidth, renderHeight, upscaleWidth, upscaleHeight);
			recordDispatch(device, lowResColor, lowResDepth, renderWidth, renderHeight, nativeColor, upscaleWidth, upscaleHeight);
			if (!this.loggedActive) {
				this.loggedActive = true;
				UpscalerMod.LOGGER.info("FSR upscaling active: {}x{} -> {}x{}", renderWidth, renderHeight, upscaleWidth, upscaleHeight);
			}
			return true;
		} catch (Throwable t) {
			this.failed = true;
			UpscalerMod.LOGGER.error("FSR dispatch failed — falling back to bilinear upscale", t);
			return false;
		}
	}

	private void ensureLibrary() {
		if (this.lib != null) {
			return;
		}
		Path dll = locateDll();
		if (dll == null) {
			throw new IllegalStateException(DLL_NAME + " not found (run dir natives/ or -Dupscaler.ffx.path)");
		}
		this.lib = FfxLibrary.load(dll);
	}

	private void ensureResources(VulkanDevice device, int renderWidth, int renderHeight, int upscaleWidth, int upscaleHeight) {
		if (renderWidth == this.contextRenderWidth && renderHeight == this.contextRenderHeight
				&& upscaleWidth == this.contextUpscaleWidth && upscaleHeight == this.contextUpscaleHeight
				&& this.context != null) {
			return;
		}

		destroyResources(device);

		VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
		long fpGetDeviceProcAddr;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			fpGetDeviceProcAddr = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
		}

		int flags = FfxUpscaleContext.FLAG_AUTO_EXPOSURE
				| FfxUpscaleContext.FLAG_DEPTH_INVERTED
				| FfxUpscaleContext.FLAG_NON_LINEAR_COLORSPACE
				| FfxUpscaleContext.FLAG_DEBUG_CHECKING;
		this.context = FfxUpscaleContext.create(this.lib,
				device.vkDevice().address(),
				device.vkDevice().getPhysicalDevice().address(),
				fpGetDeviceProcAddr,
				upscaleWidth, upscaleHeight,   // maxRenderSize: allow up to native
				upscaleWidth, upscaleHeight,
				flags, true);
		this.contextRenderWidth = renderWidth;
		this.contextRenderHeight = renderHeight;
		this.contextUpscaleWidth = upscaleWidth;
		this.contextUpscaleHeight = upscaleHeight;
		this.resetNextDispatch = true;

		// zero motion vectors (until M4)
		var blazeDevice = RenderSystem.getDevice();
		this.mvTexture = blazeDevice.createTexture(() -> "Upscaler MV", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				com.mojang.blaze3d.GpuFormat.RG16_FLOAT, renderWidth, renderHeight, 1, 1);
		this.mvTextureView = blazeDevice.createTextureView(this.mvTexture);
		blazeDevice.createCommandEncoder().clearColorTexture(this.mvTexture, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f));

		// storage-capable output image (raw VMA — Blaze3D never sets VK_IMAGE_USAGE_STORAGE_BIT)
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo imageCi = VkImageCreateInfo.calloc(stack).sType$Default()
					.imageType(VK10.VK_IMAGE_TYPE_2D)
					.format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
					.mipLevels(1)
					.arrayLayers(1)
					.samples(VK10.VK_SAMPLE_COUNT_1_BIT)
					.tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
					.usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
					.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
					.initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			imageCi.extent().set(upscaleWidth, upscaleHeight, 1);

			VmaAllocationCreateInfo allocCi = VmaAllocationCreateInfo.calloc(stack)
					.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			LongBuffer pImage = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			int result = Vma.vmaCreateImage(device.vma(), imageCi, allocCi, pImage, pAlloc, null);
			if (result != VK10.VK_SUCCESS) {
				throw new IllegalStateException("vmaCreateImage(FSR output) failed: " + result);
			}
			this.outputImage = pImage.get(0);
			this.outputAllocation = pAlloc.get(0);
			this.outputNeedsLayoutInit = true;
		}
	}

	private void recordDispatch(VulkanDevice device, GpuTexture lowResColor, GpuTexture lowResDepth,
	                            int renderWidth, int renderHeight,
	                            GpuTexture nativeColor, int upscaleWidth, int upscaleHeight) {
		var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).upscaler$getBackend();
		VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();

		long now = System.nanoTime();
		float frameTimeMs = this.lastFrameNanos == 0 ? 16.6f
				: Math.clamp((now - this.lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
		this.lastFrameNanos = now;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (this.outputNeedsLayoutInit) {
				this.outputNeedsLayoutInit = false;
				// UNDEFINED -> GENERAL, mirroring Blaze3D's own texture init barrier
				VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
				barrier.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
				barrier.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
				barrier.srcAccessMask(0);
				barrier.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
				barrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
				barrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
				barrier.image(this.outputImage);
				barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
				VK12.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
			}

			// order against MC's earlier passes that wrote color/depth this frame
			VulkanCommandEncoder.memoryBarrier(cmd, stack);

			this.context.dispatchUpscale(new FfxUpscaleContext.DispatchParams(
					cmd.address(),
					new FfxUpscaleContext.Resource(vkImage(lowResColor), FfxUpscaleContext.FORMAT_R8G8B8A8_UNORM,
							renderWidth, renderHeight, FfxUpscaleContext.USAGE_READ_ONLY, FfxUpscaleContext.STATE_COMMON),
					new FfxUpscaleContext.Resource(vkImage(lowResDepth), FfxUpscaleContext.FORMAT_R32_FLOAT,
							renderWidth, renderHeight, FfxUpscaleContext.USAGE_DEPTHTARGET, FfxUpscaleContext.STATE_COMMON),
					new FfxUpscaleContext.Resource(vkImage(this.mvTexture), FfxUpscaleContext.FORMAT_R16G16_FLOAT,
							renderWidth, renderHeight, FfxUpscaleContext.USAGE_READ_ONLY, FfxUpscaleContext.STATE_COMMON),
					new FfxUpscaleContext.Resource(this.outputImage, FfxUpscaleContext.FORMAT_R8G8B8A8_UNORM,
							upscaleWidth, upscaleHeight, FfxUpscaleContext.USAGE_UAV, FfxUpscaleContext.STATE_UNORDERED_ACCESS),
					0.0f, 0.0f,                       // jitter (M3)
					1.0f, 1.0f,                       // motion vector scale (zeros anyway until M4)
					renderWidth, renderHeight,
					upscaleWidth, upscaleHeight,
					frameTimeMs, this.resetNextDispatch,
					// reversed-Z (DEPTH_INVERTED): FFX expects cameraNear > cameraFar,
					// i.e. near carries the far-plane distance and vice versa
					1000.0f, 0.05f, (float) Math.toRadians(70.0),
					FfxUpscaleContext.DISPATCH_FLAG_NON_LINEAR_COLOR_SRGB));
			this.resetNextDispatch = false;

			// FSR's compute writes -> copy
			VulkanCommandEncoder.memoryBarrier(cmd, stack);

			// output (GENERAL) -> main color (GENERAL)
			VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.extent().set(upscaleWidth, upscaleHeight, 1);
			VK10.vkCmdCopyImage(cmd, this.outputImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
					vkImage(nativeColor), VK10.VK_IMAGE_LAYOUT_GENERAL, region);

			// copy writes -> whatever MC does next (GUI)
			VulkanCommandEncoder.memoryBarrier(cmd, stack);
		}

		int endResult = VK10.vkEndCommandBuffer(cmd);
		if (endResult != VK10.VK_SUCCESS) {
			throw new IllegalStateException("vkEndCommandBuffer(FSR) failed: " + endResult);
		}
		encoder.execute(cmd);
	}

	private static long vkImage(GpuTexture texture) {
		return ((VulkanGpuTextureAccessor) texture).upscaler$getVkImage();
	}

	private void destroyResources(VulkanDevice device) {
		boolean hadAny = this.context != null || this.outputImage != 0 || this.mvTexture != null;
		if (!hadAny) {
			return;
		}
		// resize/teardown is rare; a device-wait keeps destruction trivially safe
		VK10.vkDeviceWaitIdle(device.vkDevice());

		if (this.context != null) {
			this.context.close();
			this.context = null;
		}
		if (this.mvTextureView != null) {
			this.mvTextureView.close();
			this.mvTextureView = null;
		}
		if (this.mvTexture != null) {
			this.mvTexture.close();
			this.mvTexture = null;
		}
		if (this.outputImage != 0) {
			Vma.vmaDestroyImage(device.vma(), this.outputImage, this.outputAllocation);
			this.outputImage = 0;
			this.outputAllocation = 0;
		}
		this.contextRenderWidth = -1;
		this.contextRenderHeight = -1;
		this.contextUpscaleWidth = -1;
		this.contextUpscaleHeight = -1;
	}

	private static Path locateDll() {
		String override = System.getProperty("upscaler.ffx.path");
		if (override != null) {
			Path p = Path.of(override);
			return Files.isRegularFile(p) ? p : null;
		}
		Path runDir = FabricLoader.getInstance().getGameDir();
		Path[] candidates = {runDir.resolve("natives").resolve(DLL_NAME), runDir.resolve(DLL_NAME)};
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}
}
