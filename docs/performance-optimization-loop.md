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

Prefetching the immutable SHaRC result before secondary NEE to skip discarded diffuse-BRDF arithmetic
was also rejected. Its first run was statistically neutral, while the foreground-validated repeat fell
to 98.25 FPS / 7.342 ms query-pass time versus the 100.13 FPS / 7.166 ms control. Keeping cached
radiance live across NEE likely increased register pressure more than the removed arithmetic saved.

Rejecting primary, ineligible, and dynamic paths before SHaRC voxel/cone calculations was also
reverted. Its foreground-validated query pass averaged 7.231 ms versus an interleaved exact-control
build at 7.168 ms. The intended arithmetic reduction did not translate into lower GPU time.

## Current cave stage map

The accepted SHaRC cave configuration now measures approximately 6.42 ms query trace, 1.70 ms
reconstruction, 0.42 ms BLAS, 0.24 ms TLAS, 0.09 ms sparse update, and less than 0.06 ms in each
exposure/display/copy stage. Reconstruction is split into approximately 0.03 ms of Caustica-owned
disocclusion work and 1.67 ms in Streamline DLSS-RR. Therefore query traversal/shading remains the
first owned optimization target; acceleration-structure changes must prove they do not degrade it.

The first accepted post-SHaRC optimization specializes a diffuse-only production query shader when
**SHaRC Glossy Query** is off, while retaining and selecting the general shader when that tuning knob is
on. The specialized SPIR-V is 158,200 bytes versus 159,296 bytes. In an interleaved 4K cave A/B, the
general shader measured 95 FPS / 7.636 ms median query and the restored specialized shader measured
98 FPS / 7.387 ms. A second specialized run measured 98 FPS / 7.429 ms. The specialization does not
change the estimator: with the glossy flag off, the eliminated glossy predicate was always false.

The next accepted specialization also fixes the enabled **Live Secondary Direct** ordering at compile
time for that same safe-default shader; other knob combinations still select the general shader. This
reduced the raygen to 151,612 bytes. Artifact
`6F972BDE7D9E61F3943EC13D6EBFD5EB63496E4056FB57363C96590FF3894657` produced two consecutive
4K cave captures of 100.18 FPS / 7.102 ms query and 100.69 FPS / 7.114 ms query, versus the prior
artifact's 97.31 FPS / 7.407 ms query. The ordering and radiance expression are unchanged; only the
runtime flag branches and unreachable early-query path are absent from the specialized shader.

The specialized safe-default query shader is now compiled with Slang `-O2 -fp-mode precise`. This
changes compiler optimization only, retains precise floating-point semantics, and reduces the
validated raygen from 151,612 to 133,460 bytes. Against the same instrumentation build at default
`-O1`, the 4K-output/1080p-internal cave capture improved from 100.17 FPS / 7.217 ms query to
108.43 FPS / 6.437 ms. A 30-sample repeat measured 108.43 FPS / 6.424 ms. Artifact
`0750202785667432146BCD2E7FF27EE456597651C083620982752F3D2480225F` is the foreground-validated
runtime A/B proof build. The final artifact
`BCF094D60C8DAF7CBE104820924373799B450B9475C2891253BF08B698E520BC`, which only cleans up the
profiler assignment, passed a foreground smoke capture at 107.67 FPS / 6.524 ms query.

The foliage-heavy village scene exposed a separate scheduling regression. With OMM disabled and all
hit-object tracing unchanged, an interleaved live invocation-reorder A/B measured 101.0 FPS / 6.9176 ms
mean query with `ReorderThread`, versus 111.5 FPS / 6.1003 ms without it. Reconstruction remained
1.7001 ms versus 1.6991 ms. Live baseline, SHaRC query, and sparse-update raygens now compile with
`CAUSTICA_SER=0`; the independent offline binary compiles with `CAUSTICA_SER=1`. This preserves the
shared material/transport implementation while removing the measured live scheduling loss.
Final artifact `BDC3B5EC51CF24B6DF6C45894D6831BD2D50FA1248BAAA62EC6E9C50C4B021FA` restored the PCG
live sampler and measured 110.4375 mean / 111 median FPS with a 5.9409 ms mean query pass. The running
debug bridge reported the exact artifact hash, and Minecraft was closed immediately after capture.

Each accepted optimization gets its own commit containing source, tests, and the corresponding updated
runtime evidence. Diagnostic instrumentation stays separately switchable and may be removed without
changing the render path.
