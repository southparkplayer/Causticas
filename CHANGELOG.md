# Changelog

## Unreleased

### Added

- Streamline 2.12 DLSS Frame Generation and fixed Multi Frame Generation from 2x through the
  GPU-reported maximum.
- Physical MAILBOX VSync handoff, automatic queue policy, input-slot retirement, and Reflex integration.
- Streamline-owned DLSS Ray Reconstruction with expanded disocclusion, bias-current-color, material,
  motion, and refractive guides.
- Coherent glass and animated-water temporal guides.
- Player-facing DLSS Ray Reconstruction control and a simplified Frame Generation screen.
- Manual production-candidate workflow with pinned shader tools and signed-runtime verification.

### Changed

- Streamline production natives are bundled in the Windows x64 JAR and extracted automatically.
- Streamline extraction is atomic and content-addressed so updates cannot overwrite loaded runtime DLLs.
- Production acceptance reports are event-driven and otherwise limited to one refresh per minute.
- Device-idle telemetry now records actual controlled and steady-state counts plus reasons.

### Fixed

- Frame-scoped DLSSG options publication and one-present-per-rendered-frame ordering.
- VSync frametime pumping caused by incompatible queue policy.
- Glass blur, refractive ghosting, animated-water reflection reprojection, and total-internal-reflection
  guide initialization.
- Oversized or malformed LabPBR atlases now fail closed to neutral materials.
- Restored the selectable PsychoV23 tonemapper comparison view.

The release version and date are intentionally unset until the release candidate passes the runtime matrix.
