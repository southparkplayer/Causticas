

package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

import static dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.WORKER_TESS;
import static dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.buildCpuSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.CpuSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.WorkerTessState;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionBuilder.PreparedSection;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.Generation;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.SectionGeom;
/**
 * Per-section terrain residency synced to vanilla's loaded chunks. A singleton manager
 * keeps a map of resident 16³ sections. The 20 TPS tick maintains the desired window around the player
 * (slid incrementally on section-boundary crossings) and drains dirty events; the actual streaming —
 * snapshot dispatch, completion drain, and publish — runs once per render frame from
 * {@link RtComposite} with count-bounded completion and dispatch passes rather than a per-tick burst.
 * Residency follows vanilla because a section is only "desired" when its
 * chunk is loaded ({@code hasChunk}), so chunk load/unload drives build/free without any mixin.
 *
 * <p>Geometry comes from the Fabric Renderer API model path (correct shapes, neighbour cull, biome
 * tint, alpha cutout, and model quad transforms). Vertices are section-local (f32-exact); each TLAS
 * instance carries a
 * translation {@code sectionOrigin − rebaseOrigin} (rebase = player block at the last rebuild, so
 * transforms stay small at any world coordinate) and an {@code instanceCustomIndex} into a BDA
 * section table ({@code {primAddr, uvAddr, triBase[4]}} per section) the hit shaders read. The index
 * buffer itself is retained only for the BLAS build (per-triangle corner UVs mean shading never needs
 * an index-buffer read — lever B), so its address isn't duplicated into this table.
 *
 * <p>Tessellation reads only an immutable snapshot ({@link RtSectionSnapshots.Region}, palette-only
 * copies captured on the render thread and cached persistently across passes — see
 * {@link RtSectionSnapshots}). CPU meshing runs on
 * {@link RtWorkerPool}; snapshotting and publication stay on the render thread, while workers own GPU
 * buffer allocation/fill, OMM/BLAS preparation, and enqueue onto the single-owner GPU executor. Frees
 * are retired against graphics timeline completion (no {@code waitIdle} on the hot path).
 */
public final class RtTerrain {
    private static volatile boolean benchmarkTelemetryEnabled;
    private static volatile long benchmarkStartedNanos;
    private static final AtomicInteger benchmarkWorkerActive = new AtomicInteger();
    private static final LongAdder benchmarkDispatched = new LongAdder();
    private static final LongAdder benchmarkCpuCompleted = new LongAdder();
    private static final LongAdder benchmarkGpuCompleted = new LongAdder();
    private static final LongAdder benchmarkPublished = new LongAdder();
    private static final LongAdder benchmarkEmptyCompleted = new LongAdder();
    private static final LongAdder benchmarkNeighborBlocked = new LongAdder();
    private static final LongAdder benchmarkSnapshotNanos = new LongAdder();
    private static final LongAdder benchmarkCpuNanos = new LongAdder();
    private static final LongAdder benchmarkGpuNanos = new LongAdder();
    // The render thread snapshots and publishes; workers mesh, allocate/fill, prepare BLAS/OMM objects,
    // and enqueue builds. The streaming pass is bounded so render-thread bookkeeping stays flat.
    private static int asyncDispatchPerPass() {
        return CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS.value();
    }

    private static int completionResultsPerPass() {
        return CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS.value();
    }

    // Backpressure cap: stop dispatching once this many sections are in flight. Bounds queue depth and
    // snapshot memory (each in-flight region pins 27 cached section snapshots) when flying through the world.
    private static int maxInflight() {
        return CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.value();
    }

    private static final int NO_MISSING_INDEX = -1;
    private static final long NO_DIRTY_GROUP = 0L;
    // If no render frame has driven a streaming pass for this long, the 20 TPS tick takes over (loading
    // screens / hidden window — states where render-driven streaming has stopped).
    private static final long STREAM_FALLBACK_AFTER_NANOS = 200_000_000L;
    private static final long LIGHT_HIERARCHY_UPDATE_INTERVAL_NANOS = 100_000_000L;

    private static int sectionTableInitialCapacity() {
        return CausticaConfig.Rt.Terrain.SECTION_TABLE_INITIAL_CAPACITY.value();
    }

    private static int rebaseDistanceBlocks() {
        return CausticaConfig.Rt.Terrain.REBASE_DISTANCE_BLOCKS.value();
    }

    private static final RtTerrain INSTANCE = new RtTerrain();

    private final Long2ObjectOpenHashMap<SectionGeom> resident = new Long2ObjectOpenHashMap<>();
    // Persistent palette snapshots for tessellation regions (render-thread only); invalidated on dirty
    // sections, column unload/window-leave, and full clears.
    private final RtSectionSnapshots snapshots = new RtSectionSnapshots();
    private LongOpenHashSet published = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet(); // loaded, in-window sections with no geometry
    private final Object dirtyLock = new Object();
    private final LongOpenHashSet dirty = new LongOpenHashSet(); // edited sections to re-extract
    private final LongArrayList dirtyDrain = new LongArrayList();
    private final ArrayList<DirtyEvent> dirtyEvents = new ArrayList<>();
    private final ArrayList<DirtyEvent> dirtyEventDrain = new ArrayList<>();
    // Persistent desired window and queued work. The expensive section window is rebuilt only when the
    // player crosses a section/radius/Y-band boundary; steady ticks poll chunk columns for load changes.
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final LongOpenHashSet desiredColumns = new LongOpenHashSet();
    private final LongOpenHashSet loadedColumns = new LongOpenHashSet();
    private final LongArrayList missing = new LongArrayList();
    private final Long2IntOpenHashMap missingIndex = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap queuedDirtyGroup = new Long2LongOpenHashMap();
    private final LongArrayList reextract = new LongArrayList();
    private final LongOpenHashSet queuedReextract = new LongOpenHashSet();
    // Publish accumulators: window sync (tick) and completion drain (streaming pass) may run on different
    // frames, so evicted geometry waits here until the next publish pass retires it.
    private final List<SectionGeom> removed = new ArrayList<>();
    private final List<PreparedSection> prepared = new ArrayList<>();
    private final IdentityHashMap<PreparedSection, TerrainTaskTracker.Ticket> publicationTickets =
            new IdentityHashMap<>();
    // Publish identity and admission ownership are separate: cancellation revokes a ticket immediately,
    // while TerrainTaskTracker retains its capacity until the terminal result is drained.
    private final TerrainTaskTracker taskTracker = new TerrainTaskTracker();
    private final Long2LongOpenHashMap inFlightDirtyGroup = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<DirtyGroup> dirtyGroups = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<SectionResult> completedBuilds = new ConcurrentLinkedQueue<>();
    private final Object activeTaskLock = new Object();
    private int activeTasks;
    /** Invalidates all worker/GPU work from a detached world residency without joining it. */
    private volatile long terrainEpoch = 1L;
    private long buildToken;
    private long dirtyGroupSeq;
    private final AtomicLong cancelledTasks = new AtomicLong();
    private final AtomicLong discardedBuilds = new AtomicLong();
    private final AtomicLong buildsSubmitted = new AtomicLong();
    private final AtomicLong buildsSubmittedSinceSample = new AtomicLong();
    private final AtomicLong buildsPublished = new AtomicLong();
    private volatile long lastBuildLatencyNanos;
    private final RtSectionTable table = new RtSectionTable();
    private boolean ready;
    // Full-residency invalidation requested off the render thread. Wired to Fabric's
    // InvalidateRenderStateCallback = vanilla LevelExtractor.allChanged() (dimension change via setLevel,
    // render-distance change, F3+A). Consumed in tick(), where the RT context is available.
    private volatile boolean fullClearRequested;
    private volatile boolean dirtyPending;
    // Resource reloads replace the model/material epoch asynchronously. Quiescing drains the old epoch,
    // while this gate prevents the next client tick or render frame from immediately starting it again.
    private volatile boolean resourceReloadPaused;
    private boolean noWorldClearApplied;
    // Rebase origin (player block at the last TLAS rebuild) for the instance transforms + ray camOffset.
    public int blockX;
    public int blockY;
    public int blockZ;
    /** Coalesced asynchronous, atomically published light hierarchy and section-sized proposal grid. */
    private final RtLightGridManager lightGrid = new RtLightGridManager();
    /** Sorted light-only snapshot, updated with section publication instead of rescanning all geometry. */
    private final TreeMap<Integer, RtLightHierarchy.SectionInput> lightSections = new TreeMap<>();
    private boolean lightHierarchyDirty;
    private long lastLightHierarchyRequestNanos;
    private boolean windowValid;
    private int windowPcx;
    private int windowPcz;
    private int windowRadius;
    private int windowLoY;
    private int windowHiY;
    // When the last streaming pass ran on a render frame — the tick fallback watches this (see
    // STREAM_FALLBACK_AFTER_NANOS).
    private long lastFrameStreamNanos;

    private RtTerrain() {
        missingIndex.defaultReturnValue(NO_MISSING_INDEX);
        queuedDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
        inFlightDirtyGroup.defaultReturnValue(NO_DIRTY_GROUP);
    }

    /**
     * The manager if it has a valid (possibly zero-instance) section table to trace against, else null.
     * Null only while genuinely uninitialized (no world, or mid-teardown) — a transient empty-residency
     * window (world join, dimension change, a full evict) still returns non-null so the RT frame keeps
     * tracing (sky/entities only) instead of a caller falling back to vanilla.
     */
    public static RtTerrain currentOrNull() {
        return INSTANCE.ready ? INSTANCE : null;
    }

    public static boolean isSectionReady(BlockPos blockPos) {
        int scx = SectionPos.blockToSectionCoord(blockPos.getX());
        int scy = SectionPos.blockToSectionCoord(blockPos.getY());
        int scz = SectionPos.blockToSectionCoord(blockPos.getZ());
        long key = sectionKey(scx, scy, scz);
        return INSTANCE.ready && ((INSTANCE.resident.containsKey(key) && INSTANCE.published.contains(key)) || INSTANCE.empty.contains(key));
    }

