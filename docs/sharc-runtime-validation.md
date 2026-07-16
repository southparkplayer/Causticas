# SHaRC runtime validation

This checklist separates build proof from runtime proof. A successful JAR build does not prove that
the GPU executed SHaRC or that image quality and torch transport are correct.

> **Superseded performance conclusion (2026-07-16):** the fixed-camera 99 FPS result below is retained
> as experiment history, not acceptance. Fireflies were subsequently observed, its scene scale produced
> only 0.098% occupancy, and the estimator/material contract has not yet been validated. The current
> correctness and acceptance plan is `docs/sharc-architecture-and-validation-plan.md`.

## Observed live proof (2026-07-16)

The production JAR with SHA-256
`865AD612F741CC64A1E77A9EBE6585BBAF0EFD2EA902E6009BAFBA461FD5DF1E` was loaded by the
PrismLauncher `26.2(2)` instance on an NVIDIA GeForce RTX 5090 (Vulkan driver 610.62). The clean
launch reported `shaderBufferInt64Atomics(SHaRC)`, completed RT bring-up, and entered the test world.

Enabling the Balanced preset produced `SHaRC enabled: 2097152 entries, 80 MiB, available (NVIDIA
SHaRC 1.6.5.0)` and one named `enabled` cache reset. Across a 500-frame live sample, frame telemetry
reported:

- 6,838-7,365 query attempts and 6,838-7,364 query hits per frame.
- 35,204-36,017 update hits per frame, 137-153 occupied entries, and zero insert failures.
- Nonzero SHaRC update, resolve, and query GPU timestamps on every sampled frame.
- A fixed allocation of 83,887,040 bytes and a stable reset count of one.

The Query Hit / Miss, Termination Depth, Occupancy, and Voxel Hash debug views all rendered distinct
live output and the normal view restored after cycling back to Off. Disabling SHaRC restored the
baseline trace (`baselineTraceGpuNanos=9781376`) with zero SHaRC work and zero SHaRC allocation;
re-enabling it restored the 80 MiB allocation, all three GPU stages, occupancy, and live query hits.
No error, exception, VUID, device-loss, validation, fatal, or failed message appeared in the log after
the first SHaRC enable.

This run proves the packaged production path, menu controls, allocation/lifecycle, update-resolve-query
execution, counters, GPU timestamps, debug views, baseline fallback, and re-enable path on this system.
It does not replace the validation-layer, image-quality/torch, dimension-transition, offline-renderer,
or 15-30 minute soak gates below.

### Fixed-camera performance correction

The earlier fast preset was rejected after visible fireflies. It enabled glossy queries, queried at only
0.25 voxel of separation, and skipped live secondary direct lighting. Those knobs remain exposed for
diagnosis, but they are not accepted defaults or evidence of correct performance.

The corrected production path compiles diagnostics into separate ray-generation pipelines. The ordinary
query shader is 159,296 bytes instead of 179,492 bytes; selecting SHaRC debug views or **SHaRC Detailed
Counters** switches to the diagnostic pipelines, while normal rendering and general GPU stage timing
carry no per-path diagnostic branches or atomics.

On exact runtime artifact
`10E56CFFB16B2D9EB87C9AF9B44349E2AD506BFC78D37B6BF50BE5FB83BBF332`, the saved 4K cave camera
used the conservative contract: exponent 18, scene scale 6.25, 32x32 sparse update tiles, two update
bounces, one-voxel minimum segment, glossy queries off, and live secondary direct lighting on. After
warmup, scripted samples reported:

- SHaRC off: 89-91 FPS, mean 89.8; baseline trace 8.458 ms GPU.
- SHaRC on: 97-98 FPS, mean 97.27; query trace 7.478 ms plus 0.095 ms update+resolve.
- Improvement: +7.47 mean FPS (8.32%) and 0.885 ms lower measured SHaRC GPU work.

Before the production/diagnostic split, conservative diagnostics recorded 1,745,981 mean hits from
1,803,333 attempts per frame (96.82%) and average termination depth 1.118. A subsequent diagnostic
run on that artifact reported zero direct
numeric-risk events, zero resolved FP16 saturations, zero insert failures, and maximum cached luminance
0.140678 in this scene.

Two scripted 20-frame output sequences excluded the performance overlay and HUD from analysis. The
conservative SHaRC sequence contained zero pixels with a temporal luminance spike above 0.10 (baseline
contained 345), and its mean-image absolute difference from baseline was 0.00594 at p99 and 0.02682 at
p99.99. This rejects the observed firefly failure for this cave sequence; it is not a scene-independent
visual-equivalence or soak claim.

