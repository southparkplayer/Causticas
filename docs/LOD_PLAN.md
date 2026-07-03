# RT Far-Field LOD Plan

Status: agreed 2026-07-03. M0 + M1 implemented and play-verified. M2 implemented
2026-07-03 (compiles + shaders compile, NOT GPU-verified — see "M2 as built" below).

This is the actionable plan for far-field LOD in the RT renderer. Background and the
Voxy design study live in `../VOXY_LOD_ADOPTION_PLAN.md` — that document is a
clean-room reference, not the plan of record. Where the two differ, this file wins.

## Decisions taken

1. **In-memory first, storage last.** No custom storage format, no chunk loading
   beyond render distance, until the in-memory far field has been rendered and
   visually validated. Persistence (M4) only serializes a format that already
   proved itself.
2. **Two value flavors, both in scope from the start:**
   - *Inside render distance*: shrink the fine `RtTerrain` radius to N sections and
     cover the annulus N..RD with coarse proxies — a perf/memory win (fewer fine
     BLASes, cheaper fill while flying) that is testable on a fresh world.
   - *Retained trail*: LOD sections **survive chunk unload** under a RAM budget, so
     the horizon fills in behind the player within a session. Level 0 is dropped on
     unload (rebuildable from loaded chunks); levels ≥ 1 are retained — ≤ 1/8 the
     size per level, so a long flight stays bounded.
3. **Ingest piggybacks the existing tessellation job.** The `RtTerrain` worker job
   already holds an immutable `RenderSectionRegion` snapshot; LOD voxelization is an
   extra pass over data the worker has in hand. It inherits token/epoch staleness,
   the budgeted streaming, dirty re-extraction (every block edit already re-runs the
   job, which re-voxelizes — no separate LOD dirty hook needed), and F3+A refill for
   free.
4. **The whole mip pyramid is computed worker-side.** For levels ≤ 4 a vanilla 16³
   section's footprint at every level is self-contained (16→8→4→2→1 cells, always
   2×2×2-aligned), so no cross-section reads are needed and the main-thread apply is
   pure palette writes. This is why MAX_LEVEL is capped at 4 (= Voxy's MAX_LOD;
   level-4 cells are 16 blocks, one cell per vanilla section).
5. **Cell = global palette id only.** Kind (solid/cutout/water/lava), emission, and
   a MapColor-derived flat albedo are *functions of the block state*, so they live
   in one global `RtLodPalette` entry, not in per-cell bits. Per-cell biome tint and
   an atlas/LabPBR-derived material palette are M5. Sections store cells as
   per-section palettized byte (escalating to short) indices — a mostly-stone
   section costs ~33 KB, a sky section ~nothing.
6. **The near/far boundary is N = 10 chunks, hardcoded** (decided 2026-07-03,
   `RtLodSelector.NEAR_RADIUS_CHUNKS`). Fine terrain owns everything within 10
   chunks of the player; LOD proxies own the outside. At M2 the fine `RtTerrain`
   window shrinks to this radius. The distance-octave selection rule (level 0 to
   2N, one level per doubling) is a deliberate placeholder: the intended future
   is **camera/screen-size-based LOD** — descend while a node's projected size is
   too large — which subsumes both the constant and the octave rule into a
   per-node test.

## Architecture

```text
RtTerrain worker job (has RenderSectionRegion snapshot)
  -> RtLodWorld.ingestFromWorker: voxelize 16³ -> palette ids -> mip pyramid
  -> concurrent ingest queue (token-ordered, epoch-guarded)
  -> RtLodWorld.update (client tick): apply octant writes into 32³ sections,
     demote level 0 outside the loaded window, evict retained levels over budget
  [M1] RtLodSelector: pick far-annulus nodes, no parent/child overlap, hysteresis
  [M2] RtLodMeshBuilder -> greedy cuboid proxy mesh -> RtAccel BLAS batch
       -> extra static instances in RtComposite's per-frame TLAS
       -> world.rchit LOD branch: flat palette albedo, opaque, motionPrev = 0
```

