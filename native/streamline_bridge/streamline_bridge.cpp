#include <windows.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#define VK_USE_PLATFORM_WIN32_KHR 1
#include <vulkan/vulkan.h>

#include "sl.h"
#include "sl_consts.h"
#include "sl_core_api.h"
#include "sl_core_types.h"
#include "sl_dlss_d.h"
#include "sl_dlss_g.h"
#include "sl_helpers_vk.h"
#include "sl_pcl.h"
#include "sl_reflex.h"
#include "sl_security.h"
#include "sl_version.h"

#include "streamline_bridge.h"
#include "streamline_bridge_resources.h"

namespace {

static_assert(SL_VERSION_MAJOR == 2 && SL_VERSION_MINOR == 12 && SL_VERSION_PATCH == 0,
        "The native bridge must be compiled against Streamline SDK 2.12.0");
static_assert(sl::kSDKVersionMagic == 0xfedc, "Unexpected Streamline SDK version magic");
static_assert(sl::DLSSGOptions::s_structType.data1 == 0xfac5f1cb &&
        sl::DLSSGOptions::s_structType.data2 == 0x2dfd &&
        sl::DLSSGOptions::s_structType.data3 == 0x4f36,
        "DLSSGOptions ABI GUID changed");
static_assert(sl::DLSSGState::s_structType.data1 == 0xcc8ac8e1 &&
        sl::DLSSGState::s_structType.data2 == 0xa179 &&
        sl::DLSSGState::s_structType.data3 == 0x44f5,
        "DLSSGState ABI GUID changed");
static_assert(sl::DLSSDOptions::s_structType.data1 == 0x0ad87504 &&
        sl::DLSSDOptions::s_structType.data2 == 0x774e &&
        sl::DLSSDOptions::s_structType.data3 == 0x4bf3,
        "DLSSDOptions ABI GUID changed");
static_assert(sl::Constants::s_structType.data1 == 0xdcd35ad7 &&
        sl::Constants::s_structType.data2 == 0x4e4a &&
        sl::Constants::s_structType.data3 == 0x4bad,
        "Constants ABI GUID changed");
static_assert(sl::kStructVersion5 == 5, "Streamline structure-version constants changed");
static_assert(sizeof(sl::float4x4) == sizeof(float) * 16, "Streamline matrix ABI changed");
static_assert(sizeof(sl::float3) == sizeof(float) * 3, "Streamline float3 ABI changed");
static_assert(static_cast<uint32_t>(sl::PCLMarker::eSimulationStart) == SLBRIDGE_PCL_SIMULATION_START &&
        static_cast<uint32_t>(sl::PCLMarker::eSimulationEnd) == SLBRIDGE_PCL_SIMULATION_END &&
        static_cast<uint32_t>(sl::PCLMarker::eRenderSubmitStart) == SLBRIDGE_PCL_RENDER_SUBMIT_START &&
        static_cast<uint32_t>(sl::PCLMarker::eRenderSubmitEnd) == SLBRIDGE_PCL_RENDER_SUBMIT_END &&
        static_cast<uint32_t>(sl::PCLMarker::ePresentStart) == SLBRIDGE_PCL_PRESENT_START &&
        static_cast<uint32_t>(sl::PCLMarker::ePresentEnd) == SLBRIDGE_PCL_PRESENT_END &&
        static_cast<uint32_t>(sl::PCLMarker::eTriggerFlash) == SLBRIDGE_PCL_TRIGGER_FLASH,
        "Streamline PCL marker ABI changed");

using FnSlInit = PFun_slInit*;
using FnSlShutdown = PFun_slShutdown*;
using FnSlIsFeatureSupported = PFun_slIsFeatureSupported*;
using FnSlGetFeatureRequirements = PFun_slGetFeatureRequirements*;
using FnSlGetFeatureVersion = PFun_slGetFeatureVersion*;
using FnSlSetFeatureLoaded = PFun_slSetFeatureLoaded*;
using FnSlSetTagForFrame = PFun_slSetTagForFrame*;
using FnSlSetConstants = PFun_slSetConstants*;
using FnSlGetNewFrameToken = PFun_slGetNewFrameToken*;
using FnSlGetFeatureFunction = PFun_slGetFeatureFunction*;
using FnSlEvaluateFeature = PFun_slEvaluateFeature*;
using FnSlFreeResources = PFun_slFreeResources*;

using FnVkCreateInstance = PFN_vkCreateInstance;
using FnVkCreateDevice = PFN_vkCreateDevice;
using FnVkCreateWin32SurfaceKHR = PFN_vkCreateWin32SurfaceKHR;
using FnVkDestroySurfaceKHR = PFN_vkDestroySurfaceKHR;
using FnVkCreateSwapchainKHR = PFN_vkCreateSwapchainKHR;
using FnVkDestroySwapchainKHR = PFN_vkDestroySwapchainKHR;
using FnVkGetSwapchainImagesKHR = PFN_vkGetSwapchainImagesKHR;
using FnVkAcquireNextImageKHR = PFN_vkAcquireNextImageKHR;
using FnVkQueuePresentKHR = PFN_vkQueuePresentKHR;
using FnVkDeviceWaitIdle = PFN_vkDeviceWaitIdle;
using FnVkGetInstanceProcAddr = PFN_vkGetInstanceProcAddr;
using FnVkGetDeviceProcAddr = PFN_vkGetDeviceProcAddr;


HMODULE g_interposer{};
FnSlInit g_slInit{};
FnSlShutdown g_slShutdown{};
FnSlIsFeatureSupported g_slIsFeatureSupported{};
FnSlGetFeatureRequirements g_slGetFeatureRequirements{};
FnSlGetFeatureVersion g_slGetFeatureVersion{};
FnSlSetFeatureLoaded g_slSetFeatureLoaded{};
FnSlSetTagForFrame g_slSetTagForFrame{};
FnSlSetConstants g_slSetConstants{};
FnSlGetNewFrameToken g_slGetNewFrameToken{};
FnSlGetFeatureFunction g_slGetFeatureFunction{};
FnSlEvaluateFeature g_slEvaluateFeature{};
FnSlFreeResources g_slFreeResources{};

FnVkGetInstanceProcAddr g_vkGetInstanceProcAddr{};
FnVkGetDeviceProcAddr g_vkGetDeviceProcAddr{};

VkInstance g_instance{};
VkPhysicalDevice g_physicalDevice{};
VkDevice g_device{};
std::filesystem::path g_pluginDirectory;
bool g_initialized = false;
bool g_production = false;
bool g_dlssgLoaded = false;
bool g_dlssgSupportCached = false;
int32_t g_dlssgSupportResult = -1;
std::atomic<int32_t> g_lastResult{0};
std::mutex g_errorMutex;
std::string g_lastError;
std::atomic<int32_t> g_asyncApiError{VK_SUCCESS};
std::mutex g_traceMutex;
slbridge_trace_state g_trace{};

void setError(std::string message, int32_t result = -1) {
    std::lock_guard lock(g_errorMutex);
    g_lastResult = result;
    g_lastError = std::move(message);
}

template <typename T>
T loadExport(const char* name) {
    return reinterpret_cast<T>(GetProcAddress(g_interposer, name));
}

int32_t resultCode(sl::Result result, const char* operation) {
    const int32_t value = static_cast<int32_t>(result);
    if (result != sl::Result::eOk) {
        std::ostringstream message;
        message << operation << " failed (Streamline result 0x" << std::hex << value << ")";
        setError(message.str(), value);
    } else {
        g_lastResult = value;
    }
    return value;
}

int32_t vkResult(VkResult result, const char* operation) {
    const int32_t value = static_cast<int32_t>(result);
    if (result != VK_SUCCESS) {
        std::ostringstream message;
        message << operation << " failed (VkResult " << std::dec << value << ")";
        setError(message.str(), value);
    } else {
        g_lastResult = value;
    }
    return value;
}

void logMessage(sl::LogType type, const char* message) {
    if (type == sl::LogType::eWarn || type == sl::LogType::eError) {
        setError(message ? message : "Streamline reported an empty diagnostic");
    }
}

void apiError(const sl::APIError& error) {
    // Called on Streamline's present thread. Never allocate, log, or take a lock here.
    g_asyncApiError.store(error.vkRes, std::memory_order_release);
}

template <typename T>
T instanceProc(VkInstance instance, const char* name) {
    return g_vkGetInstanceProcAddr ? reinterpret_cast<T>(g_vkGetInstanceProcAddr(instance, name)) : nullptr;
}

template <typename T>
T deviceProc(VkDevice device, const char* name) {
    if (!g_vkGetDeviceProcAddr && g_vkGetInstanceProcAddr) {
        g_vkGetDeviceProcAddr = reinterpret_cast<FnVkGetDeviceProcAddr>(
                g_vkGetInstanceProcAddr(g_instance, "vkGetDeviceProcAddr"));
    }
    return g_vkGetDeviceProcAddr ? reinterpret_cast<T>(g_vkGetDeviceProcAddr(device, name)) : nullptr;
}

sl::DLSSGMode mapDlssgMode(uint32_t mode) {
    switch (mode) {
    case SLBRIDGE_DLSSG_FIXED:
        return sl::DLSSGMode::eOn;
    case SLBRIDGE_DLSSG_AUTO:
        return sl::DLSSGMode::eAuto;
    case SLBRIDGE_DLSSG_DYNAMIC:
        return sl::DLSSGMode::eDynamic;
    default:
        return sl::DLSSGMode::eOff;
    }
}

sl::ReflexMode mapReflexMode(uint32_t mode) {
    switch (mode) {
    case SLBRIDGE_REFLEX_ON:
        return sl::eLowLatency;
    case SLBRIDGE_REFLEX_ON_BOOST:
        return sl::eLowLatencyWithBoost;
    default:
        return sl::eOff;
    }
}

sl::DLSSMode mapDlssdMode(uint32_t mode) {
    if (mode >= static_cast<uint32_t>(sl::DLSSMode::eMaxPerformance)
            && mode <= static_cast<uint32_t>(sl::DLSSMode::eDLAA)) {
        return static_cast<sl::DLSSMode>(mode);
    }
    return sl::DLSSMode::eOff;
}

sl::DLSSDPreset mapDlssdPreset(uint32_t preset) {
    if (preset == static_cast<uint32_t>(sl::DLSSDPreset::eDefault)
            || (preset >= static_cast<uint32_t>(sl::DLSSDPreset::ePresetD)
                    && preset < static_cast<uint32_t>(sl::DLSSDPreset::eCount))) {
        return static_cast<sl::DLSSDPreset>(preset);
    }
    return sl::DLSSDPreset::eDefault;
}

sl::PCLMarker mapPclMarker(uint32_t marker) {
    return static_cast<sl::PCLMarker>(marker);
}

sl::FrameToken* tokenFromHandle(uint64_t token) {
    return reinterpret_cast<sl::FrameToken*>(static_cast<uintptr_t>(token));
}

void copyMatrix(sl::float4x4& destination, const float* source) {
    for (uint32_t row = 0; row < 4; row++) {
        destination[row] = sl::float4(source[row * 4], source[row * 4 + 1],
                source[row * 4 + 2], source[row * 4 + 3]);
    }
}

void copyVector(sl::float3& destination, const float* source) {
    destination = sl::float3(source[0], source[1], source[2]);
}

sl::Constants makeConstants(const slbridge_constants& constants) {
    sl::Constants values{};
    copyMatrix(values.cameraViewToClip, constants.camera_view_to_clip);
    copyMatrix(values.clipToCameraView, constants.clip_to_camera_view);
    copyMatrix(values.clipToLensClip, constants.clip_to_lens_clip);
    copyMatrix(values.clipToPrevClip, constants.clip_to_prev_clip);
    copyMatrix(values.prevClipToClip, constants.prev_clip_to_clip);
    values.jitterOffset = sl::float2(constants.jitter_offset[0], constants.jitter_offset[1]);
    values.mvecScale = sl::float2(constants.mvec_scale[0], constants.mvec_scale[1]);
    values.cameraPinholeOffset = sl::float2(constants.camera_pinhole_offset[0], constants.camera_pinhole_offset[1]);
    copyVector(values.cameraPos, constants.camera_pos);
    copyVector(values.cameraUp, constants.camera_up);
    copyVector(values.cameraRight, constants.camera_right);
    copyVector(values.cameraFwd, constants.camera_forward);
    values.cameraNear = constants.camera_near;
    values.cameraFar = constants.camera_far;
    values.cameraFOV = constants.camera_fov;
    values.cameraAspectRatio = constants.camera_aspect_ratio;
    values.motionVectorsInvalidValue = constants.motion_vectors_invalid_value;
    values.depthInverted = static_cast<sl::Boolean>(constants.depth_inverted);
    values.cameraMotionIncluded = static_cast<sl::Boolean>(constants.camera_motion_included);
    values.motionVectors3D = static_cast<sl::Boolean>(constants.motion_vectors_3d);
    values.reset = static_cast<sl::Boolean>(constants.reset);
    values.orthographicProjection = static_cast<sl::Boolean>(constants.orthographic_projection);
    values.motionVectorsDilated = static_cast<sl::Boolean>(constants.motion_vectors_dilated);
    values.motionVectorsJittered = static_cast<sl::Boolean>(constants.motion_vectors_jittered);
    values.minRelativeLinearDepthObjectSeparation = constants.min_relative_linear_depth_object_separation;
    return values;
}

sl::DLSSDOptions makeDlssdOptions(uint32_t mode, uint32_t outputWidth, uint32_t outputHeight,
        uint32_t preset, const float* worldToCameraView = nullptr,
        const float* cameraViewToWorld = nullptr) {
    sl::DLSSDOptions options{};
    options.mode = mapDlssdMode(mode);
    options.outputWidth = outputWidth;
    options.outputHeight = outputHeight;
    options.sharpness = 0.0f;
    options.preExposure = 1.0f;
    options.exposureScale = 1.0f;
    options.colorBuffersHDR = sl::Boolean::eTrue;
    options.indicatorInvertAxisX = sl::Boolean::eFalse;
    options.indicatorInvertAxisY = sl::Boolean::eTrue;
    options.normalRoughnessMode = sl::DLSSDNormalRoughnessMode::ePacked;
    options.alphaUpscalingEnabled = sl::Boolean::eFalse;
    if (worldToCameraView && cameraViewToWorld) {
        copyMatrix(options.worldToCameraView, worldToCameraView);
        copyMatrix(options.cameraViewToWorld, cameraViewToWorld);
    }
    const auto mappedPreset = mapDlssdPreset(preset);
    options.dlaaPreset = mappedPreset;
    options.qualityPreset = mappedPreset;
    options.balancedPreset = mappedPreset;
    options.performancePreset = mappedPreset;
    options.ultraPerformancePreset = mappedPreset;
    options.ultraQualityPreset = mappedPreset;
    return options;
}

std::wstring pluginPath(const wchar_t* path) {
    return path ? std::wstring(path) : std::wstring();
}

bool verifyProductionBinaries(const std::filesystem::path& directory) {
    // Streamline's custom embedded-signature verifier applies to the interposer. Official NGX and
    // low-latency payloads carry ordinary Authenticode signatures, not Streamline's secondary
    // certificate; applying verifyEmbeddedSignature to those official binaries rejects every valid
    // production package. Gradle verifies Authenticode across the complete packaged DLL set.
    constexpr std::array<const wchar_t*, 1> files = { L"sl.interposer.dll" };
    for (const wchar_t* file : files) {
        const auto path = directory / file;
        if (!std::filesystem::is_regular_file(path) || !sl::security::verifyEmbeddedSignature(path.c_str())) {
            setError("Production Streamline binary failed NVIDIA signature verification: " + path.string());
            return false;
        }
    }
    return true;
}

} // namespace

