# Streamline DLSS Frame Generation Integration Plan

## Goal

Implement the complete Streamline 2.12 DLSS Frame Generation feature set without forking or replacing
Minecraft's surface lifecycle. Minecraft remains the owner of `GpuSurface` configuration. Streamline owns
the intercepted Vulkan swapchain and generated presentation. Caustica supplies one coherent frame token,
camera constants, resources, options, and Reflex/PCL markers for each real present.

The implementation must never acquire, render, or present generated swapchain images itself.

## Non-negotiable invariants

1. `slInit` runs before the first Vulkan call and before the Vulkan device is created.
2. If Streamline bootstrap succeeds, every mandatory intercepted Vulkan entry point is always called through
   the Streamline proc-address path, even when DLSS-G is currently off.
3. Native fallback is selected only before instance creation. There is no mid-lifecycle mixing of raw and
   proxied swapchain calls.
4. Each real present has exactly one Streamline frame token. Constants, tags, Reflex/PCL markers, options,
   and the intercepted present all refer to that token and viewport.
5. Caustica never performs an extra acquire or present for a generated frame.
6. DLSS-G is set to `Off` before resize, minimize/restore, fullscreen transition, HDR/present-mode change,
   swapchain destruction, or plugin unload.
7. Invalid frames tag null resources and suspend interpolation. Stale world inputs are never reused for a
   menu, loading screen, paused transition, or failed render.
8. Streamline structs are constructed in C++. Java uses a stable, versioned Caustica bridge ABI and does not
   encode Streamline C++ struct sizes or offsets.
9. Streamline exclusively owns Ray Reconstruction, DLSS-G, and the Reflex/PCL integration. Caustica never
   initializes a second direct-NGX client in the same process or device lifetime.
10. Production mode verifies NVIDIA signatures, uses the embedded Project ID, and treats a numeric application ID as optional metadata.

## Ownership model

```text
Minecraft render loop
  -> Minecraft GpuSurface lifecycle and normal command submission
       -> thin Mixin adapters at existing Vulkan calls
            -> StreamlineRuntime state/coordinator
                 -> versioned native C ABI
                      -> Streamline interposer and DLSS-G/Reflex/PCL plugins
                           -> native Vulkan loader and Streamline-owned generated presentation

Caustica renderer
  -> immutable FrameGenerationInputs snapshot
       -> DLSS-G controller validates and submits constants/tags
```

### Layer responsibilities

#### Native bridge

- Securely load the pinned Streamline distribution.
- Own `slInit`/`slShutdown`, feature functions, proc addresses, error callbacks, and plugin load state.
- Construct all `sl::` types from stable Caustica POD inputs.
- Expose typed operations such as `begin_frame`, `submit_frame_inputs`, `set_options`, `get_state`,
  `set_reflex_options`, `reflex_sleep`, and `pcl_marker`.
- Proxy all mandatory Vulkan calls from `sl_hooks.h`: surface create/destroy, swapchain create/destroy,
  swapchain-image enumeration, acquire, present, and device-wait-idle. Instance/device creation also uses
  Streamline proxies so Streamline supplies its extensions, features, and queues.
- Make the presentation API error callback lock-free/non-blocking: record the first error into atomics or a
  fixed buffer and return immediately.

#### Streamline runtime and swapchain coordinator

- Own the process lifecycle and an explicit state machine; no renderer class initializes or shuts down the
  SDK opportunistically.
- Track actual native plugin-loaded state, desired user mode, active swapchain generation, capability state,
  pending transition, and latched error independently.
- Use `Minecraft.invalidateSurfaceConfiguration()` to request Minecraft's normal swapchain recreation path.
- Apply feature load/unload only after the old swapchain has been destroyed and before the new one is created.
- Keep Reflex and PCL loaded for the process lifetime after a successful bootstrap.

#### Thin Mixin adapters

- Route calls; do not contain feature policy, camera math, option selection, or error recovery.
- Keep instance/device hooks separate from surface/swapchain hooks so upstream mapping changes are localized.
- Require every target seam and fail during development if an upstream method signature changes.
- Add lifecycle notifications immediately before/after Minecraft's existing `configure`, acquire, submit, and
  present operations; do not replace the overall method.

#### DLSS-G controller

- Own the frame token, frame index, viewport, marker state, effective options, resource validity, and runtime
  state query.
- Accept an immutable `FrameGenerationInputs` snapshot from the renderer.
- Submit constants and tags exactly once, on the presenting/render thread, before the matching present.
- Set options only when the effective value changes, except for deliberate transient Off/On transitions.
- Decode all `DLSSGStatus` bits into user-facing diagnostics and immediately suspend on a non-OK status.

#### Renderer input producer

