// Closest-hit shader. Geometry is per-section: InstanceID() indexes a section table (BDA array reached
// from the push constant) holding this section's {prim, uv} buffer addresses. Per-primitive
// {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred to raygen.
//
// tableAddr/entityTableAddr are read from the inline push-constant lanes (pc), not through the
// WorldPush BDA struct: they're read on every single hit (the hottest chain in the frame), and going
// through worldPushAddr first would cost an extra global-memory load just to find the address of the
// load we actually want. The cold WorldPush struct is dereferenced only for the breaking overlay.
import world_common;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(2, 0)]] Sampler2D blockAlbedoAtlas;
// Independent bindless arrays: per-draw entity albedo slots and compact canonical material-page bundles.
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, float ior, float transmission, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) return EMISSION_SOURCE_OVERRIDE;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
}

// Strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. PT intentionally disables this
// baked AO term (0.0) to avoid double-darkening with path-traced sky-visibility occlusion.
static const float NORMAL_AO_STRENGTH = 0.0;

static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
static const float RAY_CONE_MAX_LOD = 12.0;

float2 texSizePx(Sampler2D t) {
    uint w, h;
    t.GetDimensions(w, h);
    return float2(w, h);
}

float rayConeHitWidth(uint rayCone) {
    float2 cone = unpackHalf2(rayCone);
    return max(cone.x + cone.y * max(RayTCurrent(), 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(float3 p0, float3 p1, float2 uv0, float2 uv1, float2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(uint rayCone, float2 textureSizePx, float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth(rayCone) * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint) + pc.textureMipBias, 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(uint rayCone, float2 textureSizePx) {
    float footprint = max(rayConeHitWidth(rayCone) * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint) + pc.textureMipBias, 0.0, RAY_CONE_MAX_LOD);
}

float2 clampTexelCenterToBounds(float2 uv, float2 levelSize, float4 uvBounds) {
    float2 halfTexel = 0.5 / levelSize;
    float2 lo = uvBounds.xy + halfTexel;
    float2 hi = uvBounds.zw - halfTexel;
    float2 center = (uvBounds.xy + uvBounds.zw) * 0.5;
    lo = float2(lo.x <= hi.x ? lo.x : center.x, lo.y <= hi.y ? lo.y : center.y);
    hi = float2(lo.x <= hi.x ? hi.x : center.x, lo.y <= hi.y ? hi.y : center.y);
    return clamp(uv, lo, hi);
}

float3 sampleSrgbLinearLevel(Sampler2D textureSampler, float2 uv, float level, float4 uvBounds,
        bool clampToBounds) {
    uint levelWidth, levelHeight, levelCount;
    textureSampler.GetDimensions(uint(level), levelWidth, levelHeight, levelCount);
    float2 levelSize = float2(max(levelWidth, 1u), max(levelHeight, 1u));
    float2 texel = uv * levelSize - 0.5;
    float2 base = floor(texel);
    float2 phase = frac(texel);
    float2 uv00 = (base + float2(0.5, 0.5)) / levelSize;
    float2 uv10 = (base + float2(1.5, 0.5)) / levelSize;
    float2 uv01 = (base + float2(0.5, 1.5)) / levelSize;
    float2 uv11 = (base + float2(1.5, 1.5)) / levelSize;
    if (clampToBounds) {
        uv00 = clampTexelCenterToBounds(uv00, levelSize, uvBounds);
        uv10 = clampTexelCenterToBounds(uv10, levelSize, uvBounds);
        uv01 = clampTexelCenterToBounds(uv01, levelSize, uvBounds);
        uv11 = clampTexelCenterToBounds(uv11, levelSize, uvBounds);
    }
    float3 c00 = srgbToLinear(textureSampler.SampleLevel(uv00, level).rgb);
    float3 c10 = srgbToLinear(textureSampler.SampleLevel(uv10, level).rgb);
    float3 c01 = srgbToLinear(textureSampler.SampleLevel(uv01, level).rgb);
    float3 c11 = srgbToLinear(textureSampler.SampleLevel(uv11, level).rgb);
    return lerp(lerp(c00, c10, phase.x), lerp(c01, c11, phase.x), phase.y);
}

float3 sampleSrgbLinearTrilinear(Sampler2D textureSampler, float2 uv, float lod, float4 uvBounds,
        bool clampToBounds, bool highQuality) {
    float2 authoredSize = texSizePx(textureSampler) * (uvBounds.zw - uvBounds.xy);
    bool authoredPixelArt = pc.pointSampleMaxSize > 0u
            && max(authoredSize.x, authoredSize.y) <= float(pc.pointSampleMaxSize);
    if (!highQuality || authoredPixelArt) {
        return srgbToLinear(textureSampler.SampleLevel(uv, lod).rgb);
    }
    uint width, height, levelCount;
    textureSampler.GetDimensions(0u, width, height, levelCount);
    float maxLevel = float(max(levelCount, 1u) - 1u);
    float availableLod = min(lod, maxLevel);
    float level0 = floor(availableLod);
    float level1 = min(level0 + 1.0, maxLevel);
    float phase = frac(availableLod);
    return lerp(sampleSrgbLinearLevel(textureSampler, uv, level0, uvBounds, clampToBounds),
            sampleSrgbLinearLevel(textureSampler, uv, level1, uvBounds, clampToBounds), phase);
}

float4 materialAlbedoBounds(MaterialHeader header) {
    float2 extent = 1.0 / max(abs(header.albedoUv.zw), float2(1.0, 1.0));
    return float4(header.albedoUv.xy, header.albedoUv.xy + extent);
}

// LabPBR _n decode (shared): rotate the tangent-space normal into world space via a TBN built from the
// hit triangle's vertex positions (VK_KHR_ray_tracing_position_fetch) + UVs. `n` must already be
// oriented toward the viewer (`vdir`). Clamps the result above the horizon so grazing perturbations
// don't invert it through the surface (the black-spot fix). Returns AO (blue) via `ao`; falls back to
// `n` on a degenerate UV triangle. Instance transforms are translation-only, so object edges equal
// world edges.
float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float4 ntex, out float ao) {
    ao = 1.0;
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));       // Gram-Schmidt against the viewer-oriented normal
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);   // handedness from the UV winding
    float2 nxy = ntex.xy * 2.0 - 1.0;                    // R,G = tangent-space X,Y
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));      // reconstruct Z
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));        // keep above the horizon (no flip)
    }
    ao = lerp(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

struct Surface {
    float3 normal;
    float3 f0;
    float roughness;
    float metalness;
    float emission;
    float sss;
    float ao;
};

// Semantic vanilla conductors reuse the optical constants already defined by LabPBR. These are
// normal-incidence RGB reflectances computed from measured complex IOR (eta, k), not texture tints.
float3 semanticVanillaF0(uint family, float metalness, float3 albedo) {
    float3 conductor = albedo; // unknown/modded and fictional netherite retain authored color
    if (family == MATERIAL_FAMILY_IRON || family == MATERIAL_FAMILY_RAW_IRON) {
        conductor = float3(0.5312288, 0.5123572, 0.4958285);
    } else if (family == MATERIAL_FAMILY_GOLD || family == MATERIAL_FAMILY_RAW_GOLD) {
        conductor = float3(0.9442300, 0.7761021, 0.3734020);
    } else if (family == MATERIAL_FAMILY_COPPER || family == MATERIAL_FAMILY_EXPOSED_COPPER
            || family == MATERIAL_FAMILY_WEATHERED_COPPER || family == MATERIAL_FAMILY_OXIDIZED_COPPER
            || family == MATERIAL_FAMILY_RAW_COPPER) {
        conductor = float3(0.9259522, 0.7209016, 0.5041542);
    }
    return lerp(float3(0.04), conductor, clamp(metalness, 0.0, 1.0));
}

// Canonical material pages use a compact page index shared by their three dedicated arrays.
float4 samplePageSurface0(uint page, float2 uv, float lod) {
    return materialSurface0Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    return materialSurface1Tex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}
float4 samplePageNormalAo(uint page, float2 uv, float lod) {
    return materialNormalAoTex[NonUniformResourceIndex(page)].SampleLevel(uv, lod);
}

float4 interpolateSurface0(float4 a, float4 b, float phase) {
    float4 result = lerp(a, b, phase);
    result.r = sqrt(max(lerp(a.r * a.r, b.r * b.r, phase), 0.0));
    return result;
}

float4 interpolateNormalAo(float4 a, float4 b, float phase) {
    float2 axy = a.xy * 2.0 - 1.0;
    float2 bxy = b.xy * 2.0 - 1.0;
    float3 an = normalize(float3(axy, sqrt(max(0.0, 1.0 - dot(axy, axy)))));
    float3 bn = normalize(float3(bxy, sqrt(max(0.0, 1.0 - dot(bxy, bxy)))));
    float3 n = normalize(lerp(an, bn, phase));
    return float4(n.xy * 0.5 + 0.5, lerp(a.zw, b.zw, phase));
}

// The sole terrain/entity material evaluation path. LabPBR categorical bytes have already been
// decoded on the CPU; every sample below is a continuously filterable physical channel with semantic mips.
Surface evaluateMaterial(MaterialHeader header, float2 atlasUv, float lod, float3 albedo,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        float fallbackRoughness, float fallbackMetalness, float fallbackEmission, bool allowSss,
        bool highQuality, bool authoredPixelArt) {
    Surface surface;
    uint family = (header.features >> MATERIAL_FAMILY_SHIFT) & MATERIAL_FAMILY_MASK;
    surface.normal = geometricNormal;
    surface.roughness = fallbackRoughness;
    surface.metalness = fallbackMetalness;
    surface.f0 = semanticVanillaF0(family, fallbackMetalness, albedo);
    surface.emission = fallbackEmission;
    surface.sss = 0.0;
    surface.ao = 1.0;

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float materialLod = min(lod, maxMaterialLod);
    float materialLevel0 = floor(materialLod);
    float materialLevel1 = min(materialLevel0 + 1.0, maxMaterialLod);
    float materialPhase = frac(materialLod);
    uint page = header.texturePage;
    if (allowSss && (header.features & MATERIAL_FEATURE_SPEC) == 0u
            && family == MATERIAL_FAMILY_FOLIAGE) {
        surface.sss = header.params.w;
    }
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION
            | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u) {
        float4 surface0 = highQuality && !authoredPixelArt
                ? interpolateSurface0(samplePageSurface0(page, pageUv, materialLevel0),
                        samplePageSurface0(page, pageUv, materialLevel1), materialPhase)
                : samplePageSurface0(page, pageUv, materialLod);
        // surface1 feeds both the LabPBR F0 (rgb) and the override emission mask (a) — fetch it once.
        float4 surface1 = (header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_OVERRIDE_EMISSION)) != 0u
                ? highQuality && !authoredPixelArt
                        ? lerp(samplePageSurface1(page, pageUv, materialLevel0),
                                samplePageSurface1(page, pageUv, materialLevel1), materialPhase)
                        : samplePageSurface1(page, pageUv, materialLod)
                : float4(0.0, 0.0, 0.0, 0.0);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            surface.emission = surface0.b;
            surface.sss = allowSss ? surface0.a : 0.0;
            surface.f0 = surface1.rgb;
        } else {
            // Eligibility is compiled from block-state semantics; the primitive's exact light level
            // supplies strength, while the page supplies only the normalized spatial mask.
            surface.emission = surface0.b * fallbackEmission;
        }
        if ((header.features & MATERIAL_FEATURE_OVERRIDE_EMISSION) != 0u) {
            float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
                    & MATERIAL_EMISSION_STRENGTH_MASK)
                    * (MATERIAL_MAX_OVERRIDE_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
            surface.emission = surface1.a * strength;
        }
    }
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = highQuality && !authoredPixelArt
                ? interpolateNormalAo(samplePageNormalAo(page, pageUv, materialLevel0),
                        samplePageNormalAo(page, pageUv, materialLevel1), materialPhase)
                : samplePageNormalAo(page, pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalAo, surface.ao);
    }
    return surface;
}

