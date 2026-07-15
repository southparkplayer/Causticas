#pragma once

#include <stdint.h>

#if defined(_WIN32)
#define SLBRIDGE_EXPORT __declspec(dllexport)
#else
#define SLBRIDGE_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define SLBRIDGE_ABI_VERSION 9u

enum slbridge_variant {
    SLBRIDGE_VARIANT_DEVELOPMENT = 0,
    SLBRIDGE_VARIANT_PRODUCTION = 1,
};

enum slbridge_feature {
    SLBRIDGE_FEATURE_REFLEX = 3,
    SLBRIDGE_FEATURE_PCL = 4,
    SLBRIDGE_FEATURE_DLSS_G = 1000,
    SLBRIDGE_FEATURE_DLSS_RR = 1001,
};

enum slbridge_dlssg_mode {
    SLBRIDGE_DLSSG_OFF = 0,
    SLBRIDGE_DLSSG_FIXED = 1,
    SLBRIDGE_DLSSG_AUTO = 2,
    SLBRIDGE_DLSSG_DYNAMIC = 3,
};

enum slbridge_reflex_mode {
    SLBRIDGE_REFLEX_OFF = 0,
    SLBRIDGE_REFLEX_ON = 1,
    SLBRIDGE_REFLEX_ON_BOOST = 2,
};

enum slbridge_dlssg_flags {
    SLBRIDGE_DLSSG_SHOW_ONLY_INTERPOLATED = 1u << 0,
    SLBRIDGE_DLSSG_DYNAMIC_RESOLUTION = 1u << 1,
    SLBRIDGE_DLSSG_REQUEST_VRAM_ESTIMATE = 1u << 2,
    SLBRIDGE_DLSSG_RETAIN_RESOURCES_WHEN_OFF = 1u << 3,
    SLBRIDGE_DLSSG_FULLSCREEN_MENU_DETECTION = 1u << 4,
};

enum slbridge_dlssg_queue_parallelism_mode {
    SLBRIDGE_DLSSG_BLOCK_PRESENTING_QUEUE = 0,
    SLBRIDGE_DLSSG_BLOCK_NO_CLIENT_QUEUES = 1,
};

enum slbridge_pcl_marker {
    SLBRIDGE_PCL_SIMULATION_START = 0,
    SLBRIDGE_PCL_SIMULATION_END = 1,
    SLBRIDGE_PCL_RENDER_SUBMIT_START = 2,
    SLBRIDGE_PCL_RENDER_SUBMIT_END = 3,
    SLBRIDGE_PCL_PRESENT_START = 4,
    SLBRIDGE_PCL_PRESENT_END = 5,
    SLBRIDGE_PCL_TRIGGER_FLASH = 7,
};

enum slbridge_buffer_type {
    SLBRIDGE_BUFFER_DEPTH = 0,
    SLBRIDGE_BUFFER_MOTION_VECTORS = 1,
    SLBRIDGE_BUFFER_HUDLESS_COLOR = 2,
    SLBRIDGE_BUFFER_SCALING_INPUT_COLOR = 3,
    SLBRIDGE_BUFFER_SCALING_OUTPUT_COLOR = 4,
    SLBRIDGE_BUFFER_ALBEDO = 7,
    SLBRIDGE_BUFFER_SPECULAR_ALBEDO = 8,
    SLBRIDGE_BUFFER_SPECULAR_MOTION_VECTORS = 10,
    SLBRIDGE_BUFFER_NORMAL_ROUGHNESS = 14,
    SLBRIDGE_BUFFER_UI_COLOR_AND_ALPHA = 23,
    SLBRIDGE_BUFFER_BACKBUFFER = 53,
    SLBRIDGE_BUFFER_UI_ALPHA = 69,
};

enum slbridge_resource_lifecycle {
    SLBRIDGE_RESOURCE_ONLY_VALID_NOW = 0,
    SLBRIDGE_RESOURCE_VALID_UNTIL_PRESENT = 1,
    SLBRIDGE_RESOURCE_VALID_UNTIL_EVALUATE = 2,
};

