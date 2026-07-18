# Optimized render stack

## Contract

Caustica uses one reviewed material and path-transport implementation with mode-specialized ray-generation
binaries. Material, ray-cone, glass, water, emissive, EON/GGX, and SHaRC estimator improvements therefore
remain shared. Latency-only resources and convergence-only resources do not coexist in one hot shader.

Opacity micromaps are an automatic acceleration representation on devices exposing
`VK_EXT_opacity_micromap`. Their conservative classifier preserves the authored cutout alpha test for
uncertain micro-triangles while traversal resolves provably opaque/transparent regions without invoking
the any-hit shader. Unsupported devices retain the unchanged any-hit path.

## Mode matrix

| Concern | Live baseline and SHaRC | Offline ground truth |
| --- | --- | --- |
| Entry binary | `world*.rgen.spv` / `world_sharc*.rgen.spv` | `world_offline*.rgen.spv` |
| Sampler | One-register PCG | Owen-scrambled Sobol sequence |
| Output resources | Live radiance and reconstruction guides | FP32 accumulation and pilot images |
| Invocation reorder | Compiled out | Compiled in; independently measurable |
| Optimization | Slang `-O2 -fp-mode precise` | Slang `-O2 -fp-mode precise` |
| Lifetime | Persistent online pipeline | Lazily created on offline entry, destroyed on exit |

SHaRC diagnostic query/update pipelines are also lazy. Normal rendering creates only the production
pipelines; enabling detailed counters or a SHaRC debug view creates diagnostic variants. Newly created
pipelines receive the stable bindless texture registry without resetting texture slots or forcing terrain
rebuilds.

## Why live SER is disabled

The fixed foliage-heavy scene at 3840x2160 output and 1920x1080 internal resolution was captured with
SHaRC active, detailed counters off, and identical hit-object tracing. The only A/B variable was whether
`ReorderThread` executed before the hit/miss shader:

| Variant | Mean FPS | Median FPS | Mean SHaRC query | Mean reconstruction |
| --- | ---: | ---: | ---: | ---: |
| Live reorder on (`035DB688...`) | 101.0 | 101 | 6.9176 ms | 1.7001 ms |
| Live reorder off (`99DD6B5...`) | 111.5 | 111 | 6.1003 ms | 1.6991 ms |
| Final PCG + live reorder off (`BDC3B5EC...`) | 110.4375 | 111 | 5.9409 ms | 1.6994 ms |

Disabling live reorder improved whole-frame FPS by 10.4% and reduced the query pass by 11.8%. BLAS,
TLAS, SHaRC update, and reconstruction did not move enough to explain the result. In this workload,
foliage any-hit traffic makes reorder overhead exceed its coherence benefit. The invocation-reorder device
capability remains enabled for hit objects and the separate offline specialization; live shaders do not
carry a second runtime branch or an unused alternative SPIR-V stack.

The final production JAR is `BDC3B5EC51CF24B6DF6C45894D6831BD2D50FA1248BAAA62EC6E9C50C4B021FA`.
The bridge reported that exact hash from the running process before the final capture.

## Proof boundaries

- Source/build proof requires unit tests, SPIR-V validation, production-JAR verification, and disassembly
  showing online guide descriptors without offline accumulation descriptors (and the inverse offline).
- Runtime proof requires the bridge to report the exact deployed artifact hash, expected resolution/backend,
  normal debug view, frame statistics on, and detailed SHaRC counters off.
- Performance parity is scene-relative. The current non-fork control is about 110 FPS in this scene, so the
  correct parity target is about 110 FPS. The earlier 200 FPS screenshot is not a valid current-scene target.
- Visual parity remains a user inspection gate. The specializations alter resource ownership, sampling, and
  scheduling; they do not fork the shared material or transport equations.
