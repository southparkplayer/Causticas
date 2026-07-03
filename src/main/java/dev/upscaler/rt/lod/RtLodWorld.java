package dev.upscaler.rt.lod;

import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerMod;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory mipmapped voxel sidecar of the client world for far-field RT LOD (milestone M0 of
 * docs/LOD_PLAN.md). No rendering yet: this owns the data model, ingest, retention and debug counters
 * that later milestones (selector, proxy meshes/BLAS) consume.
 *
 * <p><b>Ingest</b> piggybacks {@link dev.upscaler.rt.terrain.RtTerrain}'s worker tessellation job: the
 * worker voxelizes the same {@link RenderSectionRegion} snapshot into global palette ids, builds the
 * whole mip pyramid (self-contained for levels ≤ 4), and enqueues. Because every block edit already
 * re-runs that job, LOD data refreshes with no dirty hook of its own; ordering per vanilla section rides
 * RtTerrain's monotonic tess token, and a world epoch (bumped on every clear) fences stale jobs.
 *
 * <p><b>Retention</b>: level 0 mirrors the loaded window and is dropped once its 2×2 chunk footprint
 * falls outside render distance + margin (rebuildable — edits only happen in loaded chunks). Levels ≥ 1
 * survive unload under a RAM budget (farthest-from-player evicted first), forming the far-field trail.
 *
 * <p>Threading: worker threads only register palette entries and enqueue; all map mutation happens on
 * the client tick ({@link #update()}), wall-clock + count budgeted.
 */
public final class RtLodWorld {
    /** Levels 0..4; level-L sections are 32³ cells of 2^L blocks. Capped at 4 (see RtLodMipper). */
    public static final int MAX_LEVELS = 5;
    private static final int QUEUE_CAP = 8192;
    private static final int SWEEP_INTERVAL_TICKS = 20;
    private static final int DEBUG_LOG_INTERVAL_TICKS = 100;

    private static final RtLodWorld INSTANCE = new RtLodWorld();
    // First failure disables LOD for the session (never let sidecar bugs take down terrain or ticks).
    private static volatile boolean failed;

    private final RtLodPalette palette = new RtLodPalette();
    private final ConcurrentLinkedQueue<Ingest> ingestQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private volatile int epoch;

    private final Long2ObjectOpenHashMap<RtLodSection>[] levels;
    // Last-applied tess token per vanilla section key — out-of-order worker completions can't regress.
    private final Long2LongOpenHashMap appliedToken = new Long2LongOpenHashMap();
    private final RtLodSelector selector = new RtLodSelector();
    private long mutationCounter; // bumped on any section-map change; gates selector recompute
    private WeakReference<ClientLevel> trackedLevel = new WeakReference<>(null);
    private long tickCount;

    // Counters (worker-side adders, main-thread longs) — surfaced by the lodDebug log line.
    private final LongAdder voxelized = new LongAdder();
    private final LongAdder overflowDropped = new LongAdder();
    private long applied;
    private long staleDropped;
    private long epochDropped;
    private long demoted;
    private long evicted;

    @SuppressWarnings("unchecked")
    private RtLodWorld() {
        levels = new Long2ObjectOpenHashMap[MAX_LEVELS];
        for (int i = 0; i < MAX_LEVELS; i++) {
            levels[i] = new Long2ObjectOpenHashMap<>();
        }
    }

    private static boolean enabled() {
        return UpscalerConfig.Rt.Lod.WORLD.value();
    }

    /**
     * Worker-thread ingest hook, called from RtTerrain's tessellation job with the section snapshot it
     * already holds. Total: catches everything and latches {@code failed} so a sidecar bug can never
     * fail a terrain section (the caller has already enqueued its tessellation result).
     */
    public static void ingestFromWorker(RenderSectionRegion region, int scx, int scy, int scz, long token) {
        if (failed || !enabled()) {
            return;
        }
        try {
            INSTANCE.voxelizeAndQueue(region, scx, scy, scz, token);
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT LOD: ingest failed; LOD world disabled for this session", t);
        }
    }

    /** Per-client-tick apply/retention pass; called next to RtTerrain.update. */
    public static void update() {
        if (failed) {
            return;
        }
        try {
            INSTANCE.tick();
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("RT LOD: tick failed; LOD world disabled for this session", t);
        }
    }

    /** Drop all data and queued ingests (RT teardown). The failure latch persists for the session. */
    public static void reset() {
        INSTANCE.clearAll();
    }

    private void tick() {
        tickCount++;
        if (!enabled()) {
            if (hasData()) {
                clearAll(); // runtime toggle off — release everything
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            if (hasData()) {
                clearAll();
            }
            return;
        }
        // World identity by ClientLevel instance: dimension switches and server changes swap the level
        // object. Deliberately NOT wired to InvalidateRenderStateCallback — F3+A and render-distance
        // changes are render-state events and must not wipe the retained far-field trail.
        if (trackedLevel.get() != level) {
            clearAll();
            trackedLevel = new WeakReference<>(level);
        }
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        applyIngests();
        if (tickCount % SWEEP_INTERVAL_TICKS == 0) {
            int radius = Math.max(1, mc.options.getEffectiveRenderDistance())
                    + UpscalerConfig.Rt.Lod.DEMOTE_MARGIN_CHUNKS.value();
            demoteSweep(pbx >> 4, pbz >> 4, radius);
            evictSweep(pbx, pby, pbz);
        }
        // M1: recompute the far-field selection when the camera or the data moved (dump-only until M2).
        selector.select(levels, mutationCounter, pbx, pby, pbz);
        if (UpscalerConfig.Rt.Lod.DEBUG.value() && tickCount % DEBUG_LOG_INTERVAL_TICKS == 0) {
            logDebug();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Worker side

    private void voxelizeAndQueue(RenderSectionRegion region, int scx, int scy, int scz, long token) {
        int epochAtIngest = epoch;
        int maxLevel = UpscalerConfig.Rt.Lod.MAX_LEVEL.value();
        int sox = scx << 4, soy = scy << 4, soz = scz << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] cells = new int[16 * 16 * 16];
        int nonAir = 0;
        int idx = 0;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    pos.set(sox + lx, soy + ly, soz + lz);
                    BlockState state = region.getBlockState(pos);
                    int gid = RtLodPalette.AIR_ID;
                    if (!state.isAir()) {
                        int id = palette.idFor(state, region, pos);
                        // Keep air-kind ids (light blocks, barriers — registered but invisible) out of
                        // cells entirely, so the mipper's emission tiebreak can't propagate them.
                        if (palette.entry(id).kind() != RtLodPalette.KIND_AIR) {
                            gid = id;
                        }
                    }
                    if (gid != RtLodPalette.AIR_ID) {
                        nonAir++;
                    }
                    cells[idx++] = gid;
                }
            }
        }
        voxelized.increment();
        int[][] mips = nonAir > 0 ? RtLodMipper.buildMips(palette, cells, maxLevel) : null;
        if (queueSize.get() >= QUEUE_CAP) {
            overflowDropped.increment(); // apply is behind; the section re-ingests on its next job
            return;
        }
        ingestQueue.add(new Ingest(scx, scy, scz, token, epochAtIngest, mips));
        queueSize.incrementAndGet();
    }

    // ---------------------------------------------------------------------------------------------
    // Main-thread apply

    private void applyIngests() {
        long deadline = System.nanoTime()
                + (long) (UpscalerConfig.Rt.Lod.APPLY_BUDGET_MS.value() * 1_000_000f);
        int budget = UpscalerConfig.Rt.Lod.APPLY_PER_TICK.value();
        Ingest in;
        while (budget-- > 0 && System.nanoTime() < deadline && (in = ingestQueue.poll()) != null) {
            queueSize.decrementAndGet();
            apply(in);
        }
    }

    private void apply(Ingest in) {
        if (in.epoch != epoch) {
            epochDropped++;
            return;
        }
        long vKey = key(in.scx, in.scy, in.scz);
        if (appliedToken.get(vKey) >= in.token) {
            staleDropped++; // an older snapshot completed after a newer one — ignore
            return;
        }
        appliedToken.put(vKey, in.token);
        int top = in.mips != null ? in.mips.length - 1 : MAX_LEVELS - 1;
        for (int level = 0; level <= top; level++) {
            int[] cells = in.mips != null ? in.mips[level] : null;
            writeLevelRegion(level, in.scx, in.scy, in.scz, cells);
        }
        applied++;
        mutationCounter++;
    }

    /**
     * Write one vanilla section's footprint at {@code level}: an n³ region (n = max(1, 16>>L)) that
     * always lands inside exactly one 32³ LOD section. {@code cells == null} means all air — existing
     * data is cleared, but sections are never created for it (sky stays free).
     */
    private void writeLevelRegion(int level, int scx, int scy, int scz, int[] cells) {
        int n = Math.max(1, 16 >> level);
        int cx0 = (scx << 4) >> level; // arithmetic shift = floor division, correct for negatives
        int cy0 = (scy << 4) >> level;
        int cz0 = (scz << 4) >> level;
        long sKey = key(cx0 >> 5, cy0 >> 5, cz0 >> 5);
        RtLodSection section = levels[level].get(sKey);
        if (section == null) {
            if (cells == null) {
                return;
            }
            section = new RtLodSection(level, cx0 >> 5, cy0 >> 5, cz0 >> 5);
            levels[level].put(sKey, section);
        }
        section.setRegion(cx0 & 31, cy0 & 31, cz0 & 31, n, cells);
        section.lastTouch = tickCount;
        if (level == 0) {
            section.markIngested((scx & 1) | ((scz & 1) << 1) | ((scy & 1) << 2));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Retention

    /**
     * Drop level-0 sections whose 2×2 chunk-column footprint left the loaded window (+margin). Their
     * information lives on in the retained parent mips; if the chunks reload, re-ingest rebuilds them.
     * Their vanilla-section tokens are released too, so appliedToken stays bounded by the window.
     */
    private void demoteSweep(int pcx, int pcz, int radius) {
        ObjectIterator<Long2ObjectMap.Entry<RtLodSection>> it =
                levels[0].long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            RtLodSection s = it.next().getValue();
            int cx0 = s.sx * 2, cz0 = s.sz * 2; // chunk-column coords of the section's 2×2 footprint
            boolean outside = cx0 > pcx + radius || cx0 + 1 < pcx - radius
                    || cz0 > pcz + radius || cz0 + 1 < pcz - radius;
            if (!outside) {
                continue;
            }
            for (int o = 0; o < 8; o++) {
                appliedToken.remove(key(cx0 + (o & 1), s.sy * 2 + ((o >> 2) & 1), cz0 + ((o >> 1) & 1)));
            }
            it.remove();
            demoted++;
            mutationCounter++;
        }
    }

    /** Enforce the retained-RAM budget on levels ≥ 1: farthest-from-player first (protects the window). */
    private void evictSweep(int pbx, int pby, int pbz) {
        long budget = (long) UpscalerConfig.Rt.Lod.RETAIN_BUDGET_MB.value() * 1024L * 1024L;
        long total = 0;
        for (int level = 1; level < MAX_LEVELS; level++) {
            for (RtLodSection s : levels[level].values()) {
                total += s.estimatedBytes();
            }
        }
        if (total <= budget) {
            return;
        }
        ArrayList<EvictCandidate> candidates = new ArrayList<>();
        for (int level = 1; level < MAX_LEVELS; level++) {
            for (RtLodSection s : levels[level].values()) {
                double cx = ((s.sx * 32.0) + 16.0) * (1 << level) - pbx;
                double cy = ((s.sy * 32.0) + 16.0) * (1 << level) - pby;
                double cz = ((s.sz * 32.0) + 16.0) * (1 << level) - pbz;
                candidates.add(new EvictCandidate(key(s.sx, s.sy, s.sz), level,
                        s.estimatedBytes(), cx * cx + cy * cy + cz * cz));
            }
        }
        // Farthest first; at equal distance evict finer levels first, so the coarse roots the selector
        // descends from (and the last-resort trail data) outlive their detail levels.
        candidates.sort((a, b) -> {
            int byDist = Double.compare(b.dist2, a.dist2);
            return byDist != 0 ? byDist : Integer.compare(a.level, b.level);
        });
        for (EvictCandidate c : candidates) {
            if (total <= budget) {
                break;
            }
            levels[c.level].remove(c.key);
            total -= c.bytes;
            evicted++;
            mutationCounter++;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle / debug

    private boolean hasData() {
        if (!appliedToken.isEmpty()) {
            return true;
        }
        for (Long2ObjectOpenHashMap<RtLodSection> m : levels) {
            if (!m.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void clearAll() {
        epoch++; // fences queued + in-flight worker results from the old world
        ingestQueue.clear();
        queueSize.set(0);
        for (Long2ObjectOpenHashMap<RtLodSection> m : levels) {
            m.clear();
            m.trim();
        }
        appliedToken.clear();
        appliedToken.trim();
        trackedLevel = new WeakReference<>(null);
        mutationCounter++;
    }

    private void logDebug() {
        StringBuilder sb = new StringBuilder("RT LOD: sections");
        long retained = 0;
        long windowBytes = 0;
        for (int level = 0; level < MAX_LEVELS; level++) {
            sb.append(" L").append(level).append('=').append(levels[level].size());
            for (RtLodSection s : levels[level].values()) {
                if (level == 0) {
                    windowBytes += s.estimatedBytes();
                } else {
                    retained += s.estimatedBytes();
                }
            }
        }
        sb.append(String.format(", window %.1f MB, retained %.1f MB (budget %d)",
                windowBytes / 1048576.0, retained / 1048576.0, UpscalerConfig.Rt.Lod.RETAIN_BUDGET_MB.value()));
        sb.append(", palette ").append(palette.size());
        sb.append(", voxelized ").append(voxelized.sum())
                .append(" applied ").append(applied)
                .append(" stale ").append(staleDropped)
                .append(" epochDrop ").append(epochDropped)
                .append(" overflow ").append(overflowDropped.sum())
                .append(" demoted ").append(demoted)
                .append(" evicted ").append(evicted)
                .append(", queue ").append(queueSize.get());
        selector.appendDebug(sb);
        UpscalerMod.LOGGER.info(sb.toString());
    }

    /** Same packing as RtTerrain.sectionKey; LOD-level coords are strictly smaller, so ranges fit. */
    static long key(int x, int y, int z) {
        return (x & 0x3FFFFFFL) | ((z & 0x3FFFFFFL) << 26) | ((y & 0xFFFL) << 52);
    }

    /** One voxelized vanilla section: full mip pyramid ({@code null} = all air) + ordering/epoch fences. */
    private record Ingest(int scx, int scy, int scz, long token, int epoch, int[][] mips) {
    }

    private record EvictCandidate(long key, int level, long bytes, double dist2) {
    }
}