typedef struct slbridge_resource_desc {
    uint64_t image;
    uint64_t view;
    uint64_t memory;
    uint32_t state;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t mip_levels;
    uint32_t array_layers;
    uint32_t flags;
    uint32_t usage;
    uint32_t buffer_type;
    uint32_t lifecycle;
    uint8_t valid;
    uint8_t reserved[3];
} slbridge_resource_desc;

typedef struct slbridge_constants {
    float camera_view_to_clip[16];
    float clip_to_camera_view[16];
    float clip_to_lens_clip[16];
    float clip_to_prev_clip[16];
    float prev_clip_to_clip[16];
    float jitter_offset[2];
    float mvec_scale[2];
    float camera_pinhole_offset[2];
    float camera_pos[3];
    float camera_up[3];
    float camera_right[3];
    float camera_forward[3];
    float camera_near;
    float camera_far;
    float camera_fov;
    float camera_aspect_ratio;
    float motion_vectors_invalid_value;
    uint32_t depth_inverted;
    uint32_t camera_motion_included;
    uint32_t motion_vectors_3d;
    uint32_t reset;
    uint32_t orthographic_projection;
    uint32_t motion_vectors_dilated;
    uint32_t motion_vectors_jittered;
    float min_relative_linear_depth_object_separation;
} slbridge_constants;

typedef struct slbridge_dlssd_options {
    uint32_t mode;
    uint32_t output_width;
    uint32_t output_height;
    uint32_t preset;
    float world_to_camera_view[16];
    float camera_view_to_world[16];
} slbridge_dlssd_options;

typedef struct slbridge_dlssg_options {
    uint32_t mode;
    uint32_t num_frames_to_generate;
    uint32_t flags;
    uint32_t dynamic_res_width;
    uint32_t dynamic_res_height;
    uint32_t num_back_buffers;
    uint32_t mvec_depth_width;
    uint32_t mvec_depth_height;
    uint32_t color_width;
    uint32_t color_height;
    uint32_t color_buffer_format;
    uint32_t mvec_buffer_format;
    uint32_t depth_buffer_format;
    uint32_t hudless_buffer_format;
    uint32_t ui_buffer_format;
    uint32_t queue_parallelism_mode;
    uint32_t enable_ui_recomposition;
    float dynamic_target_frame_rate;
} slbridge_dlssg_options;

typedef struct slbridge_dlssg_state {
    uint64_t estimated_vram_usage;
    uint32_t status;
    uint32_t min_width_or_height;
    uint32_t num_frames_actually_presented;
    uint32_t num_frames_to_generate_max;
    uint32_t vsync_support_available;
    uint32_t dynamic_mfg_supported;
    uint64_t inputs_processing_completion_fence;
    uint64_t last_inputs_processing_completion_fence_value;
} slbridge_dlssg_state;

typedef struct slbridge_reflex_options {
    uint32_t mode;
    uint32_t frame_limit_us;
} slbridge_reflex_options;

typedef struct slbridge_reflex_report {
    uint64_t frame_id;
    uint64_t input_sample_time;
    uint64_t simulation_start_time;
    uint64_t simulation_end_time;
    uint64_t render_submit_start_time;
    uint64_t render_submit_end_time;
    uint64_t present_start_time;
    uint64_t present_end_time;
    uint32_t gpu_active_render_time_us;
    uint32_t gpu_frame_time_us;
} slbridge_reflex_report;

typedef struct slbridge_reflex_state {
    uint32_t low_latency_available;
    uint32_t latency_report_available;
    uint32_t flash_indicator_driver_controlled;
    uint32_t reserved;
    slbridge_reflex_report report;
} slbridge_reflex_state;

typedef struct slbridge_feature_requirements {
    uint32_t flags;
    uint32_t max_num_viewports;
    uint32_t num_required_tags;
    uint32_t vk_num_compute_queues_required;
    uint32_t vk_num_graphics_queues_required;
    uint32_t vk_num_optical_flow_queues_required;
} slbridge_feature_requirements;

typedef struct slbridge_feature_version {
    uint32_t sl_major;
    uint32_t sl_minor;
    uint32_t sl_build;
    uint32_t ngx_major;
    uint32_t ngx_minor;
    uint32_t ngx_build;
} slbridge_feature_version;

