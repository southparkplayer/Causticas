# Settings Redesign Plan

## Goal

Make every user-facing renderer setting reachable through stable catalog metadata that drives navigation, search, Essentials, runtime status, and coverage tests.

## Progress

- [x] Catalog, stable routes, metadata search, and coverage tests
- [x] Essentials plus dedicated HDR and Frame Generation pages
- [x] Responsive layout, wrapped status, visible reset actions, and page reorganization
- [x] Shared runtime behavior, documentation cleanup, and production artifact build

## Constraints

- Preserve saved `OVERVIEW`, `OUTPUT`, and `VIEW` routes.
- Keep `RtResolutionScale.INPUT_RATIO_TENTHS` as the sole DLSS input scaling authority.
- Use adapter-reported Frame Generation limits when available.
- Keep configured values distinct from effective values controlled by launch properties.

## Offline Follow-Up

- [x] Require 64 samples on every valid tile pixel before adaptive cadence or reduced batches begin.
- [x] Add timed build execution with heartbeats, process-tree termination, and focused test filters.
- [ ] Replace configured workload estimates with GPU-read adaptive path counts.
- [ ] Add fixed-scheduler reference captures and DLSS-D comparisons.
