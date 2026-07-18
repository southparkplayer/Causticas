# Archived hue-transport experiment

Status: removed from the live renderer on 2026-07-17. This document is archival only and is not part of the build.

The deleted CPU reference and its former tests are preserved verbatim as
[`RtHueTransport.java.txt`](hue-transport-2026-07-17/RtHueTransport.java.txt) and
[`RtHueTransportTest.java.txt`](hue-transport-2026-07-17/RtHueTransportTest.java.txt).

## Why it was removed

The experiment replaced the ordinary channel-wise EON and GGX multi-scatter compensation terms with a rank-1 RGB operator. Its derivation peak-normalized material reflectance and then forced the dominant eigenvalue to `0.95`. That discarded absolute reflectance magnitude: for example, a neutral reflectance of `0.37` received the same dominant contraction as a near-white material. The resulting feedback could greatly overstate multi-scattered radiance and alter texture-authored material appearance.

The implementation also exposed an inadequately defined `Spectral Saturation Rate` setting through both RT settings menus and packed it into `environmentSky.w`. The control changed the BRDF model without a corresponding reconstruction or SHARC history reset.

## Former shader operator

```slang
struct HueTransportOp {
    float3 v;
    float a;
    float b;
};

HueTransportOp deriveHueTransport(float3 reflectance, float saturationRate) {
    HueTransportOp op;
    float3 raw = saturate(reflectance);
    float peak = max(raw.x, max(raw.y, raw.z));
    if (peak <= 1.0e-5) {
        op.v = float3(0.0);
        op.a = 0.0;
        op.b = 0.0;
        return op;
    }
    float3 c = raw / peak;
    op.v = c * rsqrt(max(dot(c, c), 1.0e-12));
    op.a = 0.95;
    op.b = min(c.x, min(c.y, c.z)) * saturate(saturationRate) * op.a;
    return op;
}

float3 applyHueTransport(HueTransportOp op, float3 incident) {
    return op.b * incident + (op.a - op.b) * dot(op.v, incident) * op.v;
}

float3 applyHueTransportN(HueTransportOp op, float3 incident, float bounceCount) {
    float an = pow(op.a, bounceCount);
    float bn = pow(op.b, bounceCount);
    return bn * incident + (an - bn) * dot(op.v, incident) * op.v;
}

float3 applyHueTransportInfinite(HueTransportOp op, float3 incident) {
    float ia = 1.0 / max(1.0 - op.a, 1.0e-5);
    float ib = 1.0 / max(1.0 - op.b, 1.0e-5);
    return ib * incident + (ia - ib) * dot(op.v, incident) * op.v;
}

float3 applyHueTransportInfiniteFromSecond(
        HueTransportOp op, float3 incident, float exitProbability) {
    float e = saturate(exitProbability);
    float repeat = 1.0 - e;
    float an = e * op.a * op.a / max(1.0 - repeat * op.a, 1.0e-5);
    float bn = e * op.b * op.b / max(1.0 - repeat * op.b, 1.0e-5);
    return bn * incident + (an - bn) * dot(op.v, incident) * op.v;
}
```

## Former integration

The live experiment replaced these two scalar/channel-wise expressions:

```slang
float3 rhoMs = rho * rho * Eavg
        / max(float3(1.0e-7), 1.0 - rho * (1.0 - Eavg));

float3 Favg = f0 + (1.0 - f0) * 0.0476190476;
float3 Fms = Favg * Favg * Eavg
        / max(float3(1.0e-5), 1.0 - Favg * (1.0 - Eavg));
```

with `applyHueTransportInfiniteFromSecond(...)` calls in EON diffuse and GGX multiscatter. The removed Java class `RtHueTransport` mirrored the operator for unit tests. Those tests checked the internal closed forms but did not verify material-energy preservation.

## Removal boundary

The live renderer now uses the pre-experiment channel-wise EON/GGX expressions again. The operator, CPU mirror, setting, menu controls, localization, and push-constant payload use were removed. `environmentSky.w` remains reserved padding so the existing push-constant ABI does not move.
