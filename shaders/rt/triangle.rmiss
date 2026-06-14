#version 460
#extension GL_EXT_ray_tracing : require

// Background for rays that hit nothing. Flat dark blue so the triangle reads clearly.
layout(location = 0) rayPayloadInEXT vec3 payload;

void main() {
    payload = vec3(0.02, 0.02, 0.05);
}
