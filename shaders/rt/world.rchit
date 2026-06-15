#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// P2 closest-hit. Geometry is per-section: gl_InstanceCustomIndexEXT indexes a section table (BDA
// array reached from the push constant) holding this section's {prim, index, uv} buffer addresses.
// From there it's the same as before: per-primitive {normal, tint} (gl_PrimitiveID) + per-vertex
// atlas UVs (index buffer -> UV buffer, barycentric-interpolated) -> atlas albedo. Lighting is done
// in raygen (deferred), so this writes albedo/normal/hitT into the payload and does no shading.
struct Prim {
    vec4 normal;
    vec4 tint;
};
struct Section {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims { Prim p[]; };
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Indices { uint i[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer UVs { vec2 uv[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer SectionTable { Section s[]; };

layout(binding = 2, set = 0) uniform sampler2D blockAtlas;

layout(push_constant) uniform Push {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t tableAddr;
} pc;

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    float emission; // block light level 0..1 (stashed in prim normal.w during extraction)
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

void main() {
    Section sec = SectionTable(pc.tableAddr).s[gl_InstanceCustomIndexEXT];
    uint pid = gl_PrimitiveID;
    Prim pr = Prims(sec.primAddr).p[pid];
    vec3 n = normalize(pr.normal.xyz);
    vec3 tint = pr.tint.rgb;

    Indices indices = Indices(sec.idxAddr);
    UVs uvs = UVs(sec.uvAddr);
    uint i0 = indices.i[3u * pid + 0u];
    uint i1 = indices.i[3u * pid + 1u];
    uint i2 = indices.i[3u * pid + 2u];
    vec3 bary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    vec2 uv = bary.x * uvs.uv[i0] + bary.y * uvs.uv[i1] + bary.z * uvs.uv[i2];

    // Orient the normal toward the viewer so the AO/shadow offset and N·L stay correct even if a
    // back-face is hit.
    if (dot(n, gl_WorldRayDirectionEXT) > 0.0) {
        n = -n;
    }

    payload.albedo = textureLod(blockAtlas, uv, 0.0).rgb * tint;
    payload.normal = n;
    payload.hitT = gl_HitTEXT;
    payload.emission = pr.normal.w; // 0..1 light level, written by extraction into the free slot
}
