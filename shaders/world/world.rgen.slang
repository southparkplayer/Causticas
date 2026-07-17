// Perspective path tracer. invViewProj = inverse(proj * viewRot) maps clip to camera-relative world.
// Primary rays are reconstructed from two unprojected points (near and far plane) rather than assuming
// the eye is at the origin: Minecraft bakes view bobbing into the projection, so the effective eye comes
// from the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
//
// Lighting is an iterative path-tracing loop in raygen, not recursion from closest-hit, so
// maxRayRecursionDepth stays 1. At each surface vertex:
//   * NEE toward the sun — a single analytic directional light, gated by one hit-object visibility query
//     (TerminateOnFirstHit | SkipClosestHit). Lambertian BRDF = albedo/PI.
//   * a cosine-weighted Lambertian continuation ray (throughput *= albedo; the PI and cos cancel).
// Sky / ambient light needs no separate AO pass: continuation rays that escape land in world.rmiss,
// which returns sky radiance — so soft sky fill and contact AO both fall out of the path integral.
// Russian roulette terminates deep paths. Output is HDR (R16G16B16A16_SFLOAT); the HDR -> LDR tonemap
// happens later at the display.comp seam.
//
// SER: the build emits EXT and NV shader-invocation-reorder variants from this source.
//
// Each frame emits a single SPP-averaged (still noisy) estimate; temporal convergence is the denoiser's
// job. With RR disabled the output is the raw noisy path trace — a reference view, not a converged image.
#ifndef CAUSTICA_SHARC
#define CAUSTICA_SHARC 0
#endif
#ifndef CAUSTICA_SHARC_UPDATE
#define CAUSTICA_SHARC_UPDATE 0
#endif
#ifndef CAUSTICA_SHARC_QUERY
#define CAUSTICA_SHARC_QUERY 0
#endif
#ifndef CAUSTICA_SHARC_DIAGNOSTICS
#define CAUSTICA_SHARC_DIAGNOSTICS 1
#endif
#ifndef CAUSTICA_SHARC_GLOSSY
#define CAUSTICA_SHARC_GLOSSY 1
#endif
#ifndef CAUSTICA_SHARC_LIVE_SECONDARY_DIRECT
#define CAUSTICA_SHARC_LIVE_SECONDARY_DIRECT 0
#endif
#ifndef CAUSTICA_OFFLINE
#define CAUSTICA_OFFLINE 0
#endif
#ifndef CAUSTICA_SER
#define CAUSTICA_SER 0
#endif

import world_common;

#if CAUSTICA_SHARC_UPDATE || CAUSTICA_SHARC_QUERY
#define SHARC_UPDATE CAUSTICA_SHARC_UPDATE
#define SHARC_QUERY CAUSTICA_SHARC_QUERY
#include "../sharc/sharc_bridge.slang"
[[vk::push_constant]] SharcPushAddr pcAddr;
static SharcFrame sharcFrame;
static SharcParameters sharcParameters;
static SharcRWPtr<uint> sharcStats;
#else
[[vk::push_constant]] PushAddr pcAddr;
#endif

[[vk::binding(0, 0)]] RaytracingAccelerationStructure topLevelAS;
// HDR trace target: linear radiance, may exceed 1. Tonemap happens later at the display.comp seam.
[[vk::binding(1, 0)]] [format("rgba16f")] RWTexture2D<float4> outImage;
// Guide buffers — first-hit (primary-visibility) attributes consumed by the denoiser/DLSS-RR. Written
// every frame regardless of accumulation. gNormal.w carries roughness; gDepth carries HW reversed-Z depth.
#if !CAUSTICA_OFFLINE
[[vk::binding(3, 0)]] [format("rgba16f")] RWTexture2D<float4> gNormal;     // xyz world normal, w roughness
[[vk::binding(4, 0)]] [format("rgba16f")] RWTexture2D<float4> gAlbedo;     // rgb diffuse albedo
[[vk::binding(5, 0)]] [format("r32f")]    RWTexture2D<float>  gDepth;      // HW reversed-Z depth
[[vk::binding(6, 0)]] [format("rg16f")]   RWTexture2D<float2> gMotion;     // screen-space motion (render px)
[[vk::binding(7, 0)]] [format("rgba16f")] RWTexture2D<float4> gSpecAlbedo; // specular albedo (0 — diffuse-only)
[[vk::binding(8, 0)]] [format("rg16f")]   RWTexture2D<float2> gSpecMotion; // reflection MVs for RR
[[vk::binding(10, 0)]] [format("r16f")] RWTexture2D<float> gAnimatedGuide; // temporal responsivity source
#else
[[vk::binding(9, 0)]] [format("rgba32f")] RWTexture2D<float4> groundTruthAccum; // RGB mean + sample count
[[vk::binding(11, 0)]] [format("rgba32f")] RWTexture2D<float4> offlinePilotRead;
[[vk::binding(12, 0)]] [format("rgba32f")] RWTexture2D<float4> offlinePilotWrite;
#endif

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `pc` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
static WorldPush pc;

#if CAUSTICA_SHARC_UPDATE || CAUSTICA_SHARC_QUERY
static const uint SHARC_STAT_QUERY_ATTEMPTS = 0u;
static const uint SHARC_STAT_QUERY_HITS = 1u;
static const uint SHARC_STAT_QUERY_MISSES = 2u;
static const uint SHARC_STAT_UPDATE_HITS = 3u;
static const uint SHARC_STAT_UPDATE_MISSES = 4u;
static const uint SHARC_STAT_INSERT_FAILURES = 5u;
static const uint SHARC_STAT_TERMINATED_BOUNCE_SUM = 6u;
static const uint SHARC_STAT_TERMINATED_PATHS = 7u;
static const uint SHARC_STAT_INSERTIONS = 9u;
static const uint SHARC_STAT_COLLISIONS = 10u;
static const uint SHARC_STAT_NUMERIC_RISKS = 12u;
static const uint SHARC_STAT_RESOLVED_SATURATIONS = 13u;
static const uint SHARC_STAT_MAX_CACHED_LUMA_BITS = 14u;
static const uint SHARC_STAT_SHORT_SEGMENT_REJECTS = 15u;
static const uint SHARC_STAT_GLOSSY_REJECTS = 16u;
static const uint SHARC_STAT_DYNAMIC_REJECTS = 17u;
#if CAUSTICA_SHARC_QUERY && CAUSTICA_SHARC_DIAGNOSTICS
static float3 gv_sharcDebug;
static float3 gv_sharcHashDebug;
static float gv_sharcTerminationDepth;
static float3 gv_sharcCachedRadianceDebug;
static float3 gv_sharcVoxelSizeDebug;
static float3 gv_sharcEligibilityDebug;
static float3 gv_sharcNumericDebug;
#endif

void sharcStatAdd(uint index, uint value) {
#if CAUSTICA_SHARC_DIAGNOSTICS
    if ((sharcFrame.flags & 2u) != 0u) {
        InterlockedAdd(sharcStats[index], value);
    }
#endif
}

void sharcStatMax(uint index, uint value) {
#if CAUSTICA_SHARC_DIAGNOSTICS
    if ((sharcFrame.flags & 2u) != 0u) {
        InterlockedMax(sharcStats[index], value);
    }
#endif
}

SharcHitData causticaSharcHit(float3 position, float3 albedo, float3 emissive) {
    SharcHitData hit;
    hit.positionWorld = position;
    hit.normalWorld = payload.geometricNormal;
    hit.materialDemodulation = max(albedo, float3(1.0e-3));
    hit.emissive = emissive;
    return hit;
}

float causticaSharcVoxelSize(float3 position) {
    uint level = HashGridGetLevel(position, sharcParameters.gridParameters);
    return HashGridGetVoxelSize(level, sharcParameters.gridParameters);
}
#endif

// Ray payloads. Module-level statics so the helper functions (visibility / opticalGuideHit /
// specularReflectionMotion / tracePath) can share them, mirroring the GLSL rayPayloadEXT globals.
static Payload payload;
static float4 shadowVis; // rgb = transmittance; a = nearest water-crossing t (-1 = none)

#if CAUSTICA_SHARC_QUERY
bool causticaTrySharcQuery(int bounce, float3 hitPos, float3 albedo, float3 materialEmissive,
        bool pathEligible, bool previousLobeDiffuse, float previousGlossyAlpha,
        out float3 cachedRadiance) {
    cachedRadiance = float3(0.0);
    bool dynamicSurface = (payload.flags & PAYLOAD_SHARC_DYNAMIC) != 0u;
    float queryVoxelSize = causticaSharcVoxelSize(hitPos);
    float minSegment = queryVoxelSize * max(sharcFrame.minSegmentRatio, 0.0);
#if CAUSTICA_SHARC_GLOSSY
    float glossyDenominator = max(1.0 - previousGlossyAlpha * previousGlossyAlpha, 1.0e-6);
    float glossyCone = 2.0 * payload.hitT * sqrt(0.5 * previousGlossyAlpha
            * previousGlossyAlpha / glossyDenominator);
    bool glossyEligible = (sharcFrame.queryFlags & 1u) != 0u && glossyCone >= minSegment;
#else
    bool glossyEligible = false;
#endif
    bool segmentEligible = payload.hitT >= minSegment;
    bool lobeEligible = previousLobeDiffuse || glossyEligible;
    bool queryEligible = bounce > 0 && pathEligible && !dynamicSurface && segmentEligible && lobeEligible;
#if CAUSTICA_SHARC_DIAGNOSTICS
    bool statsEnabled = (sharcFrame.flags & 2u) != 0u;
    if (pc.debugView == 14u) {
        gv_sharcVoxelSizeDebug = float3(saturate(log2(1.0 + queryVoxelSize) / 8.0),
                saturate(log2(1.0 + minSegment) / 8.0), 0.1);
    }
    if (pc.debugView == 15u) {
        if (bounce == 0) gv_sharcEligibilityDebug = float3(0.08);
        else if (dynamicSurface) gv_sharcEligibilityDebug = float3(1.0, 0.0, 1.0);
        else if (!segmentEligible) gv_sharcEligibilityDebug = float3(1.0, 0.55, 0.0);
        else if (!lobeEligible) gv_sharcEligibilityDebug = float3(0.15, 0.35, 1.0);
        else gv_sharcEligibilityDebug = float3(0.05, 1.0, 0.15);
    }
    if (statsEnabled) {
        if (dynamicSurface) {
            sharcStatAdd(SHARC_STAT_DYNAMIC_REJECTS, 1u);
        } else if (!segmentEligible) {
            sharcStatAdd(SHARC_STAT_SHORT_SEGMENT_REJECTS, 1u);
        } else if (!lobeEligible) {
            sharcStatAdd(SHARC_STAT_GLOSSY_REJECTS, 1u);
        }
    }
#endif
    if (!queryEligible) return false;

#if CAUSTICA_SHARC_DIAGNOSTICS
    if (statsEnabled) sharcStatAdd(SHARC_STAT_QUERY_ATTEMPTS, 1u);
    if (pc.debugView == 9u) gv_sharcDebug = float3(1.0, 0.1, 0.05);
    if (pc.debugView == 12u) {
        gv_sharcHashDebug = HashGridDebugColoredHash(
                hitPos, payload.geometricNormal, sharcParameters.gridParameters);
    }
#endif
    SharcHitData queryHit = causticaSharcHit(hitPos, albedo, materialEmissive);
    if (!SharcGetCachedRadiance(sharcParameters, queryHit, cachedRadiance, false)) {
#if CAUSTICA_SHARC_DIAGNOSTICS
        if (statsEnabled) sharcStatAdd(SHARC_STAT_QUERY_MISSES, 1u);
#endif
        return false;
    }
#if CAUSTICA_SHARC_DIAGNOSTICS
    if (statsEnabled || pc.debugView == 13u || pc.debugView == 16u) {
        float cachedLuma = max(luminance(cachedRadiance), 0.0);
        sharcStatMax(SHARC_STAT_MAX_CACHED_LUMA_BITS, asuint(cachedLuma));
        if (pc.debugView == 13u) {
            float cachedHeat = saturate(log2(1.0 + cachedLuma) / 16.0);
            gv_sharcCachedRadianceDebug = float3(cachedHeat, cachedHeat * cachedHeat, 1.0 - cachedHeat);
        }
        if (any(cachedRadiance >= float3(65000.0))) {
            sharcStatAdd(SHARC_STAT_RESOLVED_SATURATIONS, 1u);
            if (pc.debugView == 16u) gv_sharcNumericDebug = float3(1.0, 0.0, 0.0);
        }
    }
    if (statsEnabled) {
        sharcStatAdd(SHARC_STAT_QUERY_HITS, 1u);
        sharcStatAdd(SHARC_STAT_TERMINATED_BOUNCE_SUM, uint(bounce));
        sharcStatAdd(SHARC_STAT_TERMINATED_PATHS, 1u);
    }
    if (pc.debugView == 9u) gv_sharcDebug = float3(0.05, 1.0, 0.15);
    if (pc.debugView == 10u) gv_sharcTerminationDepth = float(bounce);
#endif
    return true;
}
#endif

