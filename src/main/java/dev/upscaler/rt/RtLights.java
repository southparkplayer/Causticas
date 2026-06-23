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
 * (lava, glowstone, torches, lanterns, …) contribute only when a path ray lands on them, which is very
 * noisy for small emitters. ReSTIR DI samples those emitters explicitly; this enumerates them into a
 * samplable light list, per terrain section, in <b>section-local</b> coordinates (rebased per frame like
 * the section geometry).
 *
 * <p><b>One light per quad.</b> Each block face / cross-plane is a quad (two triangles); we emit one area
 * light per emissive quad rather than per triangle — halving the light count (better sampling odds) and
 * giving one clean per-face light instead of two overlapping half-face lights.
 *
 * <p><b>Emission source matches the closest-hit.</b> {@code world.rchit} emits {@code albedo · emission},
 * with {@code emission} = the LabPBR {@code _s} per-texel alpha when an {@code _s} map exists (which
 * <i>replaces</i> the block light) else the per-block light level. The light radiance is derived the same
 * way, so RIS and direct view agree on colour + brightness.
 *
 * <p><b>Concentrated emitters (torch tip, lantern flame).</b> For an {@code _s} emitter the glow is often
 * a few texels of a mostly-dark sprite. Per quad we scan only that quad's UV footprint: the light's
 * <b>radiance</b> is the mean over the <i>emissive texels only</i> (bright, undiluted), its <b>area</b> is
 * shrunk by the emissive fraction (total power unchanged), and its <b>position</b> is the emissive-texel
 * centroid mapped back to 3D — so the light lives at the glowing tip, not the face centre. A quad whose
 * footprint has no emissive texels (the torch stick) is not a light. Block-light emitters glow uniformly
 * (per MC's block-light model), so they keep the quad centroid + full area.
 *
 * <p><b>Membership flag.</b> The SIGN of {@code prim.normal.w} marks whether the quad is in the light
 * buffer. In-buffer emitters keep the positive sign and are gated by the raygen on diffuse bounces;
 * emitters too weak (a stray emissive speckle / sparse crossed planes — see the emissive-pixel fraction)
 * get a negative sign so the path tracer <i>always gathers</i> them on hits — bit-identical to the no-NEE
 * path, with no energy lost.
 */
final class RtLights {
    private RtLights() {
    }

    /** Log per-build emissive / section totals. {@code -Dupscaler.rt.lightStats}. */
    static final boolean STATS = Boolean.getBoolean("upscaler.rt.lightStats");

    /**
     * HDR radiance scale of a full (emission=1) emitter. MUST equal {@code world.rgen}'s
     * {@code EMISSIVE_STRENGTH} so the NEE/ReSTIR estimate and the direct-hit emission agree (no seam).
     */
    static final float EMISSIVE_STRENGTH = 6.0f;

    /** Block-light levels below this are non-emissive (smallest real level is 1/15 ≈ 0.067). */
    private static final float EMISSION_EPS = 0.5f / 255f;

    /** Degenerate (zero-area) quads carry no power and would divide-by-zero the normal — skip them. */
    private static final float AREA_EPS = 1.0e-9f;

    /**
     * An emitter whose (emissive-only) radiance luminance is below this is too weak to bother sampling:
     * drop it from the buffer and flag it "excluded" so the path tracer gathers it on hits instead.
     */
    private static final float LE_LUM_EPS = 0.005f;

    /**
     * Per-quad emissive footprint sampling grid (resolution-independent: a high-res pack texture is still
     * sampled at this fixed grid, one tap per cell centre). 16 matches MC's base texel grid.
     */
    private static final int FOOTPRINT_GRID = 16;

    /**
     * An {@code _s} emitter is only worth treating as an area light when its emissive pixels are reasonably
     * <i>dense</i>: {@code emissive cells / (emissive bounding-rectangle cells)} on the {@link
     * #FOOTPRINT_GRID} grid. A compact glow (torch tip, carved jack-o'-lantern face) fills its rectangle
     * well; a stray speckle or sparse crossed-plane pattern fills it poorly and is excluded from the buffer
     * (gathered on hits instead), which also avoids mutual self-shadowing. {@code
     * -Dupscaler.rt.lightMinFillRatio} (0 disables).
     */
    private static final float MIN_FILL_RATIO =
            Float.parseFloat(System.getProperty("upscaler.rt.lightMinFillRatio", "0.25"));

    /** ARGB alpha at/above which an albedo texel counts as opaque (cutout) for the mean-albedo colour. */
    private static final int ALBEDO_OPAQUE_CUTOFF = 128;

    /** Packed light record (section-local): {@code pos.xyz, area, normal.xyz, _pad, Le.rgb, _pad}. */
    static final int FLOATS_PER_LIGHT = 12;

    /** One section's emissive-quad area lights, packed section-local (rebased at per-frame assembly). */
    static final class SectionLights {
        final FloatArrayList data = new FloatArrayList();

        int count() {
            return data.size() / FLOATS_PER_LIGHT;
        }
    }

    /**
     * Append one area light per emissive <b>quad</b> of a geometry bucket, and flag each emissive prim's
     * NEE membership (the sign of {@code prim.normal.w}). Quads are reconstructed from the buffers:
     * {@code emit()} always writes a quad as two consecutive triangles {@code (0,1,2)} then {@code (0,2,3)}
     * over four consecutive verts, with {@code prim}/{@code cornerUv} in lockstep — so quad {@code k} is
     * triangles {@code 2k} (corners 0,1,2) and {@code 2k+1} (whose 3rd index is corner 3).
     */
    static void appendBucket(SectionLights out, float[] verts, int[] idx, int idxCount, float[] prim,
                             float[] cornerUv, List<TextureAtlasSprite> triSprites) {
        int quads = idxCount / 6; // two triangles per quad
        for (int k = 0; k < quads; k++) {
            int triA = 2 * k, triB = 2 * k + 1;
            int pb = triA * 12;                      // both triangles of a quad share the prim record
            float bl = prim[pb + 3];                 // block light level 0..1 (still positive here)
            boolean hasS = prim[pb + 10] > 0.5f;     // resolveMaterials set this from the _s atlas ingest
            TextureAtlasSprite sprite = triA < triSprites.size() ? triSprites.get(triA) : null;
            SpriteEmission st = sprite != null ? spriteStats(sprite) : null;
            // Emission source must match the chit: when an _s map exists it REPLACES the block light, so a
            // face whose _s authored no emission is non-emissive (the chit emits 0 there) — it must NOT fall
            // back to the block-light path (that was the jack-o'-lantern bug: 5 non-emissive pumpkin faces lit
            // at level 15). Only _s-emissive faces are _s emitters; only non-_s faces use the block light.
            boolean sEmitter;
            if (hasS) {
                if (st == null || !st.sEmissive()) {
                    continue;                        // _s present but non-emissive on this face → not a light
                }
                sEmitter = true;
            } else {
                if (bl <= EMISSION_EPS) {
                    continue;                        // no _s, no block light → not emissive
                }
                sEmitter = false;
            }

            // Quad geometry: 4 corners (P0,P1,P2 from triA; P3 = triB's 3rd index). Indices are bucket-local.
            int v0 = idx[triA * 3] * 3, v1 = idx[triA * 3 + 1] * 3, v2 = idx[triA * 3 + 2] * 3, v3 = idx[triB * 3 + 2] * 3;
            float p0x = verts[v0], p0y = verts[v0 + 1], p0z = verts[v0 + 2];
            float p1x = verts[v1], p1y = verts[v1 + 1], p1z = verts[v1 + 2];
            float p2x = verts[v2], p2y = verts[v2 + 1], p2z = verts[v2 + 2];
            float p3x = verts[v3], p3y = verts[v3 + 1], p3z = verts[v3 + 2];
            float quadArea = triArea(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z)
                    + triArea(p0x, p0y, p0z, p2x, p2y, p2z, p3x, p3y, p3z);
            if (quadArea <= AREA_EPS) {
                continue;                            // degenerate quad (no power)
            }
            float ex1 = p1x - p0x, ey1 = p1y - p0y, ez1 = p1z - p0z;
            float ex2 = p2x - p0x, ey2 = p2y - p0y, ez2 = p2z - p0z;
            float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
            float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen <= 1.0e-12f) {
                continue;
            }
            float ninv = 1.0f / nlen;
            nx *= ninv; ny *= ninv; nz *= ninv;
            float tintR = prim[pb + 4], tintG = prim[pb + 5], tintB = prim[pb + 6];

            // Light radiance Le + position + area. Default: quad centroid, full area (block-light path).
            float leR, leG, leB;
            float px = (p0x + p1x + p2x + p3x) * 0.25f;
            float py = (p0y + p1y + p2y + p3y) * 0.25f;
            float pz = (p0z + p1z + p2z + p3z) * 0.25f;
            float area = quadArea;

            if (sEmitter) {
                // Per-quad _s footprint: emissive-only radiance + shrunk area + emissive-centroid position.
                Footprint fp = scratch.get();
                if (computeFootprint(st, sprite, cornerUv, triA, triB, fp)) {
                    if (fp.count == 0) {
                        continue;                    // this quad covers no emissive texels (e.g. torch stick)
                    }
                    // Density gate: drop sparse glows (speckle / crossed-plane patterns) whose emissive
                    // pixels fill their bounding rectangle poorly. Flag excluded ⇒ always-gather on hits.
                    if (MIN_FILL_RATIO > 0f && fp.fillRatio < MIN_FILL_RATIO) {
                        markExcluded(prim, pb, bl);
                        continue;
                    }
                    float invEm = 1.0f / fp.count;
                    leR = fp.sumR * invEm * EMISSIVE_STRENGTH * tintR;
                    leG = fp.sumG * invEm * EMISSIVE_STRENGTH * tintG;
                    leB = fp.sumB * invEm * EMISSIVE_STRENGTH * tintB;
                    float frac = fp.footprint > 0 ? (float) fp.count / fp.footprint : 1.0f;
                    area = frac * quadArea;
                    // Emissive centroid (local UV) → map to 3D via whichever quad half-triangle contains it.
                    float ecu = fp.cu * invEm, ecv = fp.cv * invEm;
                    boolean mapped = baryMap(ecu, ecv, fp.lu0, fp.lv0, fp.lu1, fp.lv1, fp.lu2, fp.lv2,
                            p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z);
                    if (!mapped) {
                        mapped = baryMap(ecu, ecv, fp.lu0, fp.lv0, fp.lu2, fp.lv2, fp.lu3, fp.lv3,
                                p0x, p0y, p0z, p2x, p2y, p2z, p3x, p3y, p3z);
                    }
                    if (mapped) {
                        px = mapX; py = mapY; pz = mapZ; // baryMap wrote the result into these statics
                    } // else keep the quad-centroid fallback
                } else {
                    // No footprint (degenerate sprite rect): fall back to the whole-sprite mean × full area.
                    leR = st.sMeanR() * EMISSIVE_STRENGTH * tintR;
                    leG = st.sMeanG() * EMISSIVE_STRENGTH * tintG;
                    leB = st.sMeanB() * EMISSIVE_STRENGTH * tintB;
                }
            } else {
                // Block-light emitter: uniform glow over the geometry (MC block-light model). Colour = mean
                // opaque albedo of the sprite (white when no sprite, e.g. lava).
                float ar = 1f, ag = 1f, ab = 1f;
                if (st != null) {
                    ar = st.albR(); ag = st.albG(); ab = st.albB();
                }
                leR = bl * ar * EMISSIVE_STRENGTH * tintR;
                leG = bl * ag * EMISSIVE_STRENGTH * tintG;
                leB = bl * ab * EMISSIVE_STRENGTH * tintB;
            }

            float lum = 0.2126f * leR + 0.7152f * leG + 0.0722f * leB;
            if (lum <= LE_LUM_EPS) {
                // Emissive but too weak to be worth sampling: flag the prim "excluded" (negative sign) so the
                // path tracer always-gathers it on hits instead of gating it off (which would lose its light).
                markExcluded(prim, pb, bl);
                continue;
            }

            out.data.add(px);
            out.data.add(py);
            out.data.add(pz);
            out.data.add(area);
            out.data.add(nx);
            out.data.add(ny);
            out.data.add(nz);
            out.data.add(0.0f);
            out.data.add(leR);
            out.data.add(leG);
            out.data.add(leB);
            out.data.add(0.0f);
        }
    }

    /** Flag both triangle prim records of a quad "excluded" (negative emission sign ⇒ always-gather). */
    private static void markExcluded(float[] prim, int pbA, float bl) {
        float neg = -Math.max(Math.abs(bl), 1.0e-4f);
        prim[pbA + 3] = neg;
        prim[pbA + 12 + 3] = neg;
    }

    private static float triArea(float ax, float ay, float az, float bx, float by, float bz,
                                 float cx, float cy, float cz) {
        float ex1 = bx - ax, ey1 = by - ay, ez1 = bz - az;
        float ex2 = cx - ax, ey2 = cy - ay, ez2 = cz - az;
        float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
        return 0.5f * (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    // baryMap result (statics — collection is single-threaded render work): the mapped 3D point.
    private static float mapX, mapY, mapZ;

    /**
     * If UV {@code (u,v)} lies inside the UV triangle {@code (au,av),(bu,bv),(cu,cv)} (small tolerance),
     * interpolate the 3D verts {@code Pa,Pb,Pc} by its barycentric coords into {@code mapX/Y/Z} and return
     * true; otherwise return false.
     */
    private static boolean baryMap(float u, float v, float au, float av, float bu, float bv, float cu, float cv,
                                   float pax, float pay, float paz, float pbx, float pby, float pbz,
                                   float pcx, float pcy, float pcz) {
        float d = (bv - cv) * (au - cu) + (cu - bu) * (av - cv);
        if (Math.abs(d) < 1.0e-9f) {
            return false;
        }
        float invd = 1.0f / d;
        float w0 = ((bv - cv) * (u - cu) + (cu - bu) * (v - cv)) * invd;
        float w1 = ((cv - av) * (u - cu) + (au - cu) * (v - cv)) * invd;
        float w2 = 1.0f - w0 - w1;
        float e = 1.0e-3f;
        if (w0 < -e || w1 < -e || w2 < -e) {
            return false;
        }
        mapX = w0 * pax + w1 * pbx + w2 * pcx;
        mapY = w0 * pay + w1 * pby + w2 * pcy;
        mapZ = w0 * paz + w1 * pbz + w2 * pcz;
        return true;
    }

    /** Reusable per-quad footprint accumulator (one per thread; collection is render-thread today). */
    private static final ThreadLocal<Footprint> scratch = ThreadLocal.withInitial(Footprint::new);

    private static final class Footprint {
        int count;       // emissive grid cells in this quad's footprint
        int footprint;   // total sampled cells (FOOTPRINT_GRID²) — for the area fraction
        float fillRatio; // emissive cells / (emissive bounding-rectangle cells) — the density membership test
        float sumR, sumG, sumB; // Σ albedo·emission over emissive cells
        float cu, cv;    // Σ emissive-cell centre (local sprite UV) — averaged by count
        float lu0, lv0, lu1, lv1, lu2, lv2, lu3, lv3; // quad corner UVs in local sprite space
    }

    /**
     * Sample quad (triangles {@code triA},{@code triB})'s UV footprint on a fixed {@link #FOOTPRINT_GRID}²
     * grid against the sprite's per-texel {@code _s} radiance, filling {@code fp} — including the emissive
     * pixels' bounding rectangle and its fill ratio (density). Resolution-independent (one tap per cell
     * centre). Returns false if the sprite rect is degenerate (caller falls back to the sprite mean).
     */
    private static boolean computeFootprint(SpriteEmission st, TextureAtlasSprite sprite, float[] cornerUv,
                                            int triA, int triB, Footprint fp) {
        float su0 = sprite.getU0(), sv0 = sprite.getV0(), su1 = sprite.getU1(), sv1 = sprite.getV1();
        float du = su1 - su0, dv = sv1 - sv0;
        if (Math.abs(du) < 1.0e-9f || Math.abs(dv) < 1.0e-9f) {
            return false;
        }
        int ca = triA * 6, cb = triB * 6;
        fp.lu0 = (cornerUv[ca] - su0) / du;          fp.lv0 = (cornerUv[ca + 1] - sv0) / dv;
        fp.lu1 = (cornerUv[ca + 2] - su0) / du;      fp.lv1 = (cornerUv[ca + 3] - sv0) / dv;
        fp.lu2 = (cornerUv[ca + 4] - su0) / du;      fp.lv2 = (cornerUv[ca + 5] - sv0) / dv;
        fp.lu3 = (cornerUv[cb + 4] - su0) / du;      fp.lv3 = (cornerUv[cb + 5] - sv0) / dv; // triB's 3rd corner

        int w = st.sW(), h = st.sH();
        float minU = Math.min(Math.min(fp.lu0, fp.lu1), Math.min(fp.lu2, fp.lu3));
        float maxU = Math.max(Math.max(fp.lu0, fp.lu1), Math.max(fp.lu2, fp.lu3));
        float minV = Math.min(Math.min(fp.lv0, fp.lv1), Math.min(fp.lv2, fp.lv3));
        float maxV = Math.max(Math.max(fp.lv0, fp.lv1), Math.max(fp.lv2, fp.lv3));
        float spanU = maxU - minU, spanV = maxV - minV;

        fp.count = 0;
        fp.footprint = FOOTPRINT_GRID * FOOTPRINT_GRID;
        fp.sumR = fp.sumG = fp.sumB = 0f;
        fp.cu = fp.cv = 0f;
        int minGX = FOOTPRINT_GRID, minGY = FOOTPRINT_GRID, maxGX = -1, maxGY = -1;
        float[] leR = st.sLeR(), leG = st.sLeG(), leB = st.sLeB();
        for (int gy = 0; gy < FOOTPRINT_GRID; gy++) {
            float v = minV + (gy + 0.5f) / FOOTPRINT_GRID * spanV;
            int sy = Math.max(0, Math.min(h - 1, (int) (v * h)));
            for (int gx = 0; gx < FOOTPRINT_GRID; gx++) {
                float u = minU + (gx + 0.5f) / FOOTPRINT_GRID * spanU;
                int sx = Math.max(0, Math.min(w - 1, (int) (u * w)));
                int idx = sy * w + sx;
                float r = leR[idx], g = leG[idx], b = leB[idx];
                if (r > 0f || g > 0f || b > 0f) {
                    fp.count++;
                    fp.sumR += r; fp.sumG += g; fp.sumB += b;
                    fp.cu += u; fp.cv += v;
                    if (gx < minGX) minGX = gx;
                    if (gx > maxGX) maxGX = gx;
                    if (gy < minGY) minGY = gy;
                    if (gy > maxGY) maxGY = gy;
                }
            }
        }
        if (fp.count > 0) {
            int rectCells = (maxGX - minGX + 1) * (maxGY - minGY + 1);
            fp.fillRatio = (float) fp.count / rectCells;
        } else {
            fp.fillRatio = 0f;
        }
        return true;
    }

    // --- Per-sprite emitted-light stats (texture-derived), computed once and cached ------------------

    private static final Map<TextureAtlasSprite, SpriteEmission> SPRITE_CACHE = new ConcurrentHashMap<>();

    /**
     * {@code alb*}: mean opaque-texel albedo (block-light emitter colour). {@code sEmissive}: the
     * {@code _s} map authored emission. {@code sLe*}: per-texel {@code albedo · _s-emission} over the
     * sprite grid ({@code sW × sH}; null when not emissive). {@code sMean*}: their footprint mean (the
     * degenerate-rect fallback). {@code sFraction}: emissive texels / opaque texels.
     */
    private record SpriteEmission(float albR, float albG, float albB, boolean sEmissive,
                                  int sW, int sH, float[] sLeR, float[] sLeG, float[] sLeB,
                                  float sMeanR, float sMeanG, float sMeanB, float sFraction) {
    }

    private static final SpriteEmission WHITE =
            new SpriteEmission(1f, 1f, 1f, false, 0, 0, null, null, null, 0f, 0f, 0f, 0f);

    private static SpriteEmission spriteStats(TextureAtlasSprite sprite) {
        SpriteEmission cached = SPRITE_CACHE.get(sprite);
        if (cached != null) {
            return cached;
        }
        SpriteEmission stats = computeStats(sprite);
        SPRITE_CACHE.put(sprite, stats);
        return stats;
    }

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
        int emissiveTexels = 0;
        boolean sEmissive = false;
        float[] sLeR = sMap != null ? new float[w * h] : null;
        float[] sLeG = sMap != null ? new float[w * h] : null;
        float[] sLeB = sMap != null ? new float[w * h] : null;
        double meanR = 0, meanG = 0, meanB = 0;
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
                    int idx = y * w + x;
                    sLeR[idx] = ar * eTex; sLeG[idx] = ag * eTex; sLeB[idx] = ab * eTex;
                    meanR += sLeR[idx]; meanG += sLeG[idx]; meanB += sLeB[idx];
                    if (eTex > 0f) {
                        sEmissive = true;
                        emissiveTexels++;
                    }
                }
            }
        }
        if (sMap != null) {
            sMap.close();
        }
        float mAr = opaque > 0 ? (float) (aR / opaque) : 1f;
        float mAg = opaque > 0 ? (float) (aG / opaque) : 1f;
        float mAb = opaque > 0 ? (float) (aB / opaque) : 1f;
        float invTotal = 1f / (w * h);
        if (!sEmissive) {
            return new SpriteEmission(mAr, mAg, mAb, false, 0, 0, null, null, null, 0f, 0f, 0f, 0f);
        }
        float fraction = opaque > 0 ? (float) emissiveTexels / opaque : 0f;
        return new SpriteEmission(mAr, mAg, mAb, true, w, h, sLeR, sLeG, sLeB,
                (float) meanR * invTotal, (float) meanG * invTotal, (float) meanB * invTotal, fraction);
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
