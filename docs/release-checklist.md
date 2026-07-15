# Production Release Checklist

This checklist prepares a candidate. It does not authorize tagging, pushing, uploading, or publishing.

## Identity and source

- [ ] Choose the release version and update `mod_version` in `gradle.properties`.
- [ ] Confirm `fabric.mod.json`, README, changelog, license, and third-party notices match the candidate.
- [ ] Reconcile the intended upstream delta against `docs/upstream-release-delta-2026-07-15.md`; do not
      absorb the terrain/entity scheduling rewrites without their own proof run.
- [ ] Confirm the worktree contains no unrelated or untracked release inputs.

## NVIDIA distribution and platform compliance

- [ ] Confirm only NVIDIA's production redistributable runtime binaries are packaged; exclude development
      plugins, developer tools, symbols, SDK headers, samples, and source.
- [ ] Confirm the complete Streamline MIT, Streamline third-party, NVIDIA RTX SDKs/DLSS/NGX, and Reflex
      license texts are present under `META-INF/licenses/nvidia/`.
- [ ] Confirm Caustica's LGPL applies only to project-owned code and the public download carries
      `DISTRIBUTION_NOTICE.md` and `THIRD_PARTY_NOTICES.md`.
- [ ] Confirm the NVIDIA project/application identity used for the public build is approved for release.
- [ ] Complete NVIDIA attribution/trademark review before using NVIDIA marks in release artwork or pages.
- [ ] If the release is commercial, submit NVIDIA's required pre-release software notification.
- [ ] Confirm the selected hosting platforms permit the incorporated NVIDIA runtime binaries.

## Build and artifact

- [ ] Use Java 25, Slang 2026.13, the pinned Vulkan SDK, and Streamline SDK 2.12.0 production binaries.
- [ ] Run `gradlew.bat clean build` on Windows x64.
- [ ] Require Java tests, SPIR-V validation, native ABI tests, packaged production initialization, and
      Authenticode verification to pass.
- [ ] Inspect the JAR for production `streamline.properties`, shaders, bridge, signed Streamline plugins,
      licenses, and Fabric metadata.
- [ ] Confirm Streamline extracts atomically into a content-addressed directory and production rejects
      loose native-path overrides or development behavior files.
- [ ] Confirm the sources JAR contains no NVIDIA binaries or generated SPIR-V.
- [ ] Record the candidate SHA-256.

## Runtime matrix

- [ ] Start a fresh JVM that loads the exact candidate hash with no Caustica launch overrides.
- [ ] Verify DLSSD on/off and every quality mode used in release testing.
- [ ] Verify DLSSG off, IMMEDIATE 2x, and MAILBOX 2x.
- [ ] Verify every fixed multiplier through the GPU-reported maximum.
- [ ] Verify SDR and HDR, UI recomposition on/off, menus, chat, resize, fullscreen, world transitions,
      teleport, resource reload, and a sustained water/glass scene.
- [ ] Require generated presentation count to reach the requested multiplier, no DLSSD fallback, no queue
      fallback, no steady-state device idle, no validation errors, and no sustained cadence oscillation.
- [ ] Run `tools/verify-streamline-acceptance.ps1` against the final schema-7 report.
- [ ] Capture FrameView cadence evidence for the final MAILBOX configuration.

## Publication gate

- [ ] Obtain explicit user instruction: `Release Now`.
- [ ] Only then tag and push the approved candidate commit, upload the runtime JAR, and publish notes.
- [ ] Download the public asset and verify its SHA-256 matches the approved candidate.
