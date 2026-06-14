package dev.upscaler.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtDeviceBringup;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Standalone fallback for the Vulkan device-negotiation hook: adds the device
 * extensions the FFX runtime needs to the extension list vanilla enables at
 * vkCreateDevice time.
 *
 * <p>When Sodium is present this defers entirely to Sodium's
 * {@code DeviceExtensionRegistry} (fed via {@link dev.upscaler.client.SodiumCompat}),
 * which owns device negotiation — this is the "Phase 0" layering. Both paths
 * dedupe and Sodium returns a Set, so even if both ran the result would be
 * correct; deferring just keeps a single owner.
 *
 * <p>FFX resolves vkGetImageMemoryRequirements2KHR etc. through
 * vkGetDeviceProcAddr using the KHR-suffixed extension names; per Vulkan spec
 * that returns NULL unless the corresponding extension was enabled — even
 * though the functionality is core since 1.1 — and FFX then calls the NULL
 * pointer (verified: crash at amd_fidelityfx_vk.dll+0x1e5b0 building
 * VkMemoryRequirements2). Enabling the alias extensions is a behavioral no-op
 * for the rest of the engine.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
	private static final List<String> UPSCALER_WANTED_EXTENSIONS = List.of(
			// FFX (FSR)
			"VK_KHR_get_memory_requirements2",
			"VK_KHR_dedicated_allocation",
			// NGX (DLSS) — NVIDIA-only; skipped on other vendors. (The NGX instance
			// extension VK_KHR_get_physical_device_properties2 needs an instance hook;
			// without Sodium, DLSS relies on it being core/enabled at instance level.)
			"VK_NVX_binary_import",
			"VK_NVX_image_view_handle",
			"VK_EXT_buffer_device_address",
			"VK_KHR_push_descriptor");

	private static final boolean SODIUM_OWNS_NEGOTIATION =
			FabricLoader.getInstance().isModLoaded("sodium");

	@ModifyArgs(
			method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
	private void upscaler$addDeviceExtensions(Args args) {
		VulkanPhysicalDevice physicalDevice = args.get(1);

		if (!SODIUM_OWNS_NEGOTIATION) {
			// Standalone: add the upscaler + RT extension names to arg0 ourselves.
			Collection<String> requested = args.get(0);
			var augmented = new ArrayList<>(requested);
			for (String extension : UPSCALER_WANTED_EXTENSIONS) {
				if (augmented.contains(extension)) {
					continue;
				}
				if (physicalDevice.hasDeviceExtension(extension)) {
					augmented.add(extension);
					UpscalerMod.LOGGER.info("Enabling device extension {} for the upscaler runtime", extension);
				} else {
					UpscalerMod.LOGGER.warn("Device extension {} not supported by {} — upscaling will be unavailable",
							extension, physicalDevice.deviceName());
				}
			}
			RtDeviceBringup.addExtensions(augmented, physicalDevice);
			args.set(0, augmented);
		}

		// P0 — RT feature structs go in arg2, which Sodium never touches. Names are
		// guaranteed in arg0 when standalone, or when Sodium accepted our registration.
		if (!SODIUM_OWNS_NEGOTIATION || RtDeviceBringup.sodiumExtensionsRegistered) {
			RtDeviceBringup.addFeatures(args, physicalDevice);
		}
	}

	/**
	 * P0 verification — once the RT-augmented device is created, confirm the RT entry
	 * points loaded and log the RT/AS limits. {@code device} is the local assigned just
	 * before {@code createVma} runs.
	 */
	@Inject(
			method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createVma(Lorg/lwjgl/vulkan/VkDevice;)J"))
	private void upscaler$probeRayTracing(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions,
			Runnable criticalShaderLoader, CallbackInfoReturnable<GpuDevice> cir, @Local VkDevice device) {
		RtDeviceBringup.probe(device);
	}
}
