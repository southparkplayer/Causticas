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
import world_common;

[[vk::push_constant]] PushAddr pcAddr;

[[vk::binding(0, 0)]] RaytracingAccelerationStructure topLevelAS;
// HDR trace target: linear radiance, may exceed 1. Tonemap happens later at the display.comp seam.
[[vk::binding(1, 0)]] [format("rgba16f")] RWTexture2D<float4> outImage;
// Guide buffers — first-hit (primary-visibility) attributes consumed by the denoiser/DLSS-RR. Written
// every frame regardless of accumulation. gNormal.w carries roughness; gDepth carries HW reversed-Z depth.
[[vk::binding(3, 0)]] [format("rgba16f")] RWTexture2D<float4> gNormal;     // xyz world normal, w roughness
[[vk::binding(4, 0)]] [format("rgba16f")] RWTexture2D<float4> gAlbedo;     // rgb diffuse albedo
[[vk::binding(5, 0)]] [format("r32f")]    RWTexture2D<float>  gDepth;      // HW reversed-Z depth
[[vk::binding(6, 0)]] [format("rg16f")]   RWTexture2D<float2> gMotion;     // screen-space motion (render px)
[[vk::binding(7, 0)]] [format("rgba16f")] RWTexture2D<float4> gSpecAlbedo; // specular albedo (0 — diffuse-only)
[[vk::binding(8, 0)]] [format("rg16f")]   RWTexture2D<float2> gSpecMotion; // reflection MVs for RR

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `pc` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
static WorldPush pc;

// Ray payloads. Module-level statics so the helper functions (visibility / opticalGuideHit /
// specularReflectionMotion / tracePath) can share them, mirroring the GLSL rayPayloadEXT globals.
static Payload payload;
static float4 shadowVis; // rgb = transmittance; a = nearest water-crossing t (-1 = none)

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
// Previous animated-reflector normal; zero selects the current normal for static surfaces.
static float3 gv_specPreviousNormal;

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
static const float EMISSIVE_STRENGTH = 3.0; // HDR radiance of a full (level-15) emitter, modulated by albedo
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

float rndf(inout uint s);

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

float3 sampleEon(float3 n, float3 woWorld, float rough, inout uint seed, out float pdf) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 wo = float3(dot(woWorld, t), dot(woWorld, b), max(dot(woWorld, n), 0.0));
    float u1 = rndf(seed);
    float u2 = rndf(seed);
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

// PCG hash RNG.
uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
float rndf(inout uint s) { return float(pcg(s)) * (1.0 / 4294967296.0); }

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
float3 cosineDir(float3 n, inout uint s) {
    float u1 = rndf(s);
    float u2 = rndf(s);
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
float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    float3 right = normalize(cross(axis, pc.celestial.xyz));
    float3 up = cross(right, axis);
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. alpha = rough^2. Paired with the separable
// Smith term, the importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
float3 sampleGGXVNDF(float3 n, float3 ve, float rough, inout uint s) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = rough * rough;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
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

// SER radiance trace: trace into a hit object, reorder threads by hit coherence, THEN invoke the
// hit/miss shader — this is where the divergent Section/Prim shading work (world.rchit/world.rahit)
// actually happens, so reordering before it is what pays for the reorder's own cost.
void traceRadianceReordered(uint cullMask, float3 ro, float tmin, float3 rd, float tmax) {
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE, makeRay(ro, tmin, rd, tmax), payload);
    ReorderThread(hObj);
    HitObject::Invoke(topLevelAS, hObj, payload);
}

