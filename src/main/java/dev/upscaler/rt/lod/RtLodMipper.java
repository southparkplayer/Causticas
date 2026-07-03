package dev.upscaler.rt.lod;

/**
 * Mip-pyramid builder for one voxelized vanilla section. Each parent cell keeps the 2×2×2 child with
 * the highest (kind rank, emission) score — solid > lava > cutout > water > air — so geometry stays
 * stable and strong emitters survive coarsening when they win their cell. Known M0 loss (documented in
 * docs/LOD_PLAN.md, fixed at M5): an emissive cutout (torch) mixed with solid children loses to the
 * solid and its emission disappears.
 *
 * <p>Pure CPU + palette reads only — runs on the worker thread inside the tessellation job. A 16³
 * footprint stays 2×2×2-aligned at every level ≤ 4, so the whole pyramid is computable from the
 * section's own cells with no neighbour reads (why {@code lodMaxLevel} caps at 4).
 */
final class RtLodMipper {

    private RtLodMipper() {
    }

    /**
     * Build levels 0..maxLevel from a 16³ cell array of global palette ids in {@code (y*16+z)*16+x}
     * order: {@code [0]} is the input, {@code [L]} has {@code (16>>L)³} cells in the same order.
     */
    static int[][] buildMips(RtLodPalette palette, int[] level0, int maxLevel) {
        int[][] out = new int[maxLevel + 1][];
        out[0] = level0;
        int n = 16;
        for (int level = 1; level <= maxLevel; level++) {
            int[] src = out[level - 1];
            int m = n >> 1;
            int[] dst = new int[m * m * m];
            for (int y = 0; y < m; y++) {
                for (int z = 0; z < m; z++) {
                    for (int x = 0; x < m; x++) {
                        int base = ((y * 2) * n + (z * 2)) * n + (x * 2);
                        int best = RtLodPalette.AIR_ID;
                        int bestScore = -1;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dz = 0; dz < 2; dz++) {
                                for (int dx = 0; dx < 2; dx++) {
                                    int gid = src[base + (dy * n + dz) * n + dx];
                                    int score = score(palette, gid);
                                    if (score > bestScore) {
                                        bestScore = score;
                                        best = gid;
                                    }
                                }
                            }
                        }
                        dst[(y * m + z) * m + x] = best;
                    }
                }
            }
            out[level] = dst;
            n = m;
        }
        return out;
    }

    private static int score(RtLodPalette palette, int gid) {
        if (gid == RtLodPalette.AIR_ID) {
            return 0;
        }
        RtLodPalette.Entry e = palette.entry(gid);
        return (kindRank(e.kind()) << 8) | (e.emission() & 0xFF);
    }

    private static int kindRank(byte kind) {
        return switch (kind) {
            case RtLodPalette.KIND_SOLID -> 5;
            case RtLodPalette.KIND_LAVA -> 4;
            case RtLodPalette.KIND_CUTOUT -> 3;
            case RtLodPalette.KIND_WATER -> 2;
            default -> 0;
        };
    }
}