// First-hit (primary-visibility) guide attributes, captured at bounce 0 of tracePath. The primary ray
// is deterministic (no AA jitter yet), so every SPP sample's bounce 0 yields identical values.
static float3 gv_normal;
static float3 gv_albedo;
static float3 gv_specAlb; // specular albedo fed to DLSS-RR for specular demodulation (0 = pure diffuse).
static float gv_rough;
// Linear VIEW-SPACE depth guide inputs; sky resolves to a large far value (no negative sentinel).
static float gv_depth;
static float3 gv_hitCamRel;       // primary-surface hit position relative to the current camera
static float3 gv_motionHitCamRel; // motion-guide hit position; water tracks the refracted hit instead
static bool gv_motionUseRefracted; // true when the MV tracks the refracted hit (its own reprojection delta)
// World-space displacement of the motion-guide surface since the previous frame (0 for static
// terrain/sky, an entity's per-vertex/object motion for a dynamic hit).
static float3 gv_motionObjDisp;
static float3 gv_opticalGuideDir;
static uint gv_opticalGuideMode; // 0 none, 1 water, 2 glass exit, 3 glass entry (trace exact exit)
static float gv_opticalExitEta;
static float gv_animatedGuide;
// Previous animated-reflector normal; zero selects the current normal for static surfaces.
static float3 gv_specPreviousNormal;
// Primary Surface Replacement (PSR) is reserved for exact, static metallic mirrors. The radiance path
// follows the same deterministic delta reflection, while a separate guide ray replaces the mirror plane
// with the reflected surface in DLSSD's G-buffer.
static bool gv_psrMirror;

static const uint PAYLOAD_MATERIAL_MASK = 3u;

static const uint MATERIAL_OPAQUE = 0u;
static const uint MATERIAL_WATER = 1u;
static const uint MATERIAL_PARTICLE = 2u;
static const uint MATERIAL_GLASS = 3u;
static const uint OPTICAL_NONE = 0u;
static const uint OPTICAL_THIN_GLASS = 2u;
static const uint OPTICAL_SOLID_GLASS = 3u;
static const uint OPTICAL_SOLID_ICE = 4u;
static const uint OPTICAL_TRANSLUCENT_SURFACE = 5u;
static const uint FRAME_FLAG_RR_GUIDES = 1u << 5u;
static const uint FRAME_FLAG_FG_GUIDES = 1u << 6u;
static const uint FRAME_FLAG_OFFLINE_GROUND_TRUTH = 1u << 7u;
static const uint FRAME_FLAG_OFFLINE_CALIBRATING = 1u << 8u;

uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
uint payloadOpticalClass() {
    return (payload.flags & PAYLOAD_OPTICAL_CLASS_MASK) >> PAYLOAD_OPTICAL_CLASS_SHIFT;
}
bool payloadInterfaceEntering() { return (payload.flags & PAYLOAD_INTERFACE_ENTERING) != 0u; }
// Set by world.rchit only on a MATERIAL_WATER hit (see PAYLOAD_WATER_ENTERING) — whether the ray was
// travelling into the water volume (vs. out of it) at this hit's face.
bool payloadWaterEntering() { return (payload.flags & PAYLOAD_WATER_ENTERING) != 0u; }
float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }
float payloadEmission() { return unpackHalf2(payload.emissionSss).x; }
float payloadSss() { return unpackHalf2(payload.emissionSss).y; }
void payloadSetTraceState(bool showCelestial, float rayConeWidth, float rayConeSpread) {
    payload.flags = showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u;
    payload.rayCone = packHalf2(float2(rayConeWidth, rayConeSpread));
}

static const float PI = 3.14159265359;
static const float INV_PI = 0.31830988618;

