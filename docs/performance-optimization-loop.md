# Caustica performance optimization loop

This is the repeatable, removable diagnostic loop for SHaRC and the rest of the RT pipeline. It uses
the in-process file bridge and Vulkan timestamps; it does not require UI automation.

## Fixed-scene capture

1. Keep the camera, world time, weather, resolution, fullscreen state, render scale, and reconstruction
   mode fixed. Let particle/entity counts settle or record them with the result.
2. Build and deploy one exact JAR, then verify its SHA-256 through the bridge. A source build is not
   runtime proof until the running process reports that exact hash.
3. Warm for at least 10 seconds. Run `tools/caustica-performance-loop.ps1` with
   `-ExpectedArtifactSha256 <hash> -Label <name>`.
4. Capture the control and candidate for equal durations. Change one variable per candidate.
5. Keep **Frame Statistics** on and **SHaRC Detailed Counters** off. Detailed SHaRC counters execute
   contended per-path atomics and are a correctness diagnostic, not production timing.

The harness rejects the capture if the game is not in-world, RT/Vulkan/fullscreen is not active, the
resolution is wrong, the artifact changes, or detailed SHaRC instrumentation is enabled. It saves raw
CSV samples and a JSON summary under `build/performance-loops/`.

## Acceptance gates

A candidate is accepted only when all applicable gates pass:

- Whole-frame FPS and GPU-stage timings improve across repeated, interleaved A/B runs. A local stage
  win is rejected if trace cost or total frame performance regresses.
- The normal-view output is unchanged for algebraic/scheduling optimizations. SHaRC estimator changes
  require matched cold/warm sequences, temporal spike counts, high-percentile image deltas, and the
  cave/daylight/night/water/glass/mirror/foliage/dynamic-object matrix.
- Vulkan validation, logs, reset reasons, insertion failures, numeric risk, and FP16 saturation remain
  clean. Detailed counters are enabled only for these short diagnostic captures.
- Texture-authored emissive masks remain intact. Visible emissive surface radiance, rare bright
  fireflies, and environmental illumination are assessed separately.

Rejected experiments remain documented so they are not rediscovered. On the saved cave scene,
`PREFER_FAST_BUILD` for dynamic BLAS reduced BLAS time by roughly 0.027 ms but increased query time by
roughly 0.418 ms and reduced FPS. Reusing the combined particle BLAS by particle count also reduced
whole-frame performance. Branching around glossy-cone math when glossy queries were disabled measured
96.42 FPS / 7.564 ms query versus its same-session control's 96.88 FPS / 7.516 ms. All three were
rejected and reverted.

## Current cave stage map

The accepted SHaRC cave configuration measured approximately 7.26 ms query trace, 1.69 ms
reconstruction, 0.39 ms BLAS, 0.24 ms TLAS, 0.09 ms sparse update, and less than 0.06 ms in each
exposure/display/copy stage. Therefore query traversal/shading is the first optimization target,
reconstruction is second, and acceleration-structure changes must prove they do not degrade traversal.

The first accepted post-SHaRC optimization specializes a diffuse-only production query shader when
**SHaRC Glossy Query** is off, while retaining and selecting the general shader when that tuning knob is
on. The specialized SPIR-V is 158,200 bytes versus 159,296 bytes. In an interleaved 4K cave A/B, the
general shader measured 95 FPS / 7.636 ms median query and the restored specialized shader measured
98 FPS / 7.387 ms. A second specialized run measured 98 FPS / 7.429 ms. The specialization does not
change the estimator: with the glossy flag off, the eliminated glossy predicate was always false.

Each accepted optimization gets its own commit containing source, tests, and the corresponding updated
runtime evidence. Diagnostic instrumentation stays separately switchable and may be removed without
changing the render path.
