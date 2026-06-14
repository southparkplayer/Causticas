#version 460
#extension GL_EXT_ray_tracing : require

// Classic barycentric RGB triangle — the canonical "RT is working" image.
layout(location = 0) rayPayloadInEXT vec3 payload;
hitAttributeEXT vec2 attribs;

void main() {
    vec3 bary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    payload = bary;
}
