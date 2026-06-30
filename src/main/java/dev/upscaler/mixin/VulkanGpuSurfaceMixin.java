package dev.upscaler.mixin;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtFramePresenter;
import dev.upscaler.rt.RtHdr;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HDR Phase 0 capability logging + Phase 2 step B scRGB swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When {@code upscaler.rt.hdr.scrgbSwapchain} is on and the surface advertises scRGB
 * (R16G16B16A16_SFLOAT / EXTENDED_SRGB_LINEAR), we steer Minecraft's swapchain to it: vanilla
 * {@code pickSwapchainSurfaceFormat} only accepts SDR (color space 0, format 37/44) and {@code configure}
 * hardcodes {@code imageColorSpace(0)}. We override the picked format and the color-space arg. Falls back to
 * the vanilla SDR path when the flag is off or scRGB is unavailable. NOTE: this only creates the swapchain
 * in scRGB — the presented content is still Minecraft's SDR main target blitted in, so the image looks wrong
 * until the HDR compositor (step C) writes correct scRGB; default off.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97;
	private static final int VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT = 1000104002;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	@Final
	private org.lwjgl.vulkan.VkQueue presentQueue;

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

	@Unique
	private int upscaler$colorSpace = 0;

	@Inject(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void upscaler$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			RtHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/**
	 * Pick an scRGB surface format when requested + available, before vanilla's SDR-only selection runs.
	 * Sets {@link #upscaler$colorSpace} so {@code configure} can pass the matching color space.
	 */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void upscaler$pickScrgbFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		if (!UpscalerConfig.Rt.Hdr.SCRGB_SWAPCHAIN.value()) {
			return;
		}
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.format() == VK_FORMAT_R16G16B16A16_SFLOAT && f.colorSpace() == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT) {
				this.upscaler$colorSpace = VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT;
				UpscalerMod.LOGGER.info("HDR: selecting scRGB swapchain (format=R16G16B16A16_SFLOAT, colorSpace=EXTENDED_SRGB_LINEAR)");
				cir.setReturnValue(f);
				return;
			}
		}
		UpscalerMod.LOGGER.warn("HDR: scRGB swapchain requested but EXTENDED_SRGB_LINEAR not advertised by the surface; using SDR (enable Windows HDR / check VK_EXT_swapchain_colorspace)");
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the scRGB color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int upscaler$overrideColorSpace(int original) {
		return this.upscaler$colorSpace != 0 ? this.upscaler$colorSpace : original;
	}

	/**
	 * Step C — world-only HDR present. When the RT renderer has a fresh scRGB HDR image and the swapchain is
	 * scRGB, blit that image straight into the swapchain instead of Minecraft's SDR main target. Replaces the
	 * vanilla blit entirely (the SDR target + its UI are bypassed for now; UI compositing is a later step).
	 */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void upscaler$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0) {
			return;
		}
		RtComposite rt = RtComposite.INSTANCE;
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		long acquireSem = this.acquireSemaphores[this.currentAcquireSemaphore];
		long presentSem = this.presentSemaphores[this.currentImageIndex];
		if (rt.isHdrPresentActive()) {
			rt.presentHdr((VulkanCommandEncoder) commandEncoder, swapchainImage, this.swapchainWidth, this.swapchainHeight,
					acquireSem, presentSem);
			ci.cancel();
			return;
		}
		// Non-RT frame (menu, title panorama, loading screen) on an scRGB swapchain: vanilla's raw SDR blit
		// would wash out (SDR bytes reinterpreted as scRGB-linear). Convert sRGB -> scRGB at paper white
		// instead. Falls through to vanilla SDR if conversion resources aren't ready or the source view is
		// not a Vulkan view.
		if (rt.isScrgbSdrPresentActive()) {
			long sdrView = upscaler$vkImageView(textureView);
			if (sdrView != 0L && rt.presentSdrToScrgb((VulkanCommandEncoder) commandEncoder, swapchainImage,
					this.swapchainWidth, this.swapchainHeight, sdrView, acquireSem, presentSem)) {
				ci.cancel();
			}
		}
	}

	@Unique
	private static long upscaler$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.blaze3d.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/**
	 * DLSS Frame Generation (slice 2): after Minecraft blits the real frame into its acquired swapchain image
	 * (but before {@code present()} shows it), present the generated frame(s) into additional swapchain images
	 * via {@link RtFramePresenter}, so the display order is generated-then-real. Runs only on the normal
	 * present path — the HDR/scRGB present hooks cancel {@code blitFromTexture} at HEAD, so this TAIL is
	 * skipped there (HDR+FG deferred). Iteration 1 duplicates the final frame (no DLSSG eval yet).
	 */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void upscaler$presentGeneratedFrames(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long srcImage = textureView.texture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture t ? t.vkImage() : 0L;
		if (srcImage == 0L) {
			return;
		}
		int generatedCount = dev.upscaler.rt.pipeline.RtDlssFg.INSTANCE.effectiveMultiFrameCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames((VulkanCommandEncoder) commandEncoder, this.device,
				this.swapchain, this.swapchainImages, this.presentSemaphores,
				this.swapchainWidth, this.swapchainHeight,
				srcImage, textureView.getWidth(0), textureView.getHeight(0), generatedCount);
	}

	// Present the FG-generated frame(s) acquired/recorded at blitFromTexture TAIL — at present() HEAD, after
	// Minecraft.java's encoder.submit() has flushed (so our present semaphores are signaled) and before MC
	// presents the real frame, giving display order generated-then-real.
	@Inject(method = "present", at = @At("HEAD"))
	private void upscaler$flushGeneratedPresents(CallbackInfo ci) {
		RtFramePresenter.INSTANCE.flushPendingPresents(this.swapchain, this.presentQueue);
	}
}
