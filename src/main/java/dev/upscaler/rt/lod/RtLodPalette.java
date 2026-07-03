package dev.upscaler.rt.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global LOD material palette: a stable integer id per {@link BlockState} plus the per-state facts the
 * far field needs (surface kind, block-light emission, a MapColor-derived flat albedo). LOD cells store
 * only the id — kind/emission/color are functions of the state, so they live here, not in per-cell bits.
 * Id 0 is reserved for air.
 *
 * <p>Thread-safe: worker threads register states during voxelization ({@link #idFor}), the client tick
 * reads entries back by id ({@link #entry}). Publication of {@code byId} rides the ConcurrentHashMap
 * happens-before (the array element is written inside the computeIfAbsent mapping function, before the
 * entry becomes visible through the map or through any cell array handed across the ingest queue).
 */
public final class RtLodPalette {
    public static final int AIR_ID = 0;

    public static final byte KIND_AIR = 0;
    public static final byte KIND_WATER = 1;
    public static final byte KIND_CUTOUT = 2;
    public static final byte KIND_LAVA = 3;
    public static final byte KIND_SOLID = 4;

    /** One palette entry; {@code emission} is the vanilla block-light 0..15, {@code argb} a flat albedo. */
    public record Entry(int id, byte kind, byte emission, int argb) {
    }

    private static final Entry AIR = new Entry(AIR_ID, KIND_AIR, (byte) 0, 0);
    private static final int FALLBACK_COLOR = 0xFF7F7F7F;

    private final ConcurrentHashMap<BlockState, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile Entry[] byId;

    RtLodPalette() {
        Entry[] initial = new Entry[256];
        initial[AIR_ID] = AIR;
        byId = initial;
    }

    /** Register-or-look-up a state. Worker-thread safe; {@code view}/{@code pos} feed the MapColor query. */
    public int idFor(BlockState state, BlockGetter view, BlockPos pos) {
        Entry e = entries.get(state);
        if (e == null) {
            e = entries.computeIfAbsent(state, s -> register(s, view, pos));
        }
        return e.id();
    }

    /** Entry for an id previously returned by {@link #idFor}; unknown ids resolve to air. */
    public Entry entry(int id) {
        Entry[] a = byId;
        if (id >= 0 && id < a.length) {
            Entry e = a[id];
            if (e != null) {
                return e;
            }
        }
        return AIR;
    }

    /** Number of registered ids, including air. */
    public int size() {
        return nextId.get();
    }

    private Entry register(BlockState state, BlockGetter view, BlockPos pos) {
        byte kind = classify(state);
        int emission = Math.min(15, Math.max(0, state.getLightEmission()));
        int color = FALLBACK_COLOR;
        try {
            color = 0xFF000000 | state.getMapColor(view, pos).col;
        } catch (Throwable ignored) {
            // Modded getMapColor may assume main-thread context; a gray far-field albedo beats failing ingest.
        }
        Entry e = new Entry(nextId.getAndIncrement(), kind, (byte) emission, color);
        publish(e);
        return e;
    }

    private synchronized void publish(Entry e) {
        Entry[] a = byId;
        if (e.id() >= a.length) {
            int grown = a.length;
            while (grown <= e.id()) {
                grown <<= 1;
            }
            a = java.util.Arrays.copyOf(a, grown);
        }
        a[e.id()] = e;
        byId = a;
    }

    /**
     * Surface kind from the state alone. Fluid blocks split water/lava; INVISIBLE non-fluid render shapes
     * (air, light, barrier, structure void) count as air for the far field; waterlogged solids classify
     * by their solid block. Everything else: occluding = solid, rest (leaves/glass/plants) = cutout.
     */
    private static byte classify(BlockState state) {
        if (state.isAir()) {
            return KIND_AIR;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && state.getBlock() instanceof LiquidBlock) {
            return fluid.is(FluidTags.WATER) ? KIND_WATER : KIND_LAVA;
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE && fluid.isEmpty()) {
            return KIND_AIR;
        }
        return state.canOcclude() ? KIND_SOLID : KIND_CUTOUT;
    }
}
