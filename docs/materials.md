# Material assets

The runtime material package is compiled with the official `matc` tool from the
same Filament release as `filament-android` (`1.72.0`). Download the Windows
release archive from the Filament GitHub releases page, then run from the
repository root:

```powershell
<path-to-filament-1.72.0>\bin\matc.exe `
  -p mobile `
  -a all `
  -o app\src\main\assets\materials\sphere.filamat `
  app\src\main\materials\sphere.mat
```

The `.mat` source is authoritative. Regenerate the checked-in `.filamat` package
whenever the Filament runtime version changes because material package formats
are version-dependent.

The CPU/material contract uses 10 fixed `float4` ripple slots. The material's
static array and loop bound must remain aligned with `MAX_ACTIVE_RIPPLES` on the
Kotlin side.

## Lunar textures

The runtime surface uses two 2048x1024 equirectangular PNG assets generated from
the official [NASA SVS CGI Moon Kit](https://svs.gsfc.nasa.gov/4720):

- `textures/moon_base_color.png` is artistically color-graded from the 2025
  16-bit sRGB LROC map. It is uploaded to `SRGB8_A8` and sampled by the
  `baseColorTexture` material parameter, so Filament performs the sRGB-to-linear
  conversion required for PBR shading.
- `textures/moon_normal.png` is derived offline from the unsigned 16-bit LOLA
  elevation map. It is uploaded to linear `RGBA8` and sampled by
  `normalTexture`; it must not receive sRGB decoding.

Both textures use a trilinear mipmapped sampler. U repeats across the longitude
seam and V clamps at the poles. Source and generated image row 0 is lunar north;
rows increase southward, while columns and U increase eastward. The procedural
sphere maps `V=0` to north and `V=1` to south. Its tangent points along increasing
U/east and its bitangent along increasing V/south. Because east crossed with south
points inward, `(T, B, N)` has negative handedness. Tangent quaternions use a small
non-zero W bias when needed; following Filament's packed-frame convention, the
bitangent is reconstructed from `cross(N, T) * sign(W)`, so W has the frame's
negative handedness.

The material explicitly sets `flipUV: false`: mesh UVs already use the same
top-to-bottom convention as the uploaded image rows, so Filament must not apply
its default `V := 1-V` transformation. This explicit material setting is the
single authoritative orientation decision: no row flip or green-channel
inversion occurs elsewhere. Base color and normal data thus sample the same
lunar location, and the encoded tangent-space normal uses
`(-dH/dU, -dH/dV, +1)`, where positive X points east and positive Y points south.
Longitude differences wrap, latitude uses one-sided pole differences, and the
longitude component is softly attenuated where `cos(latitude)` approaches zero.

The sampled base color is the neutral surface before the existing ripple logic.
The signed 10-slot accumulation, `tanh` mapping, crest/trough color response,
emissive response, and ripple-driven roughness offsets are unchanged. The base
roughness remains a scalar material parameter; no separate roughness texture is
used. Height data is not packaged or sampled at runtime and does not displace
geometry.

Textures are decoded once while each renderer instance is created, on the
renderer thread. That renderer owns the Filament `Texture` objects, generates
their mip chains, releases decoded `Bitmap` data after upload, and destroys both
textures during the existing deterministic teardown. No bitmap or GPU object is
retained by the `ViewModel` or shared across Activity instances.

## Reproducing the textures

Download the two source files listed in
[`tools/moon-textures/SOURCES.md`](../tools/moon-textures/SOURCES.md) into the
ignored `tools/moon-textures/raw/` directory. On Windows, prepare the pinned
dev-only environment and generate both assets with:

```powershell
py -3 -m venv tools\moon-textures\.venv
.\tools\moon-textures\.venv\Scripts\python.exe -m pip install `
  -r tools\moon-textures\requirements.txt
.\tools\moon-textures\.venv\Scripts\python.exe tools\moon-textures\generate.py
```

The generator validates source filenames, dimensions, unsigned 16-bit data and
SHA-256 checksums before processing. It preserves source precision through
floating-point resize. Luminance, local contrast, source chroma, and authored
cool/warm tints are combined in linear light, followed by exactly one final
linear-to-sRGB encoding before RGB8 quantization. The generator also derives the
normal map from LOLA height in meters. The artistic controls near the top of
`generate.py` are the intended tuning points for saturation, cool/warm balance,
local contrast, and normal strength.

Run its deterministic tests with:

```powershell
.\tools\moon-textures\.venv\Scripts\python.exe -m unittest discover `
  -s tools\moon-textures -p "test_*.py" -v
```

After regenerating textures or editing `sphere.mat`, rebuild `sphere.filamat`
with the `matc` command above. Re-run each generator/compiler into a temporary
output directory and compare SHA-256 values to verify reproducibility.