- Know nothing about plugin loading, frame pacing, swapchain recreation, or menu settings.
- Produce depth, motion vectors, HUD-less color, optional alpha-only UI, extents/formats/layouts, camera
  constants, and reset/validity flags.
- Finish input writes and resource barriers before the tags become visible to Streamline.

## Vulkan and swapchain lifecycle

### Bootstrap

1. Read persisted user intent before Vulkan initialization.
2. Load Streamline, request DLSS-G, Reflex, and PCL, and enable manual hooking plus frame-based tagging.
3. Create the Vulkan instance and device through Streamline proc addresses. This ensures all required
   extensions, features, extra queues, and native optical-flow setup are negotiated before device creation.
4. Query feature support and versions after physical-device selection.
5. Before the first swapchain is created, choose whether the DLSS-G plugin is loaded for that swapchain.

### Mode transition requiring swapchain recreation

`Off -> any generated mode` and `any generated mode -> Off` use this transaction:

1. Persist requested settings, but do not change the active mode in-place.
2. Set the active viewport to DLSS-G `Off` on the present thread.
3. Mark the coordinator transition pending and call `Minecraft.invalidateSurfaceConfiguration()`.
4. At the next normal surface configure, allow Minecraft to wait its graphics queue and destroy the old
   swapchain through the Streamline proxy.
5. Change `slSetFeatureLoaded(kFeatureDLSS_G, ...)` after destruction.
6. Create and enumerate the replacement swapchain through the Streamline proxy.
7. On successful configure, reset history, re-query state/capabilities, and activate the requested options on
   the next valid game frame.
8. On failure, leave DLSS-G Off, retain a diagnostic, and let Minecraft's existing surface recovery run.

Changing Fixed/Dynamic/Auto mode, multiplier, target FPS, or UI recomposition while the plugin is already
loaded updates options for the next present and does not recreate the swapchain unless Streamline reports
that the current configuration is invalid.

### Resize, fullscreen, HDR, minimize, restore, and present-mode changes

- A configure-start notification first sets DLSS-G Off and invalidates frame history.
- The normal Minecraft configure path remains authoritative.
- A configure-success notification records the new extent, format, image count, and present mode, re-queries
  state, and resumes only after a new valid input snapshot exists.
- A zero-sized/minimized surface stays suspended.
- Streamline 2.12 does not support FIFO VSync with Vulkan DLSS-G. Preserve Minecraft's VSync setting but,
  when DLSS-G and Reflex are requested, normalize FIFO/FIFO_RELAXED to MAILBOX if the surface supports it.
  This is RADSER's tear-free vblank-replacement compatibility path; if MAILBOX is unavailable, keep FIFO
  and fail DLSS-G closed instead of silently disabling VSync.

### Shutdown

1. Set DLSS-G Off.
2. Wait for pending Streamline input consumption when required.
3. Destroy the swapchain/surface through proxies.
4. Call `slShutdown` before Minecraft destroys the Vulkan device and instance.
5. Unload the bridge only after shutdown completes.

## Frame lifecycle and pacing

The controller uses one active-frame state machine so synchronous redraws and minimized frames cannot reuse
an old token.

| Point | Required action |
| --- | --- |
| Start of simulation | Obtain a new token, apply changed Reflex options, call `slReflexSleep`, mark Input Sample and Simulation Start |
| Before render | Mark Simulation End; if a synchronous render has no token, create a present-only token |
| Render recording begins | Mark Render Submit Start |
| Final inputs complete | Submit common constants and resource tags once for the active token |
| Immediately before proxy present | Mark Render Submit End and Present Start; ensure options correspond to this present |
| Immediately after proxy present | Mark Present End, query lightweight DLSS-G state, close the token |
| Left mouse trigger | Emit Trigger Flash when Reflex state says the driver controls the flash indicator |

`slReflexSleep` and PCL markers continue to be called when their plugins are supported even if the visible
Reflex option is Off. DLSS-G forces effective Reflex Low Latency while generation is active, as required by
Streamline, while preserving the user's stored Reflex preference for when DLSS-G is disabled.

## Frame inputs

### Common constants

- Row-major, unjittered matrices with verified transform direction.
- Current projection and inverse projection.
- `clipToPrevClip` and `prevClipToClip` derived by tested matrix identities.
- Pixel-space motion-vector scale `{1/renderWidth, 1/renderHeight}`.
- Camera position and normalized up/right/forward vectors.
- Near/far/FOV/aspect from the actual projection, not hardcoded values.
- Reversed-depth flag matching the RT depth buffer.
- Camera-motion-included, motion-vector-jittered/dilated, reset, orthographic, and rendering-game-frames flags
  set from real renderer state.
