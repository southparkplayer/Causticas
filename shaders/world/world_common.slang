// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

#ifndef CAUSTICA_SHARC
#define CAUSTICA_SHARC 0
#endif

// Read-only device pointer. Current Vulkan SDK Slang exposes Ptr with three generic arguments; explicit
// vk::offset annotations below pin the one natural-layout tail that differs from std430 so the generated
// Java serializer remains in lock-step without requiring scalarBlockLayout.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device>;

// Push constant block. Only the first 8 bytes (worldPushAddr) are meaningful to rgen/rchit/rmiss;
// the hot inline lanes (tableAddr/entityTableAddr/frameIndex) exist so world.rahit's per-hit
// Section/EntityGeom fetch skips the extra global-memory load of dereferencing WorldPush first.
public struct PushAddr {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint frameIndex;
};

// SHaRC pipelines preserve PushAddr's first 32 bytes so the unchanged hit/miss stages see the exact
// baseline ABI. Only the SHaRC raygen variants read the final BDA lane.
public struct SharcPushAddr {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint frameIndex;
    public uint64_t sharcFrameAddr;
};

// Separate from WorldPush so the baseline pipeline's payload and per-frame data remain byte-for-byte
// unchanged when SHaRC is not packaged or is disabled.
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
    public uint flags; // bit 0 anti-firefly filter, bit 1 diagnostic counters
    public uint renderWidth;
    public uint renderHeight;
    public uint updateTileSize;
    public uint updateMaxBounces;
    public float minSegmentRatio;
    public uint queryFlags; // bit 0 strict glossy cone queries, bit 1 evaluate secondary direct before query
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint64_t tableAddr;        // section table
    public uint     debugView;
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint64_t entityTableAddr;  // entity geometry table
    public uint     flags;            // bit0 submerged, bit1 PBR, bit4 waves, bit10 Earth atmosphere
    public uint     maxBounces;
    [vk::offset(208)] public float4   sunDir;        // xyz true sun direction, w vanilla rain brightness
    [vk::offset(224)] public float4   lightDir;      // xyz active NEE light dir, w circular half-angle (rad)
    [vk::offset(240)] public float4   lightRadiance; // xyz HDR light radiance, w star brightness
    [vk::offset(256)] public float4   moonDir;       // xyz moon direction, w moonPhase (0 full .. 4 new)
    [vk::offset(272)] public float4   celestial;     // xyz celestial rotation axis, w star angle (rad)
    [vk::offset(288)] public float4   sunUv;         // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    [vk::offset(304)] public float4   moonUv;        // vanilla current-moon-phase sprite atlas rect
    [vk::offset(320)] public float4   waterParams;   // xyz camera-biome water tint, w wave time (s)
    [vk::offset(336)] public float4   waterAnchor;   // xy world-stable wave anchor, z previous wave time
    [vk::offset(352)] public float4x4 curViewProj;   // forward camera-relative view-projection
    [vk::offset(416)] public uint     breakCount;
    [vk::offset(420)] public float    torchIntensity; // normalized perceptual control; only torch-family hits use it
    [vk::offset(424)] public uint     psrMaxMirrors; // live exact-mirror recursion limit (1..32)
    [vk::offset(432)] public BreakEntry breaking[8]; // generated Java capacity/serialization
    [vk::offset(560)] public uint64_t sampleSequenceAddr; // interleaved 8D Sobol values, 1048576 samples
    [vk::offset(576)] public float4 environmentSky; // xyz dimension sky linear BT.2020; w reserved
    [vk::offset(592)] public float4 skyState; // x physical daylight, y civil twilight, z ambient, w solar envelope
    [vk::offset(608)] public float4 skyLighting; // x sun multiplier, y moon multiplier, z airglow, w lunar lit fraction
};

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
public struct Payload {
    public float3 albedo;      // hit: block albedo. miss: sky radiance.
    public float3 normal;      // hit: shading normal, viewer-oriented.
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public float3 motionPrev;  // per-vertex world displacement since last frame.
    public float3 f0;          // specular F0.
    public uint   flags;       // bits 0..1 material, bit 2 sky disc, bit 3 interface-entering, bits 4..7 optical class.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   rayCone;     // packHalf2x16(width, spread)
    // Always present: all ray families need the unperturbed geometric normal for robust ray spawning.
    // The ordinary `normal` member remains the shading normal used by BRDF evaluation.
    public float3 geometricNormal;
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit on a MATERIAL_WATER hit: true when the incoming ray travels against the prim's
// outward face normal (entering the water volume), false when it exits (water -> air). Bit 2
// (PAYLOAD_SHOW_CELESTIAL) is only meaningful on a miss and this bit only on a hit, so they never need
// to be read together, but they get distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_INTERFACE_ENTERING = 8u;
public static const uint PAYLOAD_WATER_ENTERING = PAYLOAD_INTERFACE_ENTERING;
public static const uint PAYLOAD_OPTICAL_CLASS_SHIFT = 4u;
public static const uint PAYLOAD_OPTICAL_CLASS_MASK = 0xfu << PAYLOAD_OPTICAL_CLASS_SHIFT;
#if CAUSTICA_SHARC
public static const uint PAYLOAD_SHARC_DYNAMIC = 1u << 8u;
#endif
public static const uint PAYLOAD_TORCH_EMITTER = 1u << 9u;
// Trace-state bit consumed only by world.rmiss. Exact mirrors expose the sky-view LUT's cell boundaries,
// so their continuation misses request the C2 reconstruction instead of the four-tap bilinear path.
public static const uint PAYLOAD_FILTER_SKY = 1u << 10u;

// Terrain per-triangle record (48 B, stride must match RtTerrain's upload).
// mat = {roughness, metalness, hasS, hasN} for solid/cutout; TRANSLUCENT-bucket prims repurpose it as
// a precomputed whole-sprite average color {r,g,b,a} (RtTerrain.emit()/translucentAvgColor).
public struct Prim {
    public float4 normal;
    public float4 tint;
    public float4 mat;
};

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
};

// Entity geometry record: {prim, index, uv} buffer addresses (same per-prim layout as terrain) + a
// per-vertex world-space displacement buffer for motion vectors (dispAddr == 0 ⇒ rigidDisp.xyz).
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
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


// Scene transport uses linear BT.2020. Minecraft assets are authored for an sRGB/BT.709 display,
// so color textures cross this boundary once after decoding. Keeping one wide-gamut transport basis
// through accumulation avoids mixing primary systems in temporal history. These component forms avoid
// row/column-major ambiguity.
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

public static const float3 BT2020_LUMA = float3(0.2627, 0.6780, 0.0593);

public float transportLuminance(float3 c) {
    return dot(c, BT2020_LUMA);
}
