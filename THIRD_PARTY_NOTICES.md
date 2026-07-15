# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA Streamline SDK

Caustica's Windows x64 DLSS Ray Reconstruction, Frame Generation, Multi Frame Generation, Reflex,
and PCL integrations are built against NVIDIA Streamline SDK 2.12.0. Streamline's
SDK source is MIT-licensed; NVIDIA feature runtime components loaded through it
remain proprietary third-party software and are not licensed under the LGPL.

The Streamline SDK and runtime components remain subject to NVIDIA's Streamline
license and the NVIDIA RTX SDKs and Reflex licenses included with the SDK distribution:

<https://github.com/NVIDIA-RTX/Streamline/blob/v2.12.0/license.txt>

The runtime JAR reproduces the complete applicable texts under:

- `META-INF/licenses/nvidia/STREAMLINE-SDK-MIT.txt`
- `META-INF/licenses/nvidia/STREAMLINE-THIRD-PARTY.md`
- `META-INF/licenses/nvidia/NVIDIA-RTX-SDKS.txt`
- `META-INF/licenses/nvidia/NVIDIA-REFLEX-SDK.txt`

See `META-INF/DISTRIBUTION_NOTICE.md` for the license boundary between Caustica's project-owned
code and the incorporated NVIDIA binaries.

Bundled Streamline runtime libraries may include:

- `caustica/natives/windows-x64/sl.interposer.dll`
- `caustica/natives/windows-x64/sl.common.dll`
- `caustica/natives/windows-x64/sl.dlss_d.dll`
- `caustica/natives/windows-x64/sl.dlss_g.dll`
- `caustica/natives/windows-x64/sl.reflex.dll`
- `caustica/natives/windows-x64/sl.pcl.dll`
- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/windows-x64/NvLowLatencyVk.dll`

Caustica's `streamline_bridge` native library is project-owned glue code and
follows Caustica's project license unless otherwise noted.

## EON Diffuse

The rough-diffuse BRDF implementation is derived from `portsmouth/EON-diffuse`:

<https://github.com/portsmouth/EON-diffuse>

MIT License

Copyright (c) 2024 Jamie Portsmouth

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## RenoDX PsychoV

The PsychoV tone-mapping implementation is derived from RenoDX:

<https://github.com/clshortfuse/renodx>

MIT License

Copyright (c) 2025 Carlos Lopez Jr.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
