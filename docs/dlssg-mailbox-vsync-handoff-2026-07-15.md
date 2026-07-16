# DLSS-G + MAILBOX VSync handoff

Date: 2026-07-15

Repository/worktree: `C:\Users\Administrator\Documents\Caustica-dlssg-pacing`

Branch: `codex/dlssg-pacing`

Current source commit inspected: `24eadc2 Implement physical MAILBOX VSync telemetry`

## User-required invariant

The conclusion that “MAILBOX plus DLSS-G cannot work in Caustica” is rejected. The user has observed smooth MAILBOX VSync with DLSS-G in Caustica and in older mods. That is runtime evidence that the combination is possible and remains the target.

The target is fixed 2x DLSS-G on a 240 Hz display with tear-free physical MAILBOX VSync. A frame cap is not VSync. `225`, `x3`, AutoCap, deadline pacing, and overlay-only FPS are not substitutes.

## Current state: what is proven

The current failed run did not fail at DLL loading, feature discovery, resource tagging, or physical MAILBOX creation.

Current runtime files:

- [`latest.log`](/C:/Users/Administrator/AppData/Roaming/PrismLauncher/instances/26.2(2)/minecraft/logs/latest.log)
- [`sl.log`](/C:/Users/Administrator/AppData/Roaming/PrismLauncher/instances/26.2(2)/minecraft/caustica-streamline/sl.log)
- [`dlssg-acceptance.json`](/C:/Users/Administrator/AppData/Roaming/PrismLauncher/instances/26.2(2)/minecraft/caustica-streamline/dlssg-acceptance.json)
- Deployed JAR: `C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances\26.2(2)\minecraft\mods\caustica-0.1.0.jar`
- Current deployed/build SHA-256: `5AA7EC62C1B416C6633C62B9FFE20D900B760D453088ACBFA4DCB56965BB3107`

Current `latest.log` evidence:

- Lines 166-169: options were bound with fixed mode and generated count; options mode is `1`, resources are valid (`valid=0xf`, `memory=0xf`), status is `0`, and the proxy present path is running.
- Lines 175-182: requested FIFO/FIFO_RELAXED was normalized to physical MAILBOX; native telemetry reports `nativePresentMode=MAILBOX`, `nativeCreateResult=0`, and `nativeProxyDispatch=true`.
- Lines 183-186: after the MAILBOX swapchain, 120 world presents still report `presented=1`, despite fixed DLSS-G options and valid resources. This means no interpolated presentation was confirmed in that interval.

The acceptance report therefore proves a current no-generation observation, not a general incompatibility:

```text
generatedFramesConfirmed=false
maximumNumFramesActuallyPresented=1
nativeProxyDispatch=true
nativePresentMode=MAILBOX (in the successful MAILBOX generations)
nativeCreateResult=0
```

## Historical success that must not be discarded

The proven Caustica run directly recorded real DLSS-G generation, not merely an overlay value. The historical log reported `2 total frames, 1 interpolated` repeatedly after multiple swapchain recreations. That value came from `DLSSGState.numFramesActuallyPresented`.

The user also reports older mods achieving the same MAILBOX + DLSS-G behavior. Those observations falsify any claim that physical MAILBOX inherently prevents DLSS-G.

The RADSER comparison is also positive evidence:

- [`swapchain.cpp`](/C:/RadSER/MCVR/src/core/vulkan/swapchain.cpp:121) selects MAILBOX for VSync when available.
- [`streamline_context.cpp`](/C:/RadSER/MCVR/src/core/render/streamline_context.cpp:586) uses fixed DLSS-G and `eBlockPresentingClientQueue`.
- [`render_framework.cpp`](/C:/RadSER/MCVR/src/core/render/render_framework.cpp:1077) routes one real application present through Streamline; generated presents remain Streamline-owned.
- RADSER logs contain the same unsupported common Vulkan command-hook warnings and still record active DLSS-G with `lastPresented=3`, approximately 79.72 application FPS × 3 = 239.15 internal presentations/s.

## What the previous conclusion got wrong