Package: `dev.upscaler.rt.lod`. Nothing in the near-field path (`RtTerrain`,
`RtEntities`, materials, DLSS-RR, TLAS rebuild) is replaced.

## Milestones

### M0 — In-memory LOD world, no rendering (DONE, not yet play-verified)

Data sidecar only: voxelize + mip + retain, debug counters, zero rendering change.
Definition of done: fly around and edit blocks without exceptions; per-level section
counts rise/fall predictably; retained memory respects the budget; disabling the
flag changes nothing.

### M1 — CPU LOD selection (DONE, play-verified 2026-07-03)

`RtLodSelector` picks nodes outside the near radius; no parent+child
double-selection; counts stay bounded while flying. Dump-only, still no rendering.
See "M1 as built" below.

### M2 — First proxy BLAS (DONE 2026-07-03, compiles, NOT GPU-verified)

`RtLodMeshBuilder` greedy-merges equal-palette-id faces into cuboid meshes
(opaque-only, no UVs), uploads + batches BLAS builds through `RtAccel`, merges
instances into the per-frame TLAS, and adds a `world.rchit` LOD branch shading flat
palette albedo. Proxies are opaque single-geometry BLASes, so any-hit never runs.
Coordinate discipline identical to near terrain: node-local vertices,
`nodeOrigin − rebaseOrigin` transforms. See "M2 as built".

Design deviation from the original sketch: instead of widening the terrain section
table, LOD instances carry `LOD_BIT = 0x200000` in `instanceCustomIndex` (bit 21;
entities/particles already use 23/22) routing to a separate flat BDA table of
per-node Prim-array addresses. The near-terrain table and publish path are
untouched, and the rchit LOD branch reuses the existing terrain `Prim` struct.

### M3 — Dynamic behavior hardening

Mostly free via the ingest piggyback (edits already re-voxelize). Remaining work:
rebuild dirty proxies without flicker (keep the old proxy until the replacement BLAS
lands), per-frame budgets for LOD mesh/BLAS work, LOD always lower priority than
near terrain. If main-thread apply cost ever shows in RtFrameStats, move
`RtLodWorld` to its own thread.

### M4 — Persistence

Only after M2 visuals are validated. `RtLodStorage` interface, keyed by
world/dimension identity + data/palette version; fail-closed on mismatch. Fixes the
two accepted M0 gaps (trail lost on rejoin; nothing beyond RD on fresh load).

### M5 — Quality

Biome tint, emissive-preserving mipping upgrades (torch-in-stone cells), water/lava
kinds in proxy meshes, LabPBR-derived material palette (rough/metal/F0), transition
cross-fade if the annulus edge is visible.

## M0 as built

Files: `dev/upscaler/rt/lod/{RtLodPalette, RtLodSection, RtLodMipper, RtLodWorld}`,
config block `UpscalerConfig.Rt.Lod`, one hook in `RtTerrain.dispatchSection`'s
worker lambda, tick + shutdown wiring in `UpscalerClient`.

Flags:

```text
-Dupscaler.rt.lodWorld=true         master switch (default false)
-Dupscaler.rt.lodDebug=true         5 s summary log line (default false)
-Dupscaler.rt.lodMaxLevel=4         mip levels above level 0 (1..4)
-Dupscaler.rt.lodApplyPerTick=256   max ingests applied per client tick
-Dupscaler.rt.lodApplyBudgetMs=2.0  wall-clock cap for the per-tick apply
-Dupscaler.rt.lodBudgetMb=256       RAM budget for retained levels ≥ 1
-Dupscaler.rt.lodDemoteMarginChunks=2  keep level 0 this far past render distance
```

Data model:

- `RtLodPalette` (global, thread-safe): `BlockState -> {id, kind, emission,
  MapColor argb}`. id 0 = air. Kind: water/lava from `LiquidBlock` fluid state;
  `RenderShape.INVISIBLE` + no fluid = air; else `canOcclude()` ? solid : cutout
  (waterlogged blocks classify by their solid block).
