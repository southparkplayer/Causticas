package dev.upscaler.rt;

import com.mojang.blaze3d.platform.NativeImage;
import dev.upscaler.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReSTIR DI (P3.3) — emissive light collection.
 *
 * <p>The path tracer has NEE only for the sun/moon delta light ({@code world.rgen}); block emitters
 * (lava, glowstone, torches, lanterns, …) contribute only when a path ray happens to land on them,
 * which is very noisy for small emitters. ReSTIR DI samples those emitters explicitly, but first they
 * must be enumerated into a samplable light list.
 *
 * <p>This collects, per terrain section, the emissive triangles as area lights in <b>section-local</b>
 * coordinates — the same space the section's BLAS vertices live in, so a light is rebased per frame by
 * the section's instance offset exactly like its geometry.
 *
 * <p><b>Emission source matches the closest-hit.</b> {@code world.rchit} emits {@code albedo · emission},
 * where {@code emission} is the LabPBR {@code _s} per-texel alpha when an {@code _s} map exists (which
 * <i>replaces</i> the block light level) else the per-block light level. So the light's radiance is the
 * footprint mean of {@code albedo · emission} (texture-derived, the correct area-light power), branching
 * on {@code _s} exactly as the chit does — no NEE/direct-view colour or brightness seam.
 *
 * <p><b>Membership flag.</b> Not every emissive triangle should be an area light: one with only a few
 * faint emissive texels integrates to a near-zero, badly-placed light. Such triangles are dropped from
 * the buffer and their prim is flagged "excluded" (the SIGN of {@code prim.normal.w}) so the path tracer
 * <i>always gathers</i> them on hits — bit-identical to the no-NEE path — instead of gating them off
 * (which would silently lose their light). In-buffer emitters keep the positive sign and are gated.
 */
final class RtLights {
    private RtLights() {
    }

    /** Log per-build emissive-triangle / section totals. {@code -Dupscaler.rt.lightStats}. */
    static final boolean STATS = Boolean.getBoolean("upscaler.rt.lightStats");

    /**
     * HDR radiance scale of a full (emission=1) emitter. MUST equal {@code world.rgen}'s
     * {@code EMISSIVE_STRENGTH} so the NEE/ReSTIR estimate and the direct-hit emission agree (no seam).
     */
    static final float EMISSIVE_STRENGTH = 6.0f;

    /** Block-light levels below this are non-emissive (smallest real level is 1/15 ≈ 0.067). */
    private static final float EMISSION_EPS = 0.5f / 255f;

    /** Degenerate (zero-area) triangles carry no power and would divide-by-zero the normal — skip them. */
    private static final float AREA_EPS = 1.0e-9f;

    /**
     * An emitter whose mean emitted radiance (luminance) is below this is too weak to be a useful area
     * light (e.g. a single stray emissive texel): drop it from the buffer and flag it "excluded" so the
     * path tracer gathers it on hits instead. Block lights always clear this; it only excludes faint
     * {@code _s} emitters.
     */
    private static final float LE_LUM_EPS = 0.02f;

    /** ARGB alpha at/above which an albedo texel counts as opaque (cutout) for the mean-albedo colour. */
    private static final int ALBEDO_OPAQUE_CUTOFF = 128;

    /** Packed light record (section-local): {@code pos.xyz, area, normal.xyz, _pad, Le.rgb, _pad}. */
    static final int FLOATS_PER_LIGHT = 12;

    /** One section's emissive-triangle area lights, packed section-local (rebased at per-frame assembly). */
    static final class SectionLights {
        final FloatArrayList data = new FloatArrayList();

        int count() {
            return data.size() / FLOATS_PER_LIGHT;
        }
    }