The presence or absence of `sl.dlss_g.json {"vSyncConfig":1}` is not established as the necessary cause of DLSS-G engagement.

Commit `31d535c` added that development-only configuration, and `39f8ece` removed it. It is a relevant historical difference and may affect the Streamline VSync/pacing behavior, but it cannot be promoted to “the cause” without an A/B runtime proof. Later production runs without that JSON did generate frames when VSync was off. The user’s successful Caustica and older-mod observations further require preserving the mailbox-success hypothesis.

Likewise, these are not sufficient root-cause claims:

- `VSync with FG: not supported` in [`sl.log:265`](/C:/Users/Administrator/AppData/Roaming/PrismLauncher/instances/26.2(2)/minecraft/caustica-streamline/sl.log:265) is Streamline 2.12’s Vulkan capability flag for its D3D12-only RSync path, not proof that physical Vulkan MAILBOX + FG cannot work.
- `RSync will not run because it was not initialized` is expected for Vulkan in SDK 2.12.
- `SyncInterval=0` is not Vulkan’s present-mode selector; Vulkan uses the physical `VkPresentModeKHR` swapchain mode.
- The `CmdBindPipeline`, `CmdBindDescriptorSets`, and `BeginCommandBuffer` warnings are not a DLSS-G engagement blocker; RADSER reports the same warnings while DLSS-G is active.
- The zero-extent warning is associated with the null clear-tag path. The native world-frame trace reports a valid 3840×2160 backbuffer subrect.
- `225`, AutoCap, deadline pacing, and any application frame limiter are not the missing VSync mechanism.

## Correct diagnostic boundary

The current evidence places the failure after successful application-side plumbing and at or immediately before Streamline’s internal interpolation/presentation decision:

```text
valid fixed options
  -> valid depth/motion/HUD-less/UI/backbuffer tags
  -> PRESENT_START and one proxy present
  -> physical MAILBOX created successfully
  -> Streamline state status = OK
  -> current state reports presented = 1
  -> interpolated presentation is not confirmed
```

That boundary is not yet an identified proprietary branch. Do not call it “VSync unsupported,” “MAILBOX incompatible,” or “missing vSyncConfig” without a controlled comparison that produces `numFramesActuallyPresented > 1` in both states.

## Required next proof experiment

Use read-only runtime captures before changing code:

1. Reproduce the known-good Caustica state and preserve the exact JAR SHA, Streamline variant/DLL hashes, `sl.dlss_g.json` presence/content, TOML settings, display mode, and VSync state.
2. Capture the failed current state with the same fields.
3. For both runs record, at the same time window:
   - native physical present mode and swapchain handle;
   - Streamline options mode and requested generated count;
   - `numFramesActuallyPresented` and `maximumNumFramesActuallyPresented`;
   - acquire/present/proxy event order and present wait count;
   - Reflex mode/interval and actual application cadence;
   - FrameView `MsBetweenPresents` and `MsBetweenDisplayChange`.
4. Acceptance requires both `numFramesActuallyPresented > 1` and physical display-change intervals clustering around the measured 240 Hz period (~4.167 ms for exactly 240 Hz). An overlay value alone is not sufficient.
5. Only after that A/B should the implementation choose among the remaining hypotheses: Streamline plugin behavior/configuration, manual-hook lifecycle/ownership, queue parallelism, swapchain churn/repeated options, or present cadence.

## Implementation guardrails for the next agent

- Preserve Streamline 2.12.
- Preserve physical MAILBOX when VSync is requested and MAILBOX is available.
- Keep fixed DLSS-G active; do not switch to Auto/Dynamic for this proof.
- Keep Streamline as the generated-frame/present owner.
- Do not reintroduce the rejected 225 AutoCap or deadline-pacer bloat.
- Do not manually add NVAPI/NVCPL or `vkWaitForPresentKHR` until tracing proves the existing owner is missing a required layer.
- Do not declare success from Java FPS, an overlay, or `numFramesActuallyPresented` alone; require FrameView display-change proof.

No source, build, deployment, driver, window, or process changes were made while creating this handoff.
