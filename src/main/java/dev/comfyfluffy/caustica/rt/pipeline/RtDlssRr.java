package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.streamline.StreamlineAbi;
import dev.comfyfluffy.caustica.streamline.StreamlineLibrary;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;

/** Streamline-owned DLSS Ray Reconstruction backend for the path-traced renderer. */
public final class RtDlssRr {
    public static final RtDlssRr INSTANCE = new RtDlssRr();

    // Streamline allows one common-constants packet per frame token and viewport. RR consumes raw
    // render-space inputs while DLSSG viewport 0 consumes final-present-space inputs, so RR owns viewport 1.
    private static final int VIEWPORT = 1;
    private static final int RESULT_OK = 0;
    private static final int RESOURCE_COUNT = 10;
    private static final int LIFECYCLE_VALID_UNTIL_EVALUATE = 2;

    private static final int BUFFER_DEPTH = 0;
    private static final int BUFFER_MOTION_VECTORS = 1;
    private static final int BUFFER_SCALING_INPUT_COLOR = 3;
    private static final int BUFFER_SCALING_OUTPUT_COLOR = 4;
    private static final int BUFFER_ALBEDO = 7;
    private static final int BUFFER_SPECULAR_ALBEDO = 8;
    private static final int BUFFER_SPECULAR_MOTION_VECTORS = 10;
    private static final int BUFFER_DISOCCLUSION_MASK = 11;
    private static final int BUFFER_NORMAL_ROUGHNESS = 14;
    private static final int BUFFER_BIAS_CURRENT_COLOR_HINT = 29;

    private StreamlineLibrary library;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailability;
    private boolean configured;
    private boolean resourcesCreated;
    private boolean loggedEvaluation;
    private boolean resetHistory;
    private int lastOptionsResult = Integer.MIN_VALUE;
    private int lastEvaluateResult = Integer.MIN_VALUE;
    private long javaOptionsCalls;
    private long javaEvaluateCalls;
    private int lastResourceCount;
    private boolean fallbackActive;
    private long fallbackFrames;
    private String fallbackReason = "Not evaluated";
    private final Matrix4f worldToCameraView = new Matrix4f();
    private final Matrix4f cameraViewToWorld = new Matrix4f();

    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private int featurePreset = Integer.MIN_VALUE;

    private RtDlssRr() {
    }

    public static boolean enabled() {
        return CausticaConfig.Rt.DlssRr.ENABLED.value();
    }

    public static int quality() {
        return CausticaConfig.Rt.DlssRr.QUALITY.value();
    }

    private static int renderPreset() {
        return CausticaConfig.Rt.DlssRr.PRESET.value();
    }

    /** Caustica's persisted quality values 0..5 map exactly to Streamline DLSSMode values 1..6. */
    private static int streamlineMode() {
        int quality = quality();
        return quality >= 0 && quality <= 5 ? quality + 1 : 1;
    }

    public boolean isReady() {
        return initialized && !failed && configured;
    }

    public boolean isOperational() {
        return enabled() && !failed;
    }

    public int lastOptionsResult() {
        return lastOptionsResult;
    }

    public int lastEvaluateResult() {
        return lastEvaluateResult;
    }

    public long javaOptionsCalls() {
        return javaOptionsCalls;
    }

    public long javaEvaluateCalls() {
        return javaEvaluateCalls;
    }

    public int lastResourceCount() {
        return lastResourceCount;
    }

    public boolean fallbackActive() {
        return fallbackActive;
    }

    public long fallbackFrames() {
        return fallbackFrames;
    }

    public String fallbackReason() {
        return fallbackReason;
    }

    public boolean failed() {
        return failed;
    }

    public void requestHistoryReset() {
        resetHistory = true;
    }

    public void resetFailureLatch() {
        failed = false;
        requestHistoryReset();
    }

    public static int requiredResourceCount() {
        return RESOURCE_COUNT;
    }

