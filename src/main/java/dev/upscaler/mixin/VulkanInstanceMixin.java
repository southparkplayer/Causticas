package dev.upscaler.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import dev.upscaler.UpscalerMod;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HDR Phase 3 groundwork: enable {@code VK_EXT_swapchain_colorspace} at instance creation when the platform
 * supports it. Minecraft's stock instance does not request it, so on a stock build
 * {@code vkGetPhysicalDeviceSurfaceFormatsKHR} only ever reports {@code SRGB_NONLINEAR} — no extended/HDR
 * color spaces are visible and the swapchain cannot be created in HDR10 (PQ). Adding this instance
 * extension is the prerequisite that makes HDR color spaces queryable (surfaced by the Phase 0 capability
 * log) and selectable by a later swapchain-ownership change. It is a no-op for present rendering: the
 * extension only adds color-space enum values; Minecraft still creates its swapchain with color space 0.
 *
 * <p>Gated on availability — requesting an unsupported instance extension would fail {@code vkCreateInstance}
 * and crash startup.
 */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
	private static final String SWAPCHAIN_COLORSPACE = "VK_EXT_swapchain_colorspace";

	@Shadow
	@Final
	private Set<String> enabledExtensions;

	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Set;size()I"))
	private void upscaler$addColorSpaceExtension(int debugVerbosity, boolean wantsDebugLabels, boolean validation,
			CallbackInfo ci, @Local(ordinal = 0) Set<String> availableExtensions) {
		if (availableExtensions.contains(SWAPCHAIN_COLORSPACE)) {
			if (this.enabledExtensions.add(SWAPCHAIN_COLORSPACE)) {
				UpscalerMod.LOGGER.info("Enabling instance extension {} for HDR swapchain color spaces", SWAPCHAIN_COLORSPACE);
			}
		} else {
			UpscalerMod.LOGGER.warn("Instance extension {} unavailable; HDR color spaces will not be queryable on this platform", SWAPCHAIN_COLORSPACE);
		}
	}
}