- `RtLodSection`: 32³ cells at one level; per-section palette (local byte indices,
  escalating to short past 256 entries), lazy allocation so all-air sections cost
  ~an object header. A level-L section covers 32·2^L blocks; a vanilla section maps
  into exactly one section per level (write region `max(1, 16>>L)³` at offset
  `((scx<<4)>>L)&31`).
- `RtLodMipper`: per 2×2×2, keep the highest (kindRank, emission) child —
  solid > lava > cutout > water > air. Known M0 loss: an emissive cutout (torch)
  mixed with solid children loses its emission; M5 fixes.
- `RtLodWorld`: singleton. Worker side voxelizes + builds the pyramid and enqueues
  (bounded queue, drop + count on overflow). Main-thread tick applies under a
  count + wall-clock budget, ordered per vanilla section by `RtTerrain`'s tess
  token, guarded by a world epoch (bumped on any clear). Every 20 ticks: demote
  sweep (level-0 sections whose 2×2 chunk footprint is fully outside
  RD + margin are dropped entirely — parents keep their mips) and evict sweep
  (retained levels ≥ 1 over budget: farthest-from-player first).
- World identity: `ClientLevel` reference identity; any change (dimension switch,
  leave world) clears everything. Deliberately NOT wired to
  `InvalidateRenderStateCallback` — F3+A / render-distance change must not wipe the
  trail.

Accepted M0 gaps (documented, revisit at M4 unless noted):

- Rejoining a world/server loses the trail (in-memory only).
- A same-`ClientLevel`-lifetime server dimension is the identity unit; a worker job
  spanning the exact clear instant can theoretically apply one stale section, which
  self-corrects on the next ingest of those coords.
- Toggling `lodWorld` on mid-session only ingests newly tessellated sections;
  F3+A forces a full refill.
- Light is not stored (the path tracer computes lighting; block-light emission
  rides the palette).

Validation (M0): run with `-Dupscaler.rt.lodWorld=true -Dupscaler.rt.lodDebug=true`,
fly (counts grow, retained bytes plateau at the budget), edit blocks (applied
counter moves, no exceptions), F3+A, dimension switch (counts reset), toggle flag
off (zero behavior delta). **Play-verified 2026-07-03.**

## M1 as built

`RtLodSelector`, driven from `RtLodWorld.update()` every tick; recomputed only when
the 8-block-quantized camera position moves or the LOD world mutates (a mutation
counter bumped on apply/demote/evict/clear). The quantization doubles as cheap
anti-flap hysteresis until M2 rendering needs a real one.

Algorithm: descend from the coarsest level's sections (`lodMaxLevel`).

