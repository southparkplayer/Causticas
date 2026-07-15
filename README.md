# Caustica

Caustica is a ray-traced renderer for Minecraft 26.2's Vulkan backend.
It replaces the vanilla world view with hardware ray tracing and NVIDIA DLSS
features while keeping Minecraft's familiar UI and gameplay intact.

Caustica is early software. Expect bugs, missing visual cases, and frequent
changes while the renderer is being built.

![Caustica ray-traced Minecraft scene](docs/gallery/2026-07-09_21.25.14.jpg)

## Links

- [Upstream project](https://github.com/ComfyFluffy/Caustica)
- [Discord](https://discord.gg/SeWCjyKu2)
- [Upstream Modrinth releases](https://modrinth.com/mod/caustica)
- [Upstream CurseForge releases](https://www.curseforge.com/minecraft/mc-mods/caustica/preview)
- [Gallery](docs/gallery.md)

## Features

- Vulkan hardware path-traced world rendering
- DLSS Ray Reconstruction support
- Streamline DLSS Frame Generation and Multi Frame Generation support
- HDR output
- Dynamic entity rendering in the ray-traced scene
- LabPBR-style material support
- OMM (Opacity Micro-Map) + SER (Shader Execution Reordering) optimizations

## Fork Improvements

This fork adds an energy-preserving EON rough-diffuse BRDF, corrected Kulla-Conty
GGX multiscattering, linear-light material ingestion, and selectable SDR/HDR tone
mapping with a shared PsychoV implementation. HDR and SDR retain separate output
gamut and transfer handling while sharing the same traced scene radiance.

## Requirements

- Minecraft `26.2`, Fabric Loader `0.19.3` or newer, and Fabric API
- Java 25 with a larger main-thread stack (`-Xss16m` recommended)
- **Vulkan graphics backend enabled**
- A GPU and driver with Vulkan ray tracing support
- Windows 10 RS3 64-bit or newer for the bundled Streamline 2.12 runtime
- Current NVIDIA driver and a supported GeForce RTX GPU for DLSS features
- HDR-capable display and OS HDR mode for HDR output
- On Linux, an HDR-capable Wayland compositor and a native Wayland session for HDR output
- Install LabPBR resource pack like [SPBR](https://modrinth.com/resourcepack/spbr) for better visuals

The current production JAR bundles Streamline for Windows x64. Ray Reconstruction is available on
GeForce RTX GPUs when the driver reports support. Frame Generation requires GeForce RTX 40 or 50
Series; Multi Frame Generation above 2x requires a supported GeForce RTX 50 Series GPU.

## Installation

1. Install Fabric Loader for Minecraft `26.2`.
2. Install Fabric API.
3. Put the Caustica jar in your Minecraft `mods` folder.
4. Select Java 25 and add `-Xss16m` to the instance's Java arguments. Adding
   `--enable-native-access=ALL-UNNAMED` is recommended for Java's native-access API.
5. Launch the game with the Vulkan graphics backend.
6. Open Video Settings to adjust Caustica's renderer options.

Do not download or install Streamline separately. The production JAR contains the required signed
Streamline 2.12 plugins and extracts them into `minecraft/caustica-streamline/natives` on first launch.
The NVIDIA binaries remain separately licensed and are not covered by Caustica's LGPL license; see
[the distribution notice](DISTRIBUTION_NOTICE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Using DLSS

### Ray Reconstruction

Open **Options → Video Settings → Ray Tracing** and enable **DLSS Ray Reconstruction**. Select the
desired **DLSS Quality** mode next to it. Ray Reconstruction is enabled by default; when it is off or
unavailable, Caustica traces at native output resolution.

### Frame Generation and Multi Frame Generation

Open **Options → Video Settings → Ray Tracing → DLSS Frame Generation**:

1. Set **Frame Generation Mode** to **Fixed**.
2. Select the desired multiplier. `2x` generates one frame; higher multipliers require GPU support.
3. Leave **UI Recomposition** and **Menu Suspension** enabled for normal play.
4. Use **NVIDIA Reflex: On** or **On + Boost**. Frame Generation forces effective Reflex On.
5. Enable VSync for tear-free MAILBOX presentation, or disable it for uncapped IMMEDIATE presentation.
6. Close the screen so Minecraft can recreate the swapchain when required.

If MAILBOX presentation is unavailable, Caustica fails closed to ordinary FIFO VSync without Frame
Generation. Dynamic MFG is not available in Caustica's Vulkan renderer; all exposed multipliers are fixed.

## Usage Notes

- Caustica is client-side only.
- DLSS Ray Reconstruction and Frame Generation require supported NVIDIA
  hardware and drivers.
- If Minecraft crashes during NVIDIA initialization, confirm Java 25 and `-Xss16m` are active.
- Use Java args to improve performance. Minecraft Launcher default:
  `-XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC`
- Advanced Streamline counters and the VRAM estimate are available from the Frame Generation screen's
  **Advanced Diagnostics** page.
- HDR output requires an HDR swapchain and a correctly configured HDR display.
- When HDR is enabled on Linux, Caustica selects GLFW's native Wayland backend automatically. X11/XWayland surfaces generally do not expose the required HDR10/PQ format.
- If Minecraft falls back to OpenGL after a crash, re-enable the Vulkan backend
  before using Caustica again.

## Compatibility

Caustica takes over the world renderer, so other mods that heavily modify world
rendering, shader pipelines, post-processing, or the Vulkan backend may conflict.
UI-only mods are more likely to work.

## Status

Caustica is not a finished renderer yet. Current work focuses on visual
correctness, world coverage, stability, and making the SDR/HDR presentation
paths behave consistently.

## License

Caustica's project-owned source code and documentation are licensed under the
GNU Lesser General Public License v3.0 or later. See [LICENSE.md](LICENSE.md),
[COPYING](COPYING), and [COPYING.LESSER](COPYING.LESSER).

Release artifacts may bundle NVIDIA DLSS/NGX SDK components under NVIDIA's own
license terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## TODO List

- [ ] Nether/End sky, weather, volumetric fog/clouds
- [ ] NRD + FSR for non-NVIDIA GPUs
- [ ] LOD
- [ ] ReSTIR