// Guide probes need closest-hit material/motion data but consume no sky radiance. A dedicated miss record
// avoids running the nested atmosphere march when a reflection/refraction guide escapes the scene.
void traceGuideReordered(uint rayFlags, float3 ro, float tmin, float3 rd, float tmax) {
    HitObject hObj = HitObject::TraceRay(topLevelAS, rayFlags, CULL_SECONDARY,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_GUIDE, makeRay(ro, tmin, rd, tmax), payload);
    ReorderThread(hObj);
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
    traceGuideReordered(RAY_FLAG_NONE, surfacePos + n * SURF_BIAS, RAY_TMIN, specDir, 10000.0);

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
                     bool continueThroughExit, float exitEta,
                     float rayBias, float rayConeWidth, float rayConeSpread,
                     out float3 hitCamRel, out float3 motionPrev, out bool transmitted,
                     out float3 normal, out float roughness,
                     out float3 diffuseAlbedo) {
    hitCamRel = surfacePos - pc.camOffset;
    motionPrev = float3(0.0, 0.0, 0.0);
    transmitted = dot(transmittedDir, transmittedDir) > 0.0;
    normal = surfaceNormal;
    roughness = 1.0;
    diffuseAlbedo = float3(0.0, 0.0, 0.0);
    if (!transmitted) return;

    float3 direction = normalize(transmittedDir);
    float3 ro = surfacePos - surfaceNormal * rayBias;
    for (uint crossing = 0u; crossing < 2u; crossing++) {
        payload.hitT = -1.0;
        payloadSetTraceState(false, rayConeWidth, rayConeSpread);
        traceGuideReordered(RAY_FLAG_NONE, ro, RAY_TMIN, direction, 10000.0);
        if (payload.hitT <= 0.0 || !continueThroughExit || crossing != 0u) break;

        if (payloadMaterial() != MATERIAL_GLASS) break;

        float3 interfacePos = ro + direction * payload.hitT;
        float3 interfaceNormal = payload.normal;
        // Thin panes provide exitEta=1; solid glass/ice provides its exact medium-to-outside ratio.
        // A primary ray entering a closed Minecraft cell exits its opposite face, so this bounded path
        // cannot reach the total-internal-reflection case.
        direction = refract(direction, interfaceNormal, exitEta);
        ro = interfacePos - interfaceNormal * rayBias;
    }

    if (payload.hitT < 0.0) {
        hitCamRel = (ro + direction * 1.0e6) - pc.camOffset;
        motionPrev = float3(0.0, 0.0, 0.0);
        normal = float3(0.0, 0.0, 0.0);
        roughness = 1.0;
        diffuseAlbedo = SKY_DIFF_ALBEDO;
        return;
    }

    float3 hitPos = ro + direction * payload.hitT;
    hitCamRel = hitPos - pc.camOffset;
    uint material = payloadMaterial();
    motionPrev = material != MATERIAL_WATER ? payload.motionPrev : float3(0.0, 0.0, 0.0);
    normal = payload.normal;
    roughness = material == MATERIAL_WATER ? WATER_GUIDE_ROUGH : clamp(payloadRoughness(), 0.0, 1.0);
    if (material == MATERIAL_OPAQUE) {
        bool pbr = (pc.flags & 2u) != 0u;
        diffuseAlbedo = pbr ? payload.albedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0)) : payload.albedo;
    }
}