- Node fully inside the near window (chunk-square, ±`NEAR_RADIUS_CHUNKS = 10`
  around the player, full height — matching RtTerrain's window shape): skipped.
- Node straddling the boundary: split, so finer outside children hug the edge;
  level-0 stragglers are dropped — the deliberate 0..1-section transition gap.
- Otherwise selected once its distance octave matches its level (closest-point
  distance to the node AABB; level 0 out to 20 chunks, 1 to 40, 2 to 80, 3 to 160,
  4 beyond). Selection emits only descent leaves, so parent/child overlap is
  structurally impossible.
- A node whose finer children were all evicted (coarse-only trail data) is
  selected as-is instead of leaving a hole. Eviction correspondingly drops finer
  levels before coarser at equal distance.

Output: per-level `LongArrayList`s of selected section keys (M2's input) + a
`selected L0=… (x.xx ms)` suffix on the lodDebug line.

Validation (M1): selected counts stay bounded while flying (they track the
annulus + trail, not total resident sections); selection time stays well under a
millisecond at cruise; counts shift between levels smoothly as distance changes.
**Play-verified 2026-07-03**: ~1.6k L0 + ~570 L1 selected at RD ~16, 0.07–0.12 ms
per recompute. Note: L1 selections inside the vanilla window's diagonal corners
are expected — near exclusion is chunk-Chebyshev, the octave rule is Euclidean,
so corners (~407 blocks) fall past the 320-block level-0 band. L2+ stays 0 until
a retained trail exists (fly far in one direction to see the bands cascade).

## M2 as built

New: `RtLodMeshBuilder`, `RtLodTerrain`, `RtLodIngester`; changed: `RtLodWorld`
(shared tokens, container voxelize, accessors), `RtLodSelector` (near radius is a
parameter), `RtTerrain` (window shrink + dispatch-time LOD token), `RtComposite`
(LOD frame pass, instance merge, push slot 200), `LevelExtractorMixin` (LOD dirty
hook), all four `world.*` shaders (WorldPush `lodTableAddr` in the std430 pad at
byte 200 — zero offset shifts), `UpscalerClient` (shutdown).

- **Residency** (`RtLodTerrain`, render thread, driven from `RtComposite` before
  the ready gate): diffs the selector's sets per selection-generation/mutation,
  meshes on `RtWorkerPool` from `snapshotCells()` copies, uploads + prepares
  pooled opaque BLASes under `lodBuildBudgetMs`/`lodBuildsPerFrame`, batches all
  builds into ONE `submitAsync` at a time (same pattern as RtTerrain). Nodes
  publish only after their BLAS lands; a content-version bump re-meshes with the
  old node visible until the swap (no edit flicker). Deselected nodes leave the
  TLAS immediately (no stale overlap) but keep GPU resources for
  `lodRetireGraceTicks` so band-boundary flapping is churn-free.
- **Annulus ingest** (`RtLodIngester`): once the fine window shrinks, sections
  beyond it never get tessellation jobs, so the ingester watches loaded columns in
  (fineRadius, RD+margin], snapshots a `PalettedContainer.copy()` (no 27-section
  region, no neighbour gating) and voxelizes on the worker pool.
  `hasOnlyAir` sections skip the worker entirely. Ordering rides ONE shared token
  source with the piggyback (`RtLodWorld.nextIngestToken()`, fetched at dispatch on
  the render thread), so a section crossing the boundary between sources can never
  regress. Column unload drops its appliedTokens → reload re-ingests fresh data.
- **Fine-window shrink**: `RtTerrain.horizontalChunks` =
  `RtLodWorld.fineWindowRadius(RD)` = `min(RD, 10)` while `lodRender` is active;
  the selector's near exclusion uses the same value, so coverage is exclusive by
  construction. `-Dupscaler.rt.lodRender=false` restores the full fine window and
  M0/M1 dump-only behavior (A/B lever).
- **TLAS/shading**: instances merge as extra "static base" entries ahead of
  `RtEntities.beginFrame`; instance list cached, rebuilt only on
  residency/selection change or terrain-rebase move. `world.rchit` LOD branch:
  flat prim albedo (sRGB→linear MapColor), prim geometric normal (viewer-oriented),
  emission in `normal.w` (raw 0..1, no terrain +2 flag), rough 0.9 (water 0.08),
  metal 0, F0 0.04, `motionPrev = 0`.

New flags: `lodRender` (def on), `lodBuildBudgetMs` (1.5), `lodBuildsPerFrame`
(64), `lodIngestPerTick` (64), `lodIngestMaxInflight` (128), `lodRetireGraceTicks`
(40).

GPU-verify checklist (definition of done):

- far terrain visible beyond 10 chunks (and beyond RD after flying — the trail);
- shadows/reflections/GI hit far proxies (sunset shadows from far mountains);
- no double-geometry/z-fight/double-shadow at the 10-chunk boundary (the 0..1
  section gap should read as a thin seam at most — evaluate whether it's visible);
- block edits in the annulus update the proxy (break a tree at ~12 chunks);
- fly fast: near terrain fill-in never starves (LOD budget separate from terrain);
- `-Dupscaler.rt.lodRender=false` returns exact pre-M2 behavior;
- dimension switch / F3+A / resource reload survive;
- lodDebug line: `proxies`/`tris` bounded, `drops` low, `prepQ` drains.

Known M2 limitations (by design, revisit at M5): water/leaves flattened opaque;
MapColor albedo (flat, no biome tint); emissive mixed cells lose emission; the
near-boundary transition is a hard cut.
