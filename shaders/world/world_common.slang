// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

#ifndef CAUSTICA_SHARC
#define CAUSTICA_SHARC 0
#endif

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;
    public uint frameIndex;
    public uint debugView;
};

// Fork transport modules historically call this exact ABI PushAddr.
public typealias PushAddr = WorldPushConstants;

// SHaRC uses the same upstream material-table/debug ABI and appends one cache-frame address.
public struct SharcPushAddr {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;
    public uint frameIndex;
    public uint debugView;
    public uint64_t sharcFrameAddr;
};

public struct SharcFrame {
    public uint64_t hashEntriesAddr;
    public uint64_t accumulationAddr;
    public uint64_t resolvedAddr;
    public uint64_t statsAddr;
    public float3 cameraPosition;
    public uint capacity;
    public float3 cameraPositionPrev;
    public uint frameIndex;
    public float sceneScale;
    public float radianceScale;
    public uint accumulationFrameNum;
    public uint staleFrameNumMax;
    public uint flags;
    public uint renderWidth;
    public uint renderHeight;
    public uint updateTileSize;
    public uint updateMaxBounces;
    public float minSegmentRatio;
    public uint queryFlags;
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint64_t tableAddr;        // section table
    public uint     debugView;        // retained for fork offline/diagnostic consumers
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint64_t entityTableAddr;  // entity geometry table
    public uint     flags;            // bit0 camera submerged, bit1 PBR BRDF, bit4 water waves
    public uint     maxBounces;
    [vk::offset(208)] public float4   sunDir;
    [vk::offset(224)] public float4   lightDir;
    [vk::offset(240)] public float4   lightRadiance;
    [vk::offset(256)] public float4   moonDir;
    [vk::offset(272)] public float4   celestial;
    [vk::offset(288)] public float4   celestialRadii; // sun radius, moon radius, reserved, reserved
    [vk::offset(304)] public float4   moonUv;
    [vk::offset(320)] public float4   waterParams;
    [vk::offset(336)] public float4   waterAnchor;
    [vk::offset(352)] public float4x4 curViewProj;
    [vk::offset(416)] public uint     breakCount;
    [vk::offset(420)] public float    torchIntensity;
    [vk::offset(424)] public uint     psrMaxMirrors;
    [vk::offset(432)] public BreakEntry breaking[8];
    [vk::offset(560)] public uint64_t sampleSequenceAddr;
    [vk::offset(576)] public float4 environmentSky;
    [vk::offset(592)] public float4 skyState;
    [vk::offset(608)] public float4 skyLighting;
    [vk::offset(624)] public float4 borderFogColor;
    [vk::offset(640)] public float4 borderFogParams; // start distance, end distance, reserved, reserved
    [vk::offset(656)] public float4 chunkFade; // current clock, duration, previous clock, reserved
    [vk::offset(672)] public float4 nrdSettings; // hit distance A/B/C, denoiser family (0 REBLUR, 1 RELAX)
};

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
public struct Payload {
    public float3 albedo;      // hit: block albedo. miss: sky radiance.
    public float3 normal;      // hit: geometric normal, viewer-oriented.
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public float3 motionPrev;  // per-vertex world displacement since last frame.
    public float3 f0;          // specular F0.
    public uint   flags;       // bits 0..1 material, bit 2 celestial, bit 3 water-entering, bits 4..6 emission source.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
    public float3 geometricNormal;
    public float chunkFade; // CHUNK_FADE_STATE_* for the primary terrain hit
};

public static const float CHUNK_FADE_STATE_HIDDEN = 0.0;
public static const float CHUNK_FADE_STATE_STABLE = 1.0;
public static const float CHUNK_FADE_STATE_REVEALED = 2.0;
public static const float CHUNK_FADE_STATE_SETTLED = 3.0;

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit on a dielectric hit: true when the incoming ray travels against the prim's
// outward face normal (entering the water/glass volume), false when it exits. Bit 2
// (PAYLOAD_SHOW_CELESTIAL) is only meaningful on a miss and this bit only on a hit, so they never need
// to be read together, but they get distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_WATER_ENTERING = 8u;
public static const uint PAYLOAD_EMISSION_SOURCE_SHIFT = 12u;
public static const uint PAYLOAD_EMISSION_SOURCE_MASK = 7u;
public static const uint EMISSION_SOURCE_NONE = 0u;
public static const uint EMISSION_SOURCE_UNIFORM = 1u;
public static const uint EMISSION_SOURCE_HEURISTIC = 2u;
public static const uint EMISSION_SOURCE_LAB_PBR = 3u;
public static const uint EMISSION_SOURCE_OVERRIDE = 4u;
public static const uint PAYLOAD_INTERFACE_ENTERING = PAYLOAD_WATER_ENTERING;
public static const uint PAYLOAD_OPTICAL_CLASS_SHIFT = 4u;
public static const uint PAYLOAD_OPTICAL_CLASS_MASK = 0xfu << PAYLOAD_OPTICAL_CLASS_SHIFT;
// TerrainPrim.aux0 bit 4 records that the non-glass side of this clear interface is water. The fluid
// mesher culls its coincident face, so the surviving glass/ice primitive owns the complete transition.
public static const uint PAYLOAD_INTERFACE_EXTERIOR_WATER = 1u << 15u;
#if CAUSTICA_SHARC
public static const uint PAYLOAD_SHARC_DYNAMIC = 1u << 8u;
#endif
public static const uint PAYLOAD_TORCH_EMITTER = 1u << 9u;
public static const uint PAYLOAD_FILTER_SKY = 1u << 10u;
// Camera visibility is allowed to conceal the finite RT terrain boundary with fog. Transport rays keep
// the conservative dark void so missing geometry cannot become an indirect-light source.
public static const uint PAYLOAD_PRIMARY_RAY = 1u << 11u;
// Raygen specializes the chunk-reveal off state before tracing so closest-hit never dereferences the
// cold WorldPush BDA merely to discover that reveal is disabled.
public static const uint PAYLOAD_CHUNK_REVEAL_ACTIVE = 1u << 16u;