    /**
     * Append every emissive triangle of one geometry bucket as an area light, and flag each emissive
     * prim's NEE membership (the sign of {@code prim.normal.w}). {@code verts} is the bucket's packed
     * section-local positions (3 floats/vertex); {@code idx} indexes it (bucket-local, 3/triangle);
     * {@code prim} is the per-triangle record (12 floats/triangle) — mutated in place to carry the sign;
     * {@code triSprites} is the per-triangle sprite (aligned with {@code prim}; null for fluids/untextured).
     */
    static void appendBucket(SectionLights out, float[] verts, int[] idx, int idxCount, float[] prim,
                             List<TextureAtlasSprite> triSprites) {
        int tris = idxCount / 3;
        for (int t = 0; t < tris; t++) {
            int pb = t * 12;
            float bl = prim[pb + 3];                 // block light level 0..1 (still positive here)
            boolean hasS = prim[pb + 10] > 0.5f;     // resolveMaterials set this from the _s atlas ingest
            TextureAtlasSprite sprite = t < triSprites.size() ? triSprites.get(t) : null;
            float tintR = prim[pb + 4], tintG = prim[pb + 5], tintB = prim[pb + 6];

            // Emitted radiance Le, matching the chit's emission source (｜_s replaces block light when present).
            float leR, leG, leB;
            if (sprite != null && hasS) {
                SpriteEmission st = spriteStats(sprite);
                if (!st.sEmissive()) {
                    continue;                        // _s authored no emission → chit emits ~0 → not a light
                }
                leR = st.sLeR() * EMISSIVE_STRENGTH * tintR;
                leG = st.sLeG() * EMISSIVE_STRENGTH * tintG;
                leB = st.sLeB() * EMISSIVE_STRENGTH * tintB;
            } else if (bl > EMISSION_EPS) {
                float ar = 1f, ag = 1f, ab = 1f;     // colour = mean opaque albedo (white when no sprite, e.g. lava)
                if (sprite != null) {
                    SpriteEmission st = spriteStats(sprite);
                    ar = st.albR(); ag = st.albG(); ab = st.albB();
                }
                leR = bl * ar * EMISSIVE_STRENGTH * tintR;
                leG = bl * ag * EMISSIVE_STRENGTH * tintG;
                leB = bl * ab * EMISSIVE_STRENGTH * tintB;
            } else {
                continue;                            // not emissive (and no _s emission)
            }

            float lum = 0.2126f * leR + 0.7152f * leG + 0.0722f * leB;
            if (lum <= LE_LUM_EPS) {
                // Emissive but too weak to be an area light: flag the prim "excluded" (negative sign) so the
                // path tracer always-gathers it on hits instead of gating it off. Magnitude is irrelevant for
                // _s emitters (the chit re-decodes it) but kept for block-light emitters that abs() it back.
                prim[pb + 3] = -Math.max(Math.abs(bl), 1.0e-4f);
                continue;
            }

            // In the light buffer: prim.normal.w keeps its positive sign (bl >= 0) ⇒ the chit reports
            // emitterInList = true and the raygen gates this emitter's direct-hit term on diffuse bounces.
            int i0 = idx[t * 3] * 3, i1 = idx[t * 3 + 1] * 3, i2 = idx[t * 3 + 2] * 3;
            float ax = verts[i0], ay = verts[i0 + 1], az = verts[i0 + 2];
            float bx = verts[i1], by = verts[i1 + 1], bz = verts[i1 + 2];
            float cx = verts[i2], cy = verts[i2 + 1], cz = verts[i2 + 2];
            float ex1 = bx - ax, ey1 = by - ay, ez1 = bz - az;
            float ex2 = cx - ax, ey2 = cy - ay, ez2 = cz - az;
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float crossLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float area = 0.5f * crossLen;
            if (area <= AREA_EPS) {
                continue;                            // degenerate triangle (no power, no usable normal)
            }
            float inv = 1.0f / crossLen;
            float px = (ax + bx + cx) * (1.0f / 3.0f);
            float py = (ay + by + cy) * (1.0f / 3.0f);
            float pz = (az + bz + cz) * (1.0f / 3.0f);

            out.data.add(px);
            out.data.add(py);
            out.data.add(pz);
            out.data.add(area);
            out.data.add(nx * inv);
            out.data.add(ny * inv);
            out.data.add(nz * inv);
            out.data.add(0.0f);
            out.data.add(leR);
            out.data.add(leG);
            out.data.add(leB);
            out.data.add(0.0f);
        }
    }

    // --- Per-sprite emitted-light stats (texture-derived), computed once and cached ------------------

    private static final Map<TextureAtlasSprite, SpriteEmission> SPRITE_CACHE = new ConcurrentHashMap<>();

