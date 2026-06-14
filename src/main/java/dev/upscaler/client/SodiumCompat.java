package dev.upscaler.client;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtDeviceBringup;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public final class SodiumCompat {
	private static final List<String> FFX_DEVICE_EXTENSIONS = List.of(
			"VK_KHR_get_memory_requirements2",
			"VK_KHR_dedicated_allocation");

	// Extensions NGX/DLSS requires at device/instance creation (queried via the
	// NGX shim; NVIDIA-only, requested optionally so non-NVIDIA devices degrade).
	private static final List<String> NGX_DEVICE_EXTENSIONS = List.of(
			"VK_NVX_binary_import",
			"VK_NVX_image_view_handle",
			"VK_EXT_buffer_device_address",
			"VK_KHR_push_descriptor");
	private static final List<String> NGX_INSTANCE_EXTENSIONS = List.of(
			"VK_KHR_get_physical_device_properties2");

	private static final boolean SODIUM_LOADED = FabricLoader.getInstance().isModLoaded("sodium");
	private static boolean extensionRegistrationAttempted;
	private static boolean loggedMissingApi;
	private static boolean loggedRawAccessFailure;
	private static Method setCullingViewProjectionOverride;
	private static Object rawVulkanAccess;
	private static Method vkImage;
	private static Method vkImageView;

	private SodiumCompat() {
	}

	public static void registerDeviceExtensions() {
		if (!SODIUM_LOADED || extensionRegistrationAttempted) {
			return;
		}

		extensionRegistrationAttempted = true;
		try {
			Class<?> apiClass = Class.forName("net.caffeinemc.mods.sodium.api.gpu.SodiumGpuApi");
			Object registry = apiClass.getMethod("extensions").invoke(null);
			Method requestDeviceExtension = registry.getClass().getMethod("requestDeviceExtension", String.class, boolean.class);
			Method requestInstanceExtension = registry.getClass().getMethod("requestInstanceExtension", String.class);

			// FFX (required when present) — needed by the FSR runtime.
			for (String extension : FFX_DEVICE_EXTENSIONS) {
				requestDeviceExtension.invoke(registry, extension, true);
			}
			// NGX/DLSS (optional — NVIDIA-only; absent on other vendors).
			for (String extension : NGX_DEVICE_EXTENSIONS) {
				requestDeviceExtension.invoke(registry, extension, false);
			}
			for (String extension : NGX_INSTANCE_EXTENSIONS) {
				requestInstanceExtension.invoke(registry, extension);
			}

			// Ray tracing (P0) — optional; the feature structs are added separately in
			// VulkanBackendMixin (Sodium's registry handles names only).
			if (RtDeviceBringup.ENABLED_BY_PROPERTY) {
				for (String extension : RtDeviceBringup.RT_EXTENSIONS) {
					requestDeviceExtension.invoke(registry, extension, false);
				}
				RtDeviceBringup.sodiumExtensionsRegistered = true;
				UpscalerMod.LOGGER.info("Registered ray-tracing device extensions through Sodium");
			}

			UpscalerMod.LOGGER.info("Registered FFX + NGX Vulkan extensions through Sodium");
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			UpscalerMod.LOGGER.warn("Failed to register Vulkan extensions through Sodium", e);
		}
	}

	public static void setCullingViewProjectionOverride(Matrix4fc projection, Matrix4fc viewRotation) {
		if (!SODIUM_LOADED) {
			return;
		}

		try {
			if (setCullingViewProjectionOverride == null) {
				Class<?> accessClass = Class.forName("net.caffeinemc.mods.sodium.api.render.FrameContextAccess");
				setCullingViewProjectionOverride = accessClass.getMethod("setCullingViewProjectionOverride", Matrix4fc.class);
			}

			Matrix4f viewProjection = new Matrix4f(projection).mul(viewRotation);
			setCullingViewProjectionOverride.invoke(null, viewProjection);
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			UpscalerMod.LOGGER.warn("Failed to install Sodium culling projection override", e);
		}
	}

	public static Long vkImage(GpuTexture texture) {
		if (!SODIUM_LOADED) {
			return null;
		}

		try {
			ensureRawVulkanAccess();
			if (vkImage == null) {
				vkImage = rawVulkanAccess.getClass().getMethod("vkImage", GpuTexture.class);
			}

			return (Long) vkImage.invoke(rawVulkanAccess, texture);
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			if (!loggedRawAccessFailure) {
				loggedRawAccessFailure = true;
				UpscalerMod.LOGGER.warn("Sodium raw Vulkan texture access failed; falling back to local accessor", e);
			}
		}

		return null;
	}

	public static Long vkImageView(GpuTextureView textureView) {
		if (!SODIUM_LOADED) {
			return null;
		}

		try {
			ensureRawVulkanAccess();
			if (vkImageView == null) {
				vkImageView = rawVulkanAccess.getClass().getMethod("vkImageView", GpuTextureView.class);
			}

			return (Long) vkImageView.invoke(rawVulkanAccess, textureView);
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			logMissingApi();
		} catch (IllegalAccessException | InvocationTargetException e) {
			if (!loggedRawAccessFailure) {
				loggedRawAccessFailure = true;
				UpscalerMod.LOGGER.warn("Sodium raw Vulkan texture-view access failed; falling back to local access", e);
			}
		}

		return null;
	}

	private static void ensureRawVulkanAccess() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		if (rawVulkanAccess == null) {
			Class<?> apiClass = Class.forName("net.caffeinemc.mods.sodium.api.gpu.SodiumGpuApi");
			rawVulkanAccess = apiClass.getMethod("rawVulkan").invoke(null);
		}
	}

	private static void logMissingApi() {
		if (!loggedMissingApi) {
			loggedMissingApi = true;
			UpscalerMod.LOGGER.info("Sodium is loaded without the upscaler API; using standalone upscaler hooks");
		}
	}
}