    /**
     * The static section instances to put in this frame's TLAS (BLAS address + sectionOrigin−rebase
     * transform). {@code instanceCustomIndex} is the list position, which {@link RtAccel#prepareTlas}
     * assigns and which the hit shaders use to index the section table. The list is stable between
     * residency rebuilds, so the per-frame TLAS rebuild just re-references the same BLAS each frame.
     */
    public List<RtAccel.Instance> staticInstances() {
        return table.instances;
    }

    /** Section table device address: {@code {u64 primAddr, u64 uvAddr, u32 triBase[4]}} per section, indexed by gl_InstanceCustomIndexEXT. */
    public long tableAddress() {
        return table.address();
    }

    /** RIS-sampled global light buffer device address, or 0 while no lights are published. */
    public long lightBufferAddress() {
        return lightGrid.published().lightAddress();
    }

    /** Power-weighted light alias table device address, or 0 for the shader's uniform fallback. */
    public long lightAliasBufferAddress() {
        return lightGrid.published().globalAliasAddress();
    }

    public long lightLocalAliasBufferAddress() {
        return lightGrid.published().localAliasAddress();
    }

    public float lightInvGlobalPowerSum() {
        return lightGrid.published().invGlobalPowerSum();
    }

    public long lightGridCellBufferAddress() {
        return lightGrid.published().cellAddress();
    }

    public long lightGridSpanBufferAddress() {
        return lightGrid.published().spanAddress();
    }

    public int lightGridOriginX() {
        RtLightGridManager.PublishedState hierarchy = lightGrid.published();
        return hierarchy.originX() + hierarchy.rebaseX() - blockX;
    }

    public int lightGridOriginY() {
        RtLightGridManager.PublishedState hierarchy = lightGrid.published();
        return hierarchy.originY() + hierarchy.rebaseY() - blockY;
    }

    public int lightGridOriginZ() {
        RtLightGridManager.PublishedState hierarchy = lightGrid.published();
        return hierarchy.originZ() + hierarchy.rebaseZ() - blockZ;
    }

    public int lightRebaseOffsetX() {
        return lightGrid.published().rebaseX() - blockX;
    }

    public int lightRebaseOffsetY() {
        return lightGrid.published().rebaseY() - blockY;
    }

    public int lightRebaseOffsetZ() {
        return lightGrid.published().rebaseZ() - blockZ;
    }

    public int lightGridDimX() {
        return lightGrid.published().dimX();
    }

    public int lightGridDimY() {
        return lightGrid.published().dimY();
    }

    public int lightGridDimZ() {
        return lightGrid.published().dimZ();
    }

    /** Number of compact 64-byte records in the published light buffer. */
    public int lightCount() {
        return lightGrid.published().lightCount();
    }

    /** Whether an immutable published terrain table is available for offline capture. */
    public static boolean hasPublishedSnapshot() {
        return INSTANCE.ready && INSTANCE.table.instances != null && INSTANCE.table.buffer != null;
    }

    /** Stable debug-bridge snapshot; counters are lifetime totals and latency is host-observed. */
    public static Status status() {
        RtTerrain terrain = INSTANCE;
        RtContext ctx = RtContext.currentOrNull();
        return new Status(terrain.taskTracker.outstanding(), maxInflight(), terrain.missing.size(),
                terrain.reextract.size(), terrain.resident.size(), terrain.cancelledTasks.get(),
                terrain.discardedBuilds.get(), ctx == null ? 0 : ctx.gpuExecutor().queuedBuilds(),
                terrain.buildsSubmitted.get(), terrain.buildsPublished.get(), terrain.lastBuildLatencyNanos,
                RtAccel.activeTerrainCompactionQueries());
    }

    public record Status(int outstandingTasks, int outstandingLimit, int queuedMissing, int queuedReextract,
                         int residentSections, long cancelledTasks, long discardedBuilds, int gpuQueueDepth,
                         long buildsSubmitted, long buildsPublished, long lastBuildLatencyNanos,
                         int activeCompactionQueries) {
    }

    /** Per-tick residency update: window sync + dirty drain (plus the streaming fallback, see {@link #frame}). */
    public static void update(RtContext ctx) {
        if (INSTANCE.resourceReloadPaused) return;
        INSTANCE.tick(ctx);
    }

    /**
     * Per-render-frame streaming pass, driven by {@link RtComposite#composite}: publish completed builds
     * and dispatch immutable snapshots to workers, bounded by configured per-pass counts.
     */
    public static void frame(RtContext ctx) {
        if (!INSTANCE.resourceReloadPaused && RtMaterialRegistry.INSTANCE.isReady()) INSTANCE.frameStream(ctx);
    }

    public static void shutdown(RtContext ctx) {
        INSTANCE.clear(ctx, true);
        INSTANCE.resourceReloadPaused = false;
    }

    /**
     * Stop and join every terrain worker/build before a resource reload destroys descriptor-owned images,
     * SHARC buffers, or ray pipelines. A bare {@code vkDeviceWaitIdle} is not a submission barrier: the
     * executor thread can submit immediately after it returns. The blocking clear advances the terrain
     * epoch, joins CPU work, drains accepted compute jobs, waits the device, and frees the old residency;
     * the same sections must be re-extracted for the new material atlas anyway.
     */
    public static void pauseForResourceReload() {
        INSTANCE.resourceReloadPaused = true;
    }

    public static void quiesceForResourceReload(RtContext ctx) {
        INSTANCE.resourceReloadPaused = true;
        INSTANCE.clear(ctx, true);
    }

    /** Resume streaming only after the replacement material registry and atlas descriptors are bound. */
    public static void resumeAfterResourceReload() {
        INSTANCE.resourceReloadPaused = false;
    }

    static boolean isResourceReloadPaused() {
        return INSTANCE.resourceReloadPaused;
    }