// The analytic sky (gradient + sun/moon discs + stars) is computed in world.rmiss. On a miss, raygen
// reads payload.albedo and accumulates that sky radiance. The directional NEE light is pushed separately
// as pc.lightDir / pc.lightRadiance / pc.sunDir.
static const float EMISSIVE_BASE_RADIANCE = 3.0; // legacy radiance of a full normalized emitter
static const float EMISSIVE_MAX_MULTIPLIER = 50.0;
static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
static const float WATER_IOR = 1.333;
static const float WATER_GUIDE_ROUGH = 0.02;
static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Stained glass / ice (material 3): a thin colored dielectric. IOR ~1.5 (soda-lime glass).
static const float GLASS_IOR = 1.52;
static const float ICE_IOR = 1.31;
static const float GLASS_GUIDE_ROUGH = 0.04;
// Transmitted-ray origin offset when crossing a glass face. Must be SMALLER than the terrain-side
// TRANSLUCENT_INSET (RtTerrain, 2e-4 blocks): glass is recessed into its block by that inset, so a face
// touching a slab/stair leaves a tiny gap before the neighbour's surface. A large bias (SURF_BIAS) would
// restart the ray past that neighbour and see through it; this lands the ray inside the gap so the
// neighbour is still hit, while still clearing the glass surface to avoid self-intersection.
static const float GLASS_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in payload.albedo for
// water hits, or pc.waterParams.xyz for the camera's own biome when starting submerged). A blue ocean
// tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.
static const float WATER_DENSITY = 0.05;
static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
float3 waterExtinction(float3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays geometrically
// flat; only the normal tilts). Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see applyWaterWaves) so the pattern is pinned in world space, and the
// caustics (waterCaustic) differentiate this same field, so they stay in sync with the visible ripples.
static const float WATER_WAVE_STRENGTH = 0.3;  // slope scale (higher = choppier glints / more broken reflections)
static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
static const float WAVE_G = 9.81;
static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components
float2 waterWaveGrad(float2 p, float t) {
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
    float2 g = float2(0.0, 0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // Heading: deterministic irregular spread about the wind, widening down the spectrum.
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        if (i < 4) { // meander only the components whose fronts are long enough to read as lines
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float e = exp(S * (sin(ph) - 1.0));
        g += (Q[i] / k) * (S * cos(ph) * e) * dph; // a = Q/k; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        lambda /= 1.5;
    }
    return g * WATER_WAVE_STRENGTH;
}

void waterWaveGradPair(float2 p, float currentT, float previousT,
                       out float2 currentGrad, out float2 previousGrad) {
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    currentT *= WAVE_SPEED;
    previousT *= WAVE_SPEED;
    currentGrad = float2(0.0, 0.0);
    float2 timeDerivative = float2(0.0, 0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float phase = k * dot(d, p) - w * currentT + 1.7 * float(i) * float(i);
        float phaseDt = -w;
        float2 dph = k * d;
        float2 dphDt = float2(0.0, 0.0);
        if (i < 4) {
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * currentT;
            float wphDt = 0.5 * w;
            phase += WAVE_MEANDER * sin(wph);
            phaseDt += WAVE_MEANDER * cos(wph) * wphDt;
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
            dphDt = (-WAVE_MEANDER * sin(wph) * wphDt * 0.4 * k) * pd;
        }
        float S = 1.2 + 0.12 * float(i);
        float snPhase = sin(phase);
        float csPhase = cos(phase);
        float e = exp(S * (snPhase - 1.0));
        float amplitude = Q[i] / k * S;
        currentGrad += amplitude * csPhase * e * dph;
        timeDerivative += amplitude * e * (phaseDt * (-snPhase + S * csPhase * csPhase) * dph
                + csPhase * dphDt);
        lambda /= 1.5;
    }
    previousGrad = currentGrad - (currentT - previousT) * timeDerivative;
    currentGrad *= WATER_WAVE_STRENGTH;
    previousGrad *= WATER_WAVE_STRENGTH;
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

// ---- Water caustics. Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true focusing factor computable
// instead of faked with a scrolling texture. Where the shadow ray of an underwater NEE vertex crossed
// water (recorded by world.rahit in shadowVis.a), evaluate the horizontal landing position of the
// refracted sun ray as a function of surface position and finite-difference it: the caustic intensity is
// the inverse Jacobian determinant of that surface→floor mapping (area compression = brightening, real
// fold caustics where det → 0). Because this uses the SAME wave field as the visible surface normals,
// the caustic pattern stays in sync with the ripples, and the per-sample sun-quad jitter (sampleSquare)
// shifts the pattern per sample → caustics physically blur with depth under DLSS-RR accumulation.
static const float CAUSTIC_EPS = 0.25; // finite-difference step (m); also low-passes sub-step wave detail
static const float CAUSTIC_MAX = 6.0;  // focusing clamp: keeps fold-line singularities from becoming fireflies

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz, intersected
// with the receiver plane h metres below. inc = sunlight travel direction (downward, unit).
float2 causticLanding(float2 xz, float t, float3 inc, float h) {
    float2 g = waterWaveGrad(xz, t);
    float3 nw = normalize(float3(-g.x, 1.0, -g.y));
    float3 rdw = refract(inc, nw, 1.0 / WATER_IOR); // air→water: never TIR
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

float waterCaustic(float3 exitPos, float3 lightDir, float waterDist) {
    // Grazing light: the receiver-plane intersection degenerates and caustics vanish in reality anyway
    // (long slant paths absorb + the pattern smears out) — fade the effect to neutral 1.0.
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return 1.0;
    float h = max(waterDist * lightDir.y, 0.05); // vertical depth of the receiver below the exit point
    float t = pc.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + pc.waterAnchor.xy;  // world-stable wave domain (same as applyWaterWaves)
    float2 p0 = causticLanding(base, t, inc, h);
    float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h);
    float2 pz = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    // Flat water ⇒ all landings displace identically ⇒ det = eps² ⇒ focus = 1 (energy-neutral); the
    // clamp bounds the fold-line spikes whose energy the surrounding darkening already paid for.
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    return lerp(1.0, min(focus, CAUSTIC_MAX), fade);
}

// GGX BRDF. Roughness is clamped away from 0 so the delta sun's specular highlight stays finite
// and the VNDF sample is well-conditioned. alpha = roughness^2 (perceptual -> GGX) throughout.
static const float MIN_ROUGH = 0.045;
// LabPBR smoothness is 8-bit. Red=255 decodes to roughness 0; allow one half-code of tolerance for the
// sampled UNORM conversion without turning merely glossy surfaces into delta mirrors.
static const float PSR_MIRROR_ROUGHNESS_MAX = 0.5 / 255.0;
static const uint PSR_MAX_CONFIGURED_MIRRORS = 32u;

float luminance(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// GGX normal distribution.
float ggxD(float ndh, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
float ggxG1(float ndx, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

struct PathSampler {
#if CAUSTICA_OFFLINE
    uint pixelHash;
    uint sampleIndex;
    uint bounceHash;
    uint dimension;
    uint randomBits[8];
#else
    uint state;
#endif
};

float rndf(inout PathSampler sampler);

static const float EON_C1 = 0.5 - 2.0 / (3.0 * PI);
static const float EON_C2 = 2.0 / 3.0 - 28.0 / (15.0 * PI);

float eonDirectionalAlbedo(float mu, float rough) {
    float x = 1.0 - clamp(mu, 0.0, 1.0);
    float g = x * (0.0571085289 + x * (0.491881867 + x * (-0.332181442 + x * 0.0714429953)));
    return (1.0 + rough * g) / (1.0 + EON_C1 * rough);
}

float3 evaluateEonDiffuse(float3 wi, float3 wo, float3 n, float rough, float3 albedo) {
    float muI = clamp(dot(n, wi), 0.0, 1.0);
    float muO = clamp(dot(n, wo), 0.0, 1.0);
    if (muI <= 0.0 || muO <= 0.0) return float3(0.0);
    rough = clamp(rough, 0.0, 1.0);
    float3 rho = clamp(albedo, float3(0.0), float3(1.0));
    float s = dot(wi, wo) - muI * muO;
    float sOverT = s > 0.0 ? s / max(muI, muO) : s;
    float a = 1.0 / (1.0 + EON_C1 * rough);
    float3 singleScatter = rho * INV_PI * a * (1.0 + rough * sOverT);
    float Ei = eonDirectionalAlbedo(muI, rough);
    float Eo = eonDirectionalAlbedo(muO, rough);
    float Eavg = a * (1.0 + EON_C2 * rough);
    float3 rhoMs = rho * rho * Eavg / max(float3(1.0e-7), 1.0 - rho * (1.0 - Eavg));
    float3 multipleScatter = rhoMs * INV_PI * max(1.0e-7, 1.0 - Ei) * max(1.0e-7, 1.0 - Eo)
            / max(1.0e-7, 1.0 - Eavg);
    return singleScatter + multipleScatter;
}

void eonLtcCoefficients(float mu, float rough, out float a, out float b, out float c, out float d) {
    a = 1.0 + rough * (0.303392 + (-0.518982 + 0.111709 * mu) * mu + (-0.276266 + 0.335918 * mu) * rough);
    b = rough * (-1.16407 + 1.15859 * mu + (0.150815 - 0.150105 * mu) * rough) / (mu * mu * mu - 1.43545);
    c = 1.0 + rough * (0.20013 + (-0.506373 + 0.261777 * mu) * mu);
    d = rough * (0.540852 + (-1.01625 + 0.475392 * mu) * mu) / (-1.0743 + (0.0725628 + mu) * mu);
}

float3x3 eonLtcBasis(float3 wo) {
    float len2 = dot(wo.xy, wo.xy);
    float3 x = len2 > 0.0 ? float3(wo.x, wo.y, 0.0) * rsqrt(len2) : float3(1.0, 0.0, 0.0);
    // Slang's vector constructor arguments form rows; transpose to match the column basis used by LTC.
    return transpose(float3x3(x, float3(-x.y, x.x, 0.0), float3(0.0, 0.0, 1.0)));
}

float eonCltcPdf(float3 wo, float3 wi, float rough) {
    float3 w = mul(transpose(eonLtcBasis(wo)), wi);
    float a, b, c, d;
    eonLtcCoefficients(wo.z, rough, a, b, c, d);
    float detM = c * (a - b * d);
    float3 wh = float3(c * (w.x - b * w.z), (a - b * d) * w.y, -c * (d * w.x - a * w.z));
    float len2 = max(dot(wh, wh), 1.0e-12);
    float s = 0.5 * (1.0 + rsqrt(d * d + 1.0));
    return detM * detM / (len2 * len2) * max(wh.z, 0.0) / (PI * s);
}

float4 sampleEonCltc(float3 wo, float rough, float u1, float u2) {
    float a, b, c, d;
    eonLtcCoefficients(wo.z, rough, a, b, c, d);
    float radius = sqrt(u1);
    float phi = 2.0 * PI * u2;
    float x = radius * cos(phi);
    float y = radius * sin(phi);
    float s = 0.5 * (1.0 + rsqrt(d * d + 1.0));
    x = -lerp(sqrt(max(1.0 - y * y, 0.0)), x, s);
    float3 wh = float3(x, y, sqrt(max(1.0 - x * x - y * y, 0.0)));
    float pdfWh = wh.z / (PI * s);
    float3 wi = float3(a * wh.x + b * wh.z, c * wh.y, d * wh.x + wh.z);
    float len = length(wi);
    float detM = c * (a - b * d);
    float pdf = pdfWh * len * len * len / detM;
    wi = normalize(mul(eonLtcBasis(wo), wi));
    return float4(wi, pdf);
}

float3 sampleEon(float3 n, float3 woWorld, float rough, inout PathSampler sampler, out float pdf) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 wo = float3(dot(woWorld, t), dot(woWorld, b), max(dot(woWorld, n), 0.0));
    float u1 = rndf(sampler);
    float u2 = rndf(sampler);
    float pu = pow(clamp(rough, 0.0, 1.0), 0.1)
            * (0.162925 + (-0.372058 + (0.538233 - 0.290822 * wo.z) * wo.z) * wo.z);
    pu = clamp(pu, 0.0, 1.0);
    float pc = 1.0 - pu;
    float4 sampleValue;
    float pdfCltc;
    if (u1 <= pu && pu > 0.0) {
        u1 /= pu;
        float sinTheta = sqrt(max(1.0 - u1 * u1, 0.0));
        float phi = 2.0 * PI * u2;
        sampleValue = float4(sinTheta * cos(phi), sinTheta * sin(phi), u1, 0.0);
        pdfCltc = eonCltcPdf(wo, sampleValue.xyz, rough);
    } else {
        u1 = pc > 0.0 ? (u1 - pu) / pc : 0.0;
        sampleValue = sampleEonCltc(wo, rough, u1, u2);
        pdfCltc = sampleValue.w;
    }
    pdf = pu * (0.5 * INV_PI) + pc * pdfCltc;
    float3 wi = sampleValue.xyz;
    return normalize(wi.x * t + wi.y * b + wi.z * n);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
static const int   MAX_SSS_BOUNCE = 1; // fire the transmission shadow ray on primary + first indirect hit only
float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (perceptual roughness squared), NoV is the first-hit view cosine.
float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

float3 evaluateGgx(float3 n, float3 v, float3 l, float rough, float3 f0) {
    float NoV = max(dot(n, v), 0.0);
    float NoL = max(dot(n, l), 0.0);
    if (NoV <= 0.0 || NoL <= 0.0) return float3(0.0);
    float3 h = normalize(v + l);
    float D = ggxD(max(dot(n, h), 0.0), rough);
    float G = ggxG1(NoV, rough) * ggxG1(NoL, rough);
    float3 F = fresnelSchlick(max(dot(v, h), 0.0), f0);
    return D * G * F / max(4.0 * NoV * NoL, 1.0e-6);
}

float ggxDirectionalAlbedo(float alpha, float NoV) {
    return clamp(rrSpecularAlbedo(float3(1.0), alpha, NoV).r, 0.0, 0.999);
}

float ggxAverageAlbedo(float alpha) {
    return clamp(
            0.1739274226 * ggxDirectionalAlbedo(alpha, 0.2636734140)
            + 0.3260725774 * ggxDirectionalAlbedo(alpha, 0.5749595746)
            + 0.3260725774 * ggxDirectionalAlbedo(alpha, 0.8185294870)
            + 0.1739274226 * ggxDirectionalAlbedo(alpha, 0.9646594710),
            0.0, 0.999);
}

float3 ggxMultiscatterFresnel(float Eavg, float3 f0) {
    float3 Favg = f0 + (1.0 - f0) * 0.0476190476;
    return Favg * Favg * Eavg / max(float3(1.0e-5), 1.0 - Favg * (1.0 - Eavg));
}

float3 evaluateGgxMultiscatter(float3 n, float3 v, float3 l, float rough, float3 f0) {
    float NoV = max(dot(n, v), 0.0);
    float NoL = max(dot(n, l), 0.0);
    if (NoV <= 0.0 || NoL <= 0.0) return float3(0.0);
    float alpha = rough * rough;
    float Eo = ggxDirectionalAlbedo(alpha, NoV);
    float Ei = ggxDirectionalAlbedo(alpha, NoL);
    float Eavg = ggxAverageAlbedo(alpha);
    float3 Fms = ggxMultiscatterFresnel(Eavg, f0);
    float scale = (1.0 - Eo) * (1.0 - Ei) / max(PI * (1.0 - Eavg), 1.0e-5);
    return max(Fms * scale, float3(0.0));
}

float3 ggxDiffuseTransmission(float3 n, float3 v, float3 l, float rough, float3 f0) {
    float alpha = rough * rough;
    float NoV = max(dot(n, v), 0.0);
    float NoL = max(dot(n, l), 0.0);
    float Eavg = ggxAverageAlbedo(alpha);
    float3 Fms = ggxMultiscatterFresnel(Eavg, f0);
    float EoWhite = ggxDirectionalAlbedo(alpha, NoV);
    float EiWhite = ggxDirectionalAlbedo(alpha, NoL);
    float3 Eo = clamp(rrSpecularAlbedo(f0, alpha, NoV) + Fms * (1.0 - EoWhite), float3(0.0), float3(1.0));
    float3 Ei = clamp(rrSpecularAlbedo(f0, alpha, NoL) + Fms * (1.0 - EiWhite), float3(0.0), float3(1.0));
    return (1.0 - Eo) * (1.0 - Ei);
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

inline uint sampleHash(uint x) {
    x ^= x >> 16u;
    x *= 0x21f0aaadu;
    x ^= x >> 15u;
    x *= 0xf35a2d97u;
    x ^= x >> 15u;
    return x;
}

#if CAUSTICA_OFFLINE
// Driver-safe, bounce-scoped Owen-scrambled Sobol sampling belongs exclusively to the converged offline
// renderer. The live shader uses the compact PCG state below and never fetches this sequence buffer.
static const uint SAMPLE_SEQUENCE_DIMENSIONS = 8u;
static const uint SAMPLE_SEQUENCE_MASK = 1048575u;

inline uint owenScramble(uint x, uint seed) {
    x = reversebits(x);
    x ^= x * 0x3d20adeau;
    x += seed;
    x *= (seed >> 16u) | 1u;
    x ^= x * 0x05526c56u;
    x ^= x * 0x53a22864u;
    return reversebits(x);
}

inline void beginBounce(inout PathSampler sampler, uint bounce) {
    sampler.bounceHash = sampleHash(sampler.pixelHash ^ ((bounce + 1u) * 0x9e3779b9u));
    sampler.dimension = 0u;
    ConstPtr<uint> sequence = ConstPtr<uint>(pc.sampleSequenceAddr);
    uint sequenceBase = (sampler.sampleIndex & SAMPLE_SEQUENCE_MASK) * SAMPLE_SEQUENCE_DIMENSIONS;
    [loop]
    for (uint dimension = 0u; dimension < SAMPLE_SEQUENCE_DIMENSIONS; dimension++) {
        uint value = sequence[sequenceBase + dimension];
        uint dimensionSeed = sampleHash(sampler.bounceHash
                ^ ((dimension + 1u) * 0x02e5be93u));
        sampler.randomBits[dimension] = owenScramble(value, dimensionSeed);
    }
}

inline float rndf(inout PathSampler sampler) {
    uint value = sampler.randomBits[sampler.dimension++ & (SAMPLE_SEQUENCE_DIMENSIONS - 1u)];
    return float(value >> 8u) * (1.0 / 16777216.0);
}
#else
// A one-register PCG sampler keeps the live shader independent of the offline Sobol table while retaining
// strong per-draw output permutation. The xorshift alternative was runtime-neutral and is intentionally
// not kept as another sampler variant.
inline uint liveRandom(inout uint state) {
    uint value = state;
    state = value * 747796405u + 2891336453u;
    uint word = ((value >> ((value >> 28u) + 4u)) ^ value) * 277803737u;
    return (word >> 22u) ^ word;
}

inline void beginBounce(inout PathSampler sampler, uint bounce) {
    // PCG advances on demand; it has no bounce-scoped table to populate.
}

inline float rndf(inout PathSampler sampler) {
    return float(liveRandom(sampler.state) >> 8u) * (1.0 / 16777216.0);
}
#endif

float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(pc.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(pc.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
float3 cosineDir(float3 n, inout PathSampler sampler) {
    float u1 = rndf(sampler);
    float u2 = rndf(sampler);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's
// sun/moon are square quads, so the NEE shadow ray samples a square (not a cone) — the same square the
// visible disc in world.rmiss uses, built from the same celestial tangent frame (right = arc-travel
// direction, from pc.celestial.xyz). Averaged over frames by DLSS-RR this yields soft penumbrae that
// widen with occluder distance (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
float3 sampleSquare(float3 axis, float halfAngle, inout PathSampler sampler) {
    float3 right = normalize(cross(axis, pc.celestial.xyz));
    float3 up = cross(right, axis);
    float t = tan(halfAngle);
    float u = (rndf(sampler) * 2.0 - 1.0) * t;
    float v = (rndf(sampler) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. alpha = rough^2. Paired with the separable
// Smith term, the importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
float3 sampleGGXVNDF(float3 n, float3 ve, float rough, inout PathSampler sampler) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = rough * rough;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(sampler);
    float u2 = rndf(sampler);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
// receiver billboards only. The first-person camera owner uses 0x01 (secondary only).
static const uint CULL_SECONDARY = 0x01u;
static const uint CULL_PRIMARY = 0x02u;
static const uint TERRAIN_BUCKETS = 4u;
static const uint SBT_RADIANCE = 0u;
static const uint SBT_SHADOW = TERRAIN_BUCKETS;
static const uint SBT_STRIDE_BUCKET = 1u;
static const uint MISS_RADIANCE = 0u;
static const uint MISS_GUIDE = 1u;

RayDesc makeRay(float3 origin, float tmin, float3 dir, float tmax) {
    RayDesc r;
    r.Origin = origin;
    r.TMin = tmin;
    r.Direction = dir;
    r.TMax = tmax;
    return r;
}

// Live and offline binaries share hit-object tracing but specialize invocation reordering independently.
// Reorder is disabled in the latency-sensitive live shaders after a fixed-scene A/B showed that foliage
// any-hit traffic makes its scheduling overhead larger than its coherence benefit. Offline remains free to
// compile this same source with reordering for separate convergence and throughput validation.
void traceRadiance(uint cullMask, float3 ro, float tmin, float3 rd, float tmax) {
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE, makeRay(ro, tmin, rd, tmax), payload);
#if CAUSTICA_SER
    ReorderThread(hObj);
#endif
    HitObject::Invoke(topLevelAS, hObj, payload);
}

// Guide probes need closest-hit material/motion data but consume no sky radiance. A dedicated miss record
// avoids running the nested atmosphere march when a reflection/refraction guide escapes the scene.
void traceGuide(uint rayFlags, float3 ro, float tmin, float3 rd, float tmax) {
    HitObject hObj = HitObject::TraceRay(topLevelAS, rayFlags, CULL_SECONDARY,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_GUIDE, makeRay(ro, tmin, rd, tmax), payload);
#if CAUSTICA_SER
    ReorderThread(hObj);
#endif
    HitObject::Invoke(topLevelAS, hObj, payload);
}

float3 visibility(float3 origin, float3 dir, float tmax) {
    shadowVis = float4(1.0, 1.0, 1.0, -1.0); // a = water-crossing sentinel, filled by the water any-hit
    // Shadow SBT records run any-hit only for cutout/translucent/water. Cutout alpha-tests; translucent
    // and water tint shadowVis.rgb and pass through. Solid blocks terminate traversal. There is no closest
    // or miss shader worth executing, so use a hit object only as the traversal result.
    HitObject hObj = HitObject::TraceRay(topLevelAS,
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            CULL_SECONDARY, SBT_SHADOW, SBT_STRIDE_BUCKET, 0u,
            makeRay(origin, RAY_TMIN, dir, tmax), shadowVis);
    return hObj.IsMiss() ? shadowVis.rgb : float3(0.0, 0.0, 0.0);
}

float2 projectPrevNdc(float3 worldPos) {
    float4 clip = mul(pc.prevViewProj, float4(worldPos - pc.camOffset + pc.camDelta, 1.0));
    return clip.xy / clip.w;
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P seen
// in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye sees V
// along a straight line, so the reflection appears at proj(V). Project the mirror image directly.
// Gotcha: intersecting a reflection ray with the surface plane divides by dot(mirrorDir, n), which
// explodes at grazing angles and produces non-zero MV in a static scene. Mirroring is division-free, so
// static MV is bit-exact zero at any incidence. The reflector is assumed static (terrain / water);
// reflectedMotionPrev carries the reflected content's own displacement.
float2 previousReflectionNdc(float3 surfacePos, float3 previousSurfaceNormal,
                             float3 reflectedWorldPos, float3 reflectedMotionPrev) {
    float3 n = normalize(previousSurfaceNormal); // mirror(P) is orientation-independent
    float3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    float3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - surfacePos, n);
    return projectPrevNdc(mirroredHit);
}

// Follow a bounded chain of exact mirrors, then unfold the final destination through the mirror planes
// in reverse order. T1(T2(...Tn(P))) is the virtual primary surface seen through n reflections.
float2 recursivePrimarySurfaceReplacement(float3 surfacePos, float3 surfaceNormal,
        float3 primaryDir, float primaryConeSpread) {
    // Affine transform C(P)=A*P+b. Adding mirror T composes C'=C(T(P)); storing A as columns avoids
    // retaining every plane, so a live depth of 32 has constant per-ray state.
    float3 basisX = float3(1.0, 0.0, 0.0);
    float3 basisY = float3(0.0, 1.0, 0.0);
    float3 basisZ = float3(0.0, 0.0, 1.0);
    float3 translation = float3(0.0, 0.0, 0.0);
    float3 firstNormal = normalize(surfaceNormal);
    float3 mappedNormal = firstNormal;
    translation += mappedNormal * (2.0 * dot(firstNormal, surfacePos));
    basisX -= 2.0 * mappedNormal * firstNormal.x;
    basisY -= 2.0 * mappedNormal * firstNormal.y;
    basisZ -= 2.0 * mappedNormal * firstNormal.z;
    uint mirrorCount = 1u;
    uint maxMirrors = clamp(pc.psrMaxMirrors, 1u, PSR_MAX_CONFIGURED_MIRRORS);

    float3 rayOrigin = surfacePos;
    float3 rayNormal = firstNormal;
    float3 rayDir = reflect(primaryDir, rayNormal);
    float3 finalHit = surfacePos;
    float3 finalNormal = float3(0.0, 0.0, 0.0);
    float3 finalMotion = float3(0.0, 0.0, 0.0);
    float finalRoughness = 1.0;
    float3 finalDiffuse = SKY_DIFF_ALBEDO;
    float travelled = length(surfacePos - pc.camOffset);

    for (uint segment = 0u; segment < PSR_MAX_CONFIGURED_MIRRORS; ++segment) {
        payload.hitT = -1.0;
        payloadSetTraceState(false,
                max(travelled * primaryConeSpread, RAY_CONE_MIN_WIDTH),
                max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
        traceGuide(RAY_FLAG_NONE, rayOrigin + rayNormal * SURF_BIAS,
                RAY_TMIN, rayDir, 10000.0);
        if (payload.hitT <= 0.0) {
            finalHit = rayOrigin + rayDir * 1.0e6;
            break;
        }

        finalHit = rayOrigin + rayDir * payload.hitT;
        travelled += payload.hitT;
        uint material = payloadMaterial();
        float destinationRoughness = material == MATERIAL_WATER
                ? WATER_GUIDE_ROUGH : clamp(payloadRoughness(), 0.0, 1.0);
        float destinationMetal = material == MATERIAL_OPAQUE
                ? clamp(payloadMetalness(), 0.0, 1.0) : 0.0;
        bool nestedMirror = material == MATERIAL_OPAQUE
                && destinationRoughness <= PSR_MIRROR_ROUGHNESS_MAX
                && destinationMetal >= 0.999
                && dot(payload.motionPrev, payload.motionPrev) <= 1.0e-12
                && mirrorCount < maxMirrors;
        if (nestedMirror) {
            rayOrigin = finalHit;
            rayNormal = normalize(payload.normal);
            mappedNormal = basisX * rayNormal.x + basisY * rayNormal.y + basisZ * rayNormal.z;
            translation += mappedNormal * (2.0 * dot(rayNormal, rayOrigin));
            basisX -= 2.0 * mappedNormal * rayNormal.x;
            basisY -= 2.0 * mappedNormal * rayNormal.y;
            basisZ -= 2.0 * mappedNormal * rayNormal.z;
            ++mirrorCount;
            rayDir = reflect(rayDir, rayNormal);
            continue;
        }

        finalNormal = payload.normal;
        finalRoughness = destinationRoughness;
        if (material == MATERIAL_OPAQUE) {
            finalMotion = payload.motionPrev;
            finalDiffuse = payload.albedo * (1.0 - destinationMetal);
        } else {
            finalDiffuse = material == MATERIAL_GLASS ? payload.albedo : float3(0.0, 0.0, 0.0);
        }
        break;
    }

    finalHit = basisX * finalHit.x + basisY * finalHit.y + basisZ * finalHit.z + translation;
    finalNormal = basisX * finalNormal.x + basisY * finalNormal.y + basisZ * finalNormal.z;
    finalMotion = basisX * finalMotion.x + basisY * finalMotion.y + basisZ * finalMotion.z;
    gv_hitCamRel = finalHit - pc.camOffset;
    gv_motionHitCamRel = gv_hitCamRel;
    gv_motionObjDisp = finalMotion;
    gv_motionUseRefracted = true;
    gv_normal = finalNormal;
    gv_rough = finalRoughness;
    gv_albedo = finalDiffuse;
    // The mirror chain itself has been replaced. A further specular lobe on the terminal surface would
    // require another correctly transformed specular-motion chain, so keep this bounded PSR pass diffuse.
    gv_specAlb = float3(0.0, 0.0, 0.0);
    return float2(0.0, 0.0);
}

// Reflection (specular) motion vector for DLSS-RR. Covers smooth specular blocks (metals / LabPBR _s)
// and water reflections including underwater total-internal-reflection — any primary surface with a
// sharp specular lobe (specular albedo > 0, roughness <= 0.5). A sky miss is treated as a reflected
// hit at infinity along the reflected direction, so the same mirror-image reprojection handles surface
// and sky reflections uniformly (continuous as the reflected distance -> infinity).
float2 specularReflectionMotion(float3 surfacePos, float3 surfaceNormal, float3 previousSurfaceNormal,
                                float surfaceRoughness,
                                float3 surfaceSpecularAlbedo, float3 primaryDir,
                                float2 currentNdc, float2 size, float primaryConeSpread) {
    if (length(surfaceNormal) < 0.5
            || max(surfaceSpecularAlbedo.r, max(surfaceSpecularAlbedo.g, surfaceSpecularAlbedo.b)) <= 0.001
            || surfaceRoughness > 0.5) {
        return float2(0.0, 0.0);
    }

    float3 n = normalize(surfaceNormal);
    if (gv_psrMirror) {
        return recursivePrimarySurfaceReplacement(surfacePos, n, primaryDir, primaryConeSpread);
    }
    float3 specDir = reflect(primaryDir, n);
    // The bias is ONLY for the trace origin (so the reflection ray doesn't self-intersect the surface it
    // leaves). The hit is then reconstructed from the TRUE surface point `surfacePos`, NOT the biased
    // origin: reconstructing from `surfacePos + n*SURF_BIAS` bakes a constant `-n*SURF_BIAS` world offset
    // into the mirrored virtual point, and that offset's screen projection blows up at grazing angles (n
    // lies in the screen plane there) -> a non-zero MV in a static scene. Reconstructing from surfacePos
    // cancels the term so the mirror lands exactly on the view ray (static MV = bit-exact zero) WITHOUT
    // zeroing the trace bias the main path tracer needs to avoid surface acne.
    payload.hitT = -1.0;
    payloadSetTraceState(false,
            max(length(surfacePos - pc.camOffset) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    traceGuide(RAY_FLAG_NONE, surfacePos + n * SURF_BIAS, RAY_TMIN, specDir, 10000.0);

    float3 reflectedHit;
    float3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = surfacePos + specDir * payload.hitT;
        // Reflected water/fluid is a static dielectric (its chit MV lane is repurposed); only opaque
        // geometry carries a real per-vertex displacement. Particles are off secondary rays (cull mask).
        reflectedMotionPrev = payloadMaterial() == MATERIAL_OPAQUE ? payload.motionPrev : float3(0.0, 0.0, 0.0);
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6; // sky reflection: virtual image at infinity along specDir
        reflectedMotionPrev = float3(0.0, 0.0, 0.0);
    }
    float3 prevN = length(previousSurfaceNormal) >= 0.5 ? normalize(previousSurfaceNormal) : n;
    float2 prevNdc = previousReflectionNdc(surfacePos, prevN, reflectedHit, reflectedMotionPrev);
    return (prevNdc - currentNdc) * 0.5 * size;
}

// Deterministic transmitted destination for clear-interface guides. An entered solid uses one bounded
// trace to find its real exit, refracts there, then uses one trace for the visible destination. Thin panes
// cross their collapsed exit without bending. Ordinary pixels pay no additional work.
void opticalGuideHit(float3 surfacePos, float3 transmittedDir, float3 surfaceNormal,
                     bool continueThroughExit, bool continueThroughWater, float exitEta,
                     float rayBias, float rayConeWidth, float rayConeSpread,
                     out float3 hitCamRel, out float3 motionPrev, out bool transmitted,
                     out float3 normal, out float roughness,
                     out float3 diffuseAlbedo) {
    hitCamRel = surfacePos - pc.camOffset;
    motionPrev = float3(0.0, 0.0, 0.0);
    transmitted = false;
    normal = surfaceNormal;
    roughness = 1.0;
    diffuseAlbedo = float3(0.0, 0.0, 0.0);
    if (dot(transmittedDir, transmittedDir) <= 0.0) return;

    float3 direction = normalize(transmittedDir);
    float3 ro = surfacePos - surfaceNormal * rayBias;
    // Water can cross several adjacent cells before reaching the visible opaque destination. A two-hit walk
    // incorrectly accepted the next water face as the destination and exported a neutral white silhouette.
    // Keep glass at its existing one-exit contract, but walk a bounded chain of water interfaces.
    for (uint crossing = 0u; crossing < 8u; crossing++) {
        payload.hitT = -1.0;
        payloadSetTraceState(false, rayConeWidth, rayConeSpread);
        traceGuide(RAY_FLAG_NONE, ro, RAY_TMIN, direction, 10000.0);
        if (payload.hitT <= 0.0) {
            hitCamRel = (ro + direction * 1.0e6) - pc.camOffset;
            motionPrev = float3(0.0, 0.0, 0.0);
            normal = float3(0.0, 0.0, 0.0);
            roughness = 1.0;
            diffuseAlbedo = SKY_DIFF_ALBEDO;
            transmitted = true;
            return;
        }

        uint interfaceMaterial = payloadMaterial();
        bool crossWater = continueThroughWater && interfaceMaterial == MATERIAL_WATER;
        bool crossGlassExit = !continueThroughWater && continueThroughExit
                && crossing == 0u && interfaceMaterial == MATERIAL_GLASS;
        if (!crossWater && !crossGlassExit) {
            if (interfaceMaterial == MATERIAL_OPAQUE) {
                float3 hitPos = ro + direction * payload.hitT;
                hitCamRel = hitPos - pc.camOffset;
                motionPrev = payload.motionPrev;
                normal = payload.normal;
                roughness = clamp(payloadRoughness(), 0.0, 1.0);
                bool pbr = (pc.flags & 2u) != 0u;
                diffuseAlbedo = pbr
                        ? payload.albedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0))
                        : payload.albedo;
                transmitted = true;
            }
            return;
        }

        float3 interfacePos = ro + direction * payload.hitT;
        float3 interfaceNormal = payload.normal;
        float eta = exitEta;
        if (crossWater) {
            bool entering = payloadWaterEntering();
            eta = entering ? (1.0 / WATER_IOR) : WATER_IOR;
        }
        float3 nextDirection = refract(direction, interfaceNormal, eta);
        // Total internal reflection has no transmitted diffuse destination. Keep the primary water
        // interface's zero diffuse guide and its reflection-owned specular data.
        if (dot(nextDirection, nextDirection) <= 0.0) return;
        direction = normalize(nextDirection);
        ro = interfacePos - interfaceNormal * rayBias;
    }
    // Crossing-budget exhaustion means no trustworthy diffuse destination was found.
}

// One Monte Carlo path from (ro, rd). Returns accumulated radiance along the path.
float3 tracePath(float3 ro, float3 rd, float primaryConeSpread,
                 bool captureGuides, bool captureRefractedGuides, bool primaryOnly,
                 inout PathSampler sampler) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 throughput = float3(1.0, 1.0, 1.0);
#if CAUSTICA_SHARC_UPDATE
    SharcState sharcState;
    SharcInit(sharcState);
#endif
#if CAUSTICA_SHARC_QUERY
    bool sharcPathEligible = true;
    bool sharcPreviousLobeDiffuse = false;
    float sharcPreviousGlossyAlpha = 0.0;
#endif
    float rayConeWidth = 0.0;
    float rayConeSpread = max(primaryConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(pc.maxBounces);
    int maxMirrorDepth = int(clamp(pc.psrMaxMirrors, 1u, PSR_MAX_CONFIGURED_MIRRORS));
    int exactMirrorDepth = 0;
    int rrStart = maxBounces <= 3 ? 1 : 2;
    bool pbr = (pc.flags & 2u) != 0u; // GGX BRDF + material guides, else Lambertian fallback
    // Start submerged if the camera is in water so the first segment gets the right medium orientation
    // and absorption when the eye is underwater.
    bool inWater = (pc.flags & 1u) != 0u;
    // Current medium extinction. Defaults to the camera-biome tint (correct when the eye starts
    // submerged, before any water surface is hit); refreshed to the hit water body's tint on each crossing.
    float3 waterExt = waterExtinction(pc.waterParams.xyz);
    bool waterWaves = (pc.flags & 16u) != 0u; // animated wave-normal perturbation
    // Sky-disc gate (see world.rmiss): the sun/moon disc is shown to the primary ray and to rays spawned
    // by a specular/dielectric bounce (mirror reflections + refractions of the sun/moon), but hidden from
    // diffuse-sampled continuation rays — those are already covered by the sun NEE, so the tiny disc
    // would be a double-counted firefly. Reset true on every specular/water bounce, false on a diffuse one.
    bool showCelestial = true;
    for (int bounce = 0; bounce <= max(maxBounces, maxMirrorDepth); bounce++) {
        beginBounce(sampler, uint(bounce));
        payload.hitT = -1.0;
        payloadSetTraceState(showCelestial, rayConeWidth, rayConeSpread);
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles and the first-person body,
        // but not the separately captured head. Bounce rays are secondary (CULL_SECONDARY): exclude
        // particles and include both first-person body and head for shadows, GI, and reflections.
#if CAUSTICA_SHARC_UPDATE
        // Sparse cache updates are transport samples, not camera visibility: exclude particles and the
        // first-person-only primary mask from their first segment as well.
        traceRadiance(CULL_SECONDARY, ro, 0.0, rd, 10000.0);
#else
        traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0);
#endif

        if (payload.hitT < 0.0) {
            // world.rmiss writes sky radiance (gradient + sun/moon disc + stars) into payload.albedo. The
            // packed show-celestial flag decides whether this path segment may see the bright disc.
            float3 sky = payload.albedo;
            if (captureGuides && bounce == 0) { // primary ray hit sky: guides describe "no surface"
                gv_normal = float3(0.0, 0.0, 0.0);
                // Flat mid-grey albedo guide for sky (NOT the bright sky radiance): demodulating DLSS-RR
                // by the huge sun-disc radiance rings/artifacts around the sun. A constant albedo keeps
                // the brightness in the lighting channel where RR resolves it cleanly.
                gv_albedo = SKY_DIFF_ALBEDO;
                gv_specAlb = SKY_SPEC_ALBEDO;
                gv_rough = 1.0;
                gv_hitCamRel = rd * 1.0e6; // sky at infinity: only rotation parallax (-> large far depth)
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0); // sky is static
            }
#if CAUSTICA_SHARC_UPDATE
            SharcUpdateMiss(sharcParameters, sharcState, sky);
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
#endif
            L += throughput * sky; // escaped to sky
            break;
        }

        // Beer–Lambert: attenuate along the segment just travelled if it lay inside water. Applied to
        // every hit reached while submerged (the water-exit face or the underwater floor), shifting the
        // transmitted radiance with depth.
        if (inWater) {
            throughput *= exp(-waterExt * payload.hitT);
        }

        float3 n = payload.normal; // oriented toward the incoming ray by the closest-hit
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        uint opticalClass = material == MATERIAL_GLASS ? payloadOpticalClass() : OPTICAL_NONE;
        bool translucentSurface = material == MATERIAL_GLASS
                && opticalClass == OPTICAL_TRANSLUCENT_SURFACE;

        // TRANSLUCENT is a render bucket, not a material. Honey, slime, portals and modded overlays remain
        // alpha surfaces instead of silently receiving glass refraction. This branch adds no work to rays
        // that do not hit such a surface.
        if (translucentSurface) {
#if CAUSTICA_SHARC_UPDATE
            SharcUpdateMiss(sharcParameters, sharcState, float3(0.0));
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
            break;
#endif
#if CAUSTICA_SHARC_QUERY
            sharcPathEligible = false;
#endif
            float opacity = clamp(payloadSss(), 0.0, 1.0);
            if (captureGuides && bounce == 0) {
                gv_normal = n;
                gv_rough = payloadRoughness();
                gv_specAlb = pbr ? payload.f0 : float3(0.0, 0.0, 0.0);
                gv_albedo = payload.albedo;
                gv_hitCamRel = hitPos - pc.camOffset;
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev;
            }
            if (primaryOnly) {
                break;
            }
            if (opacity < 1.0 && rndf(sampler) >= opacity) {
                ro = hitPos - n * GLASS_TRANSMIT_BIAS;
                continue;
            }
            material = MATERIAL_OPAQUE;
        }

        // Panes are collapsed to one effective thin sheet; solid glass and ice refract through their
        // closed geometry. No medium stack, ordered shadow walk, or extra pipeline is introduced:
        // ordinary pixels retain the upstream path-tracing kernel and cost.
        if (material == MATERIAL_GLASS) {
#if CAUSTICA_SHARC_UPDATE
            SharcUpdateMiss(sharcParameters, sharcState, float3(0.0));
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
            break;
#endif
#if CAUSTICA_SHARC_QUERY
            sharcPathEligible = false;
#endif
            if (bounce >= maxBounces && !(captureGuides && bounce == 0)) {
                break;
            }
            float3 glassTint = payload.albedo;
            bool entering = payloadInterfaceEntering();
            bool thinPane = opticalClass == OPTICAL_THIN_GLASS;

            // A pane model has an entry and exit shell. The entry is the effective sheet; a later exit is
            // crossed without a second tint/Fresnel event. Keep a primary exit for a camera starting inside.
            if (thinPane && !entering && bounce > 0) {
                ro = hitPos - n * GLASS_TRANSMIT_BIAS;
                continue;
            }

            float materialIor = opticalClass == OPTICAL_SOLID_ICE ? ICE_IOR : GLASS_IOR;
            float outsideIor = inWater ? WATER_IOR : 1.0;
            float etaI = thinPane ? outsideIor : (entering ? outsideIor : materialIor);
            float etaT = thinPane ? materialIor : (entering ? materialIor : outsideIor);
            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            if (thinPane) {
                // Energy-conserving incoherent two-interface sheet: collapse the unseen back face.
                F = 2.0 * F / (1.0 + F);
            }
            float3 transmittedDir = thinPane ? rd : refract(rd, n, etaI / etaT);
            if (dot(transmittedDir, transmittedDir) <= 0.0) {
                F = 1.0;
            } else {
                transmittedDir = normalize(transmittedDir);
            }

            // Approximate volume absorption once per completed solid traversal. This is deliberately local
            // to the glass hit and does not replace or alter upstream water medium attenuation.
            if (!thinPane && !entering) {
                throughput *= glassTint;
            }
            if (captureGuides && bounce == 0) { // primary glass hit: feed RR a smooth dielectric specular surface
                gv_normal = n;
                gv_rough = opticalClass == OPTICAL_SOLID_ICE ? 0.12 : GLASS_GUIDE_ROUGH;
                gv_specAlb = float3(F, F, F);
                gv_albedo = glassTint;            // demodulate by the transmission tint
                gv_hitCamRel = hitPos - pc.camOffset;
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev; // 0 for static terrain
                if (captureRefractedGuides) {
                    // Match the real transmission path: entered solids start with entry refraction, then
                    // the bounded guide continuation finds and refracts through their actual exit face.
                    gv_opticalGuideDir = transmittedDir;
                    gv_opticalGuideMode = entering ? 3u : 2u;
                    gv_opticalExitEta = thinPane ? 1.0 : materialIor / outsideIor;
                }
            }
            if (primaryOnly || bounce >= maxBounces) {
                break;
            }
            if (rndf(sampler) < F) {
                rd = reflect(rd, n);
                ro = hitPos + n * SURF_BIAS;       // reflected ray stays on the incidence side
            } else {
                if (thinPane) {
                    throughput *= glassTint;
                }
                rd = transmittedDir;
                ro = hitPos - n * GLASS_TRANSMIT_BIAS; // cross to the far side without overshooting a close neighbour
            }
            showCelestial = true;                  // specular interface: the continuation ray may see the disc
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(sampler) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE) {
#if CAUSTICA_SHARC_UPDATE
            SharcUpdateMiss(sharcParameters, sharcState, float3(0.0));
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
            break;
#endif
#if CAUSTICA_SHARC_QUERY
            sharcPathEligible = false;
#endif
            float3 albedo = payload.albedo;
            if (captureGuides) {
                gv_normal = n;
                gv_albedo = albedo; // RR demodulation target = the particle texel (keeps it sharp)
                gv_specAlb = float3(0.0, 0.0, 0.0);
                gv_rough = 1.0;
                gv_hitCamRel = hitPos - pc.camOffset;
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev; // per-particle MV: de-ghost moving particles
            }
            if (primaryOnly) {
                break;
            }

            float3 lightDir = pc.lightDir.xyz;
            float lightHalfAngle = pc.lightDir.w;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, sampler);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side.
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0);
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * pc.lightRadiance.xyz * ndl * vis;
                }
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, sampler);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse receiver: direct sun/moon was handled by NEE above
            continue;
        }

        // Water: smooth dielectric interface. Choose reflection or refraction stochastically by the
        // Fresnel reflectance F — sampling by F leaves the throughput unchanged (lossless interface), so
        // there is no diffuse albedo multiply and no sun NEE here (the surface is specular). On
        // transmission, the new medium is read from this hit's face orientation (payloadWaterEntering),
        // not toggled — see PAYLOAD_WATER_ENTERING. The bounce cap bounds stacked refractions.
        if (material == MATERIAL_WATER) {
#if CAUSTICA_SHARC_UPDATE
            SharcUpdateMiss(sharcParameters, sharcState, float3(0.0));
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
            break;
#endif
#if CAUSTICA_SHARC_QUERY
            sharcPathEligible = false;
#endif
            if (bounce >= maxBounces && !(captureGuides && bounce == 0)) {
                break;
            }
            // Guide probes reuse the module-level payload. Preserve this interface before a refracted
            // guide trace overwrites it with the secondary hit/miss payload.
            float3 surfaceWaterTint = payload.albedo;
            bool surfaceWaterEntering = payloadWaterEntering();
            // Ripple the (near-flat) surface normal before any Fresnel/guide use → glints, broken
            // reflections, wobbling refraction. This water body's biome tint (carried in payload.albedo
            // for water hits) sets the absorption applied to the next submerged segment.
            float3 previousWaterNormal = n;
            if (waterWaves) {
                // World-stable wave domain: rebased hit XZ + the pushed anchor (terrain origin mod 4096)
                // reconstructs a world-pinned coordinate that survives the per-move terrain rebase.
                float2 waterDomain = hitPos.xz + pc.waterAnchor.xy;
                if (abs(n.y) >= 0.5) {
                    float2 currentGrad, previousGrad;
                    waterWaveGradPair(waterDomain, pc.waterParams.w, pc.waterAnchor.z,
                            currentGrad, previousGrad);
                    float orientation = n.y >= 0.0 ? 1.0 : -1.0;
                    n = orientation * normalize(float3(-currentGrad.x, 1.0, -currentGrad.y));
                    previousWaterNormal = orientation
                            * normalize(float3(-previousGrad.x, 1.0, -previousGrad.y));
                }
            }
            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float etaI = inWater ? WATER_IOR : 1.0;
            float etaT = inWater ? 1.0 : WATER_IOR;
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, n, etaI / etaT);
            if (captureGuides && bounce == 0) { // primary water hit: feed RR a smooth dielectric specular surface
                gv_normal = n;
                gv_rough = WATER_GUIDE_ROUGH;
                gv_albedo = float3(0.0, 0.0, 0.0);
                gv_specAlb = float3(F, F, F);
                gv_hitCamRel = hitPos - pc.camOffset; // depth + reflection start at the water surface
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0);
                gv_specPreviousNormal = previousWaterNormal;
                if (captureRefractedGuides && dot(transmittedDir, transmittedDir) > 0.0) {
                    gv_opticalGuideDir = transmittedDir;
                    gv_opticalGuideMode = 1u;
                    gv_opticalExitEta = surfaceWaterEntering ? WATER_IOR : (1.0 / WATER_IOR);
                }
                if (waterWaves) gv_animatedGuide = 1.0;
            }
            if (primaryOnly || bounce >= maxBounces) {
                break;
            }
            waterExt = waterExtinction(surfaceWaterTint);
            if (rndf(sampler) < F) {
                rd = reflect(rd, n);
                ro = hitPos + n * SURF_BIAS; // reflected ray stays on the incidence side
            } else {
                rd = transmittedDir; // F < 1 here, so this is never total internal reflection
                ro = hitPos - n * SURF_BIAS;       // transmitted ray crosses to the far side
                // Derived from this hit's face orientation, not toggled — self-corrects instead of a
                // stray/missing face corrupting the medium for the rest of the path (see PAYLOAD_WATER_ENTERING).
                inWater = surfaceWaterEntering;
            }
            // Specular interface (mirror reflect / clear refract): the continuation ray should see the
            // sun/moon disc directly — no NEE happened here to double-count it.
            showCelestial = true;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(sampler) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        float3 albedo = payload.albedo;
        float sss = translucentSurface ? 0.0 : payloadSss(); // alpha occupied this lane for surfaces
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

        // Material split. Metals tint F0 by albedo and have no diffuse lobe; dielectrics use a fixed
        // 0.04 F0. With PBR disabled this collapses to the Lambertian fallback.
        float perceptualRough = pbr ? clamp(payloadRoughness(), 0.0, 1.0) : 1.0;
        float rough = max(perceptualRough, MIN_ROUGH);
        float metal = pbr ? clamp(payloadMetalness(), 0.0, 1.0) : 0.0;
        bool exactMirror = pbr && perceptualRough <= PSR_MIRROR_ROUGHNESS_MAX && metal >= 0.999;
        float3 diffAlb = albedo * (1.0 - metal);
        // F0 comes from the chit: dielectric 0.04, a LabPBR custom/metal F0, or albedo for metals.
        float3 F0 = pbr ? payload.f0 : lerp(float3(0.04, 0.04, 0.04), albedo, metal);

        if (captureGuides && bounce == 0) { // primary-visibility surface: capture the denoiser guide buffers
            gv_normal = n;
            gv_albedo = pbr ? diffAlb : albedo;  // RR diffuse-albedo demodulation target
            gv_rough = perceptualRough;
            gv_specAlb = pbr ? rrSpecularAlbedo(F0, rough * rough, dot(n, v)) : float3(0.0, 0.0, 0.0);
            gv_hitCamRel = ro + rd * payload.hitT - pc.camOffset; // camera-relative hit position
            gv_motionHitCamRel = gv_hitCamRel;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = payload.motionPrev; // per-vertex world motion (0 for static terrain/sky)
            // Dynamic mirror transforms need their own previous-plane transform. Restrict PSR to static
            // mirrors; moving reflective entities retain the ordinary specular-motion guide path.
            gv_psrMirror = exactMirror && dot(payload.motionPrev, payload.motionPrev) <= 1.0e-12;
        }
        if (primaryOnly) {
            break;
        }

        // A true mirror is a delta distribution, not a narrow finite GGX lobe. This removes the 0.045
        // roughness floor and stochastic 90/10 specular/diffuse branch only for authored roughness zero.
        // The reflected sun/moon arrives through the continuation miss, so delta mirrors skip NEE.
        if (exactMirror) {
#if CAUSTICA_SHARC_UPDATE
            SharcUpdateMiss(sharcParameters, sharcState, float3(0.0));
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
            break;
#endif
#if CAUSTICA_SHARC_QUERY
            sharcPathEligible = false;
#endif
            if (exactMirrorDepth >= maxMirrorDepth) break;
            throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0);
            ro = p;
            rd = reflect(rd, n);
            ++exactMirrorDepth;
            showCelestial = true;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(sampler) > q) break;
                throughput /= q;
            }
            continue;
        }

        // Emissive material radiance. Closest-hit supplies only the normalized authored/fallback mask;
        // intensity belongs here, where emitted radiance enters every camera path exactly once. This
        // covers terrain and entity/held-item materials without a light list, extra ray, or analytic light.
        float emission = payloadEmission();
#if CAUSTICA_SHARC_UPDATE || CAUSTICA_SHARC_QUERY
        float emissiveRadiance = EMISSIVE_BASE_RADIANCE * EMISSIVE_MAX_MULTIPLIER
                * clamp(pc.emissiveIntensity, 0.0, 1.0);
        // Preserve the authored LabPBR emissive mask. SHaRC's separate-emissive mode stores reflected
        // lighting demodulated from albedo, while this live material signal is evaluated on every query.
        float3 materialEmissive = albedo * emission * emissiveRadiance;

#if CAUSTICA_SHARC_QUERY
#if !CAUSTICA_SHARC_LIVE_SECONDARY_DIRECT
        if ((sharcFrame.queryFlags & 2u) == 0u) {
            float3 cachedRadiance;
            if (causticaTrySharcQuery(bounce, hitPos, albedo, materialEmissive,
                    sharcPathEligible, sharcPreviousLobeDiffuse, sharcPreviousGlossyAlpha,
                    cachedRadiance)) {
                L += throughput * cachedRadiance;
                break;
            }
        }
#endif
#endif
        float3 cacheableDirectLighting = float3(0.0);
        float3 liveDirectLighting = float3(0.0);
#else
        if (emission > 0.0) {
            float emissiveRadiance = EMISSIVE_BASE_RADIANCE * EMISSIVE_MAX_MULTIPLIER
                    * clamp(pc.emissiveIntensity, 0.0, 1.0);
            L += throughput * albedo * emission * emissiveRadiance;
        }
#endif

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (pc.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        float3 lightDir = pc.lightDir.xyz;
        float lightHalfAngle = pc.lightDir.w;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, sampler);
        }
        float ndl = max(0.0, dot(n, lightDir));
        if (ndl > 0.0) {
            float3 vis = visibility(p, lightDir, 10000.0);
            // Underwater receiver whose shadow ray crossed a water surface: scale the direct light by
            // the wave-refraction caustic at the exit point. Focusing scales the incident irradiance,
            // so it applies to the whole NEE term (diffuse + specular).
            if (inWater && waterWaves && shadowVis.a > 0.0) {
                vis *= waterCaustic(p + lightDir * shadowVis.a, lightDir, shadowVis.a);
            }
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                float3 diffuseBrdf = diffAlb * INV_PI;
                float3 liveBrdf = float3(0.0);
                if (pbr) {
                    diffuseBrdf = evaluateEonDiffuse(lightDir, v, n, perceptualRough, diffAlb)
                            * ggxDiffuseTransmission(n, v, lightDir, rough, F0);
                    liveBrdf = evaluateGgx(n, v, lightDir, rough, F0)
                            + evaluateGgxMultiscatter(n, v, lightDir, rough, F0);
                }
#if CAUSTICA_SHARC_UPDATE || CAUSTICA_SHARC_QUERY
                float3 directScale = pc.lightRadiance.xyz * ndl * vis;
                cacheableDirectLighting += diffuseBrdf * directScale;
                liveDirectLighting += liveBrdf * directScale;
#else
                L += throughput * (diffuseBrdf + liveBrdf) * pc.lightRadiance.xyz * ndl * vis;
#endif
            }
        }

        // Thin-surface SSS transmission. Light entering from the back face scatters through toward the
        // viewer via a forward-biased HG phase (leaves/grass backlit by the sun glow when you look
        // through them toward the light source). Shadow ray fires from the back face to avoid
        // self-occlusion. cosT = dot(lightDir, rd): rd tracks the ray toward the sun when the sun is
        // behind the slab, giving cosT ≈ 1 → forward-scatter peak.
        if (sss > 0.0 && bounce <= MAX_SSS_BOUNCE) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                float3 visB = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                // flicker with the same light bands as the floor around it.
                if (inWater && waterWaves && shadowVis.a > 0.0) {
                    visB *= waterCaustic(hitPos + lightDir * shadowVis.a, lightDir, shadowVis.a);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
#if CAUSTICA_SHARC_UPDATE || CAUSTICA_SHARC_QUERY
                    liveDirectLighting += diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G)
                            * backNdl * pc.lightRadiance.xyz * visB;
#else
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * pc.lightRadiance.xyz * visB;
#endif
                }
            }
        }

