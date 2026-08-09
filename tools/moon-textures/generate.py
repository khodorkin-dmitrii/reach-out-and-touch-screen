"""Build deterministic runtime lunar textures from the NASA SVS CGI Moon Kit."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image
import tifffile


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_RAW_DIR = SCRIPT_DIR / "raw"
DEFAULT_OUTPUT_DIR = SCRIPT_DIR.parents[1] / "app" / "src" / "main" / "assets" / "textures"

COLOR_FILENAME = "lroc_color_16bit_srgb_4k.tif"
HEIGHT_FILENAME = "ldem_16_uint.tif"
COLOR_SHA256 = "9731fa8af425b6c2f88f277ecca82bf8c603f3743894f64ed7b25c5bfefa22ff"
HEIGHT_SHA256 = "45a2b32d56e81ed30db07fead8abc842b249b6511219d9ca2c53f81bc2dc5d62"
COLOR_DIMENSIONS = (4096, 2048)
HEIGHT_DIMENSIONS = (5760, 2880)
OUTPUT_DIMENSIONS = (2048, 1024)

BASE_COLOR_FILENAME = "moon_base_color.png"
NORMAL_FILENAME = "moon_normal.png"

# First-pass artistic controls intended for later device-based visual tuning.
SATURATION = 1.65
COOL_WARM_BALANCE = 1.0
LOCAL_CONTRAST = 0.16
NORMAL_STRENGTH = 7.0

# Artistic grade values below are evaluated in linear light. The signed tint
# values were authored as sRGB-code-value offsets around mid-gray, so convert
# those offsets once before mixing them with linear luminance and chroma.
TINT_SRGB_PIVOT = 0.5
LINEAR_OUTPUT_MIN = 0.055
LINEAR_OUTPUT_MAX = 0.72

MOON_RADIUS_METERS = 1_737_400.0
HEIGHT_UINT_OFFSET = 20_000.0
HEIGHT_UNIT_METERS = 0.5
POLAR_COSINE_LIMIT = 0.12


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_output_dimensions(width: int, height: int) -> None:
    if width <= 0 or height <= 0 or width != 2 * height:
        raise ValueError(f"Output dimensions must be a positive 2:1 pair, got {width}x{height}")


def validate_source(
    path: Path,
    expected_filename: str,
    expected_sha256: str,
    expected_dimensions: tuple[int, int],
    expected_channels: int,
) -> np.ndarray:
    if path.name != expected_filename:
        raise ValueError(f"Expected source filename {expected_filename}, got {path.name}")
    if not path.is_file():
        raise FileNotFoundError(f"Required NASA source is missing: {path}")
    actual_sha256 = sha256_file(path)
    if actual_sha256 != expected_sha256:
        raise ValueError(
            f"SHA-256 mismatch for {path.name}: expected {expected_sha256}, got {actual_sha256}",
        )

    image = tifffile.imread(path)
    expected_shape = (
        (expected_dimensions[1], expected_dimensions[0], expected_channels)
        if expected_channels > 1
        else (expected_dimensions[1], expected_dimensions[0])
    )
    if image.shape != expected_shape or image.dtype != np.uint16:
        raise ValueError(
            f"Expected uint16 {expected_shape} data in {path.name}, got {image.dtype} {image.shape}",
        )
    return image


def resize_bilinear(image: np.ndarray, width: int, height: int) -> np.ndarray:
    """Resize a float array without reducing source precision before interpolation."""
    validate_output_dimensions(width, height)
    if image.ndim not in (2, 3) or image.shape[0] <= 1 or image.shape[1] <= 1:
        raise ValueError(f"Expected a 2D image with optional channels, got {image.shape}")

    source_height, source_width = image.shape[:2]
    x = (np.arange(width, dtype=np.float32) + 0.5) * source_width / width - 0.5
    y = (np.arange(height, dtype=np.float32) + 0.5) * source_height / height - 0.5
    x0 = np.floor(x).astype(np.int32)
    y0 = np.floor(y).astype(np.int32)
    x1 = np.clip(x0 + 1, 0, source_width - 1)
    y1 = np.clip(y0 + 1, 0, source_height - 1)
    x0 = np.clip(x0, 0, source_width - 1)
    y0 = np.clip(y0, 0, source_height - 1)
    wx = x - x0
    wy = y - y0

    if image.ndim == 3:
        horizontal = image[:, x0, :] * (1.0 - wx)[None, :, None] + image[:, x1, :] * wx[None, :, None]
        return horizontal[y0, :, :] * (1.0 - wy)[:, None, None] + horizontal[y1, :, :] * wy[:, None, None]

    horizontal = image[:, x0] * (1.0 - wx)[None, :] + image[:, x1] * wx[None, :]
    return horizontal[y0, :] * (1.0 - wy)[:, None] + horizontal[y1, :] * wy[:, None]


def srgb_to_linear(value: np.ndarray) -> np.ndarray:
    return np.where(value <= 0.04045, value / 12.92, ((value + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(value: np.ndarray) -> np.ndarray:
    value = np.clip(value, 0.0, 1.0)
    return np.where(value <= 0.0031308, value * 12.92, 1.055 * value ** (1.0 / 2.4) - 0.055)


def srgb_delta_to_linear(delta: np.ndarray, pivot: float = TINT_SRGB_PIVOT) -> np.ndarray:
    """Convert an authored signed sRGB offset to a linear-light offset."""
    delta = np.asarray(delta, dtype=np.float32)
    if not 0.0 <= pivot <= 1.0 or np.any(pivot + delta < 0.0) or np.any(pivot + delta > 1.0):
        raise ValueError("sRGB tint delta must remain in range around its pivot")
    pivot_srgb = np.asarray(pivot, dtype=np.float32)
    return srgb_to_linear(pivot_srgb + delta) - srgb_to_linear(pivot_srgb)


def encode_linear_rgb(linear_rgb: np.ndarray) -> np.ndarray:
    """Apply the pipeline's single final linear-to-sRGB encoding boundary."""
    linear_rgb = np.nan_to_num(linear_rgb, nan=0.0, posinf=1.0, neginf=0.0)
    return linear_to_srgb(np.clip(linear_rgb, 0.0, 1.0))


