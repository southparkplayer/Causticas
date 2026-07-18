package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import java.util.Locale;

/** One global reconstruction policy shared by every vendor and operating system. */
public final class RtReconstruction {
    public enum Backend { OFF, DLSS_RR, NRD }

    private RtReconstruction() {
    }

    private static volatile long cachedPhysicalDevice;
    private static volatile int cachedVendor = -1;

    public static Backend backend() {
        String configured = CausticaConfig.Rt.Reconstruction.BACKEND.get();
        return switch (configured) {
            case "off" -> Backend.OFF;
            case "dlss-rr" -> CausticaConfig.Rt.DlssRr.ENABLED.value() ? Backend.DLSS_RR : Backend.OFF;
            case "nrd" -> Backend.NRD;
            default -> automaticBackend();
        };
    }

    private static Backend automaticBackend() {
        if (!(RenderSystem.getDevice() instanceof GpuDeviceAccessor accessor)
                || !(accessor.caustica$getBackend() instanceof VulkanDevice device)) {
            return isLinux() ? Backend.NRD : Backend.OFF;
        }
        int vendor = physicalVendor(device);
        if (isLinux() || vendor == 0x1002 || vendor == 0x8086) {
            return Backend.NRD;
        }
        // Backend identity must not change halfway through a frame if a native evaluation latches a
        // failure. DLSS operational state is checked separately; explicit/next-session NRD remains
        // available without dereferencing resources created for a different backend.
        return vendor == 0x10DE && CausticaConfig.Rt.DlssRr.ENABLED.value()
                ? Backend.DLSS_RR : Backend.NRD;
    }

    private static int physicalVendor(VulkanDevice device) {
        long physicalDevice = device.vkDevice().getPhysicalDevice().address();
        if (cachedPhysicalDevice == physicalDevice && cachedVendor >= 0) return cachedVendor;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkPhysicalDeviceProperties properties =
                    org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(
                    device.vkDevice().getPhysicalDevice(), properties);
            int vendor = properties.vendorID();
            cachedPhysicalDevice = physicalDevice;
            cachedVendor = vendor;
            return vendor;
        }
    }

    public static boolean enabled() {
        return switch (backend()) {
            case DLSS_RR -> RtDlssRr.INSTANCE.isOperational();
            case NRD -> RtNrd.INSTANCE.isOperational();
            case OFF -> false;
        };
    }

    public static boolean usesDlss() {
        return backend() == Backend.DLSS_RR;
    }

    public static boolean usesNrd() {
        return backend() == Backend.NRD;
    }

    public static int resourceIdentity() {
        int identity = backend().ordinal();
        if (usesDlss()) {
            identity = 31 * identity + RtDlssRr.quality();
            return 31 * identity + Boolean.hashCode(
                    CausticaConfig.Rt.DlssRr.PARTICLE_TEMPORAL_HISTORY.value());
        }
        if (usesNrd()) {
            identity = 31 * identity + CausticaConfig.Rt.Nrd.DENOISER.get().hashCode();
            identity = 31 * identity + Boolean.hashCode(CausticaConfig.Rt.Nrd.SPHERICAL_HARMONICS.value());
            identity = 31 * identity + CausticaConfig.Rt.Nrd.UPSCALE_MODE.get().hashCode();
            identity = 31 * identity + Float.floatToIntBits(CausticaConfig.Rt.Nrd.CUSTOM_RENDER_SCALE.value());
        }
        return identity;
    }

    public static int[] queryRenderSize(int displayWidth, int displayHeight) {
        if (usesDlss()) return RtDlssRr.INSTANCE.queryOptimalRenderSize(displayWidth, displayHeight);
        if (usesNrd()) {
            float scale = nrdRenderScale();
            return new int[] {Math.max(1, Math.round(displayWidth * scale)),
                    Math.max(1, Math.round(displayHeight * scale))};
        }
        return null;
    }

    public static float nrdRenderScale() {
        return switch (CausticaConfig.Rt.Nrd.UPSCALE_MODE.get()) {
            case "native" -> 1.0f;
            case "balanced" -> 0.59f;
            case "performance" -> 0.5f;
            case "ultra-performance" -> 1.0f / 3.0f;
            case "custom" -> CausticaConfig.Rt.Nrd.CUSTOM_RENDER_SCALE.value();
            default -> 0.67f;
        };
    }

    public static void requestHistoryReset() {
        RtDlssRr.INSTANCE.requestHistoryReset();
        RtNrd.INSTANCE.requestHistoryReset();
    }

    public static void resetFailureLatches() {
        RtDlssRr.INSTANCE.resetFailureLatch();
        RtNrd.INSTANCE.resetFailureLatch();
    }

    public static void destroy() {
        RtDlssRr.INSTANCE.destroy();
        RtNrd.INSTANCE.destroy();
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
