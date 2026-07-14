package dev.comfyfluffy.caustica.streamline;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.lwjgl.PointerBuffer;

/** Owns Streamline's process/device lifetime and the native proxy fallback boundary. */
public final class StreamlineRuntime {
    public static final int FEATURE_REFLEX = 3;
    public static final int FEATURE_PCL = 4;
    public static final int FEATURE_DLSS_G = 1000;
    public static final int FEATURE_DLSS_RR = 1001;
    public static final int VARIANT_DEVELOPMENT = 0;
    public static final int VARIANT_PRODUCTION = 1;

    private static final List<String> WINDOWS_NATIVE_NAMES = List.of(
            "streamlinebridge.dll", "sl.interposer.dll", "sl.common.dll", "sl.dlss_d.dll",
            "sl.dlss_g.dll", "sl.reflex.dll", "sl.pcl.dll", "nvngx_dlssg.dll", "nvngx_dlssd.dll", "NvLowLatencyVk.dll",
            "reflex.license.txt", "nvngx_dlss.license.txt");
    private static final ThreadLocal<Integer> VULKAN_AVAILABILITY_PROBE_DEPTH = new ThreadLocal<>();

    private static final StreamlineRuntime INSTANCE = new StreamlineRuntime();

    private StreamlineLibrary library;
    private Path nativeDirectory;
    private boolean initialized;
    private boolean failed;
    private boolean featureLoaded;

    private StreamlineRuntime() {
    }

    public static boolean initializeForVulkan() {
        if (vulkanAvailabilityProbeActive()) {
            return false;
        }
        return INSTANCE.initialize();
    }

