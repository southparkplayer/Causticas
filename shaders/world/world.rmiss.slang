// Ray miss = sky. Per-frame sky data comes from the WorldPush BDA buffer addressed by the 8-byte push
// constant. The shader writes full sky radiance (Rayleigh/Mie/ozone atmosphere + transmittance-tinted
// sun/moon discs + soft horizon + starfield) into payload.albedo; raygen consumes that value for both
// accumulated radiance and bounce-0 denoiser guides.
//
// Celestial discs (sun/moon) are gated by a packed payload flag, which raygen sets per-ray from the path
// state: true on the primary ray and after every specular/dielectric bounce (so the sun/moon show up in
// water/glass/metal reflections + refractions), false after a diffuse vertex did sun/moon NEE (so the
// disc isn't double-counted against the direct light → fireflies). The atmosphere gradient + stars are
// added on every miss (any bounce): stars aren't a NEE light, so they never double-count.
import world_common;
import world_sky_lut;
import world_stars;

[[vk::push_constant]] PushAddr pcAddr;

// Vanilla celestials atlas (sun + moon-phase sprites), bound by RtComposite. Sampled with an explicit
// LOD (no derivatives in a miss shader). The sun/moon discs are drawn from its real texels.
[[vk::binding(20, 0)]] Sampler2D celestialsAtlas;
static const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

// Near-white top-of-atmosphere disc radiances: the visible warm/red tint comes from multiplying by
// transmittanceToSpace(dir) at draw time — at noon the sun reads warm-white, at the horizon it reddens
// on exactly the sky's own sunset curve.
// Presentation-only solar radiance. Direct illumination remains calibrated independently in RtComposite;
// this higher disc radiance supplies the retinal glare/bloom expected from looking at the sun without
// lifting terrain exposure or multiplying sunlight energy.
static const float SUN_DISC_AVG_LUMINANCE = 192.0;
// Presentation-only moon radiance. Keep the vanilla-sized atlas sprite bright enough to produce a
// restrained bloom response, but do not multiply it by transported moonlight or normalize it by the
// sprite's solid angle. Both of those made the visible moon explode into a white square.
static const float MOON_DISC_BASE_LUMINANCE = 0.125; // enough scene-linear energy for restrained bloom
// Vibrance applied to the atmosphere in-scatter only (not discs/stars/night ambient): luminance-
// preserving, so GI energy is unchanged.
static const float SKY_SATURATION = 1.0; // spectral conversion supplies physical chromaticity
// Physical mean apparent angular radii. These match the default NEE radii, so visible bodies and their
// lighting share one angular scale instead of reflecting Minecraft's enormous decorative sky quads.
static const float SUN_LIMB_DARKENING = 0.6;
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.

// ---- Physically-based atmosphere (Nishita / O'Neil single scattering + ozone absorption). A view ray
// is marched through the atmospheric shell; at each step the transmittance toward the sun is integrated
// (a second, shorter march), giving Rayleigh (blue-sky / red-sunset) + Mie (sun halo) in-scattered
// radiance. World up = +Y; the camera sits a couple km above a spherical planet. Output is HDR in the
// same units as tracePath.
#if 0 // Superseded by the Garcia-Linan four-sample spectral model below.
static const float  PLANET_R   = 6371000.0;
static const float  ATMOS_R    = 6471000.0;        // 100 km shell
static const float3 RAY_BETA   = float3(5.5e-6, 13.0e-6, 22.4e-6); // Rayleigh scattering at sea level
static const float  MIE_BETA   = 21.0e-6;          // Mie scattering at sea level
static const float  RAY_SCALE_H = 8000.0;          // Rayleigh density scale height
static const float  MIE_SCALE_H = 1200.0;          // Mie density scale height
static const float  MIE_G      = 0.758;            // Mie anisotropy (forward-scattering)
// Ozone: absorption only (no scattering), tent-shaped density peaking at 25 km. Its green-heavy
// absorption keeps the zenith deep blue at noon and turns twilight purple-orange.
static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
static const float  OZONE_CENTER_H = 25000.0;
static const float  OZONE_WIDTH_H  = 15000.0;
static const float  SUN_INTENSITY = 22.0;
static const int    ATMOS_PRIMARY_STEPS = 16;
static const int    ATMOS_LIGHT_STEPS   = 8;

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H); }