- Reset on first frame, resize, mode transition, world/dimension change, camera cut, shader/resource rebuild,
  or any invalid predecessor.

Matrix math and native layout conversion require deterministic unit tests with translation, rotation,
projection, and camera-cut fixtures.

### Required tags

- Depth: real extent, `R32_SFLOAT`, correct general/read layout, reversed-Z semantics.
- Motion vectors: real extent, actual `R16G16_SFLOAT` format, pixel units, correct sign/direction.
- HUD-less color: full final output without screen-fixed UI.
- UI alpha: full-resolution `R32_SFLOAT` alpha-only image derived from the transparent overlay when populated.
- Backbuffer: omitted for full-frame operation; tag only if a subregion is genuinely used.

Tags use `eValidUntilPresent` because the inputs remain unchanged until their matching proxy present. When
the frame is not valid, submit null tags for every previously used buffer type and set DLSS-G Off with
resource retention only for short, safe transitions.

### SDR and HDR

- SDR uses a full-resolution HUD-less color image matching the final SDR color semantics and format declared
  to Streamline.
- HDR must not reuse the existing FP16/scRGB intermediate as the DLSS-G color input. Add a HUD-less,
  PQ-encoded 10-bit image using the exact 10-bit swapchain format and HDR10/BT.2100 path. Composite UI
  separately through the preferred, alpha-only `eUIAlpha` tag.
- UI recomposition is enabled only when both HUD-less and UI resources are valid and format-compatible.

### Synchronization

- Default to automatic `eBlockNoClientQueues`. Maintain one tagged-input resource slot per
  application-visible swapchain image and associate each real present's
  `inputsProcessingCompletionFence`/value with the slot used by that present.
- Preserve Minecraft's acquire/present binary semaphore chain exactly. The proxy acquire and proxy present
  are the only acquire/present operations.
- Wait only when the corresponding application-image slot is reused. If Streamline supplies an invalid
  fence/value, quiesce once and remain in `eBlockPresentingClientQueue` for that swapchain generation; never
  hide a per-frame `vkDeviceWaitIdle` behind the throughput mode.
- Swapchain/resource teardown drains every outstanding slot value before destroying tagged images.

## Complete feature and menu surface

Add one discoverable `DLSS Frame Generation...` submenu to the existing RT/video settings rather than
crowding the base page. The submenu separates user intent from effective runtime state.

### User controls

- Mode: Off, Fixed, Dynamic, Auto (Legacy).
- Fixed multiplier: 2x through `(numFramesToGenerateMax + 1)x`, capability-clamped.
- Dynamic target FPS: Auto (display refresh) or explicit target.
- UI recomposition: Auto/On/Off, with effective state shown.
- Reflex: Off, On, On + Boost. Effective state shows forced On while DLSS-G is active.
- Reflex frame limit in user-facing FPS/Unlimited, converted internally to the SDK interval.
- Fullscreen-menu detection: enabled by default, available under Advanced.
- Development builds only: Show Interpolated Frame and Streamline debug/stat overlays.

### Engine-derived options, not unsafe user toggles

- Dynamic-resolution flag and target extent are driven by the renderer's actual resolution policy. They are
  shown read-only because setting the flag during fixed-ratio rendering harms quality/performance.
- Retain-resources-when-Off is used only for short transient suspension.
- VRAM estimation is an on-demand diagnostic query, never a per-frame state query.
- Queue parallelism is an Advanced option only after the input-consumption fence path is proven.

### Capability gating and diagnostics

- Unsupported controls are disabled/hidden based on `slIsFeatureSupported` and `slDLSSGGetState`.
- Dynamic mode requires `bIsDynamicMFGSupported`.
- Multipliers use `numFramesToGenerateMax`.
- The VSync control remains available. Its Vulkan DLSS-G compatibility mode reports MAILBOX + Reflex
  explicitly, while a surface without MAILBOX reports the unsupported FIFO boundary.
- Show driver/hardware support reason, Streamline/NGX feature versions, active mode, generated-frame count,
  status bits, minimum dimension, UI recomposition state, and optional VRAM estimate.
- Applying an Off/On boundary change explicitly reports that the swapchain will be recreated; it does not
  require a full game restart when bootstrap succeeded.

## Fail-closed behavior

- Bootstrap failure before instance creation: use the unmodified Vulkan path and hide/disable Streamline
  features for the session.
- Failure after proxied instance/device creation: keep using Streamline Vulkan proxies, set DLSS-G Off, and
  never switch intercepted calls to raw Vulkan mid-session.
- API-error callback: latch the error without logging/blocking on the present thread; drain it later.
- Non-OK DLSS-G runtime status: set Off immediately, decode every status bit, null tags, and require a clean
  reset before retry.
