package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtResolutionScale;
import java.util.Locale;

/** One global reconstruction policy shared by every vendor and operating system. */
public final class RtReconstruction {
    private static final double LOG_2 = Math.log(2.0);
    private static final double DLSS_NATIVE_MIP_BIAS = 0.0;
    private static final double DLSS_MIP_BIAS_EPSILON = 0.0;

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
        return resourceIdentity(CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.value());
    }

    public static int resourceIdentity(int appliedInputScalePercent) {
        int identity = backend().ordinal();
        if (usesDlss()) {
            identity = 31 * identity + RtDlssRr.quality();
            identity = 31 * identity + RtDlssRr.renderPreset();
            identity = 31 * identity + appliedInputScalePercent;
            identity = 31 * identity + Boolean.hashCode(
                    CausticaConfig.Rt.DlssRr.PARTICLE_TEMPORAL_HISTORY.value());
            return identity;
        }
        if (usesNrd()) {
            identity = 31 * identity + CausticaConfig.Rt.Nrd.DENOISER.get().hashCode();
            identity = 31 * identity + Boolean.hashCode(CausticaConfig.Rt.Nrd.SPHERICAL_HARMONICS.value());
            identity = 31 * identity + CausticaConfig.Rt.Nrd.UPSCALE_MODE.get().hashCode();
            identity = 31 * identity + Float.floatToIntBits(CausticaConfig.Rt.Nrd.CUSTOM_RENDER_SCALE.value());
            identity = 31 * identity + CausticaConfig.Rt.Nrd.UPSCALE_FILTER.get().hashCode();
        }
        return identity;
    }

    public static int[] queryRenderSize(int windowWidth, int windowHeight,
            int outputWidth, int outputHeight) {
        return queryRenderSize(windowWidth, windowHeight, outputWidth, outputHeight,
                CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.value());
    }

    public static int[] queryRenderSize(int windowWidth, int windowHeight,
            int outputWidth, int outputHeight, int inputRatioTenths) {
        if (usesDlss()) {
            // Input scale is relative to output resolution, not the raw window, so quality and output scale
            // remain independently tunable.
            int requestedWidth = RtResolutionScale.inputDimension(outputWidth, inputRatioTenths);
            int requestedHeight = RtResolutionScale.inputDimension(outputHeight, inputRatioTenths);
            return RtDlssRr.INSTANCE.querySupportedRenderSize(
                    outputWidth, outputHeight, requestedWidth, requestedHeight);
        }
        if (usesNrd()) {
            float scale = nrdRenderScale();
            return new int[] {Math.max(1, Math.round(outputWidth * scale)),
                    Math.max(1, Math.round(outputHeight * scale))};
        }
        return null;
    }

    public static DlssdResolutionPlan queryDlssdPlan(int windowWidth, int windowHeight,
            int outputWidth, int outputHeight, int inputRatioTenths) {
        if (!usesDlss()) return null;
        // Input scale is relative to output resolution, not the raw window, so quality and output scale
        // remain independently tunable.
        int requestedWidth = RtResolutionScale.inputDimension(outputWidth, inputRatioTenths);
        int requestedHeight = RtResolutionScale.inputDimension(outputHeight, inputRatioTenths);
        return RtDlssRr.INSTANCE.queryResolutionPlan(outputWidth, outputHeight,
                requestedWidth, requestedHeight, RtDlssRr.quality(), RtDlssRr.renderPreset());
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

    /** NVIDIA's display-resolution texture LOD bias for DLSS reconstruction. */
    public static float dlssMipBias(int renderWidth, int outputWidth) {
        return dlssMipBias(renderWidth, outputWidth, true);
    }

    /**
     * Display-resolution texture LOD bias. Subpixel detail adds NVIDIA's optional one-mip detail boost;
     * disabling it retains only the resolution-compensating bias.
     */
    public static float dlssMipBias(int renderWidth, int outputWidth, boolean subpixelDetail) {
        if (renderWidth <= 0 || outputWidth <= 0) {
            throw new IllegalArgumentException("DLSS mip-bias dimensions must be positive");
        }
        return (float) (DLSS_NATIVE_MIP_BIAS
                + Math.log(renderWidth / (double) outputWidth) / LOG_2
                - (subpixelDetail ? 1.0 : 0.0) + DLSS_MIP_BIAS_EPSILON);
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
