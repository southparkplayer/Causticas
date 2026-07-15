#include <cassert>
#include <cstddef>

#include "sl.h"
#include "sl_consts.h"
#include "sl_core_types.h"
#include "sl_dlss_d.h"
#include "sl_dlss_g.h"
#include "sl_pcl.h"
#include "sl_version.h"

#include "streamline_bridge.h"
#include "streamline_bridge_resources.h"

int main() {
    static_assert(sizeof(slbridge_abi_info) == 36);
    static_assert(sizeof(slbridge_trace_state) == 336);
    static_assert(offsetof(slbridge_trace_state, dlssd_optimal_calls) == 240);
    static_assert(offsetof(slbridge_trace_state, last_dlssd_viewport) == 300);
    static_assert(offsetof(slbridge_trace_state, last_swapchain_handle) == 304);
    static_assert(offsetof(slbridge_trace_state, last_swapchain_present_mode) == 312);
    static_assert(offsetof(slbridge_trace_state, last_swapchain_present_mode_known) == 332);
    static_assert(SL_VERSION_MAJOR == 2 && SL_VERSION_MINOR == 12 && SL_VERSION_PATCH == 0);
    static_assert(sl::DLSSGOptions::s_structType.data1 == 0xfac5f1cb);
    static_assert(sl::DLSSGState::s_structType.data1 == 0xcc8ac8e1);
    static_assert(sl::DLSSDOptions::s_structType.data1 == 0x0ad87504);
    static_assert(sizeof(sl::float4x4) == 64);
    static_assert(sizeof(slbridge_resource_desc) == 72);
    static_assert(sizeof(slbridge_constants) == 444);
    static_assert(offsetof(slbridge_constants, jitter_offset) == 320);
    static_assert(offsetof(slbridge_constants, mvec_scale) == 328);
    static_assert(offsetof(slbridge_constants, reset) == 424);
    static_assert(sizeof(slbridge_dlssd_options) == 144);
    static_assert(offsetof(slbridge_dlssd_options, world_to_camera_view) == 16);
    static_assert(offsetof(slbridge_dlssd_options, camera_view_to_world) == 80);
    static_assert(sizeof(slbridge_dlssg_options) == 72);
    static_assert(sizeof(slbridge_dlssg_state) == 48);
    static_assert(sizeof(slbridge_reflex_options) == 8);

    slbridge_resource_desc rrTexture{};
    rrTexture.image = 0x1000;
    rrTexture.view = 0x2000;
    rrTexture.memory = 0;
    rrTexture.width = 1920;
    rrTexture.height = 1080;
    rrTexture.valid = 1;
    if (!slbridge::detail::isCompleteDlssdVulkanTexture(rrTexture)) {
        return 2;
    }
    static_assert(sizeof(slbridge_reflex_state) == 88);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::eSimulationStart) == SLBRIDGE_PCL_SIMULATION_START);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::eSimulationEnd) == SLBRIDGE_PCL_SIMULATION_END);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::eRenderSubmitStart) == SLBRIDGE_PCL_RENDER_SUBMIT_START);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::eRenderSubmitEnd) == SLBRIDGE_PCL_RENDER_SUBMIT_END);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::ePresentStart) == SLBRIDGE_PCL_PRESENT_START);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::ePresentEnd) == SLBRIDGE_PCL_PRESENT_END);
    static_assert(static_cast<uint32_t>(sl::PCLMarker::eTriggerFlash) == SLBRIDGE_PCL_TRIGGER_FLASH);
    static_assert(sl::kBufferTypeDepth == SLBRIDGE_BUFFER_DEPTH);
    static_assert(sl::kBufferTypeMotionVectors == SLBRIDGE_BUFFER_MOTION_VECTORS);
    static_assert(sl::kBufferTypeHUDLessColor == SLBRIDGE_BUFFER_HUDLESS_COLOR);
    static_assert(sl::kBufferTypeScalingInputColor == SLBRIDGE_BUFFER_SCALING_INPUT_COLOR);
    static_assert(sl::kBufferTypeScalingOutputColor == SLBRIDGE_BUFFER_SCALING_OUTPUT_COLOR);
    static_assert(sl::kBufferTypeAlbedo == SLBRIDGE_BUFFER_ALBEDO);
    static_assert(sl::kBufferTypeSpecularAlbedo == SLBRIDGE_BUFFER_SPECULAR_ALBEDO);
    static_assert(sl::kBufferTypeSpecularMotionVectors == SLBRIDGE_BUFFER_SPECULAR_MOTION_VECTORS);
    static_assert(sl::kBufferTypeNormalRoughness == SLBRIDGE_BUFFER_NORMAL_ROUGHNESS);
    static_assert(sl::kBufferTypeUIColorAndAlpha == SLBRIDGE_BUFFER_UI_COLOR_AND_ALPHA);
    static_assert(sl::kBufferTypeBackbuffer == SLBRIDGE_BUFFER_BACKBUFFER);
    static_assert(sl::kBufferTypeUIAlpha == SLBRIDGE_BUFFER_UI_ALPHA);
    static_assert(offsetof(slbridge_dlssg_options, dynamic_target_frame_rate)
            > offsetof(slbridge_dlssg_options, enable_ui_recomposition));

    assert(SLBRIDGE_DLSSG_OFF == 0);
    assert(SLBRIDGE_DLSSG_FIXED == 1);
    assert(SLBRIDGE_DLSSG_AUTO == 2);
    assert(SLBRIDGE_DLSSG_DYNAMIC == 3);
    assert(SLBRIDGE_REFLEX_OFF == 0);
    assert(SLBRIDGE_REFLEX_ON == 1);
    assert(SLBRIDGE_REFLEX_ON_BOOST == 2);
    assert(SLBRIDGE_BUFFER_DEPTH == 0);
    assert(SLBRIDGE_BUFFER_MOTION_VECTORS == 1);
    assert(SLBRIDGE_BUFFER_HUDLESS_COLOR == 2);
    assert(SLBRIDGE_BUFFER_SCALING_INPUT_COLOR == 3);
    assert(SLBRIDGE_BUFFER_SCALING_OUTPUT_COLOR == 4);
    assert(SLBRIDGE_FEATURE_DLSS_RR == sl::kFeatureDLSS_RR);
    assert(SLBRIDGE_BUFFER_UI_COLOR_AND_ALPHA == 23);
    assert(SLBRIDGE_BUFFER_BACKBUFFER == 53);
    assert(SLBRIDGE_BUFFER_UI_ALPHA == 69);
    assert(SLBRIDGE_ABI_VERSION == 8);

    sl::Resource resource{};
    sl::Extent extent{0u, 0u, 3840u, 2160u};
    sl::ResourceTag tag(&resource, sl::kBufferTypeHUDLessColor,
            sl::ResourceLifecycle::eValidUntilPresent, &extent);
    assert(tag.extent == extent);
    sl::ResourceTag backbufferTag(nullptr, sl::kBufferTypeBackbuffer,
            sl::ResourceLifecycle{}, &extent);
    assert(backbufferTag.resource == nullptr);
    assert(backbufferTag.type == sl::kBufferTypeBackbuffer);
    assert(backbufferTag.extent == extent);
    return 0;
}
