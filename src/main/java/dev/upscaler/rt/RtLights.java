package dev.upscaler.rt;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

/**
 * ReSTIR DI (P3.3) — Stage 0: emissive light collection.
 *
 * <p>The path tracer has NEE only for the sun/moon delta light ({@code world.rgen}); block emitters
 * (lava, glowstone, torches, lanterns, …) contribute only when a path ray happens to land on them,
 * which is very noisy for small emitters. ReSTIR DI samples those emitters explicitly, but first they
 * must be enumerated into a samplable light list — there is none today (emission lives per-triangle in
 * {@code Prim.normal.w} and is otherwise only read in the closest-hit).
 *
 * <p>This collects, per terrain section, the emissive triangles as area lights in <b>section-local</b>
 * coordinates — the same space the section's BLAS vertices live in, so a light is rebased per frame by
 * the section's instance offset ({@code sectionOrigin − rebaseOrigin}) exactly like its geometry, and a
 * rebase needs no light re-extraction (only the per-frame assembly re-applies the new offset).
 *
 * <p>Stage 0 uses the per-block light level ({@code Prim.normal.w}, set at tessellation) and the biome
 * tint as an albedo proxy for the emitted radiance. The LabPBR {@code _s} per-texel emission (which
 * <i>replaces</i> the block-light level in the closest-hit) and a texture-mean albedo plug in next, at
 * {@code _s} ingest — see the ReSTIR plan; until then a textured emitter's radiance/colour may differ
 * from what the closest-hit emits per-texel.
 */
final class RtLights {
    private RtLights() {
    }

    /** Log per-build emissive-triangle / section totals. {@code -Dupscaler.rt.lightStats}. */
    static final boolean STATS = Boolean.getBoolean("upscaler.rt.lightStats");

    /**
     * HDR radiance of a full (level-15) emitter, modulated by albedo. MUST equal {@code world.rgen}'s
     * {@code EMISSIVE_STRENGTH} so the NEE/ReSTIR estimate and the direct-hit emission agree (no seam).
     */
    static final float EMISSIVE_STRENGTH = 6.0f;

    /** Drop near-zero emitters so the list stays bounded (smallest real block level is 1/15 ≈ 0.067). */
    private static final float EMISSION_EPS = 0.5f / 255f;

    /** Degenerate (zero-area) triangles carry no power and would divide-by-zero the normal — skip them. */
    private static final float AREA_EPS = 1.0e-9f;

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
     * Append every emissive triangle of one geometry bucket as an area light. {@code verts} is the
     * bucket's packed section-local positions (3 floats/vertex); {@code idx} indexes it (bucket-local,
     * 3/triangle); {@code prim} is the per-triangle record (12 floats/triangle, {@code normal.xyz +
     * emission} first, then {@code tint.rgb + material}). {@code idxCount} bounds the live index range.
     */
    static void appendBucket(SectionLights out, float[] verts, int[] idx, int idxCount, float[] prim) {
        int tris = idxCount / 3;
        for (int t = 0; t < tris; t++) {
            int pb = t * 12;
            float e = prim[pb + 3]; // normal.w = block light level 0..1 (Stage 0 emission source)
            if (e <= EMISSION_EPS) {
                continue;
            }
            int i0 = idx[t * 3] * 3, i1 = idx[t * 3 + 1] * 3, i2 = idx[t * 3 + 2] * 3;
            float ax = verts[i0], ay = verts[i0 + 1], az = verts[i0 + 2];
            float bx = verts[i1], by = verts[i1 + 1], bz = verts[i1 + 2];
            float cx = verts[i2], cy = verts[i2 + 1], cz = verts[i2 + 2];

            // Area + geometric normal from the edge cross product; centroid as the light's sample anchor.
            float ex1 = bx - ax, ey1 = by - ay, ez1 = bz - az;
            float ex2 = cx - ax, ey2 = cy - ay, ez2 = cz - az;
            float nx = ey1 * ez2 - ez1 * ey2;
            float ny = ez1 * ex2 - ex1 * ez2;
            float nz = ex1 * ey2 - ey1 * ex2;
            float crossLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float area = 0.5f * crossLen;
            if (area <= AREA_EPS) {
                continue;
            }
            float inv = 1.0f / crossLen;
            nx *= inv; ny *= inv; nz *= inv;
            float px = (ax + bx + cx) * (1.0f / 3.0f);
            float py = (ay + by + cy) * (1.0f / 3.0f);
            float pz = (az + bz + cz) * (1.0f / 3.0f);

            // Le = albedo · emission · STRENGTH. Stage 0 albedo proxy = the biome tint (white for most
            // emitters); the texture-mean albedo from the _s-stats step refines coloured/textured glows.
            float le = e * EMISSIVE_STRENGTH;
            float tr = prim[pb + 4], tg = prim[pb + 5], tb = prim[pb + 6];

            out.data.add(px);
            out.data.add(py);
            out.data.add(pz);
            out.data.add(area);
            out.data.add(nx);
            out.data.add(ny);
            out.data.add(nz);
            out.data.add(0.0f);
            out.data.add(tr * le);
            out.data.add(tg * le);
            out.data.add(tb * le);
            out.data.add(0.0f);
        }
    }
}
