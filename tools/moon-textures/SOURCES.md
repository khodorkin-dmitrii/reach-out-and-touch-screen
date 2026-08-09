# Lunar texture sources and attribution

The runtime lunar textures are derived exclusively from the official
[NASA Scientific Visualization Studio CGI Moon Kit](https://svs.gsfc.nasa.gov/4720).
Raw source TIFF files are local preprocessing inputs in the Git-ignored
`tools/moon-textures/raw/` directory and are not distributed in this repository.

## Source files

| Purpose | Filename | Direct NASA SVS URL | Dimensions and interpretation | SHA-256 |
| --- | --- | --- | --- | --- |
| Color | `lroc_color_16bit_srgb_4k.tif` | [Download](https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_16bit_srgb_4k.tif) | 4096x2048 RGB, 16 bits per channel, sRGB; 2025 LROC color map | `9731fa8af425b6c2f88f277ecca82bf8c603f3743894f64ed7b25c5bfefa22ff` |
| Height | `ldem_16_uint.tif` | [Download](https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/ldem_16_uint.tif) | 5760x2880 single-channel unsigned 16-bit LOLA elevation; half-meter units with a +20,000 sample offset | `45a2b32d56e81ed30db07fead8abc842b249b6511219d9ca2c53f81bc2dc5d62` |

NASA describes the color maps as rendering assets assembled from LRO Camera
data and the elevation map as a reformatted LOLA gridded data product. The
generator retains the 16-bit input range until floating-point preprocessing.

## Transformations and outputs

- `moon_base_color.png` — 2048x1024 RGB PNG; the LROC map is resized in linear
  light, tonally remapped to a graphite base, given muted steel-blue/blue-violet
  lowland and dusty warm highland tints, and combined with restrained amplified
  source chroma and local contrast. SHA-256:
  `bc5c309b0dc3b0ed4360cdf7ea28ba85de1d9336aad3cfea31eb2cfbc59835e9`.
- `moon_normal.png` — 2048x1024 RGB PNG; the LOLA samples are converted from the
  unsigned offset representation to meters, resized, differentiated with
  longitude wrap and clamped latitude boundaries, corrected and attenuated near
  the poles, normalized, and encoded as tangent-space RGB. SHA-256:
  `f0e64ae022aa2b3f6c3a4114c1f3de5fc5404d528cf3f8e04e4f98048ae2e679`.

The output color grade is a project-authored artistic interpretation. It is not
a scientifically calibrated mineral-composition map, an official NASA
visualization, or an indication of NASA endorsement.

Credit: Source data: NASA's Scientific Visualization Studio, using imagery and
topographic data from the Lunar Reconnaissance Orbiter.

Usage guidance: [NASA Images and Media Usage Guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/).