def quantize_srgb8(encoded_srgb: np.ndarray) -> np.ndarray:
    """Quantize bounded encoded sRGB values without applying another transfer function."""
    encoded_srgb = np.nan_to_num(encoded_srgb, nan=0.0, posinf=1.0, neginf=0.0)
    return np.rint(np.clip(encoded_srgb, 0.0, 1.0) * 255.0).astype(np.uint8)


def linear_chroma(linear_rgb: np.ndarray, luminance: np.ndarray) -> np.ndarray:
    """Return bounded RGB-minus-luminance chroma, entirely in linear light."""
    return np.clip(linear_rgb - luminance[:, :, None], -0.07, 0.07)


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    normalized = np.clip((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return normalized * normalized * (3.0 - 2.0 * normalized)


def box_blur_wrap_x_clamp_y(image: np.ndarray, radius: int) -> np.ndarray:
    horizontal = np.zeros_like(image, dtype=np.float32)
    for offset in range(-radius, radius + 1):
        horizontal += np.roll(image, offset, axis=1)
    horizontal /= 2 * radius + 1

    padded = np.pad(horizontal, ((radius, radius), (0, 0)), mode="edge")
    vertical = np.zeros_like(image, dtype=np.float32)
    for offset in range(2 * radius + 1):
        vertical += padded[offset : offset + image.shape[0], :]
    return vertical / (2 * radius + 1)


def color_grade_linear(source_srgb_linear: np.ndarray) -> np.ndarray:
    """Apply the complete artistic grade in linear light."""
    source_srgb_linear = np.nan_to_num(source_srgb_linear, nan=0.0, posinf=1.0, neginf=0.0)
    source_srgb_linear = np.clip(source_srgb_linear, 0.0, 1.0)
    source_luminance = np.sum(
        source_srgb_linear * np.array([0.2126, 0.7152, 0.0722], dtype=np.float32),
        axis=2,
    )
    low, high = np.percentile(source_luminance, (1.0, 99.0))
    tone = np.clip((source_luminance - low) / max(high - low, 1e-6), 0.0, 1.0)
    blurred_tone = box_blur_wrap_x_clamp_y(tone, radius=2)
    tone = np.clip(tone + LOCAL_CONTRAST * (tone - blurred_tone), 0.0, 1.0)
    tone = smoothstep(0.0, 1.0, tone)

    target_luminance = 0.075 + 0.62 * np.power(tone, 0.88)
    neutral = target_luminance[:, :, None] * np.array([0.95, 0.97, 1.02], dtype=np.float32)

    cool_mask = 1.0 - smoothstep(0.30, 0.64, tone)
    warm_mask = smoothstep(0.46, 0.84, tone)
    cool_tint = srgb_delta_to_linear(np.array([-0.025, 0.008, 0.060], dtype=np.float32))
    warm_tint = srgb_delta_to_linear(np.array([0.065, 0.018, -0.018], dtype=np.float32))

    source_chroma = linear_chroma(source_srgb_linear, source_luminance)
    graded = neutral
    graded += COOL_WARM_BALANCE * cool_mask[:, :, None] * cool_tint
    graded += COOL_WARM_BALANCE * warm_mask[:, :, None] * warm_tint
    graded += SATURATION * source_chroma
    return np.clip(
        np.nan_to_num(graded, nan=LINEAR_OUTPUT_MIN, posinf=LINEAR_OUTPUT_MAX, neginf=LINEAR_OUTPUT_MIN),
        LINEAR_OUTPUT_MIN,
        LINEAR_OUTPUT_MAX,
    )


def color_grade(source_srgb_linear: np.ndarray) -> np.ndarray:
    return quantize_srgb8(encode_linear_rgb(color_grade_linear(source_srgb_linear)))


def height_derivatives(height: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Return derivatives toward increasing U/east and increasing image row/south."""
    if height.ndim != 2 or height.shape[1] != 2 * height.shape[0]:
        raise ValueError(f"Height input must be a 2:1 single-channel image, got {height.shape}")

    longitude = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) * 0.5
    latitude = np.empty_like(height, dtype=np.float32)
    latitude[1:-1, :] = (height[2:, :] - height[:-2, :]) * 0.5
    latitude[0, :] = height[1, :] - height[0, :]
    latitude[-1, :] = height[-1, :] - height[-2, :]
    return longitude, latitude


def normal_map_from_height(height_meters: np.ndarray, strength: float = NORMAL_STRENGTH) -> np.ndarray:
    """Encode (-dH/dU, -dH/dV, +1), where V and image rows increase southward."""
    longitude_delta, latitude_delta = height_derivatives(height_meters.astype(np.float32))
    height, width = height_meters.shape
    latitude = np.pi / 2.0 - (np.arange(height, dtype=np.float32) + 0.5) * np.pi / height
    cosine = np.abs(np.cos(latitude))
    limited_cosine = np.maximum(cosine, POLAR_COSINE_LIMIT)
    polar_attenuation = np.minimum(cosine / POLAR_COSINE_LIMIT, 1.0)

    longitude_spacing = MOON_RADIUS_METERS * (2.0 * np.pi / width) * limited_cosine
    latitude_spacing = MOON_RADIUS_METERS * (np.pi / height)
    longitude_slope = longitude_delta / longitude_spacing[:, None]
    longitude_slope *= polar_attenuation[:, None]
    latitude_slope = latitude_delta / latitude_spacing

    normals = np.empty((height, width, 3), dtype=np.float32)
    normals[:, :, 0] = -strength * longitude_slope
    normals[:, :, 1] = -strength * latitude_slope
    normals[:, :, 2] = 1.0
    normals /= np.linalg.norm(normals, axis=2, keepdims=True)
    return np.rint(np.clip(normals * 0.5 + 0.5, 0.0, 1.0) * 255.0).astype(np.uint8)


def save_png(path: Path, pixels: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(pixels, mode="RGB").save(
        path,
        format="PNG",
        optimize=False,
        compress_level=9,
    )


def describe_output(path: Path) -> None:
    with Image.open(path) as image:
        print(
            f"{path}: {image.width}x{image.height} {image.mode}, "
            f"{path.stat().st_size} bytes, SHA-256 {sha256_file(path)}",
        )


def generate(raw_dir: Path, output_dir: Path) -> None:
    width, height = OUTPUT_DIMENSIONS
    validate_output_dimensions(width, height)
    color_path = raw_dir / COLOR_FILENAME
    height_path = raw_dir / HEIGHT_FILENAME

    color_source = validate_source(
        color_path,
        COLOR_FILENAME,
        COLOR_SHA256,
        COLOR_DIMENSIONS,
        expected_channels=3,
    )
    height_source = validate_source(
        height_path,
        HEIGHT_FILENAME,
        HEIGHT_SHA256,
        HEIGHT_DIMENSIONS,
        expected_channels=1,
    )
    print(f"Verified {color_path}: {COLOR_DIMENSIONS[0]}x{COLOR_DIMENSIONS[1]} RGB uint16")
    print(f"Verified {height_path}: {HEIGHT_DIMENSIONS[0]}x{HEIGHT_DIMENSIONS[1]} uint16")

    color_linear = srgb_to_linear(color_source.astype(np.float32) / 65535.0)
    resized_color_linear = resize_bilinear(color_linear, width, height)
    base_color = color_grade(resized_color_linear)

    height_meters = (height_source.astype(np.float32) - HEIGHT_UINT_OFFSET) * HEIGHT_UNIT_METERS
    resized_height = resize_bilinear(height_meters, width, height)
    normal = normal_map_from_height(resized_height)

    base_color_path = output_dir / BASE_COLOR_FILENAME
    normal_path = output_dir / NORMAL_FILENAME
    save_png(base_color_path, base_color)
    save_png(normal_path, normal)
    describe_output(base_color_path)
    describe_output(normal_path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=Path, default=DEFAULT_RAW_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_args()
    generate(arguments.raw_dir.resolve(), arguments.output_dir.resolve())