// Block-breaking overlay: shared by opaque terrain AND stained-glass/ice. Reconstructs the hit position
// in the same rebased space the breaking list was pushed in, nudges it into the solid block along
// -normal to get the block's integer coordinate, then decal-projects the local [0,1] position onto
// whichever axis pair the normal is most aligned with (same idea as vanilla's
// SheetedDecalTextureGenerator) and multiply-blends the matching destroy-stage crack texture in,
// mirroring vanilla's crumbling blend (DST_COLOR*SRC_COLOR, doubled).
float3 applyBreaking(float3 albedo709, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT,
        float3 n, bool highQuality) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo709;
    }
    float3 hitPos = rayOrigin + rayDir * hitT;
    int3 blockPos = int3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < breakCount; ++bi) {
        int4 ps = pcRef[0].breaking[bi].posSlot;
        if (all(ps.xyz == blockPos)) {
            float3 local = hitPos - float3(blockPos);
            float3 an = abs(n);
            float2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                           : (an.y >= an.z) ? local.xz
                           : local.xy;
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(ps.w)]));
            float3 crack709 = sampleSrgbLinearTrilinear(
                    entityAlbedoTex[NonUniformResourceIndex(ps.w)], decalUv, decalLod,
                    float4(0.0, 0.0, 1.0, 1.0), false, highQuality);
            return clamp(crack709 * albedo709, 0.0, 1.0);
        }
    }
    return albedo709;
}

