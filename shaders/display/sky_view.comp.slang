[[vk::binding(0, 0)]] [format("rgba32f")] RWTexture2D<float4> skyView;
[[vk::binding(1, 0)]] [format("rgba16f")] RWTexture2D<float4> transmittanceLut;

struct SkyViewPush {
    float4 sun;   // xyz direction, w source scale
    float4 moon;  // xyz direction, w source scale
    uint enabled;
    uint pass;
};
[[vk::push_constant]] SkyViewPush pc;

static const float PI = 3.14159265359;
static const float PLANET_R = 6371.0;
static const float ATMOS_R = 6471.0;
static const float4 SUN_SPECTRUM = float4(1.679, 1.828, 1.986, 1.307);
static const float4 MOLECULAR_BASE = float4(6.605e-3, 1.067e-2, 1.842e-2, 3.156e-2);
static const float4 OZONE_XS = float4(3.472e-25, 3.914e-25, 1.349e-25, 11.03e-27);
static const float OZONE_DU = 334.5;
static const float4 AEROSOL_ABSORB_XS = float4(2.8722e-24, 4.6168e-24, 7.9706e-24, 1.3578e-23);
static const float4 AEROSOL_SCATTER_XS = float4(1.5908e-22, 1.7711e-22, 2.0942e-22, 2.4033e-22);
static const float AEROSOL_BASE_DENSITY = 1.3681e20;
static const float AEROSOL_BACKGROUND_DENSITY = 2.0e6;
static const float AEROSOL_HEIGHT_SCALE = 0.73;
static const float AEROSOL_G = 0.8;
static const float PHOTOMETRIC_SCALE = 0.9765625;
static const int VIEW_STEPS = 32;
static const int LIGHT_STEPS = 16;

float2 raySphere(float3 origin, float3 dir, float radius) {
    float b = dot(origin, dir);
    float c = dot(origin, origin) - radius * radius;
    float d = b * b - c;
    if (d < 0.0) return float2(1.0e9, -1.0e9);
    d = sqrt(d);
    return float2(-b - d, -b + d);
}

float3 spectrumToBt2020(float4 s) {
    float3 xyz = float3(53.3869177386, 22.9813375067, 0.0) * s.x
        + float3(43.9048444664, 71.3477957001, 0.1025068680) * s.y
        + float3(1.6137278252, 18.4229605915, 31.7429211884) * s.z
        + float3(20.7626686738, 2.3614213523, 110.4800964325) * s.w;
    return float3(
        1.7166512 * xyz.x - 0.3556708 * xyz.y - 0.2533663 * xyz.z,
       -0.6666844 * xyz.x + 1.6164812 * xyz.y + 0.0157685 * xyz.z,
        0.0176399 * xyz.x - 0.0427706 * xyz.y + 0.9421031 * xyz.z);
}

float4 molecularScattering(float h) {
    return MOLECULAR_BASE * exp(-0.07771971 * pow(max(h, 0.0), 1.16364243));
}

float4 molecularAbsorption(float h) {
    float logH = log(max(h, 1.0e-4));
    float d = logH - 3.22261;
    float density = 3.78547397e20 * exp(-d * d * 5.55555555 - logH);
    return OZONE_XS * OZONE_DU * density;
}

void collision(float h, out float4 molecular, out float4 aerosol, out float4 extinction) {
    float background = AEROSOL_BACKGROUND_DENSITY / AEROSOL_BASE_DENSITY;
    float density = AEROSOL_BASE_DENSITY
        * (exp(-max(h, 0.0) / AEROSOL_HEIGHT_SCALE) + background);
    molecular = molecularScattering(h);
    aerosol = AEROSOL_SCATTER_XS * density;
    extinction = molecular + aerosol + AEROSOL_ABSORB_XS * density + molecularAbsorption(h);
}

