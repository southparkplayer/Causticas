# Upstream Synchronization Delta — 2026-07-15

The fork synchronization branch merges `upstream/main` at `1f4af90` into fork `main` at `cc3bd25`.
This records source integration only; it does not authorize a tag, release, or upstream pull request.

## Integrated upstream changes

- `edca7f6` — acceleration-structure scratch alignment. This is patch-equivalent to fork commit
  `8330203`; the merged implementation keeps upstream's field-owned cleanup and applies alignment once.
- `22505f5` — asynchronous terrain BLAS preparation/build, section snapshots and tables, GPU executor,
  graphics-timeline publication, and retirement.
- `1f4af90` — local-space entity capture, packed geometry, direct cuboid emission, persistent scratch and
  BLAS reuse, partitioned dynamic-object limits, and expanded profiling.

## Reconciliation retained from the fork

- Streamline/DLSSD/DLSSG presentation and resource-lifetime handling remain layered over the new terrain
  graphics timeline.
- First-person body/head captures use separate stable long keys. The body is primary-and-secondary visible,
  the head is secondary-only, and filtered passes bypass the full-model direct-cuboid template.
- Offline rendering retains one immutable entity snapshot until the session ends.
- Terrain meshing retains the compact optical classes used by the Slang transport shaders; water remains in
  its independent upstream geometry bucket.
- Existing external configuration keys remain compatible while internal terrain scheduling uses per-pass
  names. Upstream terrain/entity profiling counters are combined with fork counters.
- Texture-authored torch emissive masks remain authoritative; runtime intensity scales the decoded emissive
  signal and does not introduce analytic torch lighting.

## Proof status

- `git diff --check` passes for the staged synchronization merge.
- Java 25 with Slang 2026.13 and Streamline 2.12 passes `clean check` and `clean build`: 59 Java
  contract tests, generated shader records, shader compilation/validation, the native bridge ABI test,
  signed production Streamline inputs, packaged-native initialization, and production-artifact verification.
- The built `caustica-0.1.0.jar` SHA-256 is
  `08BCC10610D40753C216DE286002DA2D91F01FC82D94966EF9E921CFE146198E`.
- Runtime proof must separately cover terrain streaming/lifetime, entities and first person, OMM/materials,
  Streamline modes, world transitions, resource reload, and shutdown.
- A successful build or merge is not a release and is not runtime proof.