// One Monte Carlo path from (ro, rd). Returns accumulated radiance along the path.
float3 tracePath(float3 ro, float3 rd, float primaryConeSpread,
                 bool captureGuides, bool captureRefractedGuides, bool primaryOnly, inout uint seed) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 throughput = float3(1.0, 1.0, 1.0);
    float rayConeWidth = 0.0;
    float rayConeSpread = max(primaryConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(pc.maxBounces);
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
    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        payload.hitT = -1.0;
        payloadSetTraceState(showCelestial, rayConeWidth, rayConeSpread);
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles and the first-person body,
        // but not the separately captured head. Bounce rays are secondary (CULL_SECONDARY): exclude
        // particles and include both first-person body and head for shadows, GI, and reflections.
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0);

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
            if (opacity < 1.0 && rndf(seed) >= opacity) {
                ro = hitPos - n * GLASS_TRANSMIT_BIAS;
                continue;
            }
            material = MATERIAL_OPAQUE;
        }

        // Panes are collapsed to one effective thin sheet; solid glass and ice refract through their
        // closed geometry. No medium stack, ordered shadow walk, or extra pipeline is introduced:
        // ordinary pixels retain the upstream path-tracing kernel and cost.
        if (material == MATERIAL_GLASS) {
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
            if (rndf(seed) < F) {
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
                if (rndf(seed) > q) {
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
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
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
            rd = cosineDir(n, seed);
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
                gv_albedo = float3(0.0, 0.0, 0.0); // pure specular fallback for TIR
                gv_specAlb = float3(F, F, F);
                gv_hitCamRel = hitPos - pc.camOffset; // depth + reflection start at the water surface
                gv_motionHitCamRel = gv_hitCamRel;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0);
                gv_specPreviousNormal = previousWaterNormal;
                if (captureRefractedGuides && dot(transmittedDir, transmittedDir) > 0.0) {
                    gv_opticalGuideDir = transmittedDir;
                    gv_opticalGuideMode = 1u;
                }
            }
            if (primaryOnly || bounce >= maxBounces) {
                break;
            }
            waterExt = waterExtinction(surfaceWaterTint);
            if (rndf(seed) < F) {
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
                if (rndf(seed) > q) {
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
        }
        if (primaryOnly) {
            break;
        }

        // Emissive surfaces (lava, glowstone, torches, ...) add radiance directly, colored by albedo.
        // Block lights have no NEE, so emission is gathered by hitting emitters during the walk (counted
        // at every vertex, no double counting). Small emitters are noisy, but temporal accumulation
        // converges static views.
        float emission = payloadEmission();
        if (emission > 0.0) {
            L += throughput * albedo * emission * EMISSIVE_STRENGTH;
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (pc.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        float3 lightDir = pc.lightDir.xyz;
        float lightHalfAngle = pc.lightDir.w;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
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
                float3 brdf = diffAlb * INV_PI; // Lambertian diffuse (f = albedo/PI)
                if (pbr) {
                    brdf = evaluateEonDiffuse(lightDir, v, n, perceptualRough, diffAlb)
                            * ggxDiffuseTransmission(n, v, lightDir, rough, F0);
                    brdf += evaluateGgx(n, v, lightDir, rough, F0);
                    brdf += evaluateGgxMultiscatter(n, v, lightDir, rough, F0);
                }
                L += throughput * brdf * pc.lightRadiance.xyz * ndl * vis;
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
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * pc.lightRadiance.xyz * visB;
                }
            }
        }

        // This vertex's emission and direct lighting are complete. At the depth limit there is no next
        // ray, so sampling a continuation lobe and running roulette cannot contribute to the result.
        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance. With PBR off, use the cosine-weighted Lambertian fallback.
        if (pbr) {
            float ps = clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
            if (rndf(seed) < ps) {
                float3 h = sampleGGXVNDF(n, v, rough, seed);
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
                showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
            } else {
                float diffusePdf;
                float3 l = sampleEon(n, v, perceptualRough, seed, diffusePdf);
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
            throughput *= albedo; // Lambertian fallback (PI and cos cancel)
            showCelestial = false; // diffuse bounce: hide the disc (covered by NEE)
            ro = p;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    return L;
}

[shader("raygeneration")]
void main() {
    pc = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0]; // load the per-frame push data from the BDA buffer
    int2 pix = int2(DispatchRaysIndex().xy);
    float2 size = float2(DispatchRaysDimensions().xy);
    float2 uv = (float2(pix) + 0.5) / size;
    float2 ndc = uv * 2.0 - 1.0; // unjittered pixel centre — the motion vector reprojects this

    // Offset the primary ray within the pixel by the sub-pixel jitter reported to DLSS-RR, so
    // successive frames sample different positions and RR can resolve detail above render resolution.
    // The guide buffers below come from this jittered first hit (they must match the jittered colour);
    // only the motion vector stays on the unjittered centre, which DLSS expects.
    float2 jndc = (uv + pc.jitter / size) * 2.0 - 1.0;

    float4 nearH = mul(pc.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
    float4 farH = mul(pc.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;

    float3 origin = nearP + pc.camOffset;
    float3 dir = normalize(farP - nearP);
    float rayConeSpread = primaryRayConeSpread(jndc, size, dir);

    uint seed = (uint(pix.x) * 1973u + uint(pix.y) * 9277u + 26699u) ^ (pc.frameIndex * 2654435761u);

    uint spp = max(pc.spp, 1u);
    bool primaryOnly = pc.debugView != 0u;
    bool writeRrGuides = (pc.flags & FRAME_FLAG_RR_GUIDES) != 0u;
    bool writeMotionGuides = writeRrGuides || (pc.flags & FRAME_FLAG_FG_GUIDES) != 0u;
    bool captureGuides = writeMotionGuides || primaryOnly;
    bool captureRefractedGuides = writeMotionGuides
            || pc.debugView == 2u || pc.debugView == 5u || pc.debugView == 6u || pc.debugView == 7u;
    uint pathCount = primaryOnly ? 1u : spp;
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    gv_opticalGuideMode = 0u;
    gv_opticalExitEta = 1.0;
    gv_specPreviousNormal = float3(0.0, 0.0, 0.0);
    for (uint s = 0u; s < pathCount; s++) {
        seed = pcg(seed); // decorrelate per-sample paths (shared camera ray, no AA jitter yet)
        // The primary ray is shared by all SPP samples. Preserve guides from sample zero; the radiance path
        // keeps its own saved water-interface state when the guide probe reuses the payload.
        frameRadiance += tracePath(origin, dir, rayConeSpread,
                captureGuides && s == 0u, captureRefractedGuides && s == 0u, primaryOnly, seed);
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
                gv_opticalGuideMode == 3u, gv_opticalExitEta,
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

    // Hardware (non-linear, reversed-Z) depth for DLSS-RR + Frame Generation: project the camera-relative
    // primary hit through the forward view-projection and take ndc z/w — exactly the value a rasterizer
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag; sky's far hit -> ~0.
    // Water depth follows its surface; clear glass follows the transmitted destination to avoid blur.
    bool debugDepth = pc.debugView == 3u;
    bool debugMotion = pc.debugView == 5u;
    bool debugSpecMotion = pc.debugView == 7u;
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

    float2 specMotion = float2(0.0, 0.0);
    if (writeRrGuides || debugSpecMotion) {
        specMotion = specularReflectionMotion(
                specSurfaceCamRel + pc.camOffset, specSurfaceNormal, specSurfacePreviousNormal,
                specSurfaceRoughness,
                specSurfaceAlbedo, dir, jndc, size, rayConeSpread);
    }

    if (writeRrGuides) {
        gNormal[pix] = float4(gv_normal, gv_rough);
        gAlbedo[pix] = float4(gv_albedo, 1.0);
        gDepth[pix] = gv_depth;
        gMotion[pix] = motion;
        gSpecAlbedo[pix] = float4(gv_specAlb, 1.0);
        gSpecMotion[pix] = specMotion;
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
        } else {
            dbg = float3(clamp(0.5 + motion * 0.05, 0.0, 1.0), 0.5); // motion: red=+x, green=+y
        }
        outImage[pix] = float4(dbg, 1.0);
        return;
    }

    // Single noisy estimate; the denoiser (DLSS-RR) handles temporal convergence.
    outImage[pix] = float4(frameRadiance, 1.0);
}