- Unsupported HDR format, low resolution, unavailable MAILBOX VSync compatibility, invalid inputs, or absent world frame: Off, not a
  partially active approximation.

## Implementation phases and proof gates

### Phase 0: return to an architectural baseline

- Preserve the two existing Streamline commits.
- Discard the current uncommitted exploratory frame-submission rewrite after this plan is approved.
- Keep a patch/export if useful for reference; do not merge it as architecture.

Gate: clean worktree at `2263b26`, with this plan retained separately or recommitted as the first planning
commit.

### Phase 1: harden the native bridge and lifecycle

- Replace Java-side Streamline struct-offset marshaling with versioned bridge PODs and native typed calls.
- Make native plugin-loaded state authoritative.
- Implement early/bootstrap failure boundaries, non-blocking errors, state decoding, version/requirements
  queries, and production validation.
- Add ABI/unit tests for all bridge inputs/outputs.

Gate: native development and production bridge builds plus ABI tests pass; no renderer changes.

### Phase 2: complete Vulkan ownership and swapchain transitions

- Route every mandatory hook, including all Caustica `vkDeviceWaitIdle` calls.
- Add coordinator notifications around existing Minecraft configure/acquire/present seams.
- Implement Off/On swapchain recreation using `invalidateSurfaceConfiguration()`.
- Remove the direct-NV swapchain latency pNext path from the Streamline route.

Gate: proxy/native-off startup, repeated Off/On transitions, resize, minimize/restore, fullscreen, and close
complete with validation enabled and no leaked swapchain resources.

### Phase 3: Reflex/PCL frame-token integration

- Implement the one-token frame state machine, all markers, sleep, Reflex options/state, synchronous redraw
  handling, and trigger flash.
- Remove the old direct-NV Reflex runtime only after Streamline markers are proven.

Gate: markers share the presented frame index, the Streamline log has no missing-common-constants warning,
and Reflex latency stats populate.

### Phase 4: SDR fixed 2x DLSS-G

- Build the immutable input snapshot and verified common constants.
- Tag depth, motion, HUD-less SDR, and UI in one final input command-buffer seam.
- Enable fixed one-generated-frame mode only.

Gate: Streamline development input visualization validates every input; `DLSSGStatus == OK`; external
presentation measurement and `numFramesActuallyPresented` prove generated frames rather than duplicate
presents.

### Phase 5: complete modes and synchronization

- Add all fixed multipliers, Dynamic, Auto (Legacy), target FPS, transient menu suspension, runtime status,
  on-demand VRAM estimate, and optional no-client-queue mode with its input fence wait.

Gate: every capability-gated mode is exercised on supporting hardware; unsupported requests fail closed.

### Phase 6: HDR10 and UI recomposition

- Add exact-format 10-bit PQ HUD-less input and separate high-alpha-precision UI tag.
- Validate HDR format/color-space metadata and UI recomposition.

Gate: no HDR-format status failure; UI remains stable in motion; SDR and HDR paths both survive transitions.

### Phase 7: full menu integration and cleanup

- Add the submenu, translations, capability/status model, explicit Apply transaction, conflict messages, and
  development-only diagnostics.
- Remove `RtFramePresenter`, direct NGX DLSS-G exports/code/binaries, and direct-NV Reflex ownership.
- Update third-party notices, build packaging, and developer documentation.

Gate: no remaining manual generated-present path or direct NGX DLSS-G symbol; production package contains
only the intended signed Streamline runtime and the RR-specific NGX pieces.

### Phase 8: release verification

- Native ABI tests, Java tests, full Gradle build, mixin audit, packaged-native hash/signature checks.
- Vulkan validation with zero new errors.
- Long-run memory test covering repeated resize and mode transitions; verify `presentCommon` once per real
  frame and stable memory.
- Runtime matrix: supported/unsupported GPU, SDR/HDR, world/menu/loading/paused, VSync off, MAILBOX VSync
  compatibility, FIFO-only VSync rejection, fixed modes,
  Dynamic, Auto, UI on/off, fullscreen/windowed, minimize/restore, dimension/world change, resource reload,
  and clean shutdown.
- Capture Streamline logs, state counters, external presentation trace, and artifact hashes as proof.

Gate: source/build proof and runtime/IQ proof are reported separately; the feature is not called complete
without both.

## Implemented worktree disposition

Commits `51b2e62` and `2263b26` remain the bootstrap/proxy foundation. The exploratory direct-present path
was removed and rebuilt behind the coordinator/controller boundaries above, including a versioned bridge
ABI, the swapchain recreation transaction, input-consumption fence ownership, exact-format HDR10 input,
alpha-only UI recomposition, and the complete settings submenu.
