package dev.upscaler.rt.lod;

import dev.upscaler.UpscalerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * CPU LOD node selection (milestone M1 of docs/LOD_PLAN.md): picks, per frame-ish, the set of LOD
 * sections that would be rendered as far-field proxies. Dump-only for now — M2 consumes
 * {@link #selected} to build proxy BLASes.
 *
 * <p>Rules: descend from the coarsest level's sections. A node fully inside the near-field window
 * (fine {@code RtTerrain} sections) is skipped; a node straddling the boundary is split so its
 * outside children hug the edge (level-0 stragglers are dropped — that is the deliberate 0..1-section
 * transition gap that prevents near/far double-geometry). Otherwise a node is selected once its
 * distance octave matches its level, so selection only ever emits descent leaves — parent/child
 * overlap is structurally impossible. A node whose finer children were evicted (coarse-only trail
 * data) is selected as-is rather than leaving a hole.
 *
 * <p>Recomputed only when the 8-block-quantized camera moves or the LOD world mutates; the
 * quantization doubles as cheap anti-flap hysteresis until rendering (M2) needs a real one.
 */
final class RtLodSelector {
    /**
     * Near-field boundary: fine terrain owns everything within this many chunks, LOD outside.
     * Hardcoded for now (decided 2026-07-03); at M2 the fine {@code RtTerrain} window shrinks to
     * this radius. Future: replace the distance-octave rule below with camera/screen-size-driven
     * descent (a node subdivides while its projected size is too large), which subsumes this
     * constant into a per-node test.
     */
    static final int NEAR_RADIUS_CHUNKS = 10;
    private static final double LEVEL0_MAX_BLOCKS = NEAR_RADIUS_CHUNKS * 16 * 2.0; // level 0 band ends at 2N
    private static final int QUANT_BLOCKS = 8;

    private final LongArrayList[] selected = new LongArrayList[RtLodWorld.MAX_LEVELS];
    private int qx = Integer.MIN_VALUE;
    private int qy;
    private int qz;
    private long seenMutation = -1;
    private long lastSelectNanos;

    RtLodSelector() {
        for (int i = 0; i < selected.length; i++) {
            selected[i] = new LongArrayList();
        }
    }

    /** Selected section keys at {@code level} from the last {@link #select} pass. */
    LongArrayList selected(int level) {
        return selected[level];
    }

    /** Recompute if the quantized camera moved or the world changed; false = previous result stands. */
    boolean select(Long2ObjectOpenHashMap<RtLodSection>[] levels, long mutation, int pbx, int pby, int pbz) {
        int nqx = pbx & ~(QUANT_BLOCKS - 1);
        int nqy = pby & ~(QUANT_BLOCKS - 1);
        int nqz = pbz & ~(QUANT_BLOCKS - 1);
        if (nqx == qx && nqy == qy && nqz == qz && mutation == seenMutation) {
            return false;
        }
        qx = nqx;
        qy = nqy;
        qz = nqz;
        seenMutation = mutation;
        long t0 = System.nanoTime();
        for (LongArrayList list : selected) {
            list.clear();
        }
        int rootLevel = UpscalerConfig.Rt.Lod.MAX_LEVEL.value(); // top populated level (ingest fills 0..max)
        int pcx = pbx >> 4;
        int pcz = pbz >> 4;
        for (RtLodSection s : levels[rootLevel].values()) {
            descend(levels, rootLevel, s.sx, s.sy, s.sz, pcx, pcz, rootLevel);
        }
        lastSelectNanos = System.nanoTime() - t0;
        return true;
    }

    private void descend(Long2ObjectOpenHashMap<RtLodSection>[] levels, int level,
                         int sx, int sy, int sz, int pcx, int pcz, int rootLevel) {
        long x0 = (long) sx << (5 + level);
        long y0 = (long) sy << (5 + level);
        long z0 = (long) sz << (5 + level);
        long size = 32L << level;
        // Near-field test in chunk columns, matching RtTerrain's square full-height window.
        int cx0 = (int) (x0 >> 4);
        int cx1 = (int) ((x0 + size - 1) >> 4);
        int cz0 = (int) (z0 >> 4);
        int cz1 = (int) ((z0 + size - 1) >> 4);
        int r = NEAR_RADIUS_CHUNKS;
        if (cx0 >= pcx - r && cx1 <= pcx + r && cz0 >= pcz - r && cz1 <= pcz + r) {
            return; // fully inside the near field — fine terrain owns this volume
        }
        boolean straddles = cx0 <= pcx + r && cx1 >= pcx - r && cz0 <= pcz + r && cz1 >= pcz - r;
        int desired = levelFor(distanceToBox(x0, y0, z0, size), rootLevel);
        if ((straddles || desired < level) && level > 0) {
            boolean anyChild = false;
            for (int o = 0; o < 8; o++) {
                int csx = sx * 2 + (o & 1);
                int csz = sz * 2 + ((o >> 1) & 1);
                int csy = sy * 2 + ((o >> 2) & 1);
                if (levels[level - 1].containsKey(RtLodWorld.key(csx, csy, csz))) {
                    anyChild = true;
                    descend(levels, level - 1, csx, csy, csz, pcx, pcz, rootLevel);
                }
            }
            if (!anyChild && !straddles) {
                // Finer data was evicted (coarse-only trail): keep the coarse node over a hole.
                selected[level].add(RtLodWorld.key(sx, sy, sz));
            }
            return;
        }
        if (!straddles) {
            selected[level].add(RtLodWorld.key(sx, sy, sz));
        }
        // straddles && level == 0: dropped — the transition gap at the near boundary.
    }

    /** Distance octaves: level 0 out to 2N chunks, then one level per doubling, capped at the root. */
    private int levelFor(double distBlocks, int rootLevel) {
        int level = 0;
        double threshold = LEVEL0_MAX_BLOCKS;
        while (level < rootLevel && distBlocks >= threshold) {
            level++;
            threshold *= 2.0;
        }
        return level;
    }

    private double distanceToBox(long x0, long y0, long z0, long size) {
        double dx = axisDist(qx, x0, size);
        double dy = axisDist(qy, y0, size);
        double dz = axisDist(qz, z0, size);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDist(int p, long lo, long size) {
        if (p < lo) {
            return lo - p;
        }
        long hi = lo + size;
        return p >= hi ? p - hi + 1 : 0;
    }

    void appendDebug(StringBuilder sb) {
        sb.append(", selected");
        for (int i = 0; i < selected.length; i++) {
            sb.append(" L").append(i).append('=').append(selected[i].size());
        }
        sb.append(String.format(" (%.2f ms)", lastSelectNanos / 1_000_000.0));
    }
}