#if CAUSTICA_SHARC_QUERY
#if CAUSTICA_SHARC_LIVE_SECONDARY_DIRECT
        {
#else
        if ((sharcFrame.queryFlags & 2u) != 0u) {
#endif
            float3 cachedRadiance;
            if (causticaTrySharcQuery(bounce, hitPos, albedo, materialEmissive,
                    sharcPathEligible, sharcPreviousLobeDiffuse, sharcPreviousGlossyAlpha,
                    cachedRadiance)) {
                L += throughput * (cachedRadiance + liveDirectLighting);
                break;
            }
        }
#endif

#if CAUSTICA_SHARC_UPDATE
        bool dynamicSurface = (payload.flags & PAYLOAD_SHARC_DYNAMIC) != 0u;
        if (dynamicSurface) {
            // Dynamic geometry contributes to previously stored vertices without becoming a persistent
            // cache location of its own.
            SharcUpdateMiss(sharcParameters, sharcState,
                    cacheableDirectLighting + liveDirectLighting + materialEmissive);
            sharcStatAdd(SHARC_STAT_UPDATE_MISSES, 1u);
            break;
        }
        SharcHitData updateHit = causticaSharcHit(hitPos, albedo, materialEmissive);
        bool continueUpdate = causticaSharcUpdateHit(sharcParameters, sharcState, updateHit,
                cacheableDirectLighting, cacheableDirectLighting + liveDirectLighting);
#if CAUSTICA_SHARC_DIAGNOSTICS
        float3 scaledCacheInput = abs(cacheableDirectLighting / updateHit.materialDemodulation)
                * sharcFrame.radianceScale;
        if (any(isnan(scaledCacheInput)) || any(isinf(scaledCacheInput))
                || any(scaledCacheInput >= float3(4290000000.0))) {
            sharcStatAdd(SHARC_STAT_NUMERIC_RISKS, 1u);
        }
        sharcStatAdd(SHARC_STAT_UPDATE_HITS, 1u);
        HashGridIndex insertedIndex = HashMapFindEntry(sharcParameters.hashMapData,
                updateHit.positionWorld, updateHit.normalWorld, sharcParameters.gridParameters);
        if (insertedIndex == HASH_GRID_INVALID_CACHE_INDEX) {
            sharcStatAdd(SHARC_STAT_INSERT_FAILURES, 1u);
        } else {
            sharcStatAdd(SHARC_STAT_INSERTIONS, 1u);
            HashGridKey insertedKey = HashGridComputeSpatialHash(
                    updateHit.positionWorld, updateHit.normalWorld, sharcParameters.gridParameters);
            uint baseSlot = HashGridGetBaseSlot(insertedKey, sharcParameters.hashMapData.capacity);
            uint probeDistance = (insertedIndex + sharcParameters.hashMapData.capacity - baseSlot)
                    % sharcParameters.hashMapData.capacity;
            sharcStatAdd(SHARC_STAT_COLLISIONS, probeDistance);
        }
#endif
        if (!continueUpdate) {
            break;
        }
#elif CAUSTICA_SHARC_QUERY
        L += throughput * (cacheableDirectLighting + liveDirectLighting + materialEmissive);
#endif

        // This vertex's emission and direct lighting are complete. At the depth limit there is no next
        // ray, so sampling a continuation lobe and running roulette cannot contribute to the result.
        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance. With PBR off, use the cosine-weighted Lambertian fallback.
#if CAUSTICA_SHARC_UPDATE
        float3 throughputBeforeBounce = throughput;
#endif
        if (pbr) {
            float ps = clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
            if (rndf(sampler) < ps) {
#if CAUSTICA_SHARC_UPDATE
                causticaSharcDiscardCurrentVertex(sharcState);
#endif
#if CAUSTICA_SHARC_QUERY
                sharcPreviousLobeDiffuse = false;
                sharcPreviousGlossyAlpha = rough;
#endif
                float3 h = sampleGGXVNDF(n, v, rough, sampler);
                float3 l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break; // microfacet reflects below the surface: terminate this path
                }
                // VNDF + separable Smith ⇒ weight = F · G2/G1(v) = F · G1(l); divide by the lobe pdf ps.
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= F * ggxG1(ndl2, rough) / ps;
                ro = p;
                rd = l;
                rayConeSpread = max(rayConeSpread, rough * rough * RAY_CONE_GLOSSY_SPREAD_SCALE);
#if CAUSTICA_SHARC_QUERY
                // Glossy queries use the documented effective-cone test at the next hit.
#endif
                showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
            } else {
#if CAUSTICA_SHARC_QUERY
                sharcPreviousLobeDiffuse = true;
                sharcPreviousGlossyAlpha = 0.0;
#endif
                float diffusePdf;
                float3 l = sampleEon(n, v, perceptualRough, sampler, diffusePdf);
                float3 diffuse = evaluateEonDiffuse(l, v, n, perceptualRough, diffAlb)
                        * ggxDiffuseTransmission(n, v, l, rough, F0);
                diffuse += evaluateGgxMultiscatter(n, v, l, rough, F0);
                throughput *= diffuse * max(dot(n, l), 0.0)
                        / max(diffusePdf * (1.0 - ps), 1.0e-7);
                ro = p;
                rd = l;
                rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
                showCelestial = false; // diffuse lobe: disc covered by NEE, hide it (anti-firefly)
            }
        } else {
#if CAUSTICA_SHARC_QUERY
            sharcPreviousLobeDiffuse = true;
            sharcPreviousGlossyAlpha = 0.0;
#endif
            throughput *= albedo; // Lambertian fallback (PI and cos cancel)
            showCelestial = false; // diffuse bounce: hide the disc (covered by NEE)
            ro = p;
            rd = cosineDir(n, sampler);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(sampler) > q) {
                break;
            }
            throughput /= q;
        }