// Smaller root of ray vs sphere of radius r centred at origin (no-hit handled by x > y).
float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

float3 atmosphere(float3 dir, float3 sunDir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);      // ray misses the atmosphere entirely
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x); // clip the march at the planet surface
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = MIE_G;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;            // view-ray optical depth accumulators
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        // March toward the sun to accumulate the light-ray optical depth (transmittance to space).
        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; } // sun is below this point's horizon → in shadow
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 tau = RAY_BETA * (odR + odRL) + MIE_BETA * 1.1 * (odM + odML)
                       + OZONE_BETA * (odO + odOL);
            float3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}

// Extinction from the camera to space along `dir` (Rayleigh + Mie + ozone; the sun-below-horizon case
// needs no explicit planet test — a grazing/downward path picks up enormous optical depth, so the
// transmittance rolls to zero smoothly on its own). Tints the sun/moon discs at draw time, and is ported
// verbatim to RtComposite.writeSky so the NEE sunlight on terrain follows the identical sunset curve —
// the disc, the sky, and the light can never disagree.
float3 transmittanceToSpace(float3 dir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.y <= 0.0) return float3(1.0, 1.0, 1.0);
    float segLen = t.y / float(ATMOS_LIGHT_STEPS);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = orig + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / RAY_SCALE_H) * segLen;
        odM += exp(-h / MIE_SCALE_H) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return exp(-(RAY_BETA * odR + MIE_BETA * 1.1 * odM + OZONE_BETA * odO));
}

#endif

// García Liñán four-sample spectral atmosphere. The wavelength set (630, 560, 490, 430 nm), fitted
// solar spectrum, collision coefficients, and XYZ reconstruction weights form one model-specific unit.
// Distances here are kilometres, matching the source model.
static const float SPECTRAL_PLANET_R = 6371.0;
static const float SPECTRAL_ATMOS_R = 6471.0;
static const float4 SPECTRAL_SUN = float4(1.679, 1.828, 1.986, 1.307);
static const float4 SPECTRAL_MOLECULAR_BASE = float4(6.605e-3, 1.067e-2, 1.842e-2, 3.156e-2);
static const float4 SPECTRAL_OZONE_XS = float4(3.472e-25, 3.914e-25, 1.349e-25, 11.03e-27);
static const float SPECTRAL_OZONE_DU = 334.5;
static const float4 SPECTRAL_AEROSOL_ABSORB_XS = float4(2.8722e-24, 4.6168e-24, 7.9706e-24, 1.3578e-23);
static const float4 SPECTRAL_AEROSOL_SCATTER_XS = float4(1.5908e-22, 1.7711e-22, 2.0942e-22, 2.4033e-22);
static const float SPECTRAL_AEROSOL_BASE_DENSITY = 1.3681e20;
static const float SPECTRAL_AEROSOL_BACKGROUND_DENSITY = 2.0e6;
static const float SPECTRAL_AEROSOL_HEIGHT_SCALE = 0.73;
// Must match sky_view.comp so exact mirrors and the pre-integrated visible sky share one aureole shape.
static const float SPECTRAL_AEROSOL_G = 0.88;
static const float SPECTRAL_SKY_PHOTOMETRIC_SCALE = 0.9765625;
// Bounded isotropic return for scattering orders beyond the directly integrated first order. This is
// the real-time energy-compensation closure around the four-wavelength medium, not part of García
// Liñán's spectral reconstruction fit. It prevents the planet-shadow boundary from becoming a black
// anti-solar wedge at twilight while retaining the wavelength-dependent extinction and source terms.
static const int ATMOS_PRIMARY_STEPS = 32;
static const int ATMOS_LIGHT_STEPS = 16;

float2 spectralRaySphere(float3 origin, float3 dir, float radius) {
    float b = dot(origin, dir);
    float c = dot(origin, origin) - radius * radius;
    float discriminant = b * b - c;
    if (discriminant < 0.0) return float2(1.0e9, -1.0e9);
    discriminant = sqrt(discriminant);
    return float2(-b - discriminant, -b + discriminant);
}

// Resolve planet visibility analytically before integrating optical depth. Testing the integration
// midpoints against the surface made visibility depend on which of the fixed samples happened to land
// below ground. A delta mirror magnifies those discrete transitions into camera-centred rings (or nearly
// parallel bands at grazing angles), and the deterministic error survives offline accumulation.
bool spectralPlanetOccludes(float3 origin, float3 dir) {
    float2 ground = spectralRaySphere(origin, dir, SPECTRAL_PLANET_R);
    return ground.x > 0.0 && ground.x <= ground.y;
}