float4 transmittance(float3 origin, float3 dir) {
    float2 t = raySphere(origin, dir, ATMOS_R);
    if (t.y <= 0.0) return float4(1.0);
    float stepLength = t.y / float(LIGHT_STEPS);
    float4 depth = float4(0.0);
    for (int i = 0; i < LIGHT_STEPS; ++i) {
        float3 p = origin + dir * (stepLength * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        if (h < 0.0) return float4(0.0);
        float4 molecular, aerosol, extinction;
        collision(h, molecular, aerosol, extinction);
        depth += extinction * stepLength;
    }
    return exp(-depth);
}

float4 sampleTransmittance(float3 position, float3 lightDir) {
    float radius = max(length(position), PLANET_R);
    float altitude = clamp(radius - PLANET_R, 0.0, ATMOS_R - PLANET_R);
    float mu = clamp(dot(position / radius, lightDir), -1.0, 1.0);
    uint width, height;
    transmittanceLut.GetDimensions(width, height);
    float2 texel = float2(mu * 0.5 + 0.5, sqrt(altitude / (ATMOS_R - PLANET_R)))
            * float2(width, height) - 0.5;
    int2 base = int2(floor(texel));
    float2 f = frac(texel);
    int2 hi = int2(int(width) - 1, int(height) - 1);
    float4 a = transmittanceLut[clamp(base, int2(0), hi)];
    float4 b = transmittanceLut[clamp(base + int2(1, 0), int2(0), hi)];
    float4 c = transmittanceLut[clamp(base + int2(0, 1), int2(0), hi)];
    float4 d = transmittanceLut[clamp(base + int2(1, 1), int2(0), hi)];
    return lerp(lerp(a, b, f.x), lerp(c, d, f.x), f.y);
}

float4 integrateSpectrum(float3 dir, float3 lightDir, float sourceScale) {
    float3 origin = float3(0.0, PLANET_R + 2.0, 0.0);
    float2 t = raySphere(origin, dir, ATMOS_R);
    if (t.x > t.y || sourceScale <= 0.0) return float4(0.0);
    float start = max(t.x, 0.0);
    float end = t.y;
    float2 ground = raySphere(origin, dir, PLANET_R);
    if (ground.x > 0.0) end = min(end, ground.x);
    float stepLength = (end - start) / float(VIEW_STEPS);
    float mu = dot(dir, lightDir);
    float molecularPhase = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g2 = AEROSOL_G * AEROSOL_G;
    float phaseDen = max(1.0 + g2 - 2.0 * AEROSOL_G * mu, 1.0e-6);
    float aerosolPhase = (1.0 / (4.0 * PI)) * (1.0 - g2) / (phaseDen * sqrt(phaseDen));
    float4 radiance = float4(0.0);
    float4 viewTransmittance = float4(1.0);
    for (int i = 0; i < VIEW_STEPS; ++i) {
        float3 p = origin + dir * (start + (float(i) + 0.5) * stepLength);
        float h = max(length(p) - PLANET_R, 0.0);
        float4 molecular, aerosol, extinction;
        collision(h, molecular, aerosol, extinction);
        float4 source = SUN_SPECTRUM * sourceScale
            * (molecular * molecularPhase + aerosol * aerosolPhase)
            * sampleTransmittance(p, lightDir);
        float4 stepT = exp(-extinction * stepLength);
        radiance += viewTransmittance * source * (1.0 - stepT) / max(extinction, float4(1.0e-7));
        viewTransmittance *= stepT;
    }
    return radiance;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (pc.pass == 0u) {
        uint transWidth, transHeight;
        transmittanceLut.GetDimensions(transWidth, transHeight);
        if (tid.x >= transWidth || tid.y >= transHeight) return;
        float2 uv = (float2(tid.xy) + 0.5) / float2(transWidth, transHeight);
        float mu = uv.x * 2.0 - 1.0;
        float altitude = uv.y * uv.y * (ATMOS_R - PLANET_R);
        float3 origin = float3(0.0, PLANET_R + altitude, 0.0);
        float3 dir = float3(sqrt(max(1.0 - mu * mu, 0.0)), mu, 0.0);
        transmittanceLut[tid.xy] = transmittance(origin, dir);
        return;
    }
    uint width, height;
    skyView.GetDimensions(width, height);
    if (tid.x >= width || tid.y >= height) return;
    if (pc.enabled == 0u) { skyView[tid.xy] = float4(0.0); return; }
    float2 uv = (float2(tid.xy) + 0.5) / float2(width, height);
    // The miss shader only consumes atmospheric radiance above the geometric horizon. Spend every row
    // on that hemisphere; a black lower half otherwise interpolates into the bright horizon row.
    float elevation = uv.y * uv.y * (0.5 * PI);
    float azimuth = (uv.x - 0.5) * (2.0 * PI);
    float ce = cos(elevation);
    float3 dir = float3(ce * cos(azimuth), sin(elevation), ce * sin(azimuth));
    float4 spectrum = integrateSpectrum(dir, pc.sun.xyz, pc.sun.w)
            + integrateSpectrum(dir, pc.moon.xyz, pc.moon.w);
    skyView[tid.xy] = float4(max(spectrumToBt2020(spectrum) * PHOTOMETRIC_SCALE, float3(0.0)), 1.0);
}
