package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.LongBuffer;
import java.nio.IntBuffer;

/**
 * HDR Phase 0 capability logging + PQ swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When {@code caustica.rt.hdr.pqSwapchain} is on and the surface advertises HDR10_ST2084 (ST.2084/PQ,
 * paired with whatever pixel format the surface offers for it — commonly a 10-bit UNORM, but this is
 * discovered by scanning the surface's advertised formats rather than assumed), we steer Minecraft's
 * swapchain to it: vanilla {@code pickSwapchainSurfaceFormat} only accepts SDR (color space 0, format 37/44)
 * and {@code configure} hardcodes {@code imageColorSpace(0)}. We override the picked format and the
 * color-space arg. Falls back to the vanilla SDR path when the flag is off or PQ is unavailable; default off.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	private long swapchain;

	@Shadow
	@Final
	private int swapchainImageFormat;

	@Shadow
	@Final
	private LongList swapchainImages;

	@Shadow
	private int currentImageIndex;

	@Shadow
	private int swapchainWidth;

	@Shadow
	private int swapchainHeight;

	@Shadow
	@Final
	private long[] acquireSemaphores;

	@Shadow
	private int currentAcquireSemaphore;

	@Shadow
	private long[] presentSemaphores;

	@Redirect(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V",
			at = @At(value = "INVOKE",
				target = "Lorg/lwjgl/glfw/GLFWVulkan;glfwCreateWindowSurface(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int caustica$createSurfaceThroughStreamline(org.lwjgl.vulkan.VkInstance instance, long window,
			VkAllocationCallbacks allocator, LongBuffer surfaceOut) {
		return StreamlineRuntime.vkCreateWin32Surface(instance, window, allocator, surfaceOut);
	}

	@Redirect(method = "close()V",
			at = @At(value = "INVOKE",
				target = "Lorg/lwjgl/vulkan/KHRSurface;vkDestroySurfaceKHR(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void caustica$destroySurfaceThroughStreamline(org.lwjgl.vulkan.VkInstance instance, long surface,
			VkAllocationCallbacks allocator) {
		StreamlineRuntime.vkDestroySurface(instance, surface, allocator);
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void caustica$streamlineSurfaceClosing(CallbackInfo ci) {
		StreamlineSwapchainCoordinator.INSTANCE.closing();
		RtHdr.clearSwapchainSelection();
	}

	@Redirect(method = "destroySwapchain()V",
			at = @At(value = "INVOKE",
				target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void caustica$destroySwapchainThroughStreamline(VkDevice device, long swapchain,
			VkAllocationCallbacks allocator) {
		StreamlineRuntime.vkDestroySwapchain(device, swapchain, allocator);
	}

	@Redirect(method = "configure(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V",
			at = @At(value = "INVOKE",
				target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"))
	private int caustica$getSwapchainImagesThroughStreamline(VkDevice device, long swapchain, IntBuffer count,
			LongBuffer images) {
		int result = StreamlineRuntime.vkGetSwapchainImages(device, swapchain, count, images);
		if (result != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
			StreamlineSwapchainCoordinator.INSTANCE.configureFailed();
		}
		return result;
	}

	@Redirect(method = "acquireNextTexture()V",
			at = @At(value = "INVOKE",
				target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int caustica$acquireNextImageThroughStreamline(VkDevice device, long swapchain, long timeout,
			long semaphore, long fence, IntBuffer imageIndex) {
		return StreamlineRuntime.vkAcquireNextImage(device, swapchain, timeout, semaphore, fence, imageIndex);
	}

	@Unique
	private int caustica$colorSpace = 0;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void caustica$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			RtHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/**
	 * Pick a PQ (HDR10_ST2084) surface format when requested + available, before vanilla's SDR-only selection
	 * runs. Accepts the implemented 10-bit UNORM formats paired with that color space. Sets
	 * {@link #caustica$colorSpace} so {@code configure} can pass the matching color space.
	 */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void caustica$pickPqFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		this.caustica$colorSpace = RtHdr.resetColorSpaceForSurfaceScan(this.caustica$colorSpace);
		RtHdr.clearSwapchainSelection();
		if (!CausticaConfig.Rt.Hdr.enabled()) {
			return;
		}
		boolean unsupportedPqAdvertised = false;
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
				if (!RtHdr.isSupportedPqFormat(f.format())) {
					unsupportedPqAdvertised = true;
					continue;
				}
				this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
				RtHdr.recordSwapchainSelection(true, f.format(), f.colorSpace());
				CausticaMod.LOGGER.info("HDR: selecting PQ swapchain (format={}, colorSpace=HDR10_ST2084)", f.format());
				cir.setReturnValue(f);
				return;
			}
		}
		CausticaMod.LOGGER.warn(unsupportedPqAdvertised
				? "HDR: HDR10_ST2084 was advertised only with unsupported non-10-bit formats; using SDR"
				: "HDR: PQ swapchain requested but HDR10_ST2084 was not advertised by the surface; "
						+ "using SDR (enable OS/display HDR; on Linux use a native Wayland session with HDR enabled in the compositor)");
	}

	/** Record the format vanilla actually selected on every SDR/fallback return path. */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("RETURN"))
	private void caustica$recordSelectedSurfaceFormat(VkSurfaceFormatKHR.Buffer formats,
			CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		VkSurfaceFormatKHR selected = cir.getReturnValue();
		if (selected == null) {
			RtHdr.clearSwapchainSelection();
			return;
		}
		this.caustica$colorSpace = selected.colorSpace();
		RtHdr.recordSwapchainSelection(CausticaConfig.Rt.Hdr.enabled(),
				selected.format(), selected.colorSpace());
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the PQ color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int caustica$overrideColorSpace(int original) {
		return this.caustica$colorSpace != 0 ? this.caustica$colorSpace : original;
	}

	/** Streamline owns the swapchain proxy; Minecraft retains the configure transaction and object lifetime. */
	@Redirect(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int caustica$createSwapchainThroughStreamline(VkDevice device, VkSwapchainCreateInfoKHR pCreateInfo,
			VkAllocationCallbacks pAllocator, LongBuffer pSwapchain) {
		int result = StreamlineRuntime.vkCreateSwapchain(device, pCreateInfo, pAllocator, pSwapchain);
		if (result != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
			StreamlineSwapchainCoordinator.INSTANCE.configureFailed();
		}
		return result;
	}

	@Inject(method = "configure", at = @At("HEAD"))
	private void caustica$streamlineConfigureStarting(GpuSurface.Configuration config, CallbackInfo ci) {
		StreamlineSwapchainCoordinator.INSTANCE.configureStarting();
	}

	@Inject(method = "configure", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanGpuSurface;destroySwapchain()V",
			shift = At.Shift.AFTER))
	private void caustica$prepareStreamlineSwapchain(GpuSurface.Configuration config, CallbackInfo ci) {
		StreamlineSwapchainCoordinator.INSTANCE.prepareReplacement(config);
	}

	/** Publish the successfully created swapchain generation to the Streamline controller. */
	@Inject(method = "configure", at = @At("TAIL"))
	private void caustica$streamlineSwapchainConfigured(GpuSurface.Configuration config, CallbackInfo ci) {
		StreamlineSwapchainCoordinator.INSTANCE.configured(config, this.swapchainImageFormat,
				this.swapchainImages.size());
	}

	/** Attach Streamline PCL markers to the same token used by the one real proxy present. */
	@Redirect(method = "present",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int caustica$presentThroughStreamline(VkQueue queue, VkPresentInfoKHR presentInfo) {
		RtDlssFg.INSTANCE.beforePresent();
		int result = StreamlineRuntime.vkQueuePresent(queue, presentInfo);
		RtDlssFg.INSTANCE.afterPresent();
		return result;
	}

	/** Present Caustica's HDR10 image, then tag its final depth, motion, hudless color, and UI inputs. */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void caustica$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0) {
			return;
		}
		RtComposite rt = RtComposite.INSTANCE;
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		long acquireSem = this.acquireSemaphores[this.currentAcquireSemaphore];
		long presentSem = this.presentSemaphores[this.currentImageIndex];
		if (rt.isHdrPresentActive()) {
			VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
			rt.presentHdr(enc, swapchainImage, this.swapchainWidth, this.swapchainHeight,
					this.swapchainImageFormat, acquireSem, presentSem);
			rt.submitStreamlineFrame(enc, this.swapchainWidth, this.swapchainHeight,
					this.swapchainImageFormat, true);
			ci.cancel();
			return;
		}
		// Non-RT frame (menu, title panorama, loading screen) on a PQ swapchain: vanilla's raw SDR blit would
		// misdisplay (SDR bytes reinterpreted as PQ codes). Convert sRGB -> PQ at paper white instead. Falls
		// through to vanilla SDR if conversion resources aren't ready or the source view is not a Vulkan view.
		if (rt.isPqSdrPresentActive()) {
			long sdrView = caustica$vkImageView(textureView);
			if (sdrView != 0L && rt.presentSdrToPq((VulkanCommandEncoder) commandEncoder, swapchainImage,
					this.swapchainWidth, this.swapchainHeight, sdrView, acquireSem, presentSem)) {
				ci.cancel();
			}
		}
	}

	@Unique
	private static long caustica$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/** Tag the completed SDR frame immediately before Minecraft's normal present. */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void caustica$submitStreamlineFrame(CommandEncoderBackend commandEncoder, GpuTextureView textureView,
			CallbackInfo ci) {
		if (this.currentImageIndex < 0) {
			return;
		}
		RtComposite.INSTANCE.submitStreamlineFrame((VulkanCommandEncoder) commandEncoder,
				this.swapchainWidth, this.swapchainHeight, this.swapchainImageFormat, false);
	}
}
