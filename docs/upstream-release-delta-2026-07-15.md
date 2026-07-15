# Upstream Release Delta — 2026-07-15

Release preparation fetched `upstream/main` at `1f4af90`. The production branch is intentionally not
fast-forwarded because the remaining upstream work changes GPU scheduling and requires an independent
Streamline/DLSSD/DLSSG regression run.

## Accounted for

- Upstream `edca7f6` (`Align acceleration structure scratch addresses`) is patch-identical to branch
  commit `8330203` (stable patch ID `1b6005fd5dc2d10420e27a5f58ff5152f0a1b7e3`). It is already present.

## Deliberately deferred

- `22505f5` — `Move terrain BLAS builds off the render thread (#15)` substantially rewrites terrain
  meshing, GPU execution, and synchronization. It must not enter the release candidate without terrain,
  resource-reload, world-transition, validation-layer, and Streamline queue-overlap proof.
- `1f4af90` — `Optimize synchronous entity capture and BLAS reuse (#17)` changes entity capture and
  acceleration-structure reuse on top of the terrain-async commit. It requires entity correctness,
  animation, first-person, world-transition, and DLSSD motion-guide proof before integration.

Deferral is a release-risk decision, not a rejection of those changes. Re-evaluate both commits together
after the current production candidate ships or if a blocking defect requires them.
