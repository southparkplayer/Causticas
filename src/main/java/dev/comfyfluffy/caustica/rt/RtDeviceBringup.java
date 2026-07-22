package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtNrd;
import dev.comfyfluffy.caustica.rt.pipeline.RtReconstruction;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_PROPERTIES_EXT;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT;
import static org.lwjgl.vulkan.NVRayTracingInvocationReorder.VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
import static org.lwjgl.vulkan.NVRayTracingInvocationReorder.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV;

/**
 * RT device bring-up. Enables the hardware ray-tracing device extensions and their
 * feature structs on vanilla's Blaze3D device at {@code vkCreateDevice} time.
 *
 * <p>Vanilla assembles a {@code VkPhysicalDeviceFeatures2} pNext chain from the
 * {@code Set<VulkanFeature>} (arg2) via {@code VulkanFeature.set} →
 * {@code findOrCreateStructInPNextChain} (dedup by sType), so {@code bufferDeviceAddress}
 * merges into the existing {@code VkPhysicalDeviceVulkan12Features} struct and the two
 * KHR structs are created fresh. BDA / descriptor-indexing / SPIR-V 1.4 are core on the
 * 1.4 device, so only three extension <i>names</i> are needed; the rest are feature enables.
 *
 * <p>Extension names are added to the device extension list separately; feature structs are added here.
 * Both are gated on the selected device actually supporting RT; if not, nothing is added
 * and the device comes up exactly as vanilla. {@code caustica.rt} is read once here, at
 * {@code vkCreateDevice} time, before the device exists — flipping it later at runtime cannot add
 * device features to an already-created device, so a config change only takes effect on restart.
 */