    /**
     * {@code alb*}: mean opaque-texel albedo — the light colour for a block-light emitter.
     * {@code sLe*}: footprint mean of {@code albedo · (_s emission)} — the per-{@link #EMISSIVE_STRENGTH}
     *               area-light radiance of an {@code _s} emitter (0 when no {@code _s}/no emissive texels).
     * {@code sEmissive}: the {@code _s} map authored a non-zero emission on this sprite.
     */
    private record SpriteEmission(float albR, float albG, float albB,
                                  float sLeR, float sLeG, float sLeB, boolean sEmissive) {
    }

    private static SpriteEmission spriteStats(TextureAtlasSprite sprite) {
        SpriteEmission cached = SPRITE_CACHE.get(sprite);
        if (cached != null) {
            return cached;
        }
        SpriteEmission stats = computeStats(sprite);
        SPRITE_CACHE.put(sprite, stats);
        return stats;
    }

    private static final SpriteEmission WHITE = new SpriteEmission(1f, 1f, 1f, 0f, 0f, 0f, false);

    private static SpriteEmission computeStats(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        int w = contents.width(), h = contents.height();
        if (w <= 0 || h <= 0) {
            return WHITE;
        }
        NativeImage albedo;
        try {
            albedo = ((SpriteContentsAccessor) contents).upscaler$originalImage();
        } catch (Throwable t) {
            return WHITE;
        }
        if (albedo == null) {
            return WHITE;
        }
        // The _s map (optional) is nearest-sampled to the sprite grid, mirroring RtParallelAtlas.blit so the
        // emissive footprint lines up with the albedo. Frame 0 only (top w×h block) for animated sprites.
        NativeImage sMap = readMap(contents.name());
        int sw = 0, sh = 0;
        if (sMap != null) {
            sw = sMap.getWidth();
            sh = Math.min(sMap.getHeight(), sMap.getWidth());
        }
        int iw = albedo.getWidth(), ih = albedo.getHeight();
        double aR = 0, aG = 0, aB = 0;
        int opaque = 0;
        double leR = 0, leG = 0, leB = 0;
        int total = 0;
        boolean sEmissive = false;
        for (int y = 0; y < h; y++) {
            int ay = Math.min(ih - 1, y);
            for (int x = 0; x < w; x++) {
                int ax = Math.min(iw - 1, x);
                int ap = albedo.getPixel(ax, ay);
                float ar = ARGB.red(ap) / 255f, ag = ARGB.green(ap) / 255f, ab = ARGB.blue(ap) / 255f;
                if (ARGB.alpha(ap) >= ALBEDO_OPAQUE_CUTOFF) {
                    aR += ar; aG += ag; aB += ab; opaque++;
                }
                if (sMap != null) {
                    int sx = Math.min(sw - 1, x * sw / w);
                    int sy = Math.min(sh - 1, y * sh / h);
                    int sa = ARGB.alpha(sMap.getPixel(sx, sy));
                    float eTex = sa < 254.5f ? sa / 254f : 0f; // LabPBR: alpha 255 = no emission sentinel
                    if (eTex > 0f) {
                        sEmissive = true;
                        leR += ar * eTex; leG += ag * eTex; leB += ab * eTex;
                    }
                }
                total++;
            }
        }
        if (sMap != null) {
            sMap.close();
        }
        float mAr = opaque > 0 ? (float) (aR / opaque) : 1f;
        float mAg = opaque > 0 ? (float) (aG / opaque) : 1f;
        float mAb = opaque > 0 ? (float) (aB / opaque) : 1f;
        float invTotal = total > 0 ? 1f / total : 0f;
        return new SpriteEmission(mAr, mAg, mAb, (float) leR * invTotal, (float) leG * invTotal,
                (float) leB * invTotal, sEmissive);
    }

    /** Read a sprite's {@code _s.png} as a NativeImage (caller closes), or null if absent. */
    private static NativeImage readMap(Identifier name) {
        try {
            Identifier loc = Identifier.fromNamespaceAndPath(name.getNamespace(),
                    "textures/" + name.getPath() + "_s.png");
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                return null;
            }
            try (InputStream in = res.get().open()) {
                return NativeImage.read(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