#if CAUSTICA_SHARC_UPDATE
        SharcSetThroughput(sharcState, throughput / max(throughputBeforeBounce, float3(1.0e-6)));
#endif
#if CAUSTICA_SHARC_QUERY
        if ((payload.flags & PAYLOAD_SHARC_DYNAMIC) != 0u) sharcPathEligible = false;
#endif
    }
    return L;
}

[shader("raygeneration")]
void main() {
    pc = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0]; // load the per-frame push data from the BDA buffer
#if CAUSTICA_SHARC_UPDATE || CAUSTICA_SHARC_QUERY
    sharcFrame = ConstPtr<SharcFrame>(pcAddr.sharcFrameAddr)[0];
    sharcParameters = causticaSharcParameters(
            sharcFrame.hashEntriesAddr,
            sharcFrame.accumulationAddr,
            sharcFrame.resolvedAddr,
            sharcFrame.capacity,
            sharcFrame.cameraPosition,
            sharcFrame.sceneScale,
            sharcFrame.radianceScale,
            (sharcFrame.flags & 1u) != 0u);
    sharcStats = SharcRWPtr<uint>(sharcFrame.statsAddr);
#endif
#if CAUSTICA_SHARC_UPDATE
    // One rotating sample in each configurable square tile. The reference default is 5x5 (4%).
    uint2 tile = DispatchRaysIndex().xy;
    uint tileSize = max(sharcFrame.updateTileSize, 1u);
    uint tileHash = sampleHash(tile.x ^ sampleHash(tile.y + 0x9e3779b9u));
    uint tileArea = tileSize * tileSize;
    uint sampleOffset = (tileHash + sharcFrame.frameIndex) % tileArea;
    int2 pix = int2(tile * tileSize + uint2(sampleOffset % tileSize, sampleOffset / tileSize));
    int2 fullSize = int2(sharcFrame.renderWidth, sharcFrame.renderHeight);
    if (pix.x >= fullSize.x || pix.y >= fullSize.y) {
        return;
    }
    float2 size = float2(fullSize);
    float2 uv = (float2(pix) + 0.5) / size;
    float2 ndc = uv * 2.0 - 1.0;
    float4 nearH = mul(pc.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(pc.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    float3 origin = nearP + pc.camOffset;
    float3 direction = normalize(farP - nearP);
    PathSampler sampler;
    sampler.state = sampleHash((uint(pix.x) * 1973u + uint(pix.y) * 9277u + 26699u)
            ^ (sharcFrame.frameIndex * 2654435761u)) | 1u;
    liveRandom(sampler.state);
    pc.maxBounces = min(pc.maxBounces, sharcFrame.updateMaxBounces);
    tracePath(origin, direction, primaryRayConeSpread(ndc, size, direction),
            false, false, false, sampler);
    return;
#else
    int2 pix = int2(DispatchRaysIndex().xy);
    float2 size = float2(DispatchRaysDimensions().xy);
    float2 uv = (float2(pix) + 0.5) / size;
    float2 ndc = uv * 2.0 - 1.0;
#if CAUSTICA_OFFLINE
    bool offlineCalibrating = (pc.flags & FRAME_FLAG_OFFLINE_CALIBRATING) != 0u;
    float4 offlineState = groundTruthAccum[pix];
    uint completedSamples = uint(max(offlineState.a, 0.0) + 0.5);
    uint spp = max(pc.spp, 1u);
    int2 pilotTile = pix >> 3;
    float4 pilotState = offlinePilotRead[pilotTile];
    uint pathCount = spp;
    if (!offlineCalibrating && pilotState.a >= 4.0) {
        float relativeNoise = sqrt(max(pilotState.r, max(pilotState.g, pilotState.b)));
        float predictedError = relativeNoise / sqrt(float(completedSamples + 1u));
        pathCount = min(spp, max(1u, uint(predictedError * 100.0 + 0.999)));
    }
    float3 runningMean = offlineState.rgb;
    uint runningCount = completedSamples;
    bool pilotLeader = !offlineCalibrating && (pix.x & 7) == 0 && (pix.y & 7) == 0;
    uint totalPathCount = pathCount + (pilotLeader ? 2u : 0u);
    uint pixelHash = sampleHash(uint(pix.x) ^ sampleHash(uint(pix.y) + 0x9e3779b9u));
    float3 pilotA = float3(0.0);
    float3 pilotB = float3(0.0);
    for (uint s = 0u; s < totalPathCount; s++) {
        bool pilotSample = s >= pathCount;
        uint pilotLane = s - pathCount;
        PathSampler sampler;
        float2 sampleUv = uv;
        if (pilotSample) {
            int2 tileOrigin = pilotTile << 3;
            int2 tileExtent = min(int2(8), int2(size) - tileOrigin);
            uint tilePixels = uint(tileExtent.x * tileExtent.y);
            uint offset = (pc.frameIndex + uint(pilotTile.x) * 17u + uint(pilotTile.y) * 29u) % tilePixels;
            int2 probePix = tileOrigin + int2(int(offset % uint(tileExtent.x)), int(offset / uint(tileExtent.x)));
            sampleUv = (float2(probePix) + 0.5) / size;
            sampler.pixelHash = sampleHash(pixelHash ^ (0x68bc21ebu + pilotLane * 0x2dc966b0u));
            sampler.sampleIndex = pc.frameIndex * 2u + pilotLane;
        } else {
            sampler.pixelHash = pixelHash;
            sampler.sampleIndex = completedSamples + s;
        }
        sampler.bounceHash = 0u;
        sampler.dimension = 0u;
        beginBounce(sampler, 0xfffffffeu);
        float2 pixelJitter = float2(rndf(sampler) - 0.5, rndf(sampler) - 0.5);
        float2 sampleNdc = (sampleUv + pixelJitter / size) * 2.0 - 1.0;
        float4 nearH = mul(pc.invViewProj, float4(sampleNdc.x, sampleNdc.y, 1.0, 1.0));
        float4 farH = mul(pc.invViewProj, float4(sampleNdc.x, sampleNdc.y, 0.0, 1.0));
        float3 nearP = nearH.xyz / nearH.w;
        float3 farP = farH.xyz / farH.w;
        float3 origin = nearP + pc.camOffset;
        float3 direction = normalize(farP - nearP);
        float3 sampleRadiance = tracePath(origin, direction,
                primaryRayConeSpread(sampleNdc, size, direction), false, false, false, sampler);
        if (pilotSample) {
            if (pilotLane == 0u) pilotA = sampleRadiance;
            else pilotB = sampleRadiance;
        } else {
            runningCount++;
            runningMean += (sampleRadiance - runningMean) / float(runningCount);
        }
    }
    groundTruthAccum[pix] = float4(runningMean, float(runningCount));
    if (pilotLeader) {
        float3 scale = max(abs(pilotA) + abs(pilotB), float3(0.02));
        float3 relativeDelta = (pilotA - pilotB) / scale;
        float3 pairVariance = 0.5 * relativeDelta * relativeDelta;
        float pilotCount = pilotState.a + 1.0;
        float3 pilotMean = pilotState.rgb + (pairVariance - pilotState.rgb) / pilotCount;
        offlinePilotWrite[pilotTile] = float4(pilotMean, pilotCount);
    }
    outImage[pix] = float4(runningMean, 1.0);
    return;
#else
    uint spp = max(pc.spp, 1u);
#if CAUSTICA_SHARC_QUERY && CAUSTICA_SHARC_DIAGNOSTICS
    bool sharcDebugView = pc.debugView >= 9u && pc.debugView <= 16u;
    bool primaryOnly = pc.debugView != 0u && !sharcDebugView;
#else
    bool primaryOnly = pc.debugView != 0u;
#endif
    bool writeRrGuides = (pc.flags & FRAME_FLAG_RR_GUIDES) != 0u;
    bool writeMotionGuides = writeRrGuides || (pc.flags & FRAME_FLAG_FG_GUIDES) != 0u;
    bool captureGuides = writeMotionGuides || primaryOnly;
    bool captureRefractedGuides = writeMotionGuides
            || pc.debugView == 2u || pc.debugView == 5u || pc.debugView == 6u || pc.debugView == 7u;
#if CAUSTICA_SHARC_QUERY && CAUSTICA_SHARC_DIAGNOSTICS
    uint pathCount = (primaryOnly || sharcDebugView) ? 1u : spp;
#else
    uint pathCount = primaryOnly ? 1u : spp;
#endif
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    float2 jndc = ndc;
    float3 dir = float3(0.0, 0.0, 1.0);
    float rayConeSpread = RAY_CONE_MIN_SPREAD;
    gv_opticalGuideMode = 0u;
    gv_opticalExitEta = 1.0;
    gv_animatedGuide = 0.0;
    gv_specPreviousNormal = float3(0.0, 0.0, 0.0);
    gv_psrMirror = false;
#if CAUSTICA_SHARC_QUERY && CAUSTICA_SHARC_DIAGNOSTICS
    gv_sharcDebug = float3(0.02);
    gv_sharcHashDebug = float3(0.0);
    gv_sharcTerminationDepth = 0.0;
    gv_sharcCachedRadianceDebug = float3(0.0);
    gv_sharcVoxelSizeDebug = float3(0.0);
    gv_sharcEligibilityDebug = float3(0.02);
    gv_sharcNumericDebug = float3(0.0, 0.2, 0.0);
#endif
    uint seed = sampleHash((uint(pix.x) * 1973u + uint(pix.y) * 9277u + 26699u)
            ^ (pc.frameIndex * 2654435761u)) | 1u;
    for (uint s = 0u; s < pathCount; s++) {
        PathSampler sampler;
        seed = liveRandom(seed);
        sampler.state = seed;
        jndc = (uv + pc.jitter / size) * 2.0 - 1.0;
        float4 nearH = mul(pc.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
        float4 farH = mul(pc.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
        float3 nearP = nearH.xyz / nearH.w;
        float3 farP = farH.xyz / farH.w;
        float3 origin = nearP + pc.camOffset;
        dir = normalize(farP - nearP);
        rayConeSpread = primaryRayConeSpread(jndc, size, dir);

        float3 sampleRadiance = tracePath(origin, dir, rayConeSpread,
                captureGuides && s == 0u,
                captureRefractedGuides && s == 0u, primaryOnly, sampler);
        seed = sampler.state;
        frameRadiance += sampleRadiance;
    }
    frameRadiance /= float(pathCount);

    // Optical transmission changes the visible layer, but reflection motion still belongs to the primary
    // glass/water interface. Preserve that interface before replacing ordinary guides with the destination.
    float3 specSurfaceCamRel = gv_hitCamRel;
    float3 specSurfaceNormal = gv_normal;
    float3 specSurfacePreviousNormal = gv_specPreviousNormal;
    float specSurfaceRoughness = gv_rough;
    float3 specSurfaceAlbedo = gv_specAlb;

    // One shared call site keeps Slang from cloning the guide trace into both water and glass paths.
    if (gv_opticalGuideMode != 0u) {
        float3 destinationHitCamRel;
        float3 destinationMotionPrev;
        bool destinationValid;
        float3 destinationNormal;
        float destinationRoughness;
        float3 destinationDiffuseAlbedo;
        bool glassGuide = gv_opticalGuideMode >= 2u;
        float guideBias = glassGuide ? GLASS_TRANSMIT_BIAS : SURF_BIAS;
        float guideConeWidth = max(length(gv_hitCamRel) * rayConeSpread, RAY_CONE_MIN_WIDTH);
        opticalGuideHit(gv_hitCamRel + pc.camOffset, gv_opticalGuideDir, gv_normal,
                gv_opticalGuideMode == 3u || !glassGuide, !glassGuide, gv_opticalExitEta,
                guideBias, guideConeWidth, rayConeSpread,
                destinationHitCamRel, destinationMotionPrev, destinationValid,
                destinationNormal, destinationRoughness,
                destinationDiffuseAlbedo);
        if (destinationValid) {
            gv_motionHitCamRel = destinationHitCamRel;
            gv_motionObjDisp = destinationMotionPrev;
            gv_motionUseRefracted = true;
            gv_albedo = destinationDiffuseAlbedo;
            if (glassGuide) {
                // The ordinary visible layer is the transmitted destination. Specular albedo/motion remain
                // attached to the separately preserved primary glass interface, matching DLSSD semantics.
                gv_hitCamRel = destinationHitCamRel;
                gv_normal = destinationNormal;
                gv_rough = destinationRoughness;
            }
        }
    }

    // The existing reflection-motion trace also performs PSR for exact mirrors, so run it before ordinary
    // depth/motion are derived from the potentially replaced virtual surface.
    float2 specMotion = float2(0.0, 0.0);
    bool debugSpecMotion = pc.debugView == 7u;
    if (writeRrGuides || debugSpecMotion) {
        specMotion = specularReflectionMotion(
                specSurfaceCamRel + pc.camOffset, specSurfaceNormal, specSurfacePreviousNormal,
                specSurfaceRoughness, specSurfaceAlbedo, dir, jndc, size, rayConeSpread);
    }

    // Hardware (non-linear, reversed-Z) depth for DLSS-RR + Frame Generation: project the camera-relative
    // primary hit through the forward view-projection and take ndc z/w — exactly the value a rasterizer
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag; sky's far hit -> ~0.
    // Water depth follows its surface; clear glass follows the transmitted destination to avoid blur.
    bool debugDepth = pc.debugView == 3u;
    bool debugMotion = pc.debugView == 5u;
    if (writeMotionGuides || debugDepth) {
        float4 curClip = mul(pc.curViewProj, float4(gv_hitCamRel, 1.0));
        gv_depth = curClip.w > 0.0 ? curClip.z / curClip.w : 0.0;
    }

    float2 motion = float2(0.0, 0.0);
    if (writeMotionGuides || debugMotion) {
        float4 prevClip = mul(pc.prevViewProj,
                float4(gv_motionHitCamRel + pc.camDelta - gv_motionObjDisp, 1.0));
        float2 prevNdc = prevClip.xy / prevClip.w;
        float2 curNdc = jndc;
        if (gv_motionUseRefracted) {
            float4 curClipRefr = mul(pc.curViewProj, float4(gv_motionHitCamRel, 1.0));
            curNdc = curClipRefr.xy / curClipRefr.w;
        }
        motion = (prevNdc - curNdc) * 0.5 * size;
    }

    if (writeRrGuides) {
        gNormal[pix] = float4(gv_normal, gv_rough);
        gAlbedo[pix] = float4(gv_albedo, 1.0);
        gDepth[pix] = gv_depth;
        gMotion[pix] = motion;
        gSpecAlbedo[pix] = float4(gv_specAlb, 1.0);
        gSpecMotion[pix] = specMotion;
        gAnimatedGuide[pix] = gv_animatedGuide;
    } else if (writeMotionGuides) {
        gDepth[pix] = gv_depth;
        gMotion[pix] = motion;
    }

    // Debug guide-buffer visualization: bypass accumulation and show a guide directly.
    if (pc.debugView != 0u) {
        float3 dbg;
        if (pc.debugView == 1u) {
            dbg = gv_normal * 0.5 + 0.5;            // world normal -> [0,1]
        } else if (pc.debugView == 2u) {
            dbg = gv_albedo;                        // diffuse albedo
        } else if (pc.debugView == 3u) {
            dbg = float3(gv_depth, gv_depth, gv_depth); // HW reversed-Z depth (near=white, far/sky=black)
        } else if (pc.debugView == 4u) {
            dbg = float3(gv_rough, gv_rough, gv_rough); // roughness
        } else if (pc.debugView == 6u) {
            dbg = gv_specAlb;                       // specular albedo
        } else if (pc.debugView == 7u) {
            dbg = float3(clamp(0.5 + specMotion * 0.05, 0.0, 1.0), 0.5); // reflection motion
        }
#if CAUSTICA_SHARC_QUERY && CAUSTICA_SHARC_DIAGNOSTICS
        else if (pc.debugView == 9u) {
            dbg = gv_sharcDebug;                    // green hit, red miss, dark ineligible
        } else if (pc.debugView == 10u) {
            float depth = gv_sharcTerminationDepth / max(float(pc.maxBounces), 1.0);
            dbg = float3(depth, 1.0 - depth, 0.15);
        } else if (pc.debugView == 11u) {
            dbg = HashGridDebugOccupancy(uint2(pix), uint2(size), sharcParameters.hashMapData);
        } else if (pc.debugView == 12u) {
            dbg = gv_sharcHashDebug;
        } else if (pc.debugView == 13u) {
            dbg = gv_sharcCachedRadianceDebug;
        } else if (pc.debugView == 14u) {
            dbg = gv_sharcVoxelSizeDebug;
        } else if (pc.debugView == 15u) {
            dbg = gv_sharcEligibilityDebug;
        } else if (pc.debugView == 16u) {
            dbg = gv_sharcNumericDebug;
        }
#endif
        else {
            dbg = float3(clamp(0.5 + motion * 0.05, 0.0, 1.0), 0.5); // motion: red=+x, green=+y
        }
        outImage[pix] = float4(dbg, 1.0);
        return;
    }

    // Single noisy estimate; the denoiser (DLSS-RR) handles temporal convergence.
    outImage[pix] = float4(frameRadiance, 1.0);
#endif // CAUSTICA_OFFLINE
#endif
}