[shader("closesthit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;
    // DLSS reconstructs every noisy-color contribution to the output extent, not only the first-hit
    // guides. Use one filtering contract for primary, optical-guide, and secondary radiance samples.
    bool highQuality = pc.textureMipBias < 0.0 && (payload.flags & PAYLOAD_PRIMARY_RAY) != 0u;

    // Particle billboard: same geom-table/UV/bindless-atlas path as entities. Albedo is atlas texel *
    // per-particle colour; raygen treats material 2 as a primary-visible diffuse receiver with no
    // emission or specular response. Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((InstanceID() & PARTICLE_BIT) != 0u) {
        uint idx = InstanceID() & IDX_MASK;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[idx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * pid + 0u];
        uint p1 = ib[3u * pid + 1u];
        uint p2 = ib[3u * pid + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        payload.albedo = bt709ToBt2020(sampleSrgbLinearTrilinear(
                entityAlbedoTex[NonUniformResourceIndex(pslot)], puv, particleLod,
                float4(0.0, 0.0, 1.0, 1.0), false, highQuality) * srgbToLinear(pr.tint.rgb));
        payload.normal = pn;
        payload.geometricNormal = pn;
        payload.hitT = RayTCurrent();
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across
        // the billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back
        // to rigidDisp, which particles write as zero.
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = float3(0.0, 0.0, 0.0);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0,
                1.0, 0.0, EMISSION_SOURCE_NONE);
        return;
    }
    // Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
    // geometry; the low bits index the entity geometry table, not the section table.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[eidx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        float3 vdir = -WorldRayDirection();
        float3 n = normalize(mul(entityO2w, pr.normal.xyz));
        bool entering = dot(WorldRayDirection(), n) < 0.0;
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        float3 geometricNormal = n;
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/…).
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * pid + 0u];
        uint e1 = eidxBuf[3u * pid + 1u];
        uint e2 = eidxBuf[3u * pid + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        float2 entityAuthoredSize = texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]);
        bool entityPixelArt = pc.pointSampleMaxSize > 0u
                && max(entityAuthoredSize.x, entityAuthoredSize.y) <= float(pc.pointSampleMaxSize);
        float entityAlpha = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, 0.0).a;
        uint material = header.model;
        float3 entityAlbedo709 = sampleSrgbLinearTrilinear(
                entityAlbedoTex[NonUniformResourceIndex(texSlot)], euvCoord, entityLod,
                materialAlbedoBounds(header), false, highQuality) * srgbToLinear(pr.tint.rgb);
        float3 opticalColor709 = material == MATERIAL_GLASS
                ? lerp(float3(1.0, 1.0, 1.0), entityAlbedo709, entityAlpha) : entityAlbedo709;
        float3 albedo = bt709ToBt2020(opticalColor709);
        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, n,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, pr.normal.w, material == MATERIAL_OPAQUE,
                highQuality, entityPixelArt);
        n = surface.normal;

        payload.albedo = albedo * surface.ao;
        payload.normal = n;
        payload.geometricNormal = geometricNormal;
        payload.hitT = RayTCurrent();
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the same
        // indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz;
        } else {
            payload.motionPrev = g.rigidDisp.xyz;
        }
        payload.f0 = surface.f0;
        float emission = material == MATERIAL_OPAQUE ? surface.emission : 0.0;
        float sss = material == MATERIAL_OPAQUE ? surface.sss : 0.0;
        payloadSetPacked(payload, material, surface.roughness, surface.metalness,
                emission, sss, header.params.z, header.params.w,
                materialEmissionSource(header, emission));
        uint family = (header.features >> MATERIAL_FAMILY_SHIFT) & MATERIAL_FAMILY_MASK;
        if (material == MATERIAL_OPAQUE && family == MATERIAL_FAMILY_WOOL && header.params.w > 0.0) {
            payload.flags |= PAYLOAD_FIBER_SURFACE;
        }
        if ((pr.flags & PRIM_ENCHANTED) != 0u) {
            payload.flags |= PAYLOAD_ENCHANTED;
        }
        if (material == MATERIAL_GLASS) {
            uint opticalClass = (pr.flags & PRIM_FIRST_PERSON_THIN_GLASS) != 0u ? 2u : 3u;
            payload.flags |= (opticalClass << PAYLOAD_OPTICAL_CLASS_SHIFT);
            if (entering) {
                payload.flags |= PAYLOAD_INTERFACE_ENTERING;
            }
        }
        if (material == MATERIAL_WATER && entering) {
            payload.flags |= PAYLOAD_WATER_ENTERING;
        }
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket (solid / cutout /
    // translucent / water). PrimitiveIndex() restarts at 0 per geometry, so re-add this geometry's
    // triangle base to land in the section's packed prim/uv arrays (concatenated in BUCKET order).
    // Water prims keep tint.w == 1, converted to the packed material enum below.
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
    float3 n = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    // Water prims are single-sided, wound outward from the fluid volume (RtFluidMesher), so the
    // UNFLIPPED geometric normal is a reliable "which side is water" signal: a ray travelling against
    // it is entering the volume, one travelling with it is exiting. Captured before the toward-viewer
    // flip below and carried in the payload so raygen can set inWater from hit orientation instead of
    // toggling parity per crossing (a single missing/extra face no longer desyncs the rest of the path).
    bool entering = dot(WorldRayDirection(), n) < 0.0;

    // Lever B: per-triangle corner UVs in primitive order. uvs[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer still exists for the BLAS build; the shading path just no longer reads it.
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv0 = uvs[3u * pid + 0u];
    float2 uv1 = uvs[3u * pid + 1u];
    float2 uv2 = uvs[3u * pid + 2u];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAlbedoAtlas), tp0, tp1, tp2, uv0, uv1, uv2);
    float2 blockAuthoredSize = texSizePx(blockAlbedoAtlas)
            * (materialAlbedoBounds(materialHeader).zw - materialAlbedoBounds(materialHeader).xy);
    bool blockPixelArt = pc.pointSampleMaxSize > 0u
            && max(blockAuthoredSize.x, blockAuthoredSize.y) <= float(pc.pointSampleMaxSize);

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    float3 vdir = -WorldRayDirection(); // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }
    float3 geometricNormal = n;

    // Stained glass / ice: a thin colored filter resolved in raygen
    // (material 3). albedo carries the transmission tint = texel rgb mixed toward white by (1 − opacity),
    // so a more opaque texel tints transmitted light more strongly.
    if (materialHeader.model == MATERIAL_GLASS) {
        float alpha = blockAlbedoAtlas.SampleLevel(uv, 0.0).a;
        float3 texAlbedo = sampleSrgbLinearTrilinear(blockAlbedoAtlas, uv, blockLod,
                materialAlbedoBounds(materialHeader), true, highQuality);
        float3 tintLinear = srgbToLinear(tint);
        float3 opticalColor709 = lerp(float3(1.0, 1.0, 1.0), texAlbedo * tintLinear, alpha);
        float3 glassAlbedo = bt709ToBt2020(opticalColor709);
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, n,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false,
                highQuality, blockPixelArt);
        n = glassSurface.normal;
        // Translucent blocks (glass, ice, …) are breakable too; alpha remains the exact authored
        // coverage sample while RGB follows the display-resolution reconstruction footprint.
        payload.albedo = bt709ToBt2020(applyBreaking(opticalColor709 * glassSurface.ao,
                rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n, highQuality));
        payload.normal = n;
        payload.geometricNormal = geometricNormal;
        payload.hitT = RayTCurrent();
        payload.motionPrev = float3(0.0, 0.0, 0.0);
        payload.f0 = glassSurface.f0;
        payloadSetPacked(payload, MATERIAL_GLASS, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, materialHeader.params.z,
                materialHeader.params.w, EMISSION_SOURCE_NONE);
        payload.flags |= ((pr.aux0 & 0xfu) << PAYLOAD_OPTICAL_CLASS_SHIFT);
        if ((pr.aux0 & 0x10u) != 0u) {
            payload.flags |= PAYLOAD_INTERFACE_EXTERIOR_WATER;
            payload.iorTransmission = pr.aux1;
        }
        if (entering) {
            payload.flags |= PAYLOAD_INTERFACE_ENTERING;
        }
        return;
    }

    // Water (tint.w == 1) carries the pure biome water tint (no grey water-texture multiply): raygen
    // shades the surface as a clear dielectric and only needs the tint to derive the per-channel
    // Beer–Lambert absorption. Opaque terrain uses textured albedo.
    float3 texAlbedo = sampleSrgbLinearTrilinear(blockAlbedoAtlas, uv, blockLod,
            materialAlbedoBounds(materialHeader), true, highQuality);
    float3 tintLinear = srgbToLinear(tint);
    float3 albedo709 = texAlbedo * tintLinear;
    if (materialHeader.model == MATERIAL_WATER) {
        payload.albedo = bt709ToBt2020(tintLinear);
    } else {
        payload.albedo = bt709ToBt2020(albedo709);
    }
    payload.hitT = RayTCurrent();
    payload.motionPrev = float3(0.0, 0.0, 0.0); // static terrain: camera-only motion vector
    uint material = materialHeader.model == MATERIAL_WATER ? MATERIAL_WATER : MATERIAL_OPAQUE;

    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout /
    // translucent) render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, payload.albedo, n,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid,
            highQuality, blockPixelArt);
    n = surface.normal;
    payload.albedo *= surface.ao;
    // Block-breaking overlay: opaque/cutout terrain (water skips — fluids aren't breakable). Evaluate
    // it after normal mapping so decal projection uses the same shading orientation as the old path.
    if (material != MATERIAL_WATER) {
        payload.albedo = bt709ToBt2020(applyBreaking(albedo709 * surface.ao,
                rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n, highQuality));
    }
    payload.normal = n;
    payload.geometricNormal = geometricNormal;
    payload.f0 = surface.f0;
    payloadSetPacked(payload, material, surface.roughness, surface.metalness,
            surface.emission, surface.sss, materialHeader.params.z, materialHeader.params.w,
            materialEmissionSource(materialHeader, surface.emission));
    uint family = (materialHeader.features >> MATERIAL_FAMILY_SHIFT) & MATERIAL_FAMILY_MASK;
    if (material == MATERIAL_OPAQUE && family == MATERIAL_FAMILY_WOOL && materialHeader.params.w > 0.0) {
        payload.flags |= PAYLOAD_FIBER_SURFACE;
    }
    if ((pr.flags & TERRAIN_PRIM_TORCH) != 0u) {
        payload.flags |= PAYLOAD_TORCH_EMITTER;
    }
    if ((pr.flags & TERRAIN_PRIM_LAVA) != 0u) {
        payload.flags |= PAYLOAD_LAVA_SURFACE;
    }
    if (material == MATERIAL_WATER && entering) {
        payload.flags |= PAYLOAD_WATER_ENTERING;
    }
    // RIS emitter-NEE membership: raygen gates this emitter's direct-hit emission term (RIS covers it).
    if ((pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
        payload.flags |= PAYLOAD_EMITTER_IN_LIST;
    }
}