float3 spectralToXyz(float4 spectrum) {
    return float3(53.3869177386, 22.9813375067, 0.0) * spectrum.x
        + float3(43.9048444664, 71.3477957001, 0.1025068680) * spectrum.y
        + float3(1.6137278252, 18.4229605915, 31.7429211884) * spectrum.z
        + float3(20.7626686738, 2.3614213523, 110.4800964325) * spectrum.w;
}

float3 xyzToBt2020(float3 xyz) {
    return float3(
        1.7166512 * xyz.x - 0.3556708 * xyz.y - 0.2533663 * xyz.z,
       -0.6666844 * xyz.x + 1.6164812 * xyz.y + 0.0157685 * xyz.z,
        0.0176399 * xyz.x - 0.0427706 * xyz.y + 0.9421031 * xyz.z);
}

float3 spectralToBt2020(float4 spectrum) {
    return xyzToBt2020(spectralToXyz(spectrum));
}

float4 spectralMolecularScattering(float h, float4 optical) {
    return SPECTRAL_MOLECULAR_BASE * optical.x
        * exp(-0.07771971 * pow(max(h, 0.0), 1.16364243));
}

float4 spectralMolecularAbsorption(float h, float4 optical) {
    float logH = log(max(h, 1.0e-4));
    float density = 3.78547397e20 * exp(-pow2(logH - 3.22261) * 5.55555555 - logH);
    return SPECTRAL_OZONE_XS * SPECTRAL_OZONE_DU * optical.w * density;
}

float spectralAerosolDensity(float h, float aerosolHeightKm) {
    float background = SPECTRAL_AEROSOL_BACKGROUND_DENSITY / SPECTRAL_AEROSOL_BASE_DENSITY;
    return SPECTRAL_AEROSOL_BASE_DENSITY
        * (exp(-max(h, 0.0) / max(aerosolHeightKm, 0.1)) + background);
}

void spectralCollision(float h, out float4 molecularScatter, out float4 aerosolScatter,
                       out float4 extinction, float4 optical, float aerosolHeightKm) {
    float density = spectralAerosolDensity(h, aerosolHeightKm);
    molecularScatter = spectralMolecularScattering(h, optical);
    aerosolScatter = SPECTRAL_AEROSOL_SCATTER_XS * optical.y * density;
    extinction = molecularScatter + aerosolScatter
        + SPECTRAL_AEROSOL_ABSORB_XS * optical.z * density
        + spectralMolecularAbsorption(h, optical);
}

float4 spectralTransmittance(float3 origin, float3 dir, WorldPush pc) {
    if (spectralPlanetOccludes(origin, dir)) return float4(0.0);
    float2 t = spectralRaySphere(origin, dir, SPECTRAL_ATMOS_R);
    if (t.y <= 0.0) return float4(1.0);
    float stepLength = t.y / float(ATMOS_LIGHT_STEPS);
    float4 opticalDepth = float4(0.0);
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = origin + dir * (stepLength * (float(i) + 0.5));
        float h = max(length(p) - SPECTRAL_PLANET_R, 0.0);
        float4 molecularScatter, aerosolScatter, extinction;
        spectralCollision(h, molecularScatter, aerosolScatter, extinction,
                pc.skyOptical, pc.skyShape.x);
        opticalDepth += extinction * stepLength;
    }
    return exp(-opticalDepth);
}

float3 spectralTransmittanceToSpace(float3 dir, WorldPush pc) {
    float3 origin = float3(0.0, SPECTRAL_PLANET_R + 2.0, 0.0);
    float3 transmitted = spectralToBt2020(SPECTRAL_SUN * spectralTransmittance(origin, dir, pc));
    float3 reference = spectralToBt2020(SPECTRAL_SUN);
    return max(transmitted / max(reference, float3(1.0e-6)), float3(0.0));
}

