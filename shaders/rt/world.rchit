#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_nonuniform_qualifier : require

// P2 closest-hit. Geometry is per-section: gl_InstanceCustomIndexEXT indexes a section table (BDA
// array reached from the push constant) holding this section's {prim, index, uv} buffer addresses.
// From there it's the same as before: per-primitive {normal, tint} (gl_PrimitiveID) + per-vertex
// atlas UVs (index buffer -> UV buffer, barycentric-interpolated) -> atlas albedo. Lighting is done
// in raygen (deferred), so this writes albedo/normal/hitT into the payload and does no shading.
struct Prim {
    vec4 normal;
    vec4 tint;
    vec4 mat; // P6.1: {roughness, metalness, 0, 0} heuristic PBR material (RtMaterials)
};
struct Section {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
};
// P5.1b-2 entity geometry record: the entity's {prim, index, uv} buffer addresses (same per-prim
// {normal, tint} + UV layout as a terrain section) plus its per-object world-space displacement since
// the previous frame (xyz; w padding) for the motion vector. std430 packs disp at offset 32 (48-byte
// stride; matches RtEntities' table writes).
struct EntityGeom {
    uint64_t primAddr;
    uint64_t idxAddr;
    uint64_t uvAddr;
    vec4 disp;
};

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Prims { Prim p[]; };
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Indices { uint i[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer UVs { vec2 uv[]; };
layout(buffer_reference, std430, buffer_reference_align = 8) readonly buffer SectionTable { Section s[]; };
// P5.1b-2: per-entity geometry records, indexed by the entity instance index (the low bits of
// gl_InstanceCustomIndexEXT). See RtEntities.
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer EntityTable { EntityGeom e[]; };

layout(binding = 2, set = 0) uniform sampler2D blockAtlas;
// P6.2a: parallel LabPBR _s atlas, stitched to mirror the block atlas sprite layout (RtBlockMaterials),
// so it samples at the SAME uv as blockAtlas. Read only when the prim is flagged (pr.mat.z > 0.5).
layout(binding = 8, set = 0) uniform sampler2D blockSpecAtlas;
// P5.1b-2b: bindless entity textures — a runtime-sized array indexed per-prim (tint.w) by the entity
// hit path. Slot 0 is a fallback. Entities use per-type texture files, so each RenderType gets a slot.
layout(binding = 0, set = 1) uniform sampler2D entityTex[];

layout(push_constant) uniform Push {
    mat4 invViewProj;
    vec3 camOffset;
    uint64_t tableAddr;
    layout(offset = 184) uint64_t entityTableAddr;
} pc;

struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    float emission; // block light level 0..1 (stashed in prim normal.w during extraction)
    vec3 motionPrev; // world displacement since last frame (entity per-object MV); 0 for static terrain
    float material;  // P5.2: 0 = opaque diffuse, 1 = water (smooth dielectric, handled in raygen)
    float roughness; // P6.1: perceptual roughness for the GGX BRDF
    float metalness; // P6.1: 0 = dielectric, 1 = conductor
    vec3 f0;         // P6.2a: specular reflectance at normal incidence (dielectric 0.04 / LabPBR / metal)
};
layout(location = 0) rayPayloadInEXT Payload payload;
hitAttributeEXT vec2 attribs;

// P5.1b-2 dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
// geometry. Their gl_InstanceCustomIndexEXT (low bits) indexes the entity geometry table, not the
// section table; shading reads per-prim normal + vertex-colour tint (no atlas — entity textures are
// P5.1b-2b) and the per-object MV displacement.
const int ENTITY_BIT = 0x800000;

// P6.2a LabPBR predefined metals (green channel 230..237): complex refractive indices N (eta) and K
// (kappa) per RGB. F0 = ((n-1)^2 + k^2) / ((n+1)^2 + k^2). Values per the LabPBR 1.3 standard.
const vec3 METAL_N[8] = vec3[8](
    vec3(2.9114, 2.9497, 2.5845),   // 230 iron
    vec3(0.18299, 0.42108, 1.3734), // 231 gold
    vec3(1.3456, 0.96521, 0.61722), // 232 aluminum
    vec3(3.1071, 3.1812, 2.3230),   // 233 chrome
    vec3(0.27105, 0.67693, 1.3164), // 234 copper
    vec3(1.9100, 1.8300, 1.4400),   // 235 lead
    vec3(2.3757, 2.0847, 1.8453),   // 236 platinum
    vec3(0.15943, 0.14512, 0.13547) // 237 silver
);
const vec3 METAL_K[8] = vec3[8](
    vec3(3.0893, 2.9318, 2.7670),
    vec3(3.4242, 2.3459, 1.7704),
    vec3(7.4746, 6.3995, 5.3031),
    vec3(3.3314, 3.3291, 3.1350),
    vec3(3.6092, 2.6248, 2.2921),
    vec3(3.5100, 3.4000, 3.1800),
    vec3(4.2655, 3.7153, 3.1365),
    vec3(3.9291, 3.1900, 2.3808)
);
vec3 metalF0(int idx) {
    vec3 n = METAL_N[idx];
    vec3 k = METAL_K[idx];
    return ((n - 1.0) * (n - 1.0) + k * k) / ((n + 1.0) * (n + 1.0) + k * k);
}

void main() {
    if ((gl_InstanceCustomIndexEXT & ENTITY_BIT) != 0) {
        int eidx = gl_InstanceCustomIndexEXT & ~ENTITY_BIT;
        EntityGeom g = EntityTable(pc.entityTableAddr).e[eidx];
        Prim pr = Prims(g.primAddr).p[gl_PrimitiveID];
        vec3 n = normalize(pr.normal.xyz);
        if (dot(n, gl_WorldRayDirectionEXT) > 0.0) {
            n = -n; // orient toward the viewer, like the terrain path below
        }
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/etc.).
        Indices eidxBuf = Indices(g.idxAddr);
        UVs euv = UVs(g.uvAddr);
        uint e0 = eidxBuf.i[3u * gl_PrimitiveID + 0u];
        uint e1 = eidxBuf.i[3u * gl_PrimitiveID + 1u];
        uint e2 = eidxBuf.i[3u * gl_PrimitiveID + 2u];
        vec3 ebary = vec3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        vec2 euvCoord = ebary.x * euv.uv[e0] + ebary.y * euv.uv[e1] + ebary.z * euv.uv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        payload.albedo = texture(entityTex[nonuniformEXT(texSlot)], euvCoord).rgb * pr.tint.rgb;
        payload.normal = n;
        payload.hitT = gl_HitTEXT;
        payload.emission = 0.0;
        payload.motionPrev = g.disp.xyz; // per-object MV (P5.1c)
        payload.material = 0.0;          // entities are opaque
        payload.roughness = pr.mat.x;    // P6.1
        payload.metalness = pr.mat.y;
        payload.f0 = mix(vec3(0.04), payload.albedo, pr.mat.y); // entity F0 (dielectric / metal)
        return;
    }

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
    payload.motionPrev = vec3(0.0); // static terrain: camera-only motion vector
    payload.material = pr.tint.w;   // P5.2: 1 = water dielectric, 0 = opaque (set by extraction)

    // P6.1 heuristic defaults, overridden per-texel by LabPBR _s when present (P6.2a, flagged in mat.z).
    float rough = pr.mat.x;
    float metal = pr.mat.y;
    vec3 f0 = mix(vec3(0.04), payload.albedo, metal);
    float emission = pr.normal.w;   // 0..1 block light level (extraction), the heuristic emission source
    if (pr.mat.z > 0.5) {
        vec4 s = textureLod(blockSpecAtlas, uv, 0.0);
        rough = (1.0 - s.r) * (1.0 - s.r);          // red = perceptual smoothness -> roughness
        float g = s.g * 255.0;                      // green = reflectance / metal index
        if (g < 229.5) {                            // 0..229: dielectric, linear F0 = green/255
            metal = 0.0;
            f0 = vec3(s.g);
        } else if (g < 237.5) {                     // 230..237: predefined metal (N/K table)
            metal = 1.0;
            f0 = metalF0(int(g + 0.5) - 230);
        } else {                                    // 238..255: generic metal, albedo as F0
            metal = 1.0;
            f0 = payload.albedo;
        }
        float a = s.a * 255.0;                      // alpha = emission (255 = ignored, 0..254 = strength)
        if (a < 254.5) {
            emission = max(emission, a / 254.0);
        }
        // (blue channel: porosity 0..64 / SSS 65..255 — deferred, not consumed yet.)
    }
    payload.roughness = rough;
    payload.metalness = metal;
    payload.f0 = f0;
    payload.emission = emission;
}