extern "C" {

SLBRIDGE_EXPORT int32_t slbridge_get_abi_info(slbridge_abi_info* out_info) {
    if (!out_info) {
        return -1;
    }
    *out_info = {
        SLBRIDGE_ABI_VERSION,
        static_cast<uint32_t>(sizeof(slbridge_resource_desc)),
        static_cast<uint32_t>(sizeof(slbridge_constants)),
        static_cast<uint32_t>(sizeof(slbridge_dlssd_options)),
        static_cast<uint32_t>(sizeof(slbridge_dlssg_options)),
        static_cast<uint32_t>(sizeof(slbridge_dlssg_state)),
        static_cast<uint32_t>(sizeof(slbridge_reflex_options)),
        static_cast<uint32_t>(sizeof(slbridge_reflex_state)),
        static_cast<uint32_t>(sizeof(slbridge_trace_state)),
    };
    return 0;
}

SLBRIDGE_EXPORT int32_t slbridge_initialize(const wchar_t* plugin_directory,
        const wchar_t* log_directory, uint32_t application_id, uint32_t variant) {
    if (g_initialized) {
        return 0;
    }
    if (!plugin_directory || !*plugin_directory) {
        setError("Streamline plugin directory is empty");
        return -1;
    }

    g_pluginDirectory = std::filesystem::path(pluginPath(plugin_directory));
    g_production = variant == SLBRIDGE_VARIANT_PRODUCTION;
    if (g_production && !verifyProductionBinaries(g_pluginDirectory)) {
        return -1;
    }

    const auto interposerPath = g_pluginDirectory / L"sl.interposer.dll";
    g_interposer = LoadLibraryExW(interposerPath.c_str(), nullptr,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!g_interposer) {
        setError("Could not load " + interposerPath.string());
        return -1;
    }

    g_slInit = loadExport<FnSlInit>("slInit");
    g_slShutdown = loadExport<FnSlShutdown>("slShutdown");
    g_slIsFeatureSupported = loadExport<FnSlIsFeatureSupported>("slIsFeatureSupported");
    g_slGetFeatureRequirements = loadExport<FnSlGetFeatureRequirements>("slGetFeatureRequirements");
    g_slGetFeatureVersion = loadExport<FnSlGetFeatureVersion>("slGetFeatureVersion");
    g_slSetFeatureLoaded = loadExport<FnSlSetFeatureLoaded>("slSetFeatureLoaded");
    g_slSetTagForFrame = loadExport<FnSlSetTagForFrame>("slSetTagForFrame");
    g_slSetConstants = loadExport<FnSlSetConstants>("slSetConstants");
    g_slGetNewFrameToken = loadExport<FnSlGetNewFrameToken>("slGetNewFrameToken");
    g_slGetFeatureFunction = loadExport<FnSlGetFeatureFunction>("slGetFeatureFunction");
    g_slEvaluateFeature = loadExport<FnSlEvaluateFeature>("slEvaluateFeature");
    g_slFreeResources = loadExport<FnSlFreeResources>("slFreeResources");
    g_vkGetInstanceProcAddr = loadExport<FnVkGetInstanceProcAddr>("vkGetInstanceProcAddr");
    g_vkGetDeviceProcAddr = loadExport<FnVkGetDeviceProcAddr>("vkGetDeviceProcAddr");

    if (!g_slInit || !g_slShutdown || !g_slIsFeatureSupported || !g_slGetFeatureRequirements
            || !g_slGetFeatureVersion || !g_slSetFeatureLoaded || !g_slSetTagForFrame
            || !g_slSetConstants || !g_slGetNewFrameToken
            || !g_slGetFeatureFunction || !g_slEvaluateFeature || !g_slFreeResources
            || !g_vkGetInstanceProcAddr || !g_vkGetDeviceProcAddr) {
        setError("sl.interposer.dll is missing a required Streamline or Vulkan export");
        FreeLibrary(g_interposer);
        g_interposer = nullptr;
        return -1;
    }

    const std::wstring pluginDirectoryString = g_pluginDirectory.wstring();
    const wchar_t* pluginDirectories[] = { pluginDirectoryString.c_str() };
    const std::wstring logDirectoryString = log_directory ? std::wstring(log_directory) : std::wstring();
    static const char engineVersion[] = "Caustica/0.1.0";
    static const char projectId[] = "b6f1e9c2-7a44-4d1e-9b3a-1f2c3d4e5a6b";
    const sl::Feature features[] = {
        sl::kFeatureDLSS_RR, sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL,
    };

    sl::Preferences preferences{};
    // Keep development diagnostics in the configured log directory and callback. A native console is
    // disruptive for a GUI-hosted game and must never be part of Caustica's runtime surface.
    preferences.showConsole = false;
    preferences.logLevel = g_production ? sl::LogLevel::eDefault : sl::LogLevel::eVerbose;
    preferences.pathsToPlugins = pluginDirectories;
    preferences.numPathsToPlugins = 1;
    preferences.pathToLogsAndData = logDirectoryString.empty() ? nullptr : logDirectoryString.c_str();
    preferences.logMessageCallback = &logMessage;
    preferences.flags = sl::PreferenceFlags::eDisableCLStateTracking
            | sl::PreferenceFlags::eUseManualHooking
            | sl::PreferenceFlags::eUseFrameBasedResourceTagging;
    preferences.featuresToLoad = features;
    preferences.numFeaturesToLoad = static_cast<uint32_t>(std::size(features));
    preferences.applicationId = application_id;
    preferences.engine = sl::EngineType::eCustom;
    preferences.engineVersion = engineVersion;
    preferences.projectId = projectId;
    preferences.renderAPI = sl::RenderAPI::eVulkan;

    const int32_t result = resultCode(g_slInit(preferences, sl::kSDKVersion), "slInit");
    if (result != static_cast<int32_t>(sl::Result::eOk)) {
        FreeLibrary(g_interposer);
        g_interposer = nullptr;
        return result;
    }
    g_initialized = true;
    g_dlssgLoaded = true;
    g_dlssgSupportCached = false;
    g_asyncApiError.store(VK_SUCCESS, std::memory_order_release);
    return 0;
}

SLBRIDGE_EXPORT int32_t slbridge_shutdown(void) {
    if (!g_initialized) {
        return 0;
    }
    const int32_t result = resultCode(g_slShutdown(), "slShutdown");
    g_initialized = false;
    g_dlssgLoaded = false;
    g_dlssgSupportCached = false;
    g_instance = VK_NULL_HANDLE;
    g_physicalDevice = VK_NULL_HANDLE;
    g_device = VK_NULL_HANDLE;
    g_vkGetDeviceProcAddr = loadExport<FnVkGetDeviceProcAddr>("vkGetDeviceProcAddr");
    if (g_interposer) {
        FreeLibrary(g_interposer);
        g_interposer = nullptr;
    }
    g_slInit = nullptr;
    g_slShutdown = nullptr;
    g_slIsFeatureSupported = nullptr;
    g_slGetFeatureRequirements = nullptr;
    g_slGetFeatureVersion = nullptr;
    g_slSetFeatureLoaded = nullptr;
    g_slSetTagForFrame = nullptr;
    g_slSetConstants = nullptr;
    g_slGetNewFrameToken = nullptr;
    g_slGetFeatureFunction = nullptr;
    g_slEvaluateFeature = nullptr;
    g_slFreeResources = nullptr;
    g_vkGetInstanceProcAddr = nullptr;
    g_vkGetDeviceProcAddr = nullptr;
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_is_initialized(void) {
    return g_initialized ? 1 : 0;
}

SLBRIDGE_EXPORT int32_t slbridge_last_result(void) {
    return g_lastResult.load(std::memory_order_acquire);
}

SLBRIDGE_EXPORT const char* slbridge_last_error(void) {
    thread_local std::string errorCopy;
    std::lock_guard lock(g_errorMutex);
    errorCopy = g_lastError;
    return errorCopy.c_str();
}

SLBRIDGE_EXPORT int32_t slbridge_poll_api_error(int32_t* out_vk_result) {
    if (!out_vk_result) {
        return -1;
    }
    *out_vk_result = g_asyncApiError.exchange(VK_SUCCESS, std::memory_order_acq_rel);
    return *out_vk_result == VK_SUCCESS ? 0 : 1;
}

SLBRIDGE_EXPORT int32_t slbridge_get_trace_state(slbridge_trace_state* out_state) {
    if (!out_state) {
        return -1;
    }
    std::lock_guard lock(g_traceMutex);
    *out_state = g_trace;
    return 0;
}

SLBRIDGE_EXPORT int32_t slbridge_vk_create_instance(uint64_t create_info, uint64_t allocator,
        uint64_t instance_out) {
    if (!g_initialized || !g_vkGetInstanceProcAddr || !instance_out) {
        setError("Streamline vkCreateInstance requested before initialization");
        return -1;
    }
    auto function = instanceProc<FnVkCreateInstance>(VK_NULL_HANDLE, "vkCreateInstance");
    if (!function) {
        setError("Streamline did not provide the vkCreateInstance proxy");
        return -1;
    }
    const VkResult result = function(reinterpret_cast<const VkInstanceCreateInfo*>(create_info),
            reinterpret_cast<const VkAllocationCallbacks*>(allocator), reinterpret_cast<VkInstance*>(instance_out));
    if (result == VK_SUCCESS) {
        g_instance = *reinterpret_cast<VkInstance*>(instance_out);
    }
    return vkResult(result, "vkCreateInstance");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_enumerate_physical_devices(uint64_t instance, uint64_t count,
        uint64_t physical_devices) {
    auto function = instanceProc<PFN_vkEnumeratePhysicalDevices>(reinterpret_cast<VkInstance>(instance),
            "vkEnumeratePhysicalDevices");
    if (!function || !count) {
        setError("Streamline did not provide vkEnumeratePhysicalDevices");
        return -1;
    }
    const VkResult result = function(reinterpret_cast<VkInstance>(instance),
            reinterpret_cast<uint32_t*>(count), reinterpret_cast<VkPhysicalDevice*>(physical_devices));
    return vkResult(result, "vkEnumeratePhysicalDevices");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_create_device(uint64_t physical_device, uint64_t create_info,
        uint64_t allocator, uint64_t device_out) {
    if (!g_initialized || !g_instance || !device_out) {
        setError("Streamline vkCreateDevice requested before instance initialization");
        return -1;
    }
    auto function = instanceProc<FnVkCreateDevice>(g_instance, "vkCreateDevice");
    if (!function) {
        setError("Streamline did not provide the vkCreateDevice proxy");
        return -1;
    }
    const VkResult result = function(reinterpret_cast<VkPhysicalDevice>(physical_device),
            reinterpret_cast<const VkDeviceCreateInfo*>(create_info),
            reinterpret_cast<const VkAllocationCallbacks*>(allocator), reinterpret_cast<VkDevice*>(device_out));
    if (result == VK_SUCCESS) {
        g_physicalDevice = reinterpret_cast<VkPhysicalDevice>(physical_device);
        g_device = *reinterpret_cast<VkDevice*>(device_out);
        sl::AdapterInfo adapter{};
        adapter.vkPhysicalDevice = reinterpret_cast<void*>(physical_device);
        g_dlssgSupportResult = static_cast<int32_t>(g_slIsFeatureSupported(sl::kFeatureDLSS_G, adapter));
        g_dlssgSupportCached = true;
    }
    return vkResult(result, "vkCreateDevice");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_create_win32_surface(uint64_t instance, uint64_t hwnd,
        uint64_t allocator, uint64_t surface_out) {
    auto function = instanceProc<FnVkCreateWin32SurfaceKHR>(reinterpret_cast<VkInstance>(instance),
            "vkCreateWin32SurfaceKHR");
    if (!function || !surface_out || !hwnd) {
        setError("Streamline did not provide vkCreateWin32SurfaceKHR or the GLFW HWND was null");
        return -1;
    }
    VkWin32SurfaceCreateInfoKHR createInfo{ VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR };
    createInfo.hinstance = reinterpret_cast<HINSTANCE>(GetWindowLongPtrW(reinterpret_cast<HWND>(hwnd), GWLP_HINSTANCE));
    if (!createInfo.hinstance) {
        createInfo.hinstance = GetModuleHandleW(nullptr);
    }
    createInfo.hwnd = reinterpret_cast<HWND>(hwnd);
    const VkResult result = function(reinterpret_cast<VkInstance>(instance), &createInfo,
            reinterpret_cast<const VkAllocationCallbacks*>(allocator), reinterpret_cast<VkSurfaceKHR*>(surface_out));
    return vkResult(result, "vkCreateWin32SurfaceKHR");
}

SLBRIDGE_EXPORT void slbridge_vk_destroy_surface(uint64_t instance, uint64_t surface, uint64_t allocator) {
    auto function = instanceProc<FnVkDestroySurfaceKHR>(reinterpret_cast<VkInstance>(instance),
            "vkDestroySurfaceKHR");
    if (function) {
        function(reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkSurfaceKHR>(surface),
                reinterpret_cast<const VkAllocationCallbacks*>(allocator));
    } else {
        setError("Streamline did not provide vkDestroySurfaceKHR");
    }
}

SLBRIDGE_EXPORT int32_t slbridge_vk_create_swapchain(uint64_t device, uint64_t create_info,
        uint64_t allocator, uint64_t swapchain_out) {
    const auto* info = reinterpret_cast<const VkSwapchainCreateInfoKHR*>(create_info);
    auto function = deviceProc<FnVkCreateSwapchainKHR>(reinterpret_cast<VkDevice>(device),
            "vkCreateSwapchainKHR");
    if (!function || !swapchain_out) {
        {
            std::lock_guard lock(g_traceMutex);
            g_trace.last_swapchain_handle = 0;
            g_trace.last_swapchain_present_mode = info ? static_cast<uint32_t>(info->presentMode) : 0;
            g_trace.last_swapchain_min_image_count = info ? info->minImageCount : 0;
            g_trace.last_swapchain_image_count = 0;
            g_trace.last_swapchain_create_result = VK_ERROR_INITIALIZATION_FAILED;
            g_trace.last_swapchain_proxy_dispatch = function ? 1u : 0u;
            g_trace.last_swapchain_present_mode_known = info ? 1u : 0u;
        }
        setError("Streamline did not provide vkCreateSwapchainKHR");
        return -1;
    }
    const VkResult result = function(reinterpret_cast<VkDevice>(device),
            info, reinterpret_cast<const VkAllocationCallbacks*>(allocator),
            reinterpret_cast<VkSwapchainKHR*>(swapchain_out));
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.last_swapchain_handle = result == VK_SUCCESS
                ? reinterpret_cast<uint64_t>(*reinterpret_cast<VkSwapchainKHR*>(swapchain_out)) : 0;
        g_trace.last_swapchain_present_mode = info ? static_cast<uint32_t>(info->presentMode) : 0;
        g_trace.last_swapchain_min_image_count = info ? info->minImageCount : 0;
        g_trace.last_swapchain_image_count = 0;
        g_trace.last_swapchain_create_result = static_cast<int32_t>(result);
        g_trace.last_swapchain_proxy_dispatch = 1u;
        g_trace.last_swapchain_present_mode_known = info ? 1u : 0u;
    }
    return vkResult(result, "vkCreateSwapchainKHR");
}

SLBRIDGE_EXPORT void slbridge_vk_destroy_swapchain(uint64_t device, uint64_t swapchain, uint64_t allocator) {
    auto function = deviceProc<FnVkDestroySwapchainKHR>(reinterpret_cast<VkDevice>(device),
            "vkDestroySwapchainKHR");
    if (function) {
        function(reinterpret_cast<VkDevice>(device), reinterpret_cast<VkSwapchainKHR>(swapchain),
                reinterpret_cast<const VkAllocationCallbacks*>(allocator));
    } else {
        setError("Streamline did not provide vkDestroySwapchainKHR");
    }
}

SLBRIDGE_EXPORT int32_t slbridge_vk_get_swapchain_images(uint64_t device, uint64_t swapchain,
        uint64_t count, uint64_t images) {
    auto function = deviceProc<FnVkGetSwapchainImagesKHR>(reinterpret_cast<VkDevice>(device),
            "vkGetSwapchainImagesKHR");
    if (!function) {
        setError("Streamline did not provide vkGetSwapchainImagesKHR");
        return -1;
    }
    auto* imageCount = reinterpret_cast<uint32_t*>(count);
    const VkResult result = function(reinterpret_cast<VkDevice>(device),
            reinterpret_cast<VkSwapchainKHR>(swapchain), imageCount,
            reinterpret_cast<VkImage*>(images));
    if (result == VK_SUCCESS && imageCount) {
        std::lock_guard lock(g_traceMutex);
        g_trace.last_swapchain_image_count = *imageCount;
    }
    return vkResult(result, "vkGetSwapchainImagesKHR");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_acquire_next_image(uint64_t device, uint64_t swapchain,
        uint64_t timeout, uint64_t semaphore, uint64_t fence, uint64_t image_index) {
    auto function = deviceProc<FnVkAcquireNextImageKHR>(reinterpret_cast<VkDevice>(device),
            "vkAcquireNextImageKHR");
    if (!function) {
        setError("Streamline did not provide vkAcquireNextImageKHR");
        return -1;
    }
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.acquire_calls++;
    }
    return vkResult(function(reinterpret_cast<VkDevice>(device), reinterpret_cast<VkSwapchainKHR>(swapchain),
            timeout, reinterpret_cast<VkSemaphore>(semaphore), reinterpret_cast<VkFence>(fence),
            reinterpret_cast<uint32_t*>(image_index)), "vkAcquireNextImageKHR");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_queue_present(uint64_t queue, uint64_t present_info) {
    auto function = deviceProc<FnVkQueuePresentKHR>(g_device, "vkQueuePresentKHR");
    if (!function) {
        setError("Streamline did not provide vkQueuePresentKHR");
        return -1;
    }
    const auto* info = reinterpret_cast<const VkPresentInfoKHR*>(present_info);
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.present_calls++;
        g_trace.proxy_present_sequence = ++g_trace.event_sequence;
        g_trace.last_present_queue = queue;
        g_trace.last_present_wait_count = info ? info->waitSemaphoreCount : 0;
        g_trace.last_present_swapchain_count = info ? info->swapchainCount : 0;
        g_trace.last_present_swapchain = info && info->swapchainCount && info->pSwapchains
                ? reinterpret_cast<uint64_t>(info->pSwapchains[0]) : 0;
        g_trace.last_present_image_index = info && info->swapchainCount && info->pImageIndices
                ? info->pImageIndices[0] : UINT32_MAX;
    }
    return vkResult(function(reinterpret_cast<VkQueue>(queue), info),
            "vkQueuePresentKHR");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_device_wait_idle(uint64_t device) {
    auto function = deviceProc<FnVkDeviceWaitIdle>(reinterpret_cast<VkDevice>(device), "vkDeviceWaitIdle");
    if (!function) {
        setError("Streamline did not provide vkDeviceWaitIdle");
        return -1;
    }
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.device_wait_idle_calls++;
    }
    return vkResult(function(reinterpret_cast<VkDevice>(device)), "vkDeviceWaitIdle");
}

SLBRIDGE_EXPORT int32_t slbridge_vk_wait_timeline(uint64_t device, uint64_t semaphore,
        uint64_t value, uint64_t timeout_ns) {
    auto function = deviceProc<PFN_vkWaitSemaphores>(reinterpret_cast<VkDevice>(device), "vkWaitSemaphores");
    if (!function || !semaphore || !value) {
        setError("Streamline input timeline wait was missing an entry point or semaphore");
        return -1;
    }
    const VkSemaphore nativeSemaphore = reinterpret_cast<VkSemaphore>(static_cast<uintptr_t>(semaphore));
    VkSemaphoreWaitInfo waitInfo{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
    waitInfo.semaphoreCount = 1;
    waitInfo.pSemaphores = &nativeSemaphore;
    waitInfo.pValues = &value;
    const int32_t result = vkResult(function(reinterpret_cast<VkDevice>(device), &waitInfo, timeout_ns),
            "vkWaitSemaphores");
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.timeline_wait_calls++;
        g_trace.timeline_wait_failures += result == 0 ? 0 : 1;
        g_trace.last_timeline_semaphore = semaphore;
        g_trace.last_timeline_value = value;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_supports_feature(uint32_t feature, uint64_t physical_device) {
    if (!g_initialized || !g_slIsFeatureSupported) {
        setError("Streamline feature support requested before slInit");
        return -1;
    }
    if (feature == sl::kFeatureDLSS_G && g_dlssgSupportCached
            && reinterpret_cast<VkPhysicalDevice>(physical_device) == g_physicalDevice) {
        return resultCode(static_cast<sl::Result>(g_dlssgSupportResult), "slIsFeatureSupported(DLSS-G cached)");
    }
    sl::AdapterInfo adapter{};
    adapter.vkPhysicalDevice = reinterpret_cast<void*>(physical_device);
    return resultCode(g_slIsFeatureSupported(feature, adapter), "slIsFeatureSupported");
}

SLBRIDGE_EXPORT int32_t slbridge_get_feature_requirements(uint32_t feature,
        slbridge_feature_requirements* out_requirements) {
    if (!out_requirements || !g_slGetFeatureRequirements) {
        setError("Streamline feature requirements output was null");
        return -1;
    }
    sl::FeatureRequirements requirements{};
    const int32_t result = resultCode(g_slGetFeatureRequirements(feature, requirements), "slGetFeatureRequirements");
    if (result == static_cast<int32_t>(sl::Result::eOk)) {
        out_requirements->flags = static_cast<uint32_t>(requirements.flags);
        out_requirements->max_num_viewports = requirements.maxNumViewports;
        out_requirements->num_required_tags = requirements.numRequiredTags;
        out_requirements->vk_num_compute_queues_required = requirements.vkNumComputeQueuesRequired;
        out_requirements->vk_num_graphics_queues_required = requirements.vkNumGraphicsQueuesRequired;
        out_requirements->vk_num_optical_flow_queues_required = requirements.vkNumOpticalFlowQueuesRequired;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_get_feature_version(uint32_t feature,
        slbridge_feature_version* out_version) {
    if (!out_version || !g_slGetFeatureVersion) {
        setError("Streamline feature version output was null");
        return -1;
    }
    sl::FeatureVersion version{};
    const int32_t result = resultCode(g_slGetFeatureVersion(feature, version), "slGetFeatureVersion");
    if (result == static_cast<int32_t>(sl::Result::eOk)) {
        out_version->sl_major = version.versionSL.major;
        out_version->sl_minor = version.versionSL.minor;
        out_version->sl_build = version.versionSL.build;
        out_version->ngx_major = version.versionNGX.major;
        out_version->ngx_minor = version.versionNGX.minor;
        out_version->ngx_build = version.versionNGX.build;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_set_feature_loaded(uint32_t feature, uint32_t loaded) {
    if (!g_slSetFeatureLoaded) {
        setError("Streamline feature loading requested before slInit");
        return -1;
    }
    const int32_t result = resultCode(g_slSetFeatureLoaded(feature, loaded != 0), "slSetFeatureLoaded");
    if (result == static_cast<int32_t>(sl::Result::eOk) && feature == sl::kFeatureDLSS_G) {
        g_dlssgLoaded = loaded != 0;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_is_feature_loaded(uint32_t feature) {
    if (feature == sl::kFeatureDLSS_G) {
        return g_dlssgLoaded ? 1 : 0;
    }
    return g_initialized ? 1 : 0;
}

SLBRIDGE_EXPORT int32_t slbridge_begin_frame(uint32_t frame_index, uint64_t* out_frame_token) {
    if (!out_frame_token || !g_slGetNewFrameToken) {
        setError("Streamline frame-token output was null");
        return -1;
    }
    sl::FrameToken* token{};
    const uint32_t* requestedIndex = frame_index == UINT32_MAX ? nullptr : &frame_index;
    const int32_t result = resultCode(g_slGetNewFrameToken(token, requestedIndex), "slGetNewFrameToken");
    if (result == static_cast<int32_t>(sl::Result::eOk)) {
        *out_frame_token = reinterpret_cast<uint64_t>(token);
        std::lock_guard lock(g_traceMutex);
        g_trace.begin_frame_calls++;
        g_trace.begin_sequence = ++g_trace.event_sequence;
        g_trace.last_frame_index = frame_index;
        g_trace.last_frame_token = *out_frame_token;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_set_constants(uint64_t frame_token, uint32_t viewport,
        const slbridge_constants* constants) {
    if (!constants || !g_slSetConstants) {
        setError("Streamline constants were null");
        return -1;
    }
    auto* token = tokenFromHandle(frame_token);
    if (!token) {
        setError("Streamline frame token was null");
        return -1;
    }
    sl::Constants values = makeConstants(*constants);
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.set_constants_calls++;
        g_trace.constants_sequence = ++g_trace.event_sequence;
        g_trace.last_frame_token = frame_token;
    }
    return resultCode(g_slSetConstants(values, *token, sl::ViewportHandle(viewport)), "slSetConstants");
}

SLBRIDGE_EXPORT int32_t slbridge_tag_resources(uint64_t frame_token, uint32_t viewport,
        const slbridge_resource_desc* resources, uint32_t resource_count, uint64_t command_buffer) {
    if (!resources || !g_slSetTagForFrame) {
        setError("Streamline resource tags were null");
        return -1;
    }
    auto* token = tokenFromHandle(frame_token);
    if (!token) {
        setError("Streamline frame token was null");
        return -1;
    }

    std::vector<sl::Resource> nativeResources;
    std::vector<sl::Extent> extents;
    std::vector<sl::ResourceTag> tags;
    nativeResources.reserve(resource_count);
    extents.reserve(resource_count);
    tags.reserve(resource_count);
    uint32_t validMask = 0;
    uint32_t memoryMask = 0;
    uint32_t backbufferExtentWidth = 0;
    uint32_t backbufferExtentHeight = 0;
    for (uint32_t i = 0; i < resource_count; i++) {
        const auto& descriptor = resources[i];
        sl::Resource* native = nullptr;
        const sl::Extent* extent = nullptr;
        // Frame-based tagging reconciles every input against this present. Keep explicit extents even
        // when the resource pointer is intentionally null, which is how Streamline represents an
        // extent-only kBufferTypeBackbuffer subrect tag.
        if (descriptor.width != 0 && descriptor.height != 0) {
            extents.push_back(sl::Extent{0u, 0u, descriptor.width, descriptor.height});
            extent = &extents.back();
            if (descriptor.buffer_type == SLBRIDGE_BUFFER_BACKBUFFER) {
                backbufferExtentWidth = descriptor.width;
                backbufferExtentHeight = descriptor.height;
            }
        }
        if (descriptor.valid && descriptor.image != 0 && descriptor.view != 0) {
            if (i < 32) {
                validMask |= 1u << i;
            }
            nativeResources.emplace_back();
            native = &nativeResources.back();
            native->type = sl::ResourceType::eTex2d;
            native->native = reinterpret_cast<void*>(static_cast<uintptr_t>(descriptor.image));
            native->view = reinterpret_cast<void*>(static_cast<uintptr_t>(descriptor.view));
            native->memory = reinterpret_cast<void*>(static_cast<uintptr_t>(descriptor.memory));
            native->state = descriptor.state;
            native->width = descriptor.width;
            native->height = descriptor.height;
            native->nativeFormat = descriptor.format;
            native->mipLevels = descriptor.mip_levels;
            native->arrayLayers = descriptor.array_layers;
            native->flags = descriptor.flags;
            native->usage = descriptor.usage;
            if (descriptor.memory != 0 && i < 32) {
                memoryMask |= 1u << i;
            }

        }
        tags.emplace_back(native, static_cast<sl::BufferType>(descriptor.buffer_type),
                static_cast<sl::ResourceLifecycle>(descriptor.lifecycle), extent);
    }
    auto* command = reinterpret_cast<sl::CommandBuffer*>(static_cast<uintptr_t>(command_buffer));
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.tag_resources_calls++;
        g_trace.tags_sequence = ++g_trace.event_sequence;
        g_trace.last_frame_token = frame_token;
        g_trace.last_command_buffer = command_buffer;
        g_trace.last_resource_count = resource_count;
        g_trace.last_resource_valid_mask = validMask;
        g_trace.last_resource_memory_mask = memoryMask;
        g_trace.last_backbuffer_extent_width = backbufferExtentWidth;
        g_trace.last_backbuffer_extent_height = backbufferExtentHeight;
    }
    return resultCode(g_slSetTagForFrame(*token, sl::ViewportHandle(viewport), tags.data(), resource_count, command),
            "slSetTagForFrame");
}

SLBRIDGE_EXPORT int32_t slbridge_set_dlssg_options(uint32_t viewport,
        const slbridge_dlssg_options* options) {
    if (!options || !g_slGetFeatureFunction) {
        setError("Streamline DLSS-G options were null");
        return -1;
    }
    sl::DLSSGOptions values{};
    values.mode = mapDlssgMode(options->mode);
    // Streamline validates this field for every mode, including eOff. Its
    // documented domain starts at one, so preserve that invariant at the ABI
    // boundary even when a zero-initialized suspension payload is supplied.
    values.numFramesToGenerate = std::max(1u, options->num_frames_to_generate);
    values.flags = static_cast<sl::DLSSGFlags>(options->flags);
    values.dynamicResWidth = options->dynamic_res_width;
    values.dynamicResHeight = options->dynamic_res_height;
    values.numBackBuffers = options->num_back_buffers;
    values.mvecDepthWidth = options->mvec_depth_width;
    values.mvecDepthHeight = options->mvec_depth_height;
    values.colorWidth = options->color_width;
    values.colorHeight = options->color_height;
    values.colorBufferFormat = options->color_buffer_format;
    values.mvecBufferFormat = options->mvec_buffer_format;
    values.depthBufferFormat = options->depth_buffer_format;
    values.hudLessBufferFormat = options->hudless_buffer_format;
    values.uiBufferFormat = options->ui_buffer_format;
    values.queueParallelismMode = static_cast<sl::DLSSGQueueParallelismMode>(options->queue_parallelism_mode);
    values.enableUserInterfaceRecomposition = options->enable_ui_recomposition
            ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    values.dynamicTargetFrameRate = options->dynamic_target_frame_rate;
    values.onErrorCallback = &apiError;

    {
        std::lock_guard lock(g_traceMutex);
        g_trace.set_options_calls++;
        g_trace.options_sequence = ++g_trace.event_sequence;
        g_trace.last_options_mode = options->mode;
        g_trace.last_num_frames = options->num_frames_to_generate;
        g_trace.last_options_flags = options->flags;
        g_trace.last_color_width = options->color_width;
        g_trace.last_color_height = options->color_height;
        g_trace.last_mvec_width = options->mvec_depth_width;
        g_trace.last_mvec_height = options->mvec_depth_height;
    }

    using FnSetOptions = PFun_slDLSSGSetOptions*;
    FnSetOptions function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureDLSS_G, "slDLSSGSetOptions", reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slDLSSGSetOptions)");
    }
    if (!function) {
        setError("slGetFeatureFunction returned a null slDLSSGSetOptions function");
        return -1;
    }
    return resultCode(function(sl::ViewportHandle(viewport), values), "slDLSSGSetOptions");
}

SLBRIDGE_EXPORT int32_t slbridge_get_dlssg_state(uint32_t viewport,
        slbridge_dlssg_state* out_state, const slbridge_dlssg_options* estimate_options) {
    if (!out_state || !g_slGetFeatureFunction) {
        setError("Streamline DLSS-G state output was null");
        return -1;
    }
    using FnGetState = PFun_slDLSSGGetState*;
    FnGetState function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureDLSS_G, "slDLSSGGetState", reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slDLSSGGetState)");
    }
    if (!function) {
        setError("slGetFeatureFunction returned a null slDLSSGGetState function");
        return -1;
    }
    sl::DLSSGOptions estimate{};
    const sl::DLSSGOptions* estimatePtr = nullptr;
    if (estimate_options) {
        estimate.mode = mapDlssgMode(estimate_options->mode);
        estimate.numFramesToGenerate = std::max(1u, estimate_options->num_frames_to_generate);
        estimate.flags = static_cast<sl::DLSSGFlags>(estimate_options->flags);
        estimate.dynamicResWidth = estimate_options->dynamic_res_width;
        estimate.dynamicResHeight = estimate_options->dynamic_res_height;
        estimate.numBackBuffers = estimate_options->num_back_buffers;
        estimate.mvecDepthWidth = estimate_options->mvec_depth_width;
        estimate.mvecDepthHeight = estimate_options->mvec_depth_height;
        estimate.colorWidth = estimate_options->color_width;
        estimate.colorHeight = estimate_options->color_height;
        estimate.colorBufferFormat = estimate_options->color_buffer_format;
        estimate.mvecBufferFormat = estimate_options->mvec_buffer_format;
        estimate.depthBufferFormat = estimate_options->depth_buffer_format;
        estimate.hudLessBufferFormat = estimate_options->hudless_buffer_format;
        estimate.uiBufferFormat = estimate_options->ui_buffer_format;
        estimate.queueParallelismMode = static_cast<sl::DLSSGQueueParallelismMode>(
                estimate_options->queue_parallelism_mode);
        estimate.enableUserInterfaceRecomposition = estimate_options->enable_ui_recomposition
                ? sl::Boolean::eTrue : sl::Boolean::eFalse;
        estimate.dynamicTargetFrameRate = estimate_options->dynamic_target_frame_rate;
        estimatePtr = &estimate;
    }
    sl::DLSSGState state{};
    const int32_t result = resultCode(function(sl::ViewportHandle(viewport), state, estimatePtr), "slDLSSGGetState");
    if (result == static_cast<int32_t>(sl::Result::eOk)) {
        out_state->estimated_vram_usage = state.estimatedVRAMUsageInBytes;
        out_state->status = static_cast<uint32_t>(state.status);
        out_state->min_width_or_height = state.minWidthOrHeight;
        out_state->num_frames_actually_presented = state.numFramesActuallyPresented;
        out_state->num_frames_to_generate_max = state.numFramesToGenerateMax;
        out_state->vsync_support_available = state.bIsVsyncSupportAvailable == sl::Boolean::eTrue;
        out_state->dynamic_mfg_supported = state.bIsDynamicMFGSupported == sl::Boolean::eTrue;
        out_state->inputs_processing_completion_fence = reinterpret_cast<uint64_t>(state.inputsProcessingCompletionFence);
        out_state->last_inputs_processing_completion_fence_value = state.lastPresentInputsProcessingCompletionFenceValue;
        std::lock_guard lock(g_traceMutex);
        g_trace.get_state_calls++;
        g_trace.last_dlssg_status = out_state->status;
        g_trace.last_frames_presented = out_state->num_frames_actually_presented;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_get_dlssd_optimal_settings(uint32_t mode,
        uint32_t output_width, uint32_t output_height, uint32_t* out_render_width,
        uint32_t* out_render_height, float* out_sharpness) {
    if (!g_slGetFeatureFunction || !out_render_width || !out_render_height || !out_sharpness
            || output_width == 0 || output_height == 0 || mapDlssdMode(mode) == sl::DLSSMode::eOff) {
        setError("Streamline DLSS-RR optimal-settings arguments were invalid");
        return -1;
    }
    using FnGetOptimalSettings = PFun_slDLSSDGetOptimalSettings*;
    FnGetOptimalSettings function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureDLSS_RR, "slDLSSDGetOptimalSettings",
            reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slDLSSDGetOptimalSettings)");
    }
    if (!function) {
        setError("slGetFeatureFunction returned a null slDLSSDGetOptimalSettings function");
        return -1;
    }
    sl::DLSSDOptions options = makeDlssdOptions(mode, output_width, output_height, 0);
    sl::DLSSDOptimalSettings settings{};
    const int32_t result = resultCode(function(options, settings), "slDLSSDGetOptimalSettings");
    if (result == static_cast<int32_t>(sl::Result::eOk)) {
        *out_render_width = settings.optimalRenderWidth;
        *out_render_height = settings.optimalRenderHeight;
        *out_sharpness = settings.optimalSharpness;
    }
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.dlssd_optimal_calls++;
        g_trace.last_dlssd_mode = mode;
        g_trace.last_dlssd_result = static_cast<uint32_t>(result);
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_set_dlssd_options(uint32_t viewport,
        const slbridge_dlssd_options* options) {
    if (!g_slGetFeatureFunction || !options || options->output_width == 0 || options->output_height == 0
            || mapDlssdMode(options->mode) == sl::DLSSMode::eOff) {
        setError("Streamline DLSS-RR options were invalid");
        return -1;
    }
    using FnSetOptions = PFun_slDLSSDSetOptions*;
    FnSetOptions function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureDLSS_RR, "slDLSSDSetOptions",
            reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slDLSSDSetOptions)");
    }
    if (!function) {
        setError("slGetFeatureFunction returned a null slDLSSDSetOptions function");
        return -1;
    }
    const sl::DLSSDOptions nativeOptions = makeDlssdOptions(options->mode, options->output_width,
            options->output_height, options->preset, options->world_to_camera_view,
            options->camera_view_to_world);
    const int32_t result = resultCode(function(sl::ViewportHandle(viewport), nativeOptions),
            "slDLSSDSetOptions");
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.dlssd_options_calls++;
        g_trace.last_dlssd_mode = options->mode;
        g_trace.last_dlssd_viewport = viewport;
        g_trace.last_dlssd_result = static_cast<uint32_t>(result);
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_evaluate_dlssd(uint64_t frame_token, uint32_t viewport,
        const slbridge_resource_desc* resources, uint32_t resource_count,
        const slbridge_constants* constants, uint64_t command_buffer) {
    if (!g_slSetConstants || !g_slEvaluateFeature || !resources || !constants || !command_buffer
            || resource_count != 8) {
        setError("Streamline DLSS-RR evaluation requires eight resources, constants, and a command buffer");
        return -1;
    }
    auto* token = tokenFromHandle(frame_token);
    if (!token) {
        setError("Streamline DLSS-RR frame token was null");
        return -1;
    }

    std::vector<sl::Resource> nativeResources;
    std::vector<sl::Extent> extents;
    std::vector<sl::ResourceTag> tags;
    nativeResources.reserve(resource_count);
    extents.reserve(resource_count);
    tags.reserve(resource_count);
    bool hasInputColor = false;
    bool hasOutputColor = false;
    bool hasDepth = false;
    bool hasMotion = false;
    bool hasAlbedo = false;
    bool hasSpecularAlbedo = false;
    bool hasNormalRoughness = false;
    bool hasSpecularMotion = false;
    for (uint32_t i = 0; i < resource_count; i++) {
        const auto& descriptor = resources[i];
        if (!slbridge::detail::isCompleteDlssdVulkanTexture(descriptor)) {
            setError("Streamline DLSS-RR received an incomplete Vulkan resource descriptor");
            return -1;
        }
        extents.push_back(sl::Extent{0u, 0u, descriptor.width, descriptor.height});
        nativeResources.emplace_back();
        auto& native = nativeResources.back();
        native.type = sl::ResourceType::eTex2d;
        native.native = reinterpret_cast<void*>(static_cast<uintptr_t>(descriptor.image));
        native.view = reinterpret_cast<void*>(static_cast<uintptr_t>(descriptor.view));
        native.memory = reinterpret_cast<void*>(static_cast<uintptr_t>(descriptor.memory));
        native.state = descriptor.state;
        native.width = descriptor.width;
        native.height = descriptor.height;
        native.nativeFormat = descriptor.format;
        native.mipLevels = descriptor.mip_levels;
        native.arrayLayers = descriptor.array_layers;
        native.flags = descriptor.flags;
        native.usage = descriptor.usage;
        tags.emplace_back(&native, static_cast<sl::BufferType>(descriptor.buffer_type),
                static_cast<sl::ResourceLifecycle>(descriptor.lifecycle), &extents.back());
        switch (descriptor.buffer_type) {
        case SLBRIDGE_BUFFER_SCALING_INPUT_COLOR: hasInputColor = true; break;
        case SLBRIDGE_BUFFER_SCALING_OUTPUT_COLOR: hasOutputColor = true; break;
        case SLBRIDGE_BUFFER_DEPTH: hasDepth = true; break;
        case SLBRIDGE_BUFFER_MOTION_VECTORS: hasMotion = true; break;
        case SLBRIDGE_BUFFER_ALBEDO: hasAlbedo = true; break;
        case SLBRIDGE_BUFFER_SPECULAR_ALBEDO: hasSpecularAlbedo = true; break;
        case SLBRIDGE_BUFFER_NORMAL_ROUGHNESS: hasNormalRoughness = true; break;
        case SLBRIDGE_BUFFER_SPECULAR_MOTION_VECTORS: hasSpecularMotion = true; break;
        default: break;
        }
    }
    if (!hasInputColor || !hasOutputColor || !hasDepth || !hasMotion || !hasAlbedo
            || !hasSpecularAlbedo || !hasNormalRoughness || !hasSpecularMotion) {
        setError("Streamline DLSS-RR is missing one or more required local resource tags");
        return -1;
    }

    sl::ViewportHandle viewportHandle(viewport);
    sl::Constants localConstants = makeConstants(*constants);
    const int32_t constantsResult = resultCode(g_slSetConstants(localConstants, *token, viewportHandle),
            "slSetConstants(DLSS-RR)");
    if (constantsResult != static_cast<int32_t>(sl::Result::eOk)) {
        std::lock_guard lock(g_traceMutex);
        g_trace.dlssd_evaluate_calls++;
        g_trace.last_dlssd_frame_token = frame_token;
        g_trace.last_dlssd_command_buffer = command_buffer;
        g_trace.last_dlssd_resource_count = resource_count;
        g_trace.last_dlssd_result = static_cast<uint32_t>(constantsResult);
        g_trace.last_dlssd_viewport = viewport;
        return constantsResult;
    }
    std::vector<const sl::BaseStructure*> inputs;
    inputs.reserve(resource_count + 1);
    inputs.push_back(&viewportHandle);
    for (const auto& tag : tags) {
        inputs.push_back(&tag);
    }
    auto* command = reinterpret_cast<sl::CommandBuffer*>(static_cast<uintptr_t>(command_buffer));
    const int32_t result = resultCode(g_slEvaluateFeature(sl::kFeatureDLSS_RR, *token,
            inputs.data(), static_cast<uint32_t>(inputs.size()), command), "slEvaluateFeature(DLSS-RR)");
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.dlssd_evaluate_calls++;
        g_trace.last_dlssd_frame_token = frame_token;
        g_trace.last_dlssd_command_buffer = command_buffer;
        g_trace.last_dlssd_resource_count = resource_count;
        g_trace.last_dlssd_result = static_cast<uint32_t>(result);
        g_trace.last_dlssd_viewport = viewport;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_free_dlssd_resources(uint32_t viewport) {
    if (!g_slFreeResources) {
        setError("Streamline DLSS-RR resource release requested before initialization");
        return -1;
    }
    const int32_t result = resultCode(g_slFreeResources(sl::kFeatureDLSS_RR,
            sl::ViewportHandle(viewport)), "slFreeResources(DLSS-RR)");
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.dlssd_free_calls++;
        g_trace.last_dlssd_result = static_cast<uint32_t>(result);
        g_trace.last_dlssd_viewport = viewport;
    }
    return result;
}

SLBRIDGE_EXPORT int32_t slbridge_set_reflex_options(const slbridge_reflex_options* options) {
    if (!options || !g_slGetFeatureFunction) {
        setError("Streamline Reflex options were null");
        return -1;
    }
    using FnSetOptions = PFun_slReflexSetOptions*;
    FnSetOptions function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureReflex, "slReflexSetOptions", reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slReflexSetOptions)");
    }
    if (!function) {
        setError("slGetFeatureFunction returned a null slReflexSetOptions function");
        return -1;
    }
    sl::ReflexOptions values{};
    values.mode = mapReflexMode(options->mode);
    values.frameLimitUs = options->frame_limit_us;
    return resultCode(function(values), "slReflexSetOptions");
}

SLBRIDGE_EXPORT int32_t slbridge_reflex_sleep(uint64_t frame_token) {
    using FnSleep = PFun_slReflexSleep*;
    FnSleep function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureReflex, "slReflexSleep", reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slReflexSleep)");
    }
    if (!function || !tokenFromHandle(frame_token)) {
        setError("slReflexSleep function or frame token was null");
        return -1;
    }
    return resultCode(function(*tokenFromHandle(frame_token)), "slReflexSleep");
}

SLBRIDGE_EXPORT int32_t slbridge_pcl_set_marker(uint32_t marker, uint64_t frame_token) {
    using FnMarker = PFun_slPCLSetMarker*;
    FnMarker function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeaturePCL, "slPCLSetMarker", reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slPCLSetMarker)");
    }
    if (!function || !tokenFromHandle(frame_token)) {
        setError("slPCLSetMarker function or frame token was null");
        return -1;
    }
    {
        std::lock_guard lock(g_traceMutex);
        g_trace.pcl_marker_calls++;
        const uint64_t sequence = ++g_trace.event_sequence;
        if (marker == SLBRIDGE_PCL_PRESENT_START) {
            g_trace.present_start_sequence = sequence;
        } else if (marker == SLBRIDGE_PCL_PRESENT_END) {
            g_trace.present_end_sequence = sequence;
        }
        g_trace.last_marker = marker;
        g_trace.last_frame_token = frame_token;
    }
    return resultCode(function(mapPclMarker(marker), *tokenFromHandle(frame_token)), "slPCLSetMarker");
}

SLBRIDGE_EXPORT int32_t slbridge_get_reflex_state(slbridge_reflex_state* out_state) {
    if (!out_state || !g_slGetFeatureFunction) {
        setError("Streamline Reflex state output was null");
        return -1;
    }
    using FnGetState = PFun_slReflexGetState*;
    FnGetState function{};
    const auto lookup = g_slGetFeatureFunction(sl::kFeatureReflex, "slReflexGetState", reinterpret_cast<void*&>(function));
    if (lookup != sl::Result::eOk) {
        return resultCode(lookup, "slGetFeatureFunction(slReflexGetState)");
    }
    if (!function) {
        setError("slGetFeatureFunction returned a null slReflexGetState function");
        return -1;
    }
    sl::ReflexState state{};
    const int32_t result = resultCode(function(state), "slReflexGetState");
    if (result != static_cast<int32_t>(sl::Result::eOk)) {
        return result;
    }
    out_state->low_latency_available = state.lowLatencyAvailable;
    out_state->latency_report_available = state.latencyReportAvailable;
    out_state->flash_indicator_driver_controlled = state.flashIndicatorDriverControlled;
    const sl::ReflexReport* latest = nullptr;
    for (const auto& report : state.frameReport) {
        if (report.frameID != 0 && (!latest || report.frameID > latest->frameID)) {
            latest = &report;
        }
    }
    if (latest) {
        out_state->report.frame_id = latest->frameID;
        out_state->report.input_sample_time = latest->inputSampleTime;
        out_state->report.simulation_start_time = latest->simStartTime;
        out_state->report.simulation_end_time = latest->simEndTime;
        out_state->report.render_submit_start_time = latest->renderSubmitStartTime;
        out_state->report.render_submit_end_time = latest->renderSubmitEndTime;
        out_state->report.present_start_time = latest->presentStartTime;
        out_state->report.present_end_time = latest->presentEndTime;
        out_state->report.gpu_active_render_time_us = latest->gpuActiveRenderTimeUs;
        out_state->report.gpu_frame_time_us = latest->gpuFrameTimeUs;
    }
    return result;
}

} // extern "C"