public static const uint PAYLOAD_MATERIAL_MASK = 3u;
public static const uint MATERIAL_OPAQUE = 0u;
public static const uint MATERIAL_WATER = 1u;
public static const uint MATERIAL_PARTICLE = 2u;
public static const uint MATERIAL_GLASS = 3u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Entity-only primitive semantic. A translucent baked item captured as part of the directly visible
// first-person body is an optical presentation surface, not a macroscopic closed glass volume.
// Terrain currently uses bit 0 for torch-emitter ownership, so keep this on a separate bit.
public static const uint PRIM_FIRST_PERSON_THIN_GLASS = 1u << 1u;

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in MaterialHeader instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Compiled material record (80 B). Source-format data has already been decoded into canonical pages;
// texturePage indexes the dedicated canonical page arrays. materialUv maps sprite-local UV into the packed
// page, while albedoUv maps the vanilla atlas rectangle back to sprite-local UV.
public struct MaterialHeader {
    public uint model;
    public uint features;        // low feature bits; bits 24..31 = maximum valid material-page LOD
    public uint texturePage;
    public uint extensionOffset; // zero when no optional extension record is present
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
    public float4 params;  // roughness, metalness, IOR, transmission
    public float4 average; // whole-sprite linear-input average RGBA for translucent shadow filtering
};

public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_HEURISTIC_EMISSION = 4u;
public static const uint MATERIAL_FEATURE_OVERRIDE_EMISSION = 8u;
public static const uint MATERIAL_FEATURE_STOCHASTIC_ALPHA = 16u;
public static const uint MATERIAL_EMISSION_STRENGTH_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_STRENGTH_MASK = 255u;
public static const float MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH = 4.0;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;
public static const uint MATERIAL_MAX_LOD_MASK = 255u;

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
    public float publishedTime;
    // Keep these scalar: a trailing float3 has 16-byte alignment and silently changes this BDA array's
    // stride from the CPU-authored 48 bytes to 64 bytes.
    public float reserved0;
    public float reserved1;
    public float reserved2;
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/any-hit; PrimitiveIndex restarts at zero for each geometry.
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[2];
    public uint2 reserved;
};

// TLAS instance-custom-index encoding + terrain geometry buckets (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index
public static const uint BUCKET_CUTOUT = 1u;
public static const uint BUCKET_TRANSLUCENT = 2u;
public static const uint BUCKET_WATER = 3u;

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

public static const float3 BT2020_LUMA = float3(0.2627, 0.6780, 0.0593);

public float transportLuminance(float3 c) {
    return dot(c, BT2020_LUMA);
}

public float3 bt709ToBt2020(float3 c) {
    return float3(
        0.6274039 * c.x + 0.3292830 * c.y + 0.0433131 * c.z,
        0.0690973 * c.x + 0.9195406 * c.y + 0.0113612 * c.z,
        0.0163916 * c.x + 0.0880132 * c.y + 0.8955953 * c.z);
}

public float3 bt2020ToBt709(float3 c) {
    return float3(
         1.6604910 * c.x - 0.5876411 * c.y - 0.0728499 * c.z,
        -0.1245505 * c.x + 1.1328999 * c.y - 0.0083494 * c.z,
        -0.0181508 * c.x - 0.1005789 * c.y + 1.1187297 * c.z);
}

// Vanilla's block/entity atlases are ordinary sRGB-encoded PNGs sampled through a plain UNORM view (no
// hardware sRGB decode), so the raw texel is gamma-encoded. The path tracer's BRDF math (Lambertian/GGX,
// Beer-Lambert, NEE) is only correct on linear radiance/albedo, so every COLOR texture sample (not the
// LabPBR _s/_n data maps, which are already linear data channels) must go through this before it's used
// in any lighting calculation.
public float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4, 2.4, 2.4));
    float3 isHi = step(float3(0.04045, 0.04045, 0.04045), c);
    return lerp(lo, hi, isHi);
}