public final class RtDeviceBringup {
    public static boolean enabledByProperty() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    /**
     * The device extensions RT needs (BDA/descriptor-indexing/SPIR-V 1.4 are core on 1.4).
     * {@code ray_tracing_position_fetch} lets the closest-hit read hit triangle vertex positions
     * ({@code gl_HitTriangleVertexPositionsEXT}) for the normal-map TBN, avoiding a positions buffer
     * plumbed through the geometry tables. This remains part of Caustica's cross-vendor RT support floor.
     * {@code ray_query} lets fragment shaders (the world-overlay pass, e.g. block outline) issue inline
     * {@code rayQueryEXT} occlusion tests against the same TLAS the ray-tracing pipeline traces, without a
     * dedicated raygen dispatch.
     */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME);

    /** Optional Shader Execution Reordering capability. The normal live path never requires it. */
    public static final List<String> SER_EXTENSIONS = List.of(
            VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME,
            VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);

    /**
     * OPTIONAL RT extensions: enabled only when the selected device supports them AND the gate is on, but
     * never required — a device lacking them still comes up RT-capable (unlike {@link #RT_EXTENSIONS}, whose
     * absence disables RT entirely). {@code VK_EXT_opacity_micromap} (any-hit opt, lever C): per-triangle
     * opacity micromaps let the hardware skip {@code world.rahit} on fully-opaque/transparent cutout micro-
     * triangles, so the alpha-test any-hit runs only on the foliage silhouette. Hardware-accelerated on RTX
     * 40-series and Blackwell; absent / software elsewhere, hence optional.
     */
    public static final List<String> OPTIONAL_RT_EXTENSIONS = List.of(
            VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);

    private static volatile boolean rtRequested;
    private static volatile SerBackend serBackend = SerBackend.NONE;
    private static volatile boolean ommEnabled; // VK_EXT_opacity_micromap actually enabled on the device
    private static volatile boolean traceRaysIndirectSupported;
    private static volatile int maxRayDispatchInvocationCount;
    private static volatile boolean wideLinesEnabled; // VkPhysicalDeviceFeatures.wideLines actually enabled
    private static volatile boolean sharcInt64AtomicsEnabled;
    private static volatile float maxLineWidth = 1.0f; // device's lineWidthRange[1]; 1.0 unless wideLinesEnabled
    private static volatile int overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT; // capped to the device's framebufferColorSampleCounts
    private static volatile int maxOpacity4StateSubdivisionLevel;
    private static boolean loggedUnavailable;

    private static final VulkanPNextStruct AS_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RT_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct POSITION_FETCH_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_POSITION_FETCH_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RAY_QUERY_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct SER_NV_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV,
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.SIZEOF);
    private static final VulkanPNextStruct SER_EXT_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct OMM_FEATURES_STRUCT = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature RUNTIME_DESCRIPTOR_ARRAY_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "runtimeDescriptorArray", VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY);
    private static final VulkanFeature SAMPLED_IMAGE_NON_UNIFORM_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing",
            VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING);
    private static final VulkanFeature DESCRIPTOR_PARTIALLY_BOUND_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND);
    private static final VulkanFeature SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind",
            VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND);
    private static final VulkanFeature SHADER_INT64_FEATURE = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature ACCELERATION_STRUCTURE_FEATURE = new VulkanFeature(
            AS_FEATURES_STRUCT, "accelerationStructure", VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_TRACING_PIPELINE_FEATURE = new VulkanFeature(
            RT_FEATURES_STRUCT, "rayTracingPipeline", VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);
    private static final VulkanFeature POSITION_FETCH_FEATURE = new VulkanFeature(
            POSITION_FETCH_FEATURES_STRUCT, "rayTracingPositionFetch",
            VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.RAYTRACINGPOSITIONFETCH);
    private static final VulkanFeature RAY_QUERY_FEATURE = new VulkanFeature(
            RAY_QUERY_FEATURES_STRUCT, "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
    private static final VulkanFeature SER_NV_FEATURE = new VulkanFeature(
            SER_NV_FEATURES_STRUCT, "rayTracingInvocationReorder(NV)",
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.RAYTRACINGINVOCATIONREORDER);
    private static final VulkanFeature SER_EXT_FEATURE = new VulkanFeature(
            SER_EXT_FEATURES_STRUCT, "rayTracingInvocationReorder(EXT)",
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER);
    private static final VulkanFeature OMM_FEATURE = new VulkanFeature(
            OMM_FEATURES_STRUCT, "micromap", VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature WIDE_LINES_FEATURE = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "wideLines", VkPhysicalDeviceFeatures.WIDELINES);
    private static final List<VulkanFeature> REQUIRED_RT_FEATURES = List.of(
            BUFFER_DEVICE_ADDRESS_FEATURE, RUNTIME_DESCRIPTOR_ARRAY_FEATURE, SAMPLED_IMAGE_NON_UNIFORM_FEATURE,
            DESCRIPTOR_PARTIALLY_BOUND_FEATURE, SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE, SHADER_INT64_FEATURE,
            ACCELERATION_STRUCTURE_FEATURE, RAY_TRACING_PIPELINE_FEATURE, POSITION_FETCH_FEATURE, RAY_QUERY_FEATURE);

    private enum SerBackend {
        NONE("none", null, "world_base.rgen.spv"),
        NV("NV", VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME, "world_nv.rgen.spv"),
        EXT("EXT", VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME, "world.rgen.spv");

        final String label;
        final String extensionName;
        final String worldRaygenShader;

        SerBackend(String label, String extensionName, String worldRaygenShader) {
            this.label = label;
            this.extensionName = extensionName;
            this.worldRaygenShader = worldRaygenShader;
        }
    }

    private RtDeviceBringup() {
    }

    /** True once we have augmented a device creation to request RT (extensions + features). */
    public static boolean rtRequested() {
        return rtRequested;
    }

    public static String worldRaygenShader(boolean nrd) {
        String shader = nrd ? switch (serBackend) {
            case NV -> "world_nrd_nv.rgen.spv";
            case EXT -> "world_nrd.rgen.spv";
            case NONE -> "world_nrd_base.rgen.spv";
        } : serBackend.worldRaygenShader;
        return nrd ? advancedNrdTransportShader(shader) : highQualityTransparencyShader(shader);
    }

    public static String offlineWorldRaygenShader() {
        return switch (serBackend) {
            case NV -> "world_offline_nv.rgen.spv";
            case EXT -> "world_offline.rgen.spv";
            case NONE -> "world_offline_base.rgen.spv";
        };
    }

    public static String offlinePilotWorldRaygenShader() {
        return switch (serBackend) {
            case NV -> "world_offline_pilot_nv.rgen.spv";
            case EXT -> "world_offline_pilot.rgen.spv";
            case NONE -> "world_offline_pilot_base.rgen.spv";
        };
    }

    public static String offlineIndirectWorldRaygenShader() {
        return switch (serBackend) {
            case NV -> "world_offline_indirect_nv.rgen.spv";
            case EXT -> "world_offline_indirect.rgen.spv";
            case NONE -> "world_offline_indirect_base.rgen.spv";
        };
    }

    public static String sharcQueryRaygenShader() {
        return backendShader("world_sharc.rgen.spv", "world_sharc_nv.rgen.spv", "world_sharc_base.rgen.spv");
    }

    public static String sharcDiffuseQueryRaygenShader() {
        return backendShader("world_sharc_diffuse.rgen.spv",
                "world_sharc_diffuse_nv.rgen.spv", "world_sharc_diffuse_base.rgen.spv");
    }

    public static String sharcPrimaryQueryRaygenShader() {
        return backendShader("world_sharc_primary.rgen.spv",
                "world_sharc_primary_nv.rgen.spv", "world_sharc_primary_base.rgen.spv");
    }

    public static String sharcUpdateRaygenShader() {
        return backendShader("world_sharc_update.rgen.spv", "world_sharc_update_nv.rgen.spv",
                "world_sharc_update_base.rgen.spv");
    }

    public static String sharcDiagnosticQueryRaygenShader() {
        return backendShader("world_sharc_diagnostic.rgen.spv",
                "world_sharc_diagnostic_nv.rgen.spv", "world_sharc_diagnostic_base.rgen.spv");
    }

    public static String sharcPrimaryDiagnosticQueryRaygenShader() {
        return backendShader("world_sharc_primary_diagnostic.rgen.spv",
                "world_sharc_primary_diagnostic_nv.rgen.spv",
                "world_sharc_primary_diagnostic_base.rgen.spv");
    }

    private record FeatureSupport(List<String> missingRequired, SerBackend serBackend,
                                  boolean omm, boolean wideLines) {
        boolean supportsRt() {
            return missingRequired.isEmpty() && serBackend != SerBackend.NONE;
        }
    }

    private static String highQualityTransparencyShader(String shader) {
        if (!CausticaConfig.Rt.Reconstruction.ADVANCED_OPTICAL_TRANSPORT.value()
                || !RtReconstruction.usesDlss() || !RtDlssRr.INSTANCE.isOperational()) {
            return shader;
        }
        int suffix = shader.indexOf(".rgen.spv");
        if (suffix < 0) {
            throw new IllegalArgumentException("not a raygen shader: " + shader);
        }
        return shader.substring(0, suffix) + "_hq" + shader.substring(suffix);
    }

    private static String advancedNrdTransportShader(String shader) {
        return shader;
    }

    public static String sharcDiagnosticUpdateRaygenShader() {
        return backendShader("world_sharc_update_diagnostic.rgen.spv",
                "world_sharc_update_diagnostic_nv.rgen.spv", "world_sharc_update_diagnostic_base.rgen.spv");
    }

    /** Standard TraceRay fallback needs a real shadow closest-hit; hit-object variants skip it entirely. */
    public static String shadowClosestHitShader() {
        return serBackend == SerBackend.NONE ? "world_shadow.rchit.spv" : null;
    }

    private static String backendShader(String ext, String nv, String base) {
        return switch (serBackend) {
            case EXT -> ext;
            case NV -> nv;
            case NONE -> base;
        };
    }

    public static boolean sharcInt64AtomicsEnabled() {
        return sharcInt64AtomicsEnabled;
    }

    public static boolean serNvEnabled() {
        return serBackend == SerBackend.NV;
    }

    public static boolean serExtEnabled() {
        return serBackend == SerBackend.EXT;
    }

    /** True if {@code VK_EXT_opacity_micromap} was enabled on the device (gate on + device support). */
    public static boolean ommEnabled() {
        return ommEnabled;
    }

    public static boolean traceRaysIndirectSupported() {
        return traceRaysIndirectSupported;
    }

    public static long maxRayDispatchInvocationCount() {
        return Integer.toUnsignedLong(maxRayDispatchInvocationCount);
    }

    /** Hardware limit for 4-state opacity micromaps, populated by {@link #probe(VkDevice)}. */
    public static int maxOpacity4StateSubdivisionLevel() {
        return maxOpacity4StateSubdivisionLevel;
    }

    /** True if {@code VkPhysicalDeviceFeatures.wideLines} was enabled on the device (world-overlay thick
     *  lines, e.g. the block outline, use this instead of a screen-space quad when available). */
    public static boolean wideLinesEnabled() {
        return wideLinesEnabled;
    }

    /** The device's max native line width (raster {@code lineWidthRange[1]}); 1.0 if wideLines isn't
     *  enabled (Vulkan mandates exactly 1.0 in that case). Callers must clamp their desired width to this. */
    public static float maxLineWidth() {
        return maxLineWidth;
    }

    /** {@code VK_SAMPLE_COUNT_4_BIT} capped down to whatever the device's {@code framebufferColorSampleCounts}
     *  actually advertises (2x, or 1x/no MSAA on the rare device that lacks even that) — no device feature to
     *  enable, just a raster/framebuffer property, unlike {@link #wideLinesEnabled()}. World-overlay passes
     *  that need edge AA (e.g. the block outline's native wide line) use this as their pipeline's
     *  {@code rasterizationSamples}. */
    public static int overlayMsaaSamples() {
        return overlayMsaaSamples;
    }

    /** Optional extensions the gate wants AND the device supports — added but never required. */
    private static List<String> supportedOptionalExtensions(FeatureSupport support) {
        List<String> supported = new ArrayList<>();
        if (support.omm) supported.add(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        return supported;
    }

    private static boolean ommRequested() {
        // OMM is an optional acceleration representation with a complete any-hit fallback. Keep the
        // persisted device-creation gate authoritative so a faulting driver/workload can disable the
        // extension before Vulkan device creation rather than merely skipping its later classifier.
        return CausticaConfig.Rt.Omm.ENABLED.value();
    }

    /** Optional SHaRC feature query; absence keeps the separately packaged SHaRC shaders disabled. */
    private static boolean supportsShaderBufferInt64Atomics(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features features = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(features.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features2);
            return features.shaderBufferInt64Atomics();
        }
    }

    private static FeatureSupport queryFeatureSupport(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (VulkanFeature feature : REQUIRED_RT_FEATURES) {
                feature.struct().findOrCreateStructInPNextChain(available, stack);
            }
            boolean hasSerExt = physicalDevice.hasDeviceExtension(
                    VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            boolean hasSerNv = physicalDevice.hasDeviceExtension(
                    VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            if (hasSerExt) SER_EXT_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            if (hasSerNv) SER_NV_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            boolean queryOmm = ommRequested()
                    && physicalDevice.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
            if (queryOmm) OMM_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            WIDE_LINES_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), available);

            List<String> missing = new ArrayList<>();
            for (VulkanFeature feature : REQUIRED_RT_FEATURES) {
                if (!feature.get(available)) missing.add(feature.name());
            }
            SerBackend supportedSer = hasSerExt && SER_EXT_FEATURE.get(available) ? SerBackend.EXT
                    : hasSerNv && SER_NV_FEATURE.get(available) ? SerBackend.NV : SerBackend.NONE;
            if (supportedSer == SerBackend.NONE) missing.add("rayTracingInvocationReorder(NV or EXT)");
            return new FeatureSupport(missing, supportedSer,
                    queryOmm && OMM_FEATURE.get(available), WIDE_LINES_FEATURE.get(available));
        }
    }

    private static String firstUnsupported(VulkanPhysicalDevice physicalDevice) {
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Standalone path: add RT extension names to the (mutable) arg0 list. */
    public static void addExtensions(List<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty() || firstUnsupported(physicalDevice) != null) {
            return;
        }
        FeatureSupport support = queryFeatureSupport(physicalDevice);
        if (!support.supportsRt()) return;
        for (String ext : RT_EXTENSIONS) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
        String serExtension = support.serBackend.extensionName;
        if (serExtension != null && !augmentedExtensions.contains(serExtension)) {
            augmentedExtensions.add(serExtension);
        }
        for (String ext : supportedOptionalExtensions(support)) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
    }

    /** Add the RT VulkanFeatures to arg2 after the matching extension names have been requested. */
    @SuppressWarnings("unchecked")
    public static void addFeatures(Args args, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty()) {
            return;
        }
        rtRequested = false;
        serBackend = SerBackend.NONE;
        ommEnabled = false;
        wideLinesEnabled = false;
        sharcInt64AtomicsEnabled = false;
        maxLineWidth = 1.0f;
        String missing = firstUnsupported(physicalDevice);
        if (missing != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks {}", physicalDevice.deviceName(), missing);
            }
            return;
        }

        FeatureSupport support = queryFeatureSupport(physicalDevice);
        if (!support.supportsRt()) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks features {}",
                        physicalDevice.deviceName(), support.missingRequired);
            }
            return;
        }

        Set<VulkanFeature> features = new HashSet<>((Set<VulkanFeature>) args.get(2));
        features.addAll(REQUIRED_RT_FEATURES);
        SerBackend selectedSerBackend = support.serBackend;
        // Optional SHaRC native atomic path. The separately packaged shaders require this exact feature;
        // if absent, they remain unavailable and the baseline pipeline is used.
        sharcInt64AtomicsEnabled = RtSharcSupport.packaged() && supportsShaderBufferInt64Atomics(physicalDevice);
        if (sharcInt64AtomicsEnabled) {
            features.add(new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "shaderBufferInt64Atomics",
                    VkPhysicalDeviceVulkan12Features.SHADERBUFFERINT64ATOMICS));
        }
        if (selectedSerBackend != SerBackend.NONE) {
            features.add(selectedSerBackend == SerBackend.NV ? SER_NV_FEATURE : SER_EXT_FEATURE);
        }

        // Optional: wideLines (core VK10 feature, no extension). Lets the world-overlay pass (block
        // outline) draw a real thick native line via a raster pipeline's lineWidth / VK_DYNAMIC_STATE_LINE
        // _WIDTH instead of a screen-space quad. Its absence must not disable RT — the overlay falls back
        // to whatever the device's mandated lineWidth (1.0) allows.
        wideLinesEnabled = support.wideLines;
        if (wideLinesEnabled) {
            features.add(WIDE_LINES_FEATURE);
            maxLineWidth = physicalDevice.vkPhysicalDeviceProperties().limits().lineWidthRange(1);
        } else {
            maxLineWidth = 1.0f;
        }

        // World-overlay MSAA (block outline edge AA): a framebuffer property, not a feature — no
        // VulkanFeature entry needed, just cap the desired 4x down to what the device actually supports.
        int colorSampleCounts = physicalDevice.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts();
        if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_4_BIT;
        } else if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_2_BIT;
        } else {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT;
        }

        // Optional: opacity micromaps (any-hit opt). Only when the gate is on AND the device advertises the
        // extension — its absence must not disable RT, so it is kept out of the mandatory feature set above.
        ommEnabled = support.omm;
        if (ommEnabled) {
            features.add(OMM_FEATURE);
        }

        args.set(2, features);

        rtRequested = true;
        serBackend = selectedSerBackend;
        CausticaMod.LOGGER.info(
                "Ray tracing: enabling {}{}{} + features [bufferDeviceAddress, accelerationStructure, rayTracingPipeline, rayQuery, optionalInvocationReorder({}), liveReorder=off, offlineReorder={}"
                        + (wideLinesEnabled ? ", wideLines(max=" + maxLineWidth + ")" : "")
                        + (sharcInt64AtomicsEnabled ? ", shaderBufferInt64Atomics(SHaRC)" : "")
                        + (ommEnabled ? ", opacityMicromap" : "") + "] + overlayMsaa=" + overlayMsaaSamples + "x on [{}]",
                RT_EXTENSIONS, serBackend.extensionName == null ? "" : " + " + serBackend.extensionName,
                ommEnabled ? " + " + OPTIONAL_RT_EXTENSIONS : "", serBackend.label,
                serBackend != SerBackend.NONE ? "on" : "off", physicalDevice.deviceName());
    }

    /**
     * Post-creation verification: confirm the RT entry points actually loaded on the new
     * device and log the RT pipeline / acceleration-structure limits. If this logs "OK",
     * the device truly came up RT-capable.
     */
    public static void probe(VkDevice device) {
        if (!rtRequested) {
            CausticaMod.LOGGER.info("Ray tracing not requested; skipping RT probe");
            maxOpacity4StateSubdivisionLevel = 0;
            traceRaysIndirectSupported = false;
            maxRayDispatchInvocationCount = 0;
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            traceRaysIndirectSupported = caps.vkCmdTraceRaysIndirectKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                CausticaMod.LOGGER.error(
                        "RT extensions enabled but entry points missing (rtPipeline={}, asBuild={}, traceRays={}) — RT bring-up FAILED",
                        rtPipeline, asBuild, traceRays);
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                rtProps.pNext(asProps.address());
                // Chain the OMM properties only when the feature is enabled (else the driver would ignore an
                // unrecognized struct, but keeping the chain clean matches the enabled feature set).
                VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps = null;
                if (ommEnabled) {
                    ommProps = VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
                    asProps.pNext(ommProps.address());
                }
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(rtProps.address());
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);
                maxRayDispatchInvocationCount = rtProps.maxRayDispatchInvocationCount();

                CausticaMod.LOGGER.info(
                        "RT bring-up OK — shaderGroupHandleSize={}, shaderGroupBaseAlignment={}, maxRayRecursionDepth={}; "
                                + "maxAS geometry/instance/primitive = {}/{}/{}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(), rtProps.maxRayRecursionDepth(),
                        asProps.maxGeometryCount(), asProps.maxInstanceCount(), asProps.maxPrimitiveCount());
                if (ommProps != null) {
                    maxOpacity4StateSubdivisionLevel = ommProps.maxOpacity4StateSubdivisionLevel();
                    CausticaMod.LOGGER.info(
                            "Opacity micromaps enabled — maxSubdivisionLevel 4-state={}, 2-state={}",
                            ommProps.maxOpacity4StateSubdivisionLevel(), ommProps.maxOpacity2StateSubdivisionLevel());
                } else {
                    maxOpacity4StateSubdivisionLevel = 0;
                }
            }
        } catch (Throwable t) {
            // A probe must never break device creation.
            CausticaMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }
}
