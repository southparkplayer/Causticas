#version 460
#extension GL_EXT_ray_tracing : require

// Primary-ray miss: a simple sky gradient by ray height — placeholder until P3 lighting / a real sky
// model. hitT < 0 tells raygen this was a miss (sky), so it stores the color directly.
struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    float emission;
    vec3 motionPrev;
    float material;
    float roughness;
    float metalness;
    vec3 f0;
};
layout(location = 0) rayPayloadInEXT Payload payload;

void main() {
    float t = clamp(gl_WorldRayDirectionEXT.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 horizon = vec3(0.62, 0.71, 0.86);
    vec3 zenith = vec3(0.22, 0.45, 0.85);
    payload.albedo = mix(horizon, zenith, t);
    payload.hitT = -1.0;
    payload.emission = 0.0;
    payload.motionPrev = vec3(0.0);
    payload.material = 0.0;
    payload.roughness = 1.0;
    payload.metalness = 0.0;
    payload.f0 = vec3(0.0);
}