    public static void beginVulkanAvailabilityProbe() {
        Integer depth = VULKAN_AVAILABILITY_PROBE_DEPTH.get();
        VULKAN_AVAILABILITY_PROBE_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void endVulkanAvailabilityProbe() {
        Integer depth = VULKAN_AVAILABILITY_PROBE_DEPTH.get();
        if (depth == null || depth <= 1) {
            VULKAN_AVAILABILITY_PROBE_DEPTH.remove();
        } else {
            VULKAN_AVAILABILITY_PROBE_DEPTH.set(depth - 1);
        }
    }

    public static boolean initialized() {
        return INSTANCE.initialized;
    }

    public static boolean useVulkanProxies() {
        return !vulkanAvailabilityProbeActive() && INSTANCE.initialized && !INSTANCE.failed;
    }

    public static String lastError() {
        return INSTANCE.library == null ? "Streamline is not initialized" : INSTANCE.library.lastError();
    }

    public static String variant() {
        try (InputStream stream = StreamlineRuntime.class.getResourceAsStream("/caustica/streamline.properties")) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                return normalizeVariant(properties.getProperty("variant"));
            }
        } catch (IOException exception) {
            CausticaMod.LOGGER.warn("Could not read packaged Streamline variant", exception);
        }
        return "unknown";
    }

    public static boolean productionVariant() {
        return "production".equalsIgnoreCase(variant());
    }

    /** Development behavior JSON is deliberately unsupported in normal packages. */
    public static boolean behaviorOverrideActive() {
        return false;
    }

    public static Path diagnosticsDirectory() {
        return gameDirectory().resolve("caustica-streamline");
    }

    public static int vkCreateInstance(VkInstanceCreateInfo createInfo, VkAllocationCallbacks allocator,
            PointerBuffer instanceOut) {
        if (!useVulkanProxies()) {
            return VK10.vkCreateInstance(createInfo, allocator, instanceOut);
        }
        return INSTANCE.library.vkCreateInstance(createInfo.address(), address(allocator), MemoryUtil.memAddress(instanceOut));
    }

    public static int vkCreateDevice(org.lwjgl.vulkan.VkPhysicalDevice physicalDevice,
            org.lwjgl.vulkan.VkDeviceCreateInfo createInfo, VkAllocationCallbacks allocator,
            PointerBuffer deviceOut) {
        if (!useVulkanProxies()) {
            return VK10.vkCreateDevice(physicalDevice, createInfo, allocator, deviceOut);
        }
        return INSTANCE.library.vkCreateDevice(physicalDevice.address(), createInfo.address(), address(allocator),
                MemoryUtil.memAddress(deviceOut));
    }

    public static int vkEnumeratePhysicalDevices(VkInstance instance, IntBuffer count, PointerBuffer physicalDevices) {
        if (!useVulkanProxies()) {
            return VK10.vkEnumeratePhysicalDevices(instance, count, physicalDevices);
        }
        return INSTANCE.library.vkEnumeratePhysicalDevices(instance.address(), MemoryUtil.memAddress(count),
                physicalDevices == null ? 0L : MemoryUtil.memAddress(physicalDevices));
    }

    public static int vkCreateWin32Surface(VkInstance instance, long hwnd, VkAllocationCallbacks allocator,
            LongBuffer surfaceOut) {
        if (!useVulkanProxies()) {
            return org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface(instance, hwnd, allocator, surfaceOut);
        }
        long nativeHwnd = GLFWNativeWin32.glfwGetWin32Window(hwnd);
        if (nativeHwnd == 0L) {
            CausticaMod.LOGGER.error("GLFW did not provide a Win32 HWND; refusing to mix a native surface with Streamline proxies");
            return VK10.VK_ERROR_INITIALIZATION_FAILED;
        }
        return INSTANCE.library.vkCreateWin32Surface(instance.address(), nativeHwnd, address(allocator),
                MemoryUtil.memAddress(surfaceOut));
    }

    public static void vkDestroySurface(VkInstance instance, long surface, VkAllocationCallbacks allocator) {
        if (!useVulkanProxies()) {
            org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(instance, surface, allocator);
            return;
        }
        INSTANCE.library.vkDestroySurface(instance.address(), surface, address(allocator));
    }

    public static int vkCreateSwapchain(VkDevice device, VkSwapchainCreateInfoKHR createInfo,
            VkAllocationCallbacks allocator, LongBuffer swapchainOut) {
        if (!useVulkanProxies()) {
            return KHRSwapchain.vkCreateSwapchainKHR(device, createInfo, allocator, swapchainOut);
        }
        return INSTANCE.library.vkCreateSwapchain(device.address(), createInfo.address(), address(allocator),
                MemoryUtil.memAddress(swapchainOut));
    }

    public static void vkDestroySwapchain(VkDevice device, long swapchain, VkAllocationCallbacks allocator) {
        if (!useVulkanProxies()) {
            KHRSwapchain.vkDestroySwapchainKHR(device, swapchain, allocator);
            return;
        }
        INSTANCE.library.vkDestroySwapchain(device.address(), swapchain, address(allocator));
    }

    public static int vkGetSwapchainImages(VkDevice device, long swapchain, IntBuffer count, LongBuffer images) {
        if (!useVulkanProxies()) {
            return KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, count, images);
        }
        return INSTANCE.library.vkGetSwapchainImages(device.address(), swapchain, MemoryUtil.memAddress(count),
                images == null ? 0L : MemoryUtil.memAddress(images));
    }

    public static int vkAcquireNextImage(VkDevice device, long swapchain, long timeout, long semaphore, long fence,
            IntBuffer imageIndex) {
        if (!useVulkanProxies()) {
            return KHRSwapchain.vkAcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, imageIndex);
        }
        return INSTANCE.library.vkAcquireNextImage(device.address(), swapchain, timeout, semaphore, fence,
                MemoryUtil.memAddress(imageIndex));
    }

    public static int vkQueuePresent(VkQueue queue, VkPresentInfoKHR presentInfo) {
        if (!useVulkanProxies()) {
            return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
        }
        return INSTANCE.library.vkQueuePresent(queue.address(), presentInfo.address());
    }

    public static int vkDeviceWaitIdle(VkDevice device) {
        if (!useVulkanProxies()) {
            return VK10.vkDeviceWaitIdle(device);
        }
        return INSTANCE.library.vkDeviceWaitIdle(device.address());
    }

    /** Called immediately before a swapchain is created or recreated. */
    public static boolean prepareSwapchain(boolean requestedFrameGeneration) {
        if (!useVulkanProxies()) {
            return false;
        }
        INSTANCE.featureLoaded = INSTANCE.library.isFeatureLoaded(FEATURE_DLSS_G);
        boolean desiredLoaded = requestedFrameGeneration;
        if (INSTANCE.featureLoaded != desiredLoaded) {
            int result = INSTANCE.library.setFeatureLoaded(FEATURE_DLSS_G, desiredLoaded);
            if (result != 0) {
                CausticaMod.LOGGER.warn("Streamline could not {} DLSS-G before swapchain creation: {}",
                        desiredLoaded ? "load" : "unload", INSTANCE.library.lastError());
                return false;
            }
            INSTANCE.featureLoaded = desiredLoaded;
        }
        return true;
    }

    public static int supportsFeature(int feature, long physicalDevice) {
        return INSTANCE.library == null ? -1 : INSTANCE.library.supportsFeature(feature, physicalDevice);
    }

    public static StreamlineLibrary library() {
        return INSTANCE.library;
    }

    public static void shutdown() {
        synchronized (INSTANCE) {
            if (INSTANCE.library != null) {
                try {
                    INSTANCE.library.shutdown();
                } catch (Throwable throwable) {
                    CausticaMod.LOGGER.warn("Streamline shutdown failed", throwable);
                }
            }
            INSTANCE.library = null;
            INSTANCE.nativeDirectory = null;
            INSTANCE.initialized = false;
            INSTANCE.failed = false;
            INSTANCE.featureLoaded = false;
        }
    }

    private synchronized boolean initialize() {
        if (initialized) {
            return true;
        }
        if (failed || !isWindowsX64()) {
            return false;
        }
        boolean bridgeInitialized = false;
        try {
            String packagedVariant = variant();
            if ("unknown".equals(packagedVariant)) {
                throw new IllegalStateException("Streamline variant metadata is missing or invalid");
            }
            nativeDirectory = locateNativeDirectory();
            if (nativeDirectory == null) {
                throw new IllegalStateException("Streamline Windows x64 natives are not bundled");
            }
            Path bridge = nativeDirectory.resolve("streamlinebridge.dll");
            if (!Files.isRegularFile(bridge)) {
                throw new IllegalStateException("Missing Streamline bridge " + bridge);
            }
            library = StreamlineLibrary.load(bridge);
            StreamlineAbi.validate(library);
            int variant = "production".equals(packagedVariant) ? VARIANT_PRODUCTION : VARIANT_DEVELOPMENT;
            int applicationId = applicationId(variant);
            Path logDirectory = gameDirectory().resolve("caustica-streamline");
            Files.createDirectories(logDirectory);
            try (Arena arena = Arena.ofConfined()) {
                int result = library.initialize(StreamlineLibrary.utf16(arena, nativeDirectory.toString()),
                        StreamlineLibrary.utf16(arena, logDirectory.toString()), applicationId, variant);
                if (result != 0) {
                    throw new IllegalStateException("slInit failed: 0x" + Integer.toHexString(result)
                            + " " + library.lastError());
                }
            }
            bridgeInitialized = true;
            featureLoaded = library.isFeatureLoaded(FEATURE_DLSS_G);
            initialized = true;
            CausticaMod.LOGGER.info("Streamline 2.12.0 initialized ({}; variant={})", nativeDirectory, variant == 0 ? "development" : "production");
            return true;
        } catch (Throwable throwable) {
            boolean rolledBack = true;
            if (bridgeInitialized && library != null) {
                try {
                    rolledBack = library.shutdown() == 0;
                } catch (Throwable shutdownFailure) {
                    throwable.addSuppressed(shutdownFailure);
                    rolledBack = false;
                }
            }
            initialized = false;
            featureLoaded = false;
            failed = true;
            library = null;
            CausticaMod.LOGGER.warn("Streamline initialization failed; Vulkan presentation will remain native", throwable);
            if (!rolledBack) {
                throw new IllegalStateException(
                        "Streamline initialized but could not roll back; refusing to mix proxy and native Vulkan", throwable);
            }
            return false;
        }
    }

    private static int applicationId(int variant) {
        String value = System.getenv("NVIDIA_APPLICATION_ID");
        // The bridge always supplies Caustica's stable projectId, custom-engine identity, and version.
        // Streamline/NGX therefore supports zero here when no separately assigned numeric application ID
        // exists; deployments that have one can still provide it explicitly.
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0 || parsed > 0xFFFFFFFFL) {
                throw new NumberFormatException("out of uint32 range");
            }
            return (int) parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("NVIDIA_APPLICATION_ID must be an unsigned integer", exception);
        }
    }

    private static Path locateNativeDirectory() throws IOException {
        String override = System.getProperty("caustica.streamline.path", "").trim();
        if (!override.isEmpty()) {
            if (productionVariant()) {
                throw new IllegalStateException(
                        "Production Streamline refuses caustica.streamline.path; use packaged natives");
            }
            Path path = Path.of(override);
            return Files.isDirectory(path) ? path : null;
        }
        String packagedVariant = variant();
        Path directory = packagedNativeDirectory(gameDirectory(), packagedVariant);
        Files.createDirectories(directory);
        // Older development builds wrote behavior-changing plugin JSON beside the DLLs. A package never
        // creates those files, and extraction removes stale copies before Streamline sees this directory.
        removeStaleBehaviorConfiguration(directory);
        boolean complete = true;
        for (String name : WINDOWS_NATIVE_NAMES) {
            complete &= extractBundled(name, directory.resolve(name));
        }
        return complete ? directory : null;
    }

    private static boolean extractBundled(String name, Path destination) throws IOException {
        try (InputStream stream = StreamlineRuntime.class.getResourceAsStream(
                "/caustica/natives/windows-x64/" + name)) {
            if (stream == null) {
                return false;
            }
            byte[] bytes = stream.readAllBytes();
            if (!sameBytes(destination, bytes)) {
                Files.write(destination, bytes);
            }
            return true;
        }
    }

    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private static Path gameDirectory() {
        try {
            return FabricLoader.getInstance().getGameDir();
        } catch (Throwable ignored) {
            return Path.of("run");
        }
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    static Path packagedNativeDirectory(Path gameDirectory, String packagedVariant) {
        return gameDirectory.resolve("caustica-streamline").resolve("natives")
                .resolve("windows-x64").resolve(normalizeVariant(packagedVariant));
    }

    static void removeStaleBehaviorConfiguration(Path pluginDirectory) throws IOException {
        Files.deleteIfExists(pluginDirectory.resolve("sl.dlss_g.json"));
    }

    private static String normalizeVariant(String value) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "production".equals(normalized) || "development".equals(normalized)
                ? normalized : "unknown";
    }

    private static boolean vulkanAvailabilityProbeActive() {
        Integer depth = VULKAN_AVAILABILITY_PROBE_DEPTH.get();
        return depth != null && depth > 0;
    }

    private static long address(VkAllocationCallbacks allocator) {
        return allocator == null ? 0L : allocator.address();
    }
}
