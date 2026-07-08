# Candela

Candela is an experimental ray-traced renderer for Minecraft's Vulkan backend.
It replaces the vanilla world view with hardware ray tracing and NVIDIA DLSS
features while keeping Minecraft's familiar UI and gameplay intact.

Candela is early software. Expect bugs, missing visual cases, and frequent
changes while the renderer is being built.

## Features

- Hardware ray-traced world rendering for Minecraft's Vulkan renderer
- DLSS Ray Reconstruction support
- Optional DLSS Frame Generation (experimental)
- Optional HDR output
- Ray-traced terrain lighting and materials
- Dynamic entity rendering in the ray-traced scene
- LabPBR-style material support

## Requirements

- Minecraft `26.2`
- Fabric Loader `0.19.3` or newer
- Fabric API for Minecraft `26.2`
- Java `25`
- Vulkan graphics backend enabled
- A GPU and driver with Vulkan ray tracing support
- NVIDIA RTX GPU and supported driver for DLSS features
- HDR-capable display and OS HDR mode for HDR output

## Installation

1. Install Fabric Loader for Minecraft `26.2`.
2. Install Fabric API.
3. Put the Candela jar in your Minecraft `mods` folder.
4. Launch the game with the Vulkan graphics backend.
5. Open Video Settings to adjust Candela's renderer options.

## Usage Notes

- Candela is client-side only.
- DLSS Ray Reconstruction and Frame Generation require supported NVIDIA
  hardware and drivers.
- Frame Generation is experimental and needs to be enabled by modifying the configuration file.
- HDR output requires an HDR swapchain and a correctly configured HDR display.
- If Minecraft falls back to OpenGL after a crash, re-enable the Vulkan backend
  before using Candela again.

## Compatibility

Candela takes over the world renderer, so other mods that heavily modify world
rendering, shader pipelines, post-processing, or the Vulkan backend may conflict.
UI-only mods are more likely to work.

## Status

Candela is not a finished renderer yet. Current work focuses on visual
correctness, world coverage, stability, and making the SDR/HDR presentation
paths behave consistently.