    /**
     * Record one Streamline DLSS-RR evaluation into {@code commandBuffer}. RR uses local render-space
     * constants because its inputs have not undergone the final Vulkan Y reversal; DLSSG later publishes
     * one global present-space constants packet for its pre-flipped resources using the same frame token.
     */
    public boolean evaluate(long commandBuffer, RtImage color, RtImage depth, RtImage motion,
            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normalRoughness,
            RtImage specularMotion, RtImage disocclusion, RtImage biasCurrentColor, RtImage output,
            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
            float jitterX, float jitterY, Matrix4fc projection,
            Matrix4fc currentViewProjection, Matrix4fc previousViewProjection, Matrix4fc viewRotation,
            double cameraX, double cameraY, double cameraZ,
            float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ, boolean reset) {
        if (!isReady() || commandBuffer == 0L) {
            return false;
        }
        long frameToken = RtDlssFg.INSTANCE.frameTokenForFeature();
        if (frameToken == 0L) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment options = StreamlineAbi.allocate(arena, StreamlineAbi.DLSSD_OPTIONS_SIZE);
            writeOptions(options, streamlineMode(), displayWidth, displayHeight, renderPreset(), viewRotation);
            javaOptionsCalls++;
            lastOptionsResult = library.setDlssdOptions(VIEWPORT, options);
            check(lastOptionsResult, "slDLSSDSetOptions");

            MemorySegment constants = StreamlineAbi.allocate(arena, StreamlineAbi.CONSTANTS_SIZE);
            RtDlssFg.INSTANCE.writeRenderSpaceConstants(constants, projection, currentViewProjection,
                    previousViewProjection, viewRotation, jitterX, jitterY, renderWidth, renderHeight,
                    cameraX, cameraY, cameraZ, cameraDeltaX, cameraDeltaY, cameraDeltaZ,
                    reset || resetHistory);

            MemorySegment resources = StreamlineAbi.allocate(arena,
                    StreamlineAbi.RESOURCE_DESC_SIZE * RESOURCE_COUNT);
            writeResource(resources, 0, color, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_SCALING_INPUT_COLOR);
            writeResource(resources, 1, depth, VK10.VK_FORMAT_R32_SFLOAT,
                    renderWidth, renderHeight, BUFFER_DEPTH);
            writeResource(resources, 2, motion, VK10.VK_FORMAT_R16G16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_MOTION_VECTORS);
            writeResource(resources, 3, diffuseAlbedo, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_ALBEDO);
            writeResource(resources, 4, specularAlbedo, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_SPECULAR_ALBEDO);
            writeResource(resources, 5, normalRoughness, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_NORMAL_ROUGHNESS);
            writeResource(resources, 6, specularMotion, VK10.VK_FORMAT_R16G16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_SPECULAR_MOTION_VECTORS);
            writeResource(resources, 7, disocclusion, VK10.VK_FORMAT_R16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_DISOCCLUSION_MASK);
            writeResource(resources, 8, biasCurrentColor, VK10.VK_FORMAT_R16_SFLOAT,
                    renderWidth, renderHeight, BUFFER_BIAS_CURRENT_COLOR_HINT);
            writeResource(resources, 9, output, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    displayWidth, displayHeight, BUFFER_SCALING_OUTPUT_COLOR);

            resourcesCreated = true;
            lastResourceCount = RESOURCE_COUNT;
            javaEvaluateCalls++;
            lastEvaluateResult = library.evaluateDlssd(frameToken, VIEWPORT, resources, RESOURCE_COUNT,
                    constants, commandBuffer);
            check(lastEvaluateResult, "slEvaluateFeature(DLSS-RR)");
            resetHistory = false;
            fallbackActive = false;
            fallbackReason = "None";
            if (!loggedEvaluation) {
                loggedEvaluation = true;
                CausticaMod.LOGGER.info(
                        "Streamline DLSS-RR evaluation active: {}x{} -> {}x{} (quality {}, preset {}, frame token 0x{})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality(), renderPreset(),
                        Long.toHexString(frameToken));
            }
            return true;
        } catch (Throwable throwable) {
            failed = true;
            fallbackActive = true;
            fallbackReason = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            CausticaMod.LOGGER.error("Streamline DLSS-RR evaluate failed; RT composite continues without it",
                    throwable);
            return false;
        }
    }

    /** Records the renderer's actual post-RR fallback decision for acceptance telemetry. */
    public void recordFallback(boolean rrRequestedForFrame, boolean evaluationCompleted) {
        if (!rrRequestedForFrame) {
            fallbackActive = false;
            fallbackReason = "DLSSD disabled or debug view selected";
        } else if (evaluationCompleted) {
            fallbackActive = false;
            fallbackReason = "None";
        } else {
            fallbackActive = true;
            fallbackFrames++;
            if (!failed) {
                fallbackReason = "DLSSD evaluation did not complete";
            }
        }
        StreamlineAcceptanceReport.publish();
    }

    /** Query the Streamline DLSS-RR plugin for the exact input size for the selected quality mode. */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return null;
        }
        try {
            if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend()
                    instanceof VulkanDevice device)) {
                throw new IllegalStateException("Vulkan backend unavailable for DLSSD optimal-size query");
            }
            ensureInitialized(device);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment renderWidth = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment renderHeight = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment sharpness = arena.allocate(ValueLayout.JAVA_FLOAT);
                int result = library.getDlssdOptimalSettings(streamlineMode(), displayWidth, displayHeight,
                        renderWidth, renderHeight, sharpness);
                check(result, "slDLSSDGetOptimalSettings");
                int width = renderWidth.get(ValueLayout.JAVA_INT, 0);
                int height = renderHeight.get(ValueLayout.JAVA_INT, 0);
                if (width <= 0 || height <= 0) {
                    throw new IllegalStateException("Streamline DLSS-RR returned invalid render size "
                            + width + "x" + height);
                }
                return new int[] { width, height };
            }
        } catch (Throwable throwable) {
            failed = true;
            fallbackActive = true;
            fallbackReason = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            CausticaMod.LOGGER.error(
                    "Streamline DLSS-RR optimal-size query failed; using native-resolution fallback",
                    throwable);
            return null;
        }
    }

    /** Configure the lazy Streamline feature instance for the requested dimensions and quality. */
    public boolean ensureFeature(long commandBuffer, int renderWidth, int renderHeight,
            int displayWidth, int displayHeight) {
        if (!enabled() || failed || commandBuffer == 0L) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend()
                instanceof VulkanDevice device)) {
            return false;
        }
        try {
            ensureInitialized(device);
            int quality = quality();
            int preset = renderPreset();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || featurePreset != preset) {
                releaseResources(device);
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                featurePreset = preset;
                configured = true;
                resetHistory = true;
                CausticaMod.LOGGER.info("Streamline DLSS-RR configured: {}x{} -> {}x{} (quality {}, preset {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset);
            }
            return true;
        } catch (Throwable throwable) {
            failed = true;
            CausticaMod.LOGGER.error("Streamline DLSS-RR setup failed; RT composite continues without it",
                    throwable);
            return false;
        }
    }

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        if (!StreamlineRuntime.initializeForVulkan() || StreamlineRuntime.library() == null) {
            throw new IllegalStateException("Streamline runtime unavailable; DLSS-RR cannot initialize");
        }
        library = StreamlineRuntime.library();
        int supportResult = StreamlineRuntime.supportsFeature(StreamlineRuntime.FEATURE_DLSS_RR,
                device.vkDevice().getPhysicalDevice().address());
        boolean available = supportResult == RESULT_OK;
        if (!loggedAvailability) {
            loggedAvailability = true;
            CausticaMod.LOGGER.info("Streamline DLSS Ray Reconstruction available: {}{}", available,
                    available ? "" : " (" + StreamlineRuntime.lastError() + ")");
        }
        if (!available) {
            throw new IllegalStateException("Streamline DLSS Ray Reconstruction is unavailable: "
                    + StreamlineRuntime.lastError());
        }
        initialized = true;
    }

    /** Release only the Streamline RR viewport resources; Streamline owns the shared NGX lifetime. */
    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend()
                instanceof VulkanDevice device) {
            releaseResources(device);
        }
        initialized = false;
        failed = false;
        configured = false;
        resourcesCreated = false;
        loggedEvaluation = false;
        resetHistory = false;
        library = null;
    }

    private void releaseResources(VulkanDevice device) {
        if (resourcesCreated && library != null && StreamlineRuntime.initialized()) {
            StreamlineRuntime.vkDeviceWaitIdle(device.vkDevice(), "DLSSD resource release", false);
            int result = library.freeDlssdResources(VIEWPORT);
            if (result != RESULT_OK) {
                CausticaMod.LOGGER.warn("Could not release Streamline DLSS-RR resources: {}",
                        StreamlineRuntime.lastError());
            }
        }
        resourcesCreated = false;
        configured = false;
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureDisplayWidth = -1;
        featureDisplayHeight = -1;
        featureQuality = Integer.MIN_VALUE;
        featurePreset = Integer.MIN_VALUE;
    }

    private void check(int result, String operation) {
        if (result != RESULT_OK) {
            throw new IllegalStateException(operation + " failed: " + StreamlineRuntime.lastError());
        }
    }

    private void writeOptions(MemorySegment segment, int mode, int outputWidth, int outputHeight,
            int preset, Matrix4fc viewRotation) {
        ByteBuffer bytes = StreamlineAbi.bytes(segment);
        bytes.putInt(0, mode);
        bytes.putInt(4, outputWidth);
        bytes.putInt(8, outputHeight);
        bytes.putInt(12, preset);
        worldToCameraView.set(viewRotation);
        cameraViewToWorld.set(viewRotation).invert();
        StreamlineAbi.writeRowVectorMatrix(bytes, 16, worldToCameraView);
        StreamlineAbi.writeRowVectorMatrix(bytes, 80, cameraViewToWorld);
    }

    private static void writeResource(MemorySegment resources, int index, RtImage image, int format,
            int width, int height, int bufferType) {
        if (image == null || image.image == 0L || image.view == 0L
                || image.width != width || image.height != height || image.format != format) {
            throw new IllegalArgumentException("Incomplete Streamline DLSS-RR resource " + index
                    + " for " + width + "x" + height);
        }
        writeResource(resources, index, image.image, image.view, format, width, height, bufferType, image.usage);
    }

    static void writeResource(MemorySegment resources, int index, long image, long view, int format,
            int width, int height, int bufferType, int usage) {
        ByteBuffer bytes = StreamlineAbi.bytes(resources);
        int base = index * StreamlineAbi.RESOURCE_DESC_SIZE;
        bytes.putLong(base, image);
        bytes.putLong(base + 8, view);
        // Streamline's Vulkan manual-hooking contract identifies textures with VkImage + VkImageView.
        // VkDeviceMemory is intentionally null; exposing allocator backing ownership is unsupported.
        bytes.putLong(base + 16, 0L);
        bytes.putInt(base + 24, VK10.VK_IMAGE_LAYOUT_GENERAL);
        bytes.putInt(base + 28, width);
        bytes.putInt(base + 32, height);
        bytes.putInt(base + 36, format);
        bytes.putInt(base + 40, 1);
        bytes.putInt(base + 44, 1);
        bytes.putInt(base + 48, 0);
        bytes.putInt(base + 52, usage);
        bytes.putInt(base + 56, bufferType);
        bytes.putInt(base + 60, LIFECYCLE_VALID_UNTIL_EVALUATE);
        bytes.put(base + 64, (byte) 1);
    }
}