    /**
     * Mark every section overlapping a dirty block area — <em>plus the bordering neighbour sections</em>
     * — for re-extraction. Fed by the LevelExtractor hook (vanilla's block-change signal). Thread-safe;
     * drained on the next {@link #tick}.
     *
     * <p>The block area is expanded by one block on every side before mapping to sections, matching
     * vanilla's own dirty expansion. A change touching a section edge therefore also re-extracts the
     * adjacent section: that neighbour's cull faces toward the change (a broken block uncovers a face)
     * and, for fluids, its shared-edge surface heights (the top-face corner heights are averaged from
     * the blocks straddling the section boundary) both depend on the edited block. Without re-extracting
     * it the neighbour keeps stale geometry — opaque holes and a disconnected water surface at the seam.
     * Interior edits stay within one section (±1 doesn't cross a 16-block boundary).
     */
    public static void markBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        RtTerrain terrain = INSTANCE;
        synchronized (terrain.dirtyLock) {
            LongArrayList keys = new LongArrayList();
            for (int scx = (minX - 1) >> 4; scx <= (maxX + 1) >> 4; scx++) {
                for (int scy = (minY - 1) >> 4; scy <= (maxY + 1) >> 4; scy++) {
                    for (int scz = (minZ - 1) >> 4; scz <= (maxZ + 1) >> 4; scz++) {
                        keys.add(sectionKey(scx, scy, scz));
                    }
                }
            }
            if (!keys.isEmpty()) {
                long groupId = ++terrain.dirtyGroupSeq;
                if (groupId == NO_DIRTY_GROUP) {
                    groupId = ++terrain.dirtyGroupSeq;
                }
                terrain.dirtyEvents.add(new DirtyEvent(groupId, keys));
                terrain.dirtyPending = true;
            }
        }
    }

    /**
     * Request a full residency clear, applied on the next {@link #tick} (render thread, where the RT
     * context is available). Wired to Fabric's {@code InvalidateRenderStateCallback} — vanilla's
     * {@link net.minecraft.client.renderer.extract.LevelExtractor#allChanged()}, which fires on a
     * dimension change (via {@code setLevel}), a render-distance change, and F3+A. Thread-safe.
     */
    public static void requestFullClear() {
        RtTerrainOmm.clearCache();
        INSTANCE.fullClearRequested = true;
    }

    /** Light-list extraction ownership changes only when RIS crosses the disabled boundary. */
    public static boolean requiresLightListRebuild(int previousCandidates, int currentCandidates) {
        return (previousCandidates == 0) != (currentCandidates == 0);
    }

    /** Enable zero-persistence benchmark counters and start a fresh RT-residency run. */
    public static void setBenchmarkTelemetryEnabled(boolean enabled) {
        benchmarkTelemetryEnabled = enabled;
        benchmarkWorkerActive.set(0);
        benchmarkDispatched.reset();
        benchmarkCpuCompleted.reset();
        benchmarkGpuCompleted.reset();
        benchmarkPublished.reset();
        benchmarkEmptyCompleted.reset();
        benchmarkNeighborBlocked.reset();
        benchmarkSnapshotNanos.reset();
        benchmarkCpuNanos.reset();
        benchmarkGpuNanos.reset();
        benchmarkStartedNanos = enabled ? System.nanoTime() : 0L;
        if (enabled) requestFullClear();
    }

    /** Render-thread snapshot consumed by the opt-in file-backed benchmark bridge. */
    public static StreamingStats streamingStats() {
        RtTerrain t = INSTANCE;
        int active;
        synchronized (t.activeTaskLock) {
            active = t.activeTasks;
        }
        int workers = benchmarkWorkerActive.get();
        int completed = t.completedBuilds.size();
        int inFlight = t.taskTracker.outstanding();
        RtContext ctx = RtContext.currentOrNull();
        int gpuQueued = ctx == null ? 0 : ctx.gpuExecutor().queuedBuilds();
        return new StreamingStats(benchmarkTelemetryEnabled, benchmarkStartedNanos,
                t.desired.size(), t.missing.size(), t.reextract.size(), inFlight, workers,
                gpuQueued, completed, t.published.size(), t.empty.size(), t.resident.size(), active,
                benchmarkDispatched.sum(), benchmarkCpuCompleted.sum(), benchmarkGpuCompleted.sum(),
                benchmarkPublished.sum(), benchmarkEmptyCompleted.sum(), benchmarkNeighborBlocked.sum(),
                benchmarkSnapshotNanos.sum(), benchmarkCpuNanos.sum(), benchmarkGpuNanos.sum());
    }

    public record StreamingStats(boolean enabled, long startedNanos, int desired, int missing,
                                 int reextract, int inFlight, int workerActive, int gpuQueued,
                                 int completedQueued, int published, int empty, int resident,
                                 int activeTasks, long dispatchedTotal, long cpuCompletedTotal,
                                 long gpuCompletedTotal, long publishedTotal, long emptyCompletedTotal,
                                 long neighborBlockedTotal, long snapshotNanosTotal,
                                 long cpuNanosTotal, long gpuNanosTotal) {
        public int actionableBacklog() {
            return missing + reextract + inFlight + completedQueued;
        }
    }

    private void tick(RtContext ctx) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            if (!noWorldClearApplied) {
                clear(ctx, false);
                noWorldClearApplied = true;
            }
            return;
        }
        noWorldClearApplied = false;
        if (!RtMaterialRegistry.INSTANCE.isReady()) {
            return; // resource reload gap: keep old work dormant until the new epoch requests a full clear
        }

        // Full clear on an explicit invalidation — vanilla's LevelExtractor.allChanged() via the Fabric
        // InvalidateRenderStateCallback. That fires on a dimension switch (setLevel → allChanged),
        // render-distance change, and F3+A. Without it, End→Overworld keeps the old dimension's geometry:
        // residency is keyed by raw section coords (no world identity), so the same coords stay resident
        // and are never rebuilt for the new world.
        if (fullClearRequested) {
            fullClearRequested = false;
            clear(ctx, false);
        }

        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;
        int r = horizontalChunks(mc);
        ClientChunkCache chunkSource = level.getChunkSource();
        int minSecY = level.getMinY() >> 4;
        int maxSecY = (level.getMinY() + level.getHeight() - 1) >> 4;
        int loY = minSecY;
        int hiY = maxSecY;

        // Evicted geometry lands in `removed` and is consumed by the next streaming pass's build kick.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.windowSync")) {
            syncDesiredWindow(chunkSource, pcx, psy, pcz, r, loY, hiY, removed);
        }

        // Re-extract edited sections. Drain under a short lock so concurrent block updates are not lost.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.dirtyDrain")) {
            drainDirty();
            if (!dirtyDrain.isEmpty()) {
                for (LongIterator it = dirtyDrain.iterator(); it.hasNext(); ) {
                    long key = it.nextLong();
                    handleDirtySection(key, NO_DIRTY_GROUP);
                }
            }
            if (!dirtyEventDrain.isEmpty()) {
                for (DirtyEvent event : dirtyEventDrain) {
                    handleDirtyEvent(event);
                }
            }
        }
        // Dispatch/drain/build normally runs per render frame (RtComposite → frame()). If no frame has
        // streamed recently — loading screen, no world rendering — drive it from here with the bigger
        // bounded fallback pass so the world still fills.
        if (System.nanoTime() - lastFrameStreamNanos > STREAM_FALLBACK_AFTER_NANOS) {
            stream(ctx, true);
        }
    }

    /** The per-render-frame entry point: run one count-bounded streaming pass. */
    private void frameStream(RtContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        lastFrameStreamNanos = System.nanoTime();
        stream(ctx, false);
    }

    /**
     * One streaming pass: drain completed worker/executor builds, publish ready sections, and dispatch
     * new section snapshots to the worker pool. Per-pass result and dispatch caps bound render-thread
     * work. Skips silently when there is nothing to do (no stats row).
     */
    private void stream(RtContext ctx, boolean fallback) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (reextract.isEmpty() && missing.isEmpty()
                && completedBuilds.isEmpty()
                && !lightGrid.hasCompletions()
                && !lightHierarchyDirty
                && removed.isEmpty() && prepared.isEmpty()) {
            return;
        }
        int pbx = mc.player.getBlockX();
        int pby = mc.player.getBlockY();
        int pbz = mc.player.getBlockZ();
        int pcx = pbx >> 4, pcz = pbz >> 4, psy = pby >> 4;

        ClientChunkCache chunkSource = level.getChunkSource();
        long started = System.nanoTime();
        int queued = missing.size() + reextract.size() + completedBuilds.size();
        long budget = fallback
                ? TerrainStreamBudget.fixedBudgetNanos(CausticaConfig.Rt.Terrain.STREAM_FALLBACK_BUDGET_MS.value())
                : TerrainStreamBudget.adaptiveBudgetNanos(
                        CausticaConfig.Rt.Terrain.STREAM_BUDGET_MS.value(),
                        CausticaConfig.Rt.Terrain.STREAM_BUDGET_MAX_MS.value(), queued,
                        taskTracker.outstanding(), maxInflight());
        long deadline = TerrainStreamBudget.deadline(started, budget);

        // Drain completed GPU builds first — publication is visible fill progress, so it gets priority.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.drainCompletion")) {
            drainCompletedBuilds(ctx, prepared, removed, completionResultsPerPass(), deadline);
        }

        if ((!removed.isEmpty() || !prepared.isEmpty()) && System.nanoTime() < deadline) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.publish")) {
                applyBuildChanges(ctx, prepared, removed, shouldRebase(pbx, pby, pbz), pbx, pby, pbz);
                removed.clear();
                prepared.clear();
            }
        }

        // Swap only a fully uploaded hierarchy. The previous complete generation remains active until
        // this point, so an asynchronous rebuild can never expose a half-populated RIS table.
        if (lightGrid.hasCompletions()) {
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.lightGridPublish")) {
                lightGrid.publishReady(ctx);
            }
        }

        // Snapshot and dispatch a bounded number of new worker-owned section builds.
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("terrain.snapshotDispatch")) {
            DispatchContext dispatch = null;
            int dispatchSlots = Math.min(asyncDispatchPerPass(),
                    Math.max(0, maxInflight() - taskTracker.outstanding()));
            if (dispatchSlots > 0 && !reextract.isEmpty() && System.nanoTime() < deadline) {
                if (dispatch == null) {
                    dispatch = dispatchContext(ctx, level);
                }
                dispatchSlots -= dispatchReextract(dispatch, chunkSource, dispatchSlots, pcx, psy, pcz, deadline);
            }
            if (dispatchSlots > 0 && !missing.isEmpty() && System.nanoTime() < deadline) {
                if (dispatch == null) {
                    dispatch = dispatchContext(ctx, level);
                }
                dispatchMissingBuilds(dispatch, chunkSource, dispatchSlots, pcx, psy, pcz, deadline);
            }
        }
        flushLightHierarchyUpdate(ctx);
        recordTerrainTelemetry(ctx);
    }

    private void recordTerrainTelemetry(RtContext ctx) {
        RtFrameStats.FRAME.count("terrainOutstandingTasks", taskTracker.outstanding());
        RtFrameStats.FRAME.count("terrainOutstandingLimit", maxInflight());
        RtFrameStats.FRAME.count("terrainQueuedMissing", missing.size());
        RtFrameStats.FRAME.count("terrainQueuedReextract", reextract.size());
        RtFrameStats.FRAME.count("terrainResidentSections", resident.size());
        RtFrameStats.FRAME.count("terrainGpuQueueDepth", ctx.gpuExecutor().queuedBuilds());
        RtFrameStats.FRAME.count("terrainBuildsSubmitted", buildsSubmittedSinceSample.getAndSet(0L));
        RtFrameStats.FRAME.count("terrainBuildLatencyNanos", lastBuildLatencyNanos);
        RtFrameStats.FRAME.count("terrainActiveCompactionQueries", RtAccel.activeTerrainCompactionQueries());
    }

    private void syncDesiredWindow(ClientChunkCache chunkSource, int pcx, int psy, int pcz,
                                   int radius, int loY, int hiY, List<SectionGeom> removed) {
        if (!windowValid || windowRadius != radius || windowLoY != loY || windowHiY != hiY
                || Math.abs(pcx - windowPcx) > radius || Math.abs(pcz - windowPcz) > radius) {
            // First window, a shape change, or a jump past any overlap (teleport) — build from scratch.
            rebuildDesiredWindow(chunkSource, pcx, pcz, radius, loY, hiY, removed);
        } else if (windowPcx != pcx || windowPcz != pcz) {
            slideDesiredWindow(chunkSource, pcx, pcz, radius, loY, hiY, removed);
        } else {
            pollLoadedColumns(chunkSource, loY, hiY, removed);
        }
    }

    private void rebuildDesiredWindow(ClientChunkCache chunkSource, int pcx, int pcz,
                                      int radius, int loY, int hiY, List<SectionGeom> removed) {
        snapshots.clear(); // teleport / shape change — no per-column eviction diff, drop everything
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        missingIndex.clear();

        for (int scx = pcx - radius; scx <= pcx + radius; scx++) {
            for (int scz = pcz - radius; scz <= pcz + radius; scz++) {
                long column = columnKey(scx, scz);
                desiredColumns.add(column);
                if (!chunkSource.hasChunk(scx, scz)) {
                    continue;
                }
                loadedColumns.add(column);
                addDesiredColumnSections(scx, scz, loY, hiY);
            }
        }

        pruneUndesired(removed);
        removeKeysNotIn(queuedReextract, desired);
        windowValid = true;
        windowPcx = pcx;
        windowPcz = pcz;
        windowRadius = radius;
        windowLoY = loY;
        windowHiY = hiY;
    }

    /**
     * Slide the desired window after a section-boundary crossing: touch only the columns entering and
     * leaving the (2r+1)² rect instead of rebuilding it (the rebuild — ~100k hash inserts + a full
     * resident prune + a queue sort at r=32 — was a 10–30 ms hitch every 16 blocks of flight).
     */
    private void slideDesiredWindow(ClientChunkCache chunkSource, int pcx, int pcz,
                                    int radius, int loY, int hiY, List<SectionGeom> removed) {
        int newMinX = pcx - radius, newMaxX = pcx + radius;
        int newMinZ = pcz - radius, newMaxZ = pcz + radius;
        for (int scx = windowPcx - radius; scx <= windowPcx + radius; scx++) {
            boolean xOutside = scx < newMinX || scx > newMaxX;
            for (int scz = windowPcz - radius; scz <= windowPcz + radius; scz++) {
                if (!xOutside && scz >= newMinZ && scz <= newMaxZ) {
                    continue; // still in the window
                }
                long column = columnKey(scx, scz);
                desiredColumns.remove(column);
                // Only loaded columns ever had desired sections / queued work (see addDesiredColumnSections
                // call sites) — nothing to remove for a never-loaded column.
                if (loadedColumns.remove(column)) {
                    removeDesiredColumnSections(scx, scz, loY, hiY, removed);
                }
            }
        }
        int oldMinX = windowPcx - radius, oldMaxX = windowPcx + radius;
        int oldMinZ = windowPcz - radius, oldMaxZ = windowPcz + radius;
        for (int scx = newMinX; scx <= newMaxX; scx++) {
            boolean xOutside = scx < oldMinX || scx > oldMaxX;
            for (int scz = newMinZ; scz <= newMaxZ; scz++) {
                if (!xOutside && scz >= oldMinZ && scz <= oldMaxZ) {
                    continue;
                }
                long column = columnKey(scx, scz);
                desiredColumns.add(column);
                if (chunkSource.hasChunk(scx, scz)) {
                    loadedColumns.add(column);
                    addDesiredColumnSections(scx, scz, loY, hiY);
                }
            }
        }
        windowPcx = pcx;
        windowPcz = pcz;
    }

    private void pollLoadedColumns(ClientChunkCache chunkSource, int loY, int hiY, List<SectionGeom> removed) {
        for (LongIterator it = desiredColumns.iterator(); it.hasNext(); ) {
            long column = it.nextLong();
            int scx = columnX(column);
            int scz = columnZ(column);
            boolean loaded = chunkSource.hasChunk(scx, scz);
            boolean wasLoaded = loadedColumns.contains(column);
            if (loaded == wasLoaded) {
                continue;
            }
            if (loaded) {
                loadedColumns.add(column);
                addDesiredColumnSections(scx, scz, loY, hiY);
            } else {
                loadedColumns.remove(column);
                removeDesiredColumnSections(scx, scz, loY, hiY, removed);
            }
        }
    }

    private void addDesiredColumnSections(int scx, int scz, int loY, int hiY) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            desired.add(key);
            enqueueMissingIfNeeded(key);
        }
    }

    private void removeDesiredColumnSections(int scx, int scz, int loY, int hiY, List<SectionGeom> removed) {
        for (int scy = loY; scy <= hiY; scy++) {
            long key = sectionKey(scx, scy, scz);
            // Unloaded or out of the window — the chunk may reload with different data, so the cached
            // snapshot can't be trusted past this point.
            snapshots.invalidate(key);
            desired.remove(key);
            clearQueuedWork(key, true);
            invalidateInFlight(key);
            empty.remove(key);
            SectionGeom g = resident.remove(key);
            if (g != null) {
                removed.add(g);
            }
        }
    }

    private void pruneUndesired(List<SectionGeom> removed) {
        for (ObjectIterator<Long2ObjectMap.Entry<SectionGeom>> it = resident.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<SectionGeom> e = it.next();
            if (!desired.contains(e.getLongKey())) {
                removed.add(e.getValue());
                it.remove();
            }
        }
        removeKeysNotIn(empty, desired);
        removeInFlightNotIn(desired);
        removeQueuedGroupsNotIn(desired);
    }

    private void handleDirtyEvent(DirtyEvent event) {
        int groupMembers = 0;
        for (LongIterator it = event.keys().iterator(); it.hasNext(); ) {
            if (canGroupDirtySection(it.nextLong())) {
                groupMembers++;
            }
        }

        long groupId = NO_DIRTY_GROUP;
        if (groupMembers > 1) {
            groupId = event.groupId();
            dirtyGroups.put(groupId, new DirtyGroup(groupId, groupMembers, event.keys()));
        }

        for (LongIterator it = event.keys().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            long memberGroup = groupId != NO_DIRTY_GROUP
                    && dirtyGroups.containsKey(groupId)
                    && canGroupDirtySection(key) ? groupId : NO_DIRTY_GROUP;
            if (!handleDirtySection(key, memberGroup) && memberGroup != NO_DIRTY_GROUP) {
                cancelDirtyGroup(memberGroup);
            }
        }
    }

    private boolean canGroupDirtySection(long key) {
        return desired.contains(key) && (resident.containsKey(key) || empty.contains(key));
    }

    private boolean handleDirtySection(long key, long dirtyGroup) {
        snapshots.invalidate(key); // block data changed — the cached palette snapshot is stale
        boolean wasEmpty = empty.remove(key);
        if (wasEmpty && dirtyGroup != NO_DIRTY_GROUP) {
            DirtyGroup group = dirtyGroups.get(dirtyGroup);
            if (group != null) {
                group.restoreEmptyKeys.add(key);
            }
        }
        invalidateInFlight(key); // invalidate any in-flight build of the now-stale section
        if (!desired.contains(key)) {
            clearQueuedWork(key, true);
            return false;
        }
        // Keep the old geometry resident + traced; re-dispatch and swap when the new mesh is ready
        // (no eviction gap -> no flicker). Non-resident dirty keys re-enter the normal missing queue.
        SectionGeom g = resident.get(key);
        if (g != null) {
            if (queuedReextract.add(key)) {
                reextract.add(key);
            }
            setQueuedGroup(key, dirtyGroup);
            return true;
        } else {
            return enqueueMissing(key, dirtyGroup);
        }
    }

    private boolean enqueueMissingIfNeeded(long key) {
        if (resident.containsKey(key) || empty.contains(key) || taskTracker.containsCurrent(key)) {
            return false;
        }
        if (missingIndex.get(key) != NO_MISSING_INDEX) {
            return false;
        }
        setQueuedGroup(key, NO_DIRTY_GROUP);
        missingIndex.put(key, missing.size());
        missing.add(key);
        return true;
    }

    private boolean enqueueMissing(long key, long dirtyGroup) {
        if (resident.containsKey(key) || empty.contains(key) || taskTracker.containsCurrent(key)) {
            return false;
        }
        // `missing` is unsorted; dispatch ranks it directly by distance from the player.
        int index = missingIndex.get(key);
        if (index == NO_MISSING_INDEX) {
            missingIndex.put(key, missing.size());
            missing.add(key);
        }
        setQueuedGroup(key, dirtyGroup);
        return true;
    }

    private void clearQueuedWork(long key, boolean cancelGroup) {
        removeMissing(key);
        queuedReextract.remove(key);
        clearQueuedGroup(key, cancelGroup);
    }

    private void setQueuedGroup(long key, long groupId) {
        long oldGroup = groupId == NO_DIRTY_GROUP ? queuedDirtyGroup.remove(key) : queuedDirtyGroup.put(key, groupId);
        if (oldGroup != NO_DIRTY_GROUP && oldGroup != groupId) {
            cancelDirtyGroup(oldGroup);
        }
    }

    private void clearQueuedGroup(long key, boolean cancelGroup) {
        long groupId = queuedDirtyGroup.remove(key);
        if (cancelGroup && groupId != NO_DIRTY_GROUP) {
            cancelDirtyGroup(groupId);
        }
    }

    private boolean isQueuedAnywhere(long key) {
        return missingIndex.get(key) != NO_MISSING_INDEX
                || queuedReextract.contains(key);
    }

    /** Remove an unsorted missing entry in O(1) by moving the last entry into its slot. */
    private void removeMissing(long key) {
        int index = missingIndex.remove(key);
        if (index == NO_MISSING_INDEX) {
            return;
        }
        int lastIndex = missing.size() - 1;
        if (index != lastIndex) {
            long movedKey = missing.getLong(lastIndex);
            missing.set(index, movedKey);
            missingIndex.put(movedKey, index);
        }
        missing.removeLong(lastIndex);
    }

    private void invalidateInFlight(long key) {
        TerrainTaskTracker.Ticket ticket = taskTracker.cancelCurrent(key);
        long groupId = inFlightDirtyGroup.remove(key);
        if (ticket != null) {
            cancelledTasks.incrementAndGet();
            RtFrameStats.FRAME.count("terrainCancelledTasks", 1);
        }
        if (ticket != null && groupId != NO_DIRTY_GROUP) {
            cancelDirtyGroup(groupId);
        }
    }

    private void removeInFlightNotIn(LongOpenHashSet keep) {
        LongArrayList cancelled = taskTracker.cancelNotIn(keep);
        cancelledTasks.addAndGet(cancelled.size());
        RtFrameStats.FRAME.count("terrainCancelledTasks", cancelled.size());
        for (LongIterator it = cancelled.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            long groupId = inFlightDirtyGroup.remove(key);
            if (groupId != NO_DIRTY_GROUP) {
                cancelDirtyGroup(groupId);
            }
        }
    }

    private void removeQueuedGroupsNotIn(LongOpenHashSet keep) {
        LongArrayList cancelGroups = new LongArrayList();
        for (LongIterator it = queuedDirtyGroup.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!keep.contains(key)) {
                long groupId = queuedDirtyGroup.get(key);
                it.remove();
                if (groupId != NO_DIRTY_GROUP) {
                    cancelGroups.add(groupId);
                }
            }
        }
        for (LongIterator it = cancelGroups.iterator(); it.hasNext(); ) {
            cancelDirtyGroup(it.nextLong());
        }
    }

    /**
     * Whether a section may be built now: all eight of its horizontal neighbour chunks are loaded. We
     * extract using vanilla's model/fluid renderers, which read across chunk borders for cull faces and
     * (for fluids) the surrounding blocks that set a water surface's edge/corner heights. If a border
     * section is built while a neighbour chunk is still missing, those reads return air — the
     * neighbour-facing faces and the shared water surface come out wrong, and nothing re-dirties the
     * section once the chunk arrives (a bulk chunk load fires no per-block update). Deferring the build
     * until every neighbour is present makes the first build correct.
     *
     * <p>We deliberately gate on <em>all</em> neighbours, not just those inside the RT view window. A
     * section at the window edge can have an outward neighbour that is outside the current vanilla-loaded
     * area. Without this, the edge section would mesh against air, then show a seam once the player moves
     * and that neighbour becomes interior and is rendered.
     */
    private boolean neighborChunksReady(ClientChunkCache chunkSource, int scx, int scz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!chunkSource.hasChunk(scx + dx, scz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int horizontalChunks(Minecraft mc) {
        return Math.max(1, mc.options.getEffectiveRenderDistance());
    }

    private void drainDirty() {
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        if (!dirtyPending) {
            return;
        }
        synchronized (dirtyLock) {
            for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
                dirtyDrain.add(it.nextLong());
            }
            dirtyEventDrain.addAll(dirtyEvents);
            dirty.clear();
            dirtyEvents.clear();
            dirtyPending = false;
        }
    }

    private static void removeKeysNotIn(LongSet keys, LongOpenHashSet keep) {
        for (LongIterator it = keys.iterator(); it.hasNext(); ) {
            if (!keep.contains(it.nextLong())) {
                it.remove();
            }
        }
    }

    /**
     * Snapshot each missing section on the render thread and submit its build to the worker pool. The
     * per-task meshing objects (renderer / captures / MutableBlockPos) are allocated inside the task so
     * nothing mutable is shared across threads; the captured {@code region}, model sets and block
     * colors are read-only. Capped by the configured dispatch count.
     */
    private static DispatchContext dispatchContext(RtContext ctx, ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        return new DispatchContext(ctx, level,
                mc.getModelManager().getBlockStateModelSet(), mc.getModelManager().getFluidStateModelSet(),
                mc.getBlockColors(), mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).spriteFinder());
    }

    /**
     * Dispatch the best missing sections. {@code missing} is an indexed unsorted queue; every call scans
     * its contiguous key array while collecting the top candidates by
     * (column-distance², |Δy|) into a bounded
     * max-heap — nearest-first order continuously tracks the player with no sort anywhere (the old
     * sorted-queue approach re-sorted the whole list on every window rebuild, a multi-ms spike at high
     * render distance). Ranking by <b>column</b> first makes the dispatch order column-coherent: all
     * sections of a column share the same 3×3 chunk neighbourhood, and the pass-scoped
     * {@link RenderRegionCache} dedupes {@code SectionCopy}s, so after a column's first snapshot the rest
     * are nearly free.
     */
    private void dispatchMissingBuilds(DispatchContext dispatch, ClientChunkCache chunkSource, int remaining,
                                       int pcx, int psy, int pcz, long deadline) {
        if (missing.isEmpty() || remaining <= 0) {
            return;
        }
        // Over-collect 2x the remaining slots so candidates skipped for unready neighbour chunks (they cluster at
        // the window edge) don't leave dispatch slots idle.
        int k = Math.min(missing.size(), Math.max(8, remaining * 2));

        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        for (int read = 0, n = missing.size(); read < n && System.nanoTime() < deadline; read++) {
            long key = missing.getLong(read);
            // rank = columnDist²(16+) | |Δy|(0..15): column-major nearest-first.
            long rank = distanceRank(key, pcx, psy, pcz);
            if (heapSize < k) {
                heapRank[heapSize] = rank;
                heapKey[heapSize] = key;
                siftUp(heapRank, heapKey, heapSize++);
            } else if (rank < heapRank[0]) {
                heapRank[0] = rank;
                heapKey[0] = key;
                siftDown(heapRank, heapKey, heapSize, 0);
            }
        }
        // Heapsort the candidates ascending (best first), then dispatch up to the per-pass cap.
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        for (int i = 0; i < heapSize && remaining > 0 && System.nanoTime() < deadline; i++) {
            long key = heapKey[i];
            if (!desired.contains(key) || resident.containsKey(key) || empty.contains(key)
                    || taskTracker.containsCurrent(key)) {
                removeMissing(key);
                clearQueuedGroup(key, true);
                continue;
            }
            int sx = sectionX(key);
            int sz = sectionZ(key);
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                if (benchmarkTelemetryEnabled) benchmarkNeighborBlocked.increment();
                continue; // stays queued; dispatched once the neighbours load
            }
            removeMissing(key);
            remaining--;
            dispatchSectionBuild(dispatch, key, sx, sectionY(key), sz);
        }
    }

    /** Max-heap sift-up on parallel (rank, key) arrays — worst candidate at the root. */
    private static void siftUp(long[] rank, long[] key, int i) {
        while (i > 0) {
            int parent = (i - 1) >> 1;
            if (rank[parent] >= rank[i]) {
                return;
            }
            long r = rank[parent]; rank[parent] = rank[i]; rank[i] = r;
            long q = key[parent]; key[parent] = key[i]; key[i] = q;
            i = parent;
        }
    }

    private static void siftDown(long[] rank, long[] key, int size, int i) {
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int big = i;
            if (left < size && rank[left] > rank[big]) {
                big = left;
            }
            if (right < size && rank[right] > rank[big]) {
                big = right;
            }
            if (big == i) {
                return;
            }
            long r = rank[big]; rank[big] = rank[i]; rank[i] = r;
            long q = key[big]; key[big] = key[i]; key[i] = q;
            i = big;
        }
    }

    /**
     * Re-extraction of edited (dirty) sections that are still resident: dispatch a fresh
     * rebuild while leaving the old geometry resident and traced, so it's swapped — never evicted
     * with a gap — when the new mesh is published and the replaced geometry is retired. This
     * is what prevents the visible flicker on block updates that plain eviction would cause.
     */
    private int dispatchReextract(DispatchContext dispatch, ClientChunkCache chunkSource, int remaining,
                                  int pcx, int psy, int pcz, long deadline) {
        if (reextract.isEmpty()) {
            return 0;
        }
        int k = Math.min(reextract.size(), Math.max(8, remaining * 2));
        long[] heapRank = new long[k];
        long[] heapKey = new long[k];
        int heapSize = 0;
        for (int i = 0; i < reextract.size() && System.nanoTime() < deadline; ) {
            long key = reextract.getLong(i);
            if (!queuedReextract.contains(key)) {
                if (!isQueuedAnywhere(key)) {
                    clearQueuedGroup(key, true);
                }
                removeUnsorted(reextract, i);
                continue;
            }
            // Skip ones the window pass freed this tick (out of view) — they're being retired, not rebuilt.
            SectionGeom g = resident.get(key);
            if (g == null || !desired.contains(key) || taskTracker.containsCurrent(key)) {
                queuedReextract.remove(key);
                clearQueuedGroup(key, true);
                removeUnsorted(reextract, i);
                continue;
            }
            int sx = g.sx >> 4;
            int sz = g.sz >> 4;
            if (!neighborChunksReady(chunkSource, sx, sz)) {
                i++;
                continue;
            }
            long rank = distanceRank(key, pcx, psy, pcz);
            if (heapSize < k) {
                heapRank[heapSize] = rank;
                heapKey[heapSize] = key;
                siftUp(heapRank, heapKey, heapSize++);
            } else if (rank < heapRank[0]) {
                heapRank[0] = rank;
                heapKey[0] = key;
                siftDown(heapRank, heapKey, heapSize, 0);
            }
            i++;
        }
        for (int end = heapSize - 1; end > 0; end--) {
            long r = heapRank[0]; heapRank[0] = heapRank[end]; heapRank[end] = r;
            long q = heapKey[0]; heapKey[0] = heapKey[end]; heapKey[end] = q;
            siftDown(heapRank, heapKey, end, 0);
        }
        int dispatched = 0;
        for (int i = 0; i < heapSize && remaining > 0 && System.nanoTime() < deadline; i++) {
            long key = heapKey[i];
            SectionGeom g = resident.get(key);
            queuedReextract.remove(key);
            removeUnsorted(reextract, reextract.indexOf(key));
            dispatchSectionBuild(dispatch, key, g.sx >> 4, g.sy >> 4, g.sz >> 4);
            remaining--;
            dispatched++;
        }
        return dispatched;
    }

    private static long distanceRank(long key, int pcx, int psy, int pcz) {
        int dx = sectionX(key) - pcx;
        int dz = sectionZ(key) - pcz;
        long colDist2 = (long) dx * dx + (long) dz * dz;
        long dy = Math.min(0xFFFF, Math.abs(sectionY(key) - psy));
        return (colDist2 << 16) | dy;
    }

    private static void removeUnsorted(LongArrayList queue, int index) {
        int last = queue.size() - 1;
        if (index != last) {
            queue.set(index, queue.getLong(last));
        }
        queue.removeLong(last);
    }

    /** Snapshot one section and dispatch its complete worker → GPU build lifecycle. */
    private void dispatchSectionBuild(DispatchContext dispatch, long key, int sx, int sy, int sz) {
        RtFrameStats.FRAME.count("sectionsSnapshotted", 1);
        long snapshotStart = benchmarkTelemetryEnabled ? System.nanoTime() : 0L;
        RtSectionSnapshots.Region region = snapshots.createRegion(dispatch.level(), sx, sy, sz);
        if (benchmarkTelemetryEnabled) {
            benchmarkSnapshotNanos.add(System.nanoTime() - snapshotStart);
            benchmarkDispatched.increment();
        }
        long token = ++buildToken;
        long dirtyGroup = queuedDirtyGroup.remove(key);
        if (dirtyGroup != NO_DIRTY_GROUP && !dirtyGroups.containsKey(dirtyGroup)) {
            dirtyGroup = NO_DIRTY_GROUP;
        }
        RtMaterialRegistry.Snapshot materialSnapshot = RtMaterialRegistry.INSTANCE.requireSnapshot();
        TerrainTaskTracker.Ticket ticket = taskTracker.accept(key, token);
        SectionTask task = new SectionTask(ticket, key, token, sx << 4, sy << 4, sz << 4, dirtyGroup,
                terrainEpoch, materialSnapshot.epoch());
        if (dirtyGroup != NO_DIRTY_GROUP) {
            inFlightDirtyGroup.put(key, dirtyGroup);
        } else {
            inFlightDirtyGroup.remove(key);
        }
        beginActiveTask();
        try {
            RtWorkerPool.INSTANCE.submit(() -> {
                long cpuStart = benchmarkTelemetryEnabled ? System.nanoTime() : 0L;
                if (benchmarkTelemetryEnabled) benchmarkWorkerActive.incrementAndGet();
                try {
                    if (!isTaskCurrent(task)) {
                        completeTask(task, null, null, null);
                        return;
                    }
                    WorkerTessState ws = WORKER_TESS.get(); // thread-confined; reset per task, arrays amortized
                    ws.reset(dispatch.blockColors(), dispatch.blockSpriteFinder());
                    FluidRenderer fluidRenderer = new FluidRenderer(dispatch.fluidModelSet());
                    CpuSection cpu = buildCpuSection(region, dispatch.modelSet(), ws.blockEmitter, ws.blockRandom,
                            ws.capture,
                            fluidRenderer, ws.fluidCapture, ws.mesh, ws.pos, materialSnapshot, sx, sy, sz);
                    if (!isTaskCurrent(task)) {
                        completeTask(task, null, null, null);
                        return;
                    }
                    PackedSection packed = cpu.packed();
                    if (packed == null) {
                        completeTask(task, null, null, null);
                    } else {
                        PreparedSection prepared = RtSectionBuilder.prepare(dispatch.ctx(), packed,
                                cpu.opacityMicromap(), task.key, task.sox, task.soy, task.soz);
                        if (!isTaskCurrent(task)) {
                            destroyPreparedSection(prepared);
                            completeTask(task, null, null, null);
                            return;
                        }
                        try {
                            submitTerrainBuild(dispatch.ctx(), task, prepared);
                        } catch (Throwable t) {
                            RtSectionBuilder.destroy(prepared);
                            throw t;
                        }
                    }
                } catch (Throwable t) {
                    completeTask(task, null, null, t);
                    throw t;
                } finally {
                    if (cpuStart != 0L) {
                        benchmarkCpuNanos.add(System.nanoTime() - cpuStart);
                        benchmarkCpuCompleted.increment();
                        benchmarkWorkerActive.decrementAndGet();
                    }
                }
            });
        } catch (Throwable t) {
            taskTracker.cancelCurrent(key);
            taskTracker.retire(ticket);
            inFlightDirtyGroup.remove(key);
            finishActiveTask();
            throw t;
        }
    }

    /** Build/query, then compact-copy a terrain BLAS before making it eligible for publication. */
    private void submitTerrainBuild(RtContext ctx, SectionTask task, PreparedSection prepared) {
        if (benchmarkTelemetryEnabled) task.gpuStartedNanos = System.nanoTime();
        buildsSubmitted.incrementAndGet();
        buildsSubmittedSinceSample.incrementAndGet();
        ctx.gpuExecutor().submit(
                () -> !isTaskCurrent(task),
                cmd -> {
                    RtSectionBuilder.recordUpload(cmd, prepared);
                    RtAccel.recordBlasBuilds(ctx, cmd, List.of(prepared.blas()));
                },
                () -> {
                    RtAccel.freeBlasScratch(List.of(prepared.blas()));
                    prepared.releaseUpload();
                },
                (build, failure) -> {
                    if (build != null && failure == null) {
                        lastBuildLatencyNanos = build.ageNanos();
                    }
                    if (failure != null) {
                        completeTask(task, prepared, build, failure);
                        return;
                    }
                    if (!isTaskCurrent(task)) {
                        completeTask(task, prepared, build, null);
                        return;
                    }
                    // The immutable source BLAS is already complete and traceable. Publish it directly;
                    // the optional compact-size query/copy phase has produced repeatable device loss on
                    // the current NVIDIA driver while startup terrain builds overlap graphics work.
                    prepared.releaseBuildInputs();
                    completeTask(task, prepared, build, null);
                });
    }

    private void completeTask(SectionTask task, PreparedSection prepared, RtGpuExecutor.Build build, Throwable failure) {
        completeTask(new SectionResult(task, prepared, build, failure));
    }

    private void completeTask(SectionResult result) {
        try {
            long gpuStarted = result.task().gpuStartedNanos;
            if (gpuStarted != 0L) {
                result.task().gpuStartedNanos = 0L;
                benchmarkGpuNanos.add(System.nanoTime() - gpuStarted);
                benchmarkGpuCompleted.increment();
            }
            completedBuilds.add(result);
        } finally {
            finishActiveTask();
        }
    }

    private boolean isTaskCurrent(SectionTask task) {
        return task.terrainEpoch == terrainEpoch && !task.ticket.cancelled();
    }

    private void beginActiveTask() {
        synchronized (activeTaskLock) {
            activeTasks++;
        }
    }

    private void finishActiveTask() {
        synchronized (activeTaskLock) {
            if (--activeTasks < 0) {
                throw new IllegalStateException("RT terrain active-task underflow");
            }
            if (activeTasks == 0) {
                activeTaskLock.notifyAll();
            }
        }
    }

    private void awaitActiveTasks() {
        synchronized (activeTaskLock) {
            while (activeTasks != 0) {
                try {
                    activeTaskLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while joining RT terrain tasks", e);
                }
            }
        }
    }

    /**
     * Publish terminal worker/executor results (up to the configured result count per pass). A task
     * whose ticket is no longer current is stale and its unpublished native result is
     * destroyed instead of entering the table.
     */
    private void drainCompletedBuilds(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                      int resultCap, long deadline) {
        int remaining = resultCap;
        while (remaining > 0 && System.nanoTime() < deadline) {
            SectionResult result = completedBuilds.poll();
            if (result == null) {
                break;
            }
            remaining--;
            SectionTask task = result.task();
            boolean publicationPending = false;
            try {
                boolean tokenValid = taskTracker.isCurrent(task.ticket);
                boolean materialValid = task.materialEpoch == RtMaterialRegistry.INSTANCE.epoch();
                boolean valid = tokenValid && task.terrainEpoch == terrainEpoch && materialValid;
                if (!valid) {
                    discardedBuilds.incrementAndGet();
                    RtFrameStats.FRAME.count("terrainDiscardedBuilds", 1);
                    if (tokenValid) {
                        taskTracker.cancelCurrent(task.key);
                        long staleGroup = inFlightDirtyGroup.remove(task.key);
                        if (staleGroup != NO_DIRTY_GROUP) cancelDirtyGroup(staleGroup);
                        enqueueMissingIfNeeded(task.key);
                        if (!materialValid) {
                            RtFrameStats.FRAME.count("terrainMaterialEpochRejects", 1);
                        }
                    }
                    destroyCompletedResult(ctx, result);
                    continue;
                }
                long dirtyGroup = inFlightDirtyGroup.remove(task.key);
                if (result.failure() != null) {
                    if (result.prepared() != null) {
                        destroyPreparedSection(result.prepared());
                    }
                    if (dirtyGroup != NO_DIRTY_GROUP) {
                        cancelDirtyGroup(dirtyGroup);
                    }
                    throw new RuntimeException("RT terrain section build failed for section "
                            + (task.sox >> 4) + "," + (task.soy >> 4) + "," + (task.soz >> 4),
                            result.failure());
                }
                PreparedSection built = result.prepared();
                if (built == null && benchmarkTelemetryEnabled) benchmarkEmptyCompleted.increment();
                if (built != null) {
                    try {
                        ctx.gpuExecutor().markPublished(result.build());
                        RtFrameStats.FRAME.count("terrainBuildsCompleted", 1);
                    } catch (Throwable t) {
                        RtSectionBuilder.destroy(built);
                        if (dirtyGroup != NO_DIRTY_GROUP) {
                            cancelDirtyGroup(dirtyGroup);
                        }
                        throw new RuntimeException("RT terrain GPU build failed for section "
                                + (task.sox >> 4) + "," + (task.soy >> 4) + "," + (task.soz >> 4), t);
                    }
                    publicationTickets.put(built, task.ticket);
                    publicationPending = true;
                }
                if (dirtyGroup != NO_DIRTY_GROUP && dirtyGroups.containsKey(dirtyGroup)) {
                    DirtyGroup group = dirtyGroups.get(dirtyGroup);
                    if (built == null) {
                        SectionGeom prev = resident.get(task.key);
                        if (prev != null) {
                            group.removed.add(prev);
                        } else {
                            group.restoreEmptyKeys.add(task.key);
                        }
                        group.emptyKeys.add(task.key);
                    } else {
                        empty.remove(task.key);
                        group.prepared.add(built);
                    }
                    completeDirtyGroupMember(group, prepared, removed);
                } else {
                    if (built == null) {
                        // Legitimately empty (air or fully-enclosed). If this was an in-place re-extract whose new
                        // state is empty, evict the old geom and retire it in this publish pass.
                        SectionGeom prev = resident.remove(task.key);
                        if (prev != null) {
                            removed.add(prev);
                        }
                        empty.add(task.key);
                    } else {
                        empty.remove(task.key);
                        prepared.add(built);
                    }
                }
            } finally {
                if (!publicationPending) {
                    taskTracker.retire(task.ticket);
                }
            }
        }
    }

    private void destroyCompletedResult(RtContext ctx, SectionResult result) {
        if (result.prepared() == null) {
            return;
        }
        destroyPreparedSection(result.prepared());
    }

    private void completeDirtyGroupMember(DirtyGroup group, List<PreparedSection> prepared, List<SectionGeom> removed) {
        if (--group.remaining > 0) {
            return;
        }
        dirtyGroups.remove(group.id);
        prepared.addAll(group.prepared);
        removed.addAll(group.removed);
        for (LongIterator it = group.emptyKeys.iterator(); it.hasNext(); ) {
            empty.add(it.nextLong());
        }
    }

    private void cancelDirtyGroup(long groupId) {
        DirtyGroup group = dirtyGroups.remove(groupId);
        if (group == null) {
            return;
        }
        for (PreparedSection ps : group.prepared) {
            retirePublicationTicket(ps);
            destroyPreparedSection(ps);
        }
        for (LongIterator it = group.keys.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (queuedDirtyGroup.get(key) == groupId) {
                queuedDirtyGroup.remove(key);
            }
            if (inFlightDirtyGroup.get(key) == groupId) {
                inFlightDirtyGroup.remove(key);
                TerrainTaskTracker.Ticket ticket = taskTracker.cancelCurrent(key);
                if (ticket != null) {
                    cancelledTasks.incrementAndGet();
                    RtFrameStats.FRAME.count("terrainCancelledTasks", 1);
                }
            }
        }
        // Atomic publication was abandoned, but every desired member must still converge. Requeue the
        // members independently instead of leaving old geometry or an old empty classification indefinitely.
        for (LongIterator it = group.keys.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!desired.contains(key) || taskTracker.containsCurrent(key)) {
                continue;
            }
            if (resident.containsKey(key)) {
                if (queuedReextract.add(key)) {
                    reextract.add(key);
                }
            } else {
                empty.remove(key);
                enqueueMissingIfNeeded(key);
            }
        }
    }

    private void cancelAllDirtyGroups() {
        if (dirtyGroups.isEmpty()) {
            return;
        }
        for (DirtyGroup group : dirtyGroups.values()) {
            for (PreparedSection ps : group.prepared) {
                retirePublicationTicket(ps);
                destroyPreparedSection(ps);
            }
        }
        dirtyGroups.clear();
        queuedDirtyGroup.clear();
        inFlightDirtyGroup.clear();
    }

    private void destroyPreparedSection(PreparedSection ps) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            RtSectionBuilder.destroy(ps);
        } else {
            ctx.gpuExecutor().enqueueDestroyUnpublished(() -> RtSectionBuilder.destroy(ps));
        }
    }

    private void retirePublicationTicket(PreparedSection ps) {
        TerrainTaskTracker.Ticket ticket = publicationTickets.remove(ps);
        if (ticket != null) {
            taskTracker.retire(ticket);
        }
    }

    /** Per-tick render-thread snapshot dependencies shared by reextract + missing dispatch. */
    private record DispatchContext(RtContext ctx, ClientLevel level, BlockStateModelSet modelSet,
                                   FluidStateModelSet fluidModelSet, BlockColors blockColors,
                                   SpriteFinder blockSpriteFinder) {
    }

    private record DirtyEvent(long groupId, LongArrayList keys) {
    }

    private static final class DirtyGroup {
        final long id;
        final LongArrayList keys;
        final ArrayList<PreparedSection> prepared = new ArrayList<>();
        final ArrayList<SectionGeom> removed = new ArrayList<>();
        final LongArrayList emptyKeys = new LongArrayList();
        final LongArrayList restoreEmptyKeys = new LongArrayList();
        int remaining;

        DirtyGroup(long id, int remaining, LongArrayList keys) {
            this.id = id;
            this.remaining = remaining;
            this.keys = new LongArrayList(keys);
        }
    }

    /** An outstanding worker/GPU section build; terminal results enter one completion queue. */
    private static final class SectionTask {
        final TerrainTaskTracker.Ticket ticket;
        final long key;
        final long token;
        final int sox;
        final int soy;
        final int soz;
        final long dirtyGroup;
        final long terrainEpoch;
        final long materialEpoch;
        volatile long gpuStartedNanos;
        SectionTask(TerrainTaskTracker.Ticket ticket, long key, long token, int sox, int soy, int soz, long dirtyGroup,
                    long terrainEpoch, long materialEpoch) {
            this.ticket = ticket;
            this.key = key;
            this.token = token;
            this.sox = sox;
            this.soy = soy;
            this.soz = soz;
            this.dirtyGroup = dirtyGroup;
            this.terrainEpoch = terrainEpoch;
            this.materialEpoch = materialEpoch;
        }
    }

    private record SectionResult(SectionTask task, PreparedSection prepared,
                                 RtGpuExecutor.Build build, Throwable failure) {
    }

    private boolean shouldRebase(int rbx, int rby, int rbz) {
        return !ready || table.buffer == null || table.instances == null
                || Math.abs(rbx - blockX) > rebaseDistanceBlocks()
                || Math.abs(rby - blockY) > rebaseDistanceBlocks()
                || Math.abs(rbz - blockZ) > rebaseDistanceBlocks();
    }

    private void applyBuildChanges(RtContext ctx, List<PreparedSection> prepared, List<SectionGeom> removed,
                                   boolean rebase, int rbx, int rby, int rbz) {
        long lastGraphicsUse = ctx.gpuExecutor().latestGraphicsUseValue();
        int baseX = rebase ? rbx : blockX;
        int baseY = rebase ? rby : blockY;
        int baseZ = rebase ? rbz : blockZ;

        boolean lightsChanged = false;
        for (SectionGeom g : removed) {
            SectionGeom current = resident.get(g.key);
            boolean removesPublishedLights = current == g && hasLights(g.lights);
            int removedLightSlot = removesPublishedLights ? g.slot : -1;
            table.removePublished(resident, published, g);
            lightsChanged |= removesPublishedLights;
            if (removedLightSlot >= 0) lightSections.remove(removedLightSlot);
        }
        retire(ctx, lastGraphicsUse, removed);


        if (!prepared.isEmpty()) {
            Generation oldGeneration = table.beginWriteGeneration(ctx, table.liveSlotCapacity(prepared, resident));
            if (oldGeneration != null) {
                retireGeneration(ctx, lastGraphicsUse, oldGeneration);

            }
        }

        for (PreparedSection ps : prepared) {
            TerrainTaskTracker.Ticket publicationTicket = publicationTickets.remove(ps);
            try {
            SectionGeom g = new SectionGeom(ps.key(), ps.uvs(), ps.material(),
                    ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz(), ps.lights());
            if (!desired.contains(ps.key())) {
                // Left the window while its batched BLAS build was in flight (window sync keeps running
                // during builds). Never published — retire the fresh, unreferenced geometry.
                ctx.gpuExecutor().enqueueDestroyUnpublished(g::destroy);
                discardedBuilds.incrementAndGet();
                RtFrameStats.FRAME.count("terrainDiscardedBuilds", 1);
                continue;
            }
            SectionGeom prev = resident.get(ps.key());
            boolean sectionLightsChanged = !sameLightRecords(prev != null ? prev.lights : null, g.lights);
            lightsChanged |= sectionLightsChanged;
            if (prev != null && prev.slot >= 0) {
                g.slot = prev.slot;
                g.instanceIndex = prev.instanceIndex;
                table.slots.set(g.slot, g);
                resident.put(ps.key(), g);
                table.write(g);
                table.instanceList.set(g.instanceIndex, table.instanceFor(g, baseX, baseY, baseZ));
                retire(ctx, lastGraphicsUse, List.of(prev));
            } else {
                g.slot = table.allocateSlot();
                g.instanceIndex = table.instanceList.size();
                table.slots.set(g.slot, g);
                resident.put(ps.key(), g);
                table.write(g);
                table.instanceList.add(table.instanceFor(g, baseX, baseY, baseZ));
            }
            if (sectionLightsChanged || prev == null) updateLightSection(g);
            published.add(ps.key());
            if (benchmarkTelemetryEnabled) benchmarkPublished.increment();
            buildsPublished.incrementAndGet();
            RtFrameStats.FRAME.count("terrainBuildsPublished", 1);
            RtFrameStats.FRAME.count("sectionsUploaded", 1);
            } finally {
                if (publicationTicket != null) {
                    taskTracker.retire(publicationTicket);
                }
            }
        }
        table.flushWrites();

        if (resident.isEmpty()) {
            Generation emptyGeneration = table.detachGeneration();
            if (emptyGeneration != null) {
                retireGeneration(ctx, lastGraphicsUse, emptyGeneration);
            }
            table.nextSlot = 0;
            table.freeSlots.clear();
            table.slots.clear();
            table.instanceList.clear();
            table.instances = null;
            lightSections.clear();
            published.clear();
            // The instance list + slot registry were just reset, but evicted geometry can still be waiting
            // in the `removed` accumulator (window sync runs while a build is in flight — e.g. a respawn
            // evicts everything at once) or in a dirty group's removed list. Their instanceIndex/slot point
            // into the cleared lists; neutralize them so the eventual removePublishedSection is a no-op
            // (their buffers are still retired normally when the accumulator is consumed).
            for (SectionGeom g : this.removed) {
                g.instanceIndex = -1;
                g.slot = -1;
            }
            for (DirtyGroup group : dirtyGroups.values()) {
                for (SectionGeom g : group.removed) {
                    g.instanceIndex = -1;
                    g.slot = -1;
                }
            }
            // Zero resident sections (e.g. every section just evicted on a respawn) is a transient
            // streaming state, not "no world" — keep tracing (sky/entities only) instead of handing the
            // frame back to vanilla; see ensureEmptyTableReady.
            markLightHierarchyDirty();
            flushLightHierarchyUpdate(ctx);
            ensureEmptyTableReady(ctx);
            return;
        }

        if (rebase) {
            for (int i = 0, n = table.instanceList.size(); i < n; i++) {
                RtAccel.Instance inst = table.instanceList.get(i);
                SectionGeom g = table.slots.get(inst.customIndex());
                table.instanceList.set(i, table.instanceFor(g, baseX, baseY, baseZ));
            }
            blockX = rbx;
            blockY = rby;
            blockZ = rbz;
        }
        table.instances = table.instanceList;
        if (lightsChanged || rebase) {
            markLightHierarchyDirty();
        }
        ready = true;
    }

    private static boolean hasLights(float[] lights) {
        return lights != null && lights.length > 0;
    }

    private void updateLightSection(SectionGeom g) {
        if (!hasLights(g.lights)) {
            lightSections.remove(g.slot);
            return;
        }
        lightSections.put(g.slot, new RtLightHierarchy.SectionInput(g.slot,
                g.sx >> 4, g.sy >> 4, g.sz >> 4, g.lights));
    }

    private void markLightHierarchyDirty() {
        lightHierarchyDirty = true;
    }

    /** Snapshot lit sections only after the previous complete generation has published. */
    private void flushLightHierarchyUpdate(RtContext ctx) {
        if (!lightHierarchyDirty || !lightGrid.isIdle()) return;
        long now = System.nanoTime();
        if (lastLightHierarchyRequestNanos != 0L
                && now - lastLightHierarchyRequestNanos < LIGHT_HIERARCHY_UPDATE_INTERVAL_NANOS) {
            return;
        }
        lightGrid.request(ctx, lightSections.values(), blockX, blockY, blockZ);
        lightHierarchyDirty = false;
        lastLightHierarchyRequestNanos = now;
    }

    /** Keep a valid zero-instance table through transient empty-residency windows. */
    private void ensureEmptyTableReady(RtContext ctx) {
        table.ensureEmpty(ctx);
        ready = true;
    }

    /** Queue old GPU resources until the last graphics submission that could reference them completes. */
    private void retire(RtContext ctx, long lastGraphicsUse, List<SectionGeom> removed) {
        for (SectionGeom g : removed) {
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse, g::destroy);
        }
    }

    private void retireGeneration(RtContext ctx, long lastGraphicsUse, Generation generation) {
        ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse,
                () -> table.recycleGeneration(generation));
    }

    /** Join outstanding worker/GPU tasks and destroy every unpublished terminal result. */
    private void drainTasksForClear(RtContext ctx) {
        // A dead executor cannot make further task progress. Throw on the render thread before waiting;
        // its failure path has already terminally failed every accepted queued build.
        ctx.gpuExecutor().throwIfFailed();
        awaitActiveTasks();
        lightGrid.awaitIdle();
        Throwable failure = null;
        SectionResult result;
        while ((result = completedBuilds.poll()) != null) {
            try {
                if (result.prepared() != null) {
                    destroyPreparedSection(result.prepared());
                }
                if (result.failure() != null && failure == null) {
                    failure = result.failure();
                }
            } finally {
                taskTracker.retire(result.task().ticket);
            }
        }
        taskTracker.cancelAllCurrent();
        inFlightDirtyGroup.clear();
        if (failure != null) {
            throw new RuntimeException("RT terrain worker/build failed during teardown", failure);
        }
    }

    /** Full teardown (world exit / shutdown): drain the GPU, then free everything incl. an in-flight build. */
    private void clear(RtContext ctx, boolean shutdown) {
        if (!shutdown) {
            clearAsync(ctx);
            return;
        }

        // Device teardown is the one path that must prove every worker and GPU callback has relinquished
        // its resources before the executor, allocator, and VkDevice disappear.
        terrainEpoch++;
        lightGrid.cancelPending();
        drainTasksForClear(ctx);
        cancelAllDirtyGroups();
        ctx.waitIdle();
        ctx.gpuExecutor().flushDestroysAfterDeviceIdle();
        table.destroyRecycledGenerations();
        snapshots.clear();
        synchronized (dirtyLock) {
            dirty.clear(); // any pending re-extract keys refer to the old world/coords — drop them
            dirtyEvents.clear();
            dirtyPending = false;
        }
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        missingIndex.clear();
        queuedDirtyGroup.clear();
        reextract.clear();
        queuedReextract.clear();
        windowValid = false;
        lightGrid.destroyAfterDeviceIdle();
        lightSections.clear();
        lightHierarchyDirty = false;
        lastLightHierarchyRequestNanos = 0L;
        if (resident.isEmpty() && table.buffer == null && removed.isEmpty() && prepared.isEmpty()) {
            empty.clear();
            table.instances = null;
            published.clear();
            table.capacity = 0;
            table.nextSlot = 0;
            table.freeSlots.clear();
            table.slots.clear();
            table.instanceList.clear();
            removed.clear();
            prepared.clear();
            ready = false;
            return;
        }
        Generation currentGeneration = table.detachGeneration();
        if (currentGeneration != null) {
            currentGeneration.buffer().destroy();
        }
        table.capacity = 0;
        table.nextSlot = 0;
        table.freeSlots.clear();
        table.slots.clear();
        table.instanceList.clear();
        lightSections.clear();
        for (SectionGeom g : resident.values()) {
            g.destroy();
        }
        resident.clear();
        empty.clear();
        table.instances = null;
        published.clear();
        // The accumulators can hold evicted-but-not-yet-retired geometry (window sync fills `removed`
        // between streaming passes) and built-but-not-yet-published sections; the GPU is idle here, free them.
        for (SectionGeom g : removed) {
            g.destroy();
        }
        removed.clear();
        for (PreparedSection ps : prepared) {
            retirePublicationTicket(ps);
            RtSectionBuilder.destroy(ps);
        }
        prepared.clear();
        ready = false;
    }

    /**
     * Invalidate a world residency without joining worker/GPU work or waiting for graphics. Render-owned
     * state is detached immediately; its native resources are retired on the executor after the last
     * graphics submission that could still reference them. Results arriving from the old epoch self-retire
     * as unpublished resources in {@link #completeTask(SectionResult)}.
     */
    private void clearAsync(RtContext ctx) {
        ctx.gpuExecutor().throwIfFailed();
        terrainEpoch++;

        // Token maps are render-thread ownership, so clearing them makes every old completion unpublishable
        // even in the narrow race where it observed the previous epoch immediately before this increment.
        taskTracker.cancelAllCurrent();
        inFlightDirtyGroup.clear();
        cancelAllDirtyGroups();

        long lastGraphicsUse = ctx.gpuExecutor().latestGraphicsUseValue();
        Generation oldGeneration = table.detachGeneration();

        Set<SectionGeom> oldGeometry = Collections.newSetFromMap(new IdentityHashMap<>());
        oldGeometry.addAll(resident.values());
        oldGeometry.addAll(removed);
        resident.clear();
        removed.clear();

        ArrayList<PreparedSection> oldPrepared = new ArrayList<>(prepared);
        for (PreparedSection ps : oldPrepared) {
            retirePublicationTicket(ps);
        }
        prepared.clear();
        SectionResult completed;
        while ((completed = completedBuilds.poll()) != null) {
            try {
                if (completed.prepared() != null) {
                    oldPrepared.add(completed.prepared());
                }
            } finally {
                taskTracker.retire(completed.task().ticket);
            }
        }

        snapshots.clear();
        synchronized (dirtyLock) {
            dirty.clear();
            dirtyEvents.clear();
            dirtyPending = false;
        }
        dirtyDrain.clear();
        dirtyEventDrain.clear();
        desired.clear();
        desiredColumns.clear();
        loadedColumns.clear();
        missing.clear();
        missingIndex.clear();
        queuedDirtyGroup.clear();
        reextract.clear();
        queuedReextract.clear();
        empty.clear();
        published.clear();
        windowValid = false;

        table.capacity = 0;
        table.nextSlot = 0;
        table.freeSlots.clear();
        table.slots.clear();
        table.instanceList.clear();
        table.instances = null;
        lightSections.clear();
        lightHierarchyDirty = false;
        lastLightHierarchyRequestNanos = 0L;

        if (oldGeneration != null) {
            retireGeneration(ctx, lastGraphicsUse, oldGeneration);
        }
        lightGrid.invalidate(ctx, lastGraphicsUse);
        if (!oldGeometry.isEmpty()) {
            ArrayList<SectionGeom> retirement = new ArrayList<>(oldGeometry);
            ctx.gpuExecutor().enqueueDestroyAfterGraphics(lastGraphicsUse,
                    () -> destroyDetachedGeometry(retirement));
        }
        if (!oldPrepared.isEmpty()) {
            ctx.gpuExecutor().enqueueDestroyUnpublished(() -> destroyDetachedPrepared(oldPrepared));
        }

        // Keep the RT seam alive as an empty world while the new desired window begins filling.
        ensureEmptyTableReady(ctx);
    }

    private static void destroyDetachedGeometry(List<SectionGeom> geometry) {
        Throwable failure = null;
        for (SectionGeom geom : geometry) {
            try {
                geom.destroy();
            } catch (Throwable t) {
                if (failure == null) failure = t;
                else failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw new RuntimeException("Failed to retire detached RT terrain geometry", failure);
        }
    }

    private static void destroyDetachedPrepared(List<PreparedSection> sections) {
        Throwable failure = null;
        for (PreparedSection section : sections) {
            try {
                RtSectionBuilder.destroy(section);
            } catch (Throwable t) {
                if (failure == null) failure = t;
                else failure.addSuppressed(t);
            }
        }
        if (failure != null) {
            throw new RuntimeException("Failed to retire detached RT terrain builds", failure);
        }
    }

    private static long columnKey(int scx, int scz) {
        return ((long) scx << 32) ^ (scz & 0xFFFFFFFFL);
    }

    private static int columnX(long key) {
        return (int) (key >> 32);
    }

    private static int columnZ(long key) {
        return (int) key;
    }

    /** Pack section coords into a stable map key; ranges fit comfortably in the masks. */
    static long sectionKey(int scx, int scy, int scz) {
        return (scx & 0x3FFFFFFL) | ((scz & 0x3FFFFFFL) << 26) | ((scy & 0xFFFL) << 52);
    }

    private static int sectionX(long key) {
        return (int) (key << 38 >> 38);
    }

    private static int sectionZ(long key) {
        return (int) (key << 12 >> 38);
    }

    private static int sectionY(long key) {
        return (int) (key >> 52);
    }

    static boolean sameLightRecords(float[] a, float[] b) {
        if (a == null || a.length == 0) return b == null || b.length == 0;
        if (b == null || b.length == 0) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) return false;
        }
        return true;
    }
}