typedef struct slbridge_abi_info {
    uint32_t abi_version;
    uint32_t resource_desc_size;
    uint32_t constants_size;
    uint32_t dlssd_options_size;
    uint32_t dlssg_options_size;
    uint32_t dlssg_state_size;
    uint32_t reflex_options_size;
    uint32_t reflex_state_size;
    uint32_t trace_state_size;
} slbridge_abi_info;

/** Last-observed app-to-Streamline frame data. This is diagnostic state, not a control surface. */
typedef struct slbridge_trace_state {
    uint64_t begin_frame_calls;
    uint64_t set_constants_calls;
    uint64_t tag_resources_calls;
    uint64_t set_options_calls;
    uint64_t get_state_calls;
    uint64_t pcl_marker_calls;
    uint64_t acquire_calls;
    uint64_t present_calls;
    uint64_t last_frame_token;
    uint64_t last_command_buffer;
    uint64_t last_present_queue;
    uint64_t last_present_swapchain;
    uint64_t event_sequence;
    uint64_t begin_sequence;
    uint64_t constants_sequence;
    uint64_t tags_sequence;
    uint64_t options_sequence;
    uint64_t present_start_sequence;
    uint64_t proxy_present_sequence;
    uint64_t present_end_sequence;
    uint32_t last_frame_index;
    uint32_t last_marker;
    uint32_t last_options_mode;
    uint32_t last_num_frames;
    uint32_t last_options_flags;
    uint32_t last_color_width;
    uint32_t last_color_height;
    uint32_t last_mvec_width;
    uint32_t last_mvec_height;
    uint32_t last_resource_count;
    uint32_t last_resource_valid_mask;
    uint32_t last_present_wait_count;
    uint32_t last_present_swapchain_count;
    uint32_t last_present_image_index;
    uint32_t last_dlssg_status;
    uint32_t last_frames_presented;
    uint32_t last_backbuffer_extent_width;
    uint32_t last_backbuffer_extent_height;
    uint32_t last_resource_memory_mask;
    uint32_t reserved;
    uint64_t dlssd_optimal_calls;
    uint64_t dlssd_options_calls;
    uint64_t dlssd_evaluate_calls;
    uint64_t dlssd_free_calls;
    uint64_t last_dlssd_frame_token;
    uint64_t last_dlssd_command_buffer;
    uint32_t last_dlssd_mode;
    uint32_t last_dlssd_resource_count;
    uint32_t last_dlssd_result;
    uint32_t last_dlssd_viewport;
    uint64_t last_swapchain_handle;
    uint32_t last_swapchain_present_mode;
    uint32_t last_swapchain_min_image_count;
    uint32_t last_swapchain_image_count;
    int32_t last_swapchain_create_result;
    uint32_t last_swapchain_proxy_dispatch;
    uint32_t last_swapchain_present_mode_known;
    uint64_t timeline_wait_calls;
    uint64_t timeline_wait_failures;
    uint64_t device_wait_idle_calls;
    uint64_t last_timeline_semaphore;
    uint64_t last_timeline_value;
} slbridge_trace_state;

SLBRIDGE_EXPORT int32_t slbridge_get_abi_info(slbridge_abi_info* out_info);

SLBRIDGE_EXPORT int32_t slbridge_initialize(const wchar_t* plugin_directory,
        const wchar_t* log_directory, uint32_t application_id, uint32_t variant);
SLBRIDGE_EXPORT int32_t slbridge_shutdown(void);
SLBRIDGE_EXPORT int32_t slbridge_is_initialized(void);
SLBRIDGE_EXPORT int32_t slbridge_last_result(void);
SLBRIDGE_EXPORT const char* slbridge_last_error(void);
SLBRIDGE_EXPORT int32_t slbridge_poll_api_error(int32_t* out_vk_result);
SLBRIDGE_EXPORT int32_t slbridge_get_trace_state(slbridge_trace_state* out_state);

SLBRIDGE_EXPORT int32_t slbridge_vk_create_instance(uint64_t create_info, uint64_t allocator,
        uint64_t instance_out);
SLBRIDGE_EXPORT int32_t slbridge_vk_enumerate_physical_devices(uint64_t instance, uint64_t count,
        uint64_t physical_devices);
SLBRIDGE_EXPORT int32_t slbridge_vk_create_device(uint64_t physical_device, uint64_t create_info,
        uint64_t allocator, uint64_t device_out);