### Post-integration query specialization

Artifact `7932640D17D22CB9552BBD95A9CCFA6E3AF5C812F740B701A6D3A5B4943D9ED9` adds a
158,200-byte diffuse-only query raygen selected when glossy querying is disabled, while preserving the
159,296-byte general raygen for the exposed glossy-query knob. In an interleaved 4K cave A/B, the
general shader measured 95 FPS / 7.636 ms median query and the specialized shader measured 98 FPS /
7.387 ms; an earlier specialized run also measured 98 FPS / 7.429 ms. The specialization removes only
dead glossy-cone evaluation: with `queryFlags & 1 == 0`, the prior `glossyEligible` expression was false,
so query eligibility and radiance are unchanged.

This artifact also adds opt-in full-pipeline GPU timestamps and the bridge-driven capture harness. With
Frame Statistics off, the extra BLAS/TLAS/reconstruction/exposure/display/copy timestamp writes are not
recorded. SHaRC Detailed Counters remains a separate, explicitly expensive diagnostic switch.

## Menu and diagnostics

In **Caustica Settings**, set **SHaRC Indirect Cache** to `Ready`/`Active` and select Low,
Balanced, or High memory. An unavailable build, GPU, or pipeline shows its actual fallback reason in
the same control instead of claiming that SHaRC is active.

The existing **Debug View** control includes:

- SHaRC Query Hit / Miss: green hit, red miss, dark ineligible.
- SHaRC Termination Depth: cache-terminated path depth.
- SHaRC Occupancy: NVIDIA hash-grid occupancy visualization.
- SHaRC Voxel Hash: per-query voxel/hash color.
- SHaRC Cached Radiance: logarithmic cached-radiance heat map.
- SHaRC Voxel Size: selected voxel and minimum-segment scale.
- SHaRC Query Eligibility: dynamic, short-segment, lobe, and eligible classification.
- SHaRC Numeric Risk: accumulator/resolve risk visualization.

Enable **Frame Statistics** to write asynchronous full-pipeline GPU timestamp results and general
counters to `rt-frame-stats/frame.csv`. Timestamp columns are counters ending in `GpuNanos`; the
ordinary `frame.*Ms` columns are CPU command-recording envelopes. Enable **SHaRC Detailed Counters**
only for short diagnostic captures: it selects the instrumented SHaRC shaders whose per-path global
atomics are intentionally excluded from production performance measurements.

For repeatable testing without UI automation, `tools/caustica-debug-bridge.ps1` can set SHaRC,
Frame Statistics, debug view, and scene scale; sample live FPS; report active state; and request a clean
shutdown through the file-backed in-process bridge.

## Vulkan proof

Run with Vulkan validation enabled, then:

1. Enter a fixed test world with SHaRC off and confirm the baseline path.
2. Enable SHaRC. Require one named `enabled` reset followed by update, resolve, and query each frame.
3. Wait for warmup and require nonzero query hits and sensible occupancy.
4. Toggle repeatedly; switch memory presets; resize; reload resources; change dimensions; and teleport
   far enough to force a terrain-origin rebase.
5. Confirm reset names match only the authorized reasons and that ordinary streaming, resize,
   day/night progression, and torch-intensity edits do not continually clear the cache.
6. Run offline ground truth and confirm SHaRC allocations are released and the baseline pipeline is used.
7. Require zero descriptor, buffer-usage, synchronization, and lifetime validation errors.

## Image and torch proof

Capture the same camera and sample sequence for baseline, cold SHaRC, warm SHaRC, and offline ground
truth. Cover caves, daylight, night, water, glass, mirrors, rough metals, cutout vegetation, dynamic
entities, particles, and block edits.

Torch acceptance remains three separate observations:

1. Only texture-authored emissive texels change visible surface radiance immediately.
2. Rare bright fireflies are counted separately and are not evidence of working emission control.
3. Nearby environmental illumination changes and converges through cache history.

## Soak and performance proof

Run 15-30 minutes of travel, streaming, edits, entities, time changes, and dimension transitions.
Record baseline trace GPU time versus SHaRC update + resolve + query GPU time, fixed allocated bytes,
query hit rate, occupancy, collision estimate, stale evictions, insertion failures, reset count/reason,
and average cache-termination depth. Do not accept CPU recording time as GPU performance evidence.