float3 spectralAtmosphere(float3 dir, float3 lightDir, float sourceScale, WorldPush pc) {
    if (sourceScale <= 0.0) return float3(0.0);
    float3 origin = float3(0.0, SPECTRAL_PLANET_R + 2.0, 0.0);
    float2 t = spectralRaySphere(origin, dir, SPECTRAL_ATMOS_R);
    if (t.x > t.y) return float3(0.0);
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 ground = spectralRaySphere(origin, dir, SPECTRAL_PLANET_R);
    if (ground.x > 0.0) tEnd = min(tEnd, ground.x);
    float stepLength = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, lightDir);
    float molecularPhase = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float aerosolG = clamp(pc.skyShape.y, 0.0, 0.95);
    float g2 = aerosolG * aerosolG;
    float phaseDen = max(1.0 + g2 - 2.0 * aerosolG * mu, 1.0e-6);
    float aerosolPhase = (1.0 / (4.0 * PI)) * (1.0 - g2) / (phaseDen * sqrt(phaseDen));

    float4 radiance = float4(0.0);
    float4 viewTransmittance = float4(1.0);
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = origin + dir * (tStart + (float(i) + 0.5) * stepLength);
        float h = max(length(p) - SPECTRAL_PLANET_R, 0.0);
        float4 molecularScatter, aerosolScatter, extinction;
        spectralCollision(h, molecularScatter, aerosolScatter, extinction,
                pc.skyOptical, pc.skyShape.x);
        float4 lightTransmittance = spectralTransmittance(p, lightDir, pc);
        float4 source = SPECTRAL_SUN * sourceScale
            * (molecularScatter * molecularPhase + aerosolScatter * aerosolPhase)
            * lightTransmittance;
        float4 stepTransmittance = exp(-extinction * stepLength);
        float4 integratedSource = source * (1.0 - stepTransmittance)
            / max(extinction, float4(1.0e-7));
        radiance += viewTransmittance * integratedSource;
        viewTransmittance *= stepTransmittance;
    }
    return max(spectralToBt2020(radiance) * SPECTRAL_SKY_PHOTOMETRIC_SCALE
            * pc.skyShape.z * pc.skyTint.xyz, float3(0.0));
}

// A delta mirror exposes the angular texel grid of the sky-view LUT because it supplies no BRDF
// footprint that could integrate over the table. Filtering the same finite grid merely turns its cell
// edges into wider rectangular ghosts. Mirror misses are uncommon and already pay for a recursive PSR
// guide, so evaluate the continuous spectral atmosphere here instead of feeding deterministic LUT error
// into either the offline running mean or DLSS-RR history.
float3 sampleMirrorSky(float3 dir, WorldPush pc) {
    float solarEnvelope = pc.skyState.w;
    float rainBrightness = pc.sunDir.w;
    float solarSource = solarEnvelope * pc.skyLighting.x * rainBrightness;
    float lunarSource = (0.25 / 120000.0) * pc.skyLighting.y * pc.skyLighting.w
            * (1.0 - solarEnvelope) * rainBrightness;
    return spectralAtmosphere(dir, pc.sunDir.xyz, solarSource, pc)
            + spectralAtmosphere(dir, pc.moonDir.xyz, lunarSource, pc);
}

// Build the celestial body's square tangent frame: `right` along the arc-travel direction, `up`
// perpendicular within the sky. Shared by the visible disc and the NEE square sampling in raygen.
void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

// Gnomonic projection of `dir` into the body's tangent square, in units of the half-angle. local ∈
// [-1,1]² is inside the square. Returns 0 behind the body. The square (not a cone) matches MC's quad.
float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;                  // d·bodyDir == 1 (point on the tangent plane)
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    // Minecraft 26.2's celestial sprites have opaque alpha and retain faint non-black halo texels at
    // the square boundary. Feather the geometric edge so those authored low values cannot expose the
    // full tangent quad after HDR exposure or a specular bounce.
    return 1.0 - smoothstep(0.90, 1.0, m);
}

// Energy-normalized linear limb darkening over a circular finite solar emitter. The 1-u/3 disc
// average keeps changing the profile from silently changing the sunlight carried by the disc.
float solarDisc(float3 dir, float3 sunDir, float radius, float rayFootprint,
                float limbDarkening, out float limb) {
    float angularDistance = acos(clamp(dot(dir, sunDir), -1.0, 1.0));
    float edge = max(rayFootprint, 1.0e-5);
    float coverage = 1.0 - smoothstep(radius - edge, radius + edge, angularDistance);
    float normalizedRadius = saturate(angularDistance / max(radius, 1.0e-6));
    float mu = sqrt(max(1.0 - normalizedRadius * normalizedRadius, 0.0));
    limb = (1.0 - limbDarkening * (1.0 - mu))
            / (1.0 - limbDarkening / 3.0);
    return coverage;
}