SLBRIDGE_EXPORT int32_t slbridge_vk_create_win32_surface(uint64_t instance, uint64_t hwnd,
        uint64_t allocator, uint64_t surface_out);
SLBRIDGE_EXPORT void slbridge_vk_destroy_surface(uint64_t instance, uint64_t surface,
        uint64_t allocator);
SLBRIDGE_EXPORT int32_t slbridge_vk_create_swapchain(uint64_t device, uint64_t create_info,
        uint64_t allocator, uint64_t swapchain_out);
SLBRIDGE_EXPORT void slbridge_vk_destroy_swapchain(uint64_t device, uint64_t swapchain,
        uint64_t allocator);
SLBRIDGE_EXPORT int32_t slbridge_vk_get_swapchain_images(uint64_t device, uint64_t swapchain,
        uint64_t count, uint64_t images);
SLBRIDGE_EXPORT int32_t slbridge_vk_acquire_next_image(uint64_t device, uint64_t swapchain,
        uint64_t timeout, uint64_t semaphore, uint64_t fence, uint64_t image_index);
SLBRIDGE_EXPORT int32_t slbridge_vk_queue_present(uint64_t queue, uint64_t present_info);
SLBRIDGE_EXPORT int32_t slbridge_vk_device_wait_idle(uint64_t device);
SLBRIDGE_EXPORT int32_t slbridge_vk_wait_timeline(uint64_t device, uint64_t semaphore,
        uint64_t value, uint64_t timeout_ns);

SLBRIDGE_EXPORT int32_t slbridge_supports_feature(uint32_t feature, uint64_t physical_device);
SLBRIDGE_EXPORT int32_t slbridge_get_feature_requirements(uint32_t feature,
        slbridge_feature_requirements* out_requirements);
SLBRIDGE_EXPORT int32_t slbridge_get_feature_version(uint32_t feature,
        slbridge_feature_version* out_version);
SLBRIDGE_EXPORT int32_t slbridge_set_feature_loaded(uint32_t feature, uint32_t loaded);
SLBRIDGE_EXPORT int32_t slbridge_is_feature_loaded(uint32_t feature);

SLBRIDGE_EXPORT int32_t slbridge_begin_frame(uint32_t frame_index, uint64_t* out_frame_token);
SLBRIDGE_EXPORT int32_t slbridge_set_constants(uint64_t frame_token, uint32_t viewport,
        const slbridge_constants* constants);
SLBRIDGE_EXPORT int32_t slbridge_tag_resources(uint64_t frame_token, uint32_t viewport,
        const slbridge_resource_desc* resources, uint32_t resource_count, uint64_t command_buffer);
SLBRIDGE_EXPORT int32_t slbridge_set_dlssg_options(uint32_t viewport,
        const slbridge_dlssg_options* options);
SLBRIDGE_EXPORT int32_t slbridge_get_dlssg_state(uint32_t viewport,
        slbridge_dlssg_state* out_state, const slbridge_dlssg_options* estimate_options);

SLBRIDGE_EXPORT int32_t slbridge_get_dlssd_optimal_settings(uint32_t mode,
        uint32_t output_width, uint32_t output_height, uint32_t* out_render_width,
        uint32_t* out_render_height, float* out_sharpness);
SLBRIDGE_EXPORT int32_t slbridge_set_dlssd_options(uint32_t viewport,
        const slbridge_dlssd_options* options);
SLBRIDGE_EXPORT int32_t slbridge_evaluate_dlssd(uint64_t frame_token, uint32_t viewport,
        const slbridge_resource_desc* resources, uint32_t resource_count,
        const slbridge_constants* constants, uint64_t command_buffer);
SLBRIDGE_EXPORT int32_t slbridge_free_dlssd_resources(uint32_t viewport);

SLBRIDGE_EXPORT int32_t slbridge_set_reflex_options(const slbridge_reflex_options* options);
SLBRIDGE_EXPORT int32_t slbridge_reflex_sleep(uint64_t frame_token);
SLBRIDGE_EXPORT int32_t slbridge_pcl_set_marker(uint32_t marker, uint64_t frame_token);
SLBRIDGE_EXPORT int32_t slbridge_get_reflex_state(slbridge_reflex_state* out_state);

#ifdef __cplusplus
}
#endif
