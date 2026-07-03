package dev.upscaler.rt.lod;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/**
 * One 32³ LOD section at a single mip level, covering {@code 32 · 2^level} blocks per axis. Cells are
 * global {@link RtLodPalette} ids stored through a per-section palette: local byte indices escalating to
 * short past 256 distinct ids. Storage is lazily allocated, so an all-air (sky) section costs roughly an
 * object header — important because the loaded window keeps thousands of level-0 sections resident.
 *
 * <p>Main-thread confined (all mutation happens in {@link RtLodWorld}'s tick apply).
 */
public final class RtLodSection {
    public static final int SIZE = 32;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    public final int level;
    public final int sx;
    public final int sy;
    public final int sz;

    // Per-section palette: localPalette[local] = global id; localPalette[0] = air, always.
    private int[] localPalette = {RtLodPalette.AIR_ID};
    private int localPaletteSize = 1;
    private Int2IntOpenHashMap lookup; // global id -> local index; allocated with the cell data
    private byte[] dataB; // local indices while the palette fits in a byte...
    private short[] dataS; // ...escalated here past 256 entries (mutually exclusive with dataB)
    private int nonAir;
    private int ingestMask; // level 0 only: which of the 8 vanilla-section octants have been ingested
    long lastTouch; // RtLodWorld tick of the last write (eviction ordering input)

    RtLodSection(int level, int sx, int sy, int sz) {
        this.level = level;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    /** Global palette id at local cell coords (0..31 each); air where storage was never allocated. */
    public int get(int lx, int ly, int lz) {
        if (dataB == null && dataS == null) {
            return RtLodPalette.AIR_ID;
        }
        int idx = ((ly * SIZE) + lz) * SIZE + lx;
        int local = dataB != null ? dataB[idx] & 0xFF : dataS[idx] & 0xFFFF;
        return localPalette[local];
    }

    /**
     * Write an n³ cell region at local offset (ox,oy,oz). {@code cells} holds global ids in
     * {@code (y*n + z)*n + x} order; {@code null} means all air (clears any previous content without
     * allocating storage for never-written sections). Consecutive equal ids resolve their local index
     * once — terrain runs make most lookups free.
     */
    public void setRegion(int ox, int oy, int oz, int n, int[] cells) {
        if (cells == null && dataB == null && dataS == null) {
            return; // clearing a section that never held data
        }
        int lastGid = Integer.MIN_VALUE;
        int lastLocal = 0;
        for (int y = 0; y < n; y++) {
            for (int z = 0; z < n; z++) {
                int dst = ((oy + y) * SIZE + (oz + z)) * SIZE + ox;
                int src = (y * n + z) * n;
                for (int x = 0; x < n; x++) {
                    int gid = cells == null ? RtLodPalette.AIR_ID : cells[src + x];
                    if (gid != lastGid) {
                        lastGid = gid;
                        lastLocal = localFor(gid);
                    }
                    writeCell(dst + x, lastLocal);
                }
            }
        }
    }

    public int nonAir() {
        return nonAir;
    }

    public int ingestMask() {
        return ingestMask;
    }

    void markIngested(int octant) {
        ingestMask |= 1 << octant;
    }

    /** Approximate retained heap cost, for the RAM budget and debug counters. */
    public long estimatedBytes() {
        long bytes = 96 + (long) localPalette.length * Integer.BYTES;
        if (dataB != null) {
            bytes += VOLUME;
        }
        if (dataS != null) {
            bytes += 2L * VOLUME;
        }
        if (lookup != null) {
            bytes += 48 + 16L * localPaletteSize;
        }
        return bytes;
    }

    private int localFor(int gid) {
        if (gid == RtLodPalette.AIR_ID) {
            return 0;
        }
        if (dataB == null && dataS == null) {
            dataB = new byte[VOLUME];
            lookup = new Int2IntOpenHashMap(16);
            lookup.defaultReturnValue(-1);
        }
        int local = lookup.get(gid);
        if (local >= 0) {
            return local;
        }
        if (localPaletteSize == 256 && dataB != null) {
            // Escalate to short indices before handing out local index 256.
            short[] wide = new short[VOLUME];
            for (int i = 0; i < VOLUME; i++) {
                wide[i] = (short) (dataB[i] & 0xFF);
            }
            dataS = wide;
            dataB = null;
        }
        if (localPaletteSize == localPalette.length) {
            localPalette = java.util.Arrays.copyOf(localPalette, localPalette.length * 2);
        }
        local = localPaletteSize++;
        localPalette[local] = gid;
        lookup.put(gid, local);
        return local;
    }

    private void writeCell(int idx, int local) {
        if (dataB == null && dataS == null) {
            return; // air write into never-allocated storage
        }
        int old;
        if (dataB != null) {
            old = dataB[idx] & 0xFF;
            dataB[idx] = (byte) local;
        } else {
            old = dataS[idx] & 0xFFFF;
            dataS[idx] = (short) local;
        }
        // Local index 0 is exclusively air (lookup never maps a non-air gid to 0).
        nonAir += (local != 0 ? 1 : 0) - (old != 0 ? 1 : 0);
    }
}