bool validUvRect(float4 rect) {
    return all(rect.zw > rect.xy) && all(rect.xy >= float2(0.0)) && all(rect.zw <= float2(1.0));
}

float celestialTextureLod(float4 rect, float halfAngle, uint rayCone) {
    uint atlasWidth, atlasHeight;
    celestialsAtlas.GetDimensions(atlasWidth, atlasHeight);
    float2 spriteSize = (rect.zw - rect.xy) * float2(atlasWidth, atlasHeight);
    float angularFootprint = max(unpackHalf2(rayCone).y, 1.0e-7);
    float texelFootprint = angularFootprint * max(spriteSize.x, spriteSize.y)
            / max(2.0 * halfAngle, 1.0e-7);
    float maxLod = log2(max(max(spriteSize.x, spriteSize.y), 1.0));
    return clamp(log2(max(texelFootprint, 1.0)), 0.0, maxLod);
}

[shader("miss")]
void main(inout Payload payload) {
    WorldPush pc = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    // Debug views consume primary-hit guides rather than radiance. Their reflection/refraction probes
    // only need hit-vs-miss state, so avoid the nested atmosphere integration for every debug miss.
    if (pc.debugView != 0u) {
        payload.albedo = float3(0.0, 0.0, 0.0);
        payload.hitT = -1.0;
        payload.normal = float3(0.0, 0.0, 0.0);
        payload.geometricNormal = float3(0.0, 0.0, 0.0);
        payload.motionPrev = float3(0.0, 0.0, 0.0);
        payload.f0 = float3(0.0, 0.0, 0.0);
        payload.flags = 0u;
        payload.roughMetal = packHalf2(float2(1.0, 0.0));
        payload.emissionSss = packHalf2(float2(0.0, 0.0));
        payload.rayCone = 0u;
        return;
    }
    float3 dir = normalize(WorldRayDirection());
    float solarEnvelope = pc.skyState.w;
    float ambientMultiplier = pc.skyState.z;
    float3 sd = pc.sunDir.xyz;
    float VdotS = dot(dir, sd);
    float starBrightness = pc.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;
    bool filterSky = (payload.flags & PAYLOAD_FILTER_SKY) != 0u;
    bool primaryRay = (payload.flags & PAYLOAD_PRIMARY_RAY) != 0u;
    // Exact mirrors keep the continuous atmosphere path; ordinary glossy/specular misses only opt into
    // the camera-visible lower-hemisphere fallback and continue using the cheaper sky-view LUT.
    bool presentationSky = primaryRay || filterSky
            || (payload.flags & PAYLOAD_PRESENTATION_SKY) != 0u;
    bool earthAtmosphere = (pc.flags & (1u << 10u)) != 0u;

    // Physically-based in-scattering: blue zenith, bright/desaturated horizon, warm Mie halo + red
    // sunset all fall out of the Rayleigh/Mie/ozone march. At night the sun is below the horizon so the
    // light march is blocked → near-zero; a faint night ambient + the rotating starfield sit underneath.
    //
    // Evaluate the exact upper-hemisphere horizon value on both sides of y=0, then collapse it into a tightly
    // bounded ground/void term immediately below. This closes the horizon seam without turning a terrain
    // precision hole into a meaningful light source under high exposure.
    bool aboveHorizon = dir.y >= 0.0;
    float belowHorizonFade = exp(min(dir.y, 0.0) * 48.0);
    float3 groundVoid = bt709ToBt2020(float3(0.35, 0.42, 0.55)) * (2.0e-6 * belowHorizonFade);
    float fallbackGradient = (aboveHorizon || presentationSky)
            ? 0.25 + 0.75 * clamp(max(dir.y, 0.0) * 0.5 + 0.5, 0.0, 1.0) : 0.0;
    float3 horizonDir = normalize(float3(dir.x + 1.0e-6, 0.0, dir.z));
    float3 skyDir = aboveHorizon ? dir : horizonDir;
    float3 col;
    if (earthAtmosphere) {
        col = filterSky ? sampleMirrorSky(skyDir, pc) : sampleSkyView(skyDir);
    } else {
        col = max(pc.environmentSky.xyz, float3(0.0)) * fallbackGradient;
    }
    // Luminance-preserving vibrance on the in-scatter only (see SKY_SATURATION).
    float lum = transportLuminance(col);
    col = max(lerp(float3(lum, lum, lum), col, pc.skyShape.w), float3(0.0, 0.0, 0.0));
    float up = clamp(skyDir.y, 0.0, 1.0);
    float3 nightAmbient = bt709ToBt2020(lerp(
            SKY_AIRGLOW_HORIZON * pc.skyAirglowHorizon.xyz,
            SKY_AIRGLOW_ZENITH * pc.skyAirglowZenith.xyz, up));
    col += earthAtmosphere
            ? nightAmbient * pc.skyLighting.z * (1.0 - solarEnvelope) : float3(0.0);
    bool directSkyStarLayer = (pc.flags & FRAME_FLAG_DIRECT_SKY_STARS) != 0u;
    if (earthAtmosphere && aboveHorizon && (!primaryRay || !directSkyStarLayer)) col += sharedStars(dir,
            starBrightness * pc.skyCelestialAppearance.w, VdotS, pc.celestial,
            pc.skyStarAppearance.x, pc.skyStarAppearance.y, pc.skyStarTint.xyz);
    col *= ambientMultiplier;
    // Match the upper-hemisphere value at y=0, then collapse to the bounded void within one
    // thousandth of a direction unit. This removes the seam without opening broad geometry leaks.
    if (!presentationSky && earthAtmosphere && !aboveHorizon) {
        float boundaryWeight = smoothstep(-1.0e-3, 0.0, dir.y);
        col = lerp(groundVoid * ambientMultiplier, col, boundaryWeight);
    }

    // The analytic solar disc and atlas-authored lunar phase are gated off after diffuse NEE so direct
    // lighting is not counted again by a continuation miss.
    if (showCelestial && earthAtmosphere) {
        float2 local;
        float limb;
        float sunCoverage = solarDisc(dir, sd, pc.celestialRadii.x,
                1.0e-4, pc.skyCelestialAppearance.z, limb);
        if (aboveHorizon && sunCoverage > 0.0) {
            col += SUN_DISC_AVG_LUMINANCE * pc.skyCelestialAppearance.x * pc.skySunTint.xyz
                    * spectralTransmittanceToSpace(dir, pc)
                    * (limb * sunCoverage * pc.sunDir.w);
        }
        // Same horizon gate as the sun: with the soft-horizon change, below-horizon rays no longer clip
        // at the planet, so without this the moon disc would stay visible after it set.
        float moonCoverage = squareBody(dir, pc.moonDir.xyz, pc.celestialRadii.y, pc.celestial.xyz, local);
        if (aboveHorizon && moonCoverage > 0.0 && validUvRect(pc.moonUv)) {
            float2 uv = lerp(pc.moonUv.xy, pc.moonUv.zw, local * 0.5 + 0.5);
            // The vanilla atlas is already a tiny pixel-art sprite. Mip sampling it from the packed
            // atlas pulls neighbouring phase texels into the border and creates the coloured halo.
            float4 t = celestialsAtlas.SampleLevel(uv, 0.0);
            // The selected vanilla phase sprite remains authoritative; synthesizing a second phase mask
            // here would fight resource packs and make the displayed phase disagree with Minecraft.
            float3 textureLinear = bt709ToBt2020(srgbToLinear(t.rgb));
            float3 moonTint = spectralTransmittanceToSpace(dir, pc)
                * bt709ToBt2020(float3(1.02, 0.98, 0.90)) * pc.skyMoonTint.xyz;
            col += MOON_DISC_BASE_LUMINANCE * pc.skyCelestialAppearance.y * moonTint
                    * textureLinear * (t.a * moonCoverage * pc.sunDir.w);
        }
    }

    payload.albedo = clamp(col, float3(0.0), float3(65504.0)); // rgba16f storage boundary
    payload.hitT = -1.0;
    payload.normal = float3(0.0, 0.0, 0.0);
    payload.geometricNormal = float3(0.0, 0.0, 0.0);
    payload.motionPrev = float3(0.0, 0.0, 0.0);
    payload.f0 = float3(0.0, 0.0, 0.0);
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.rayCone = 0u;
}
