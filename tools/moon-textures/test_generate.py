from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
from PIL import Image

import generate


class MoonTexturePipelineTest(unittest.TestCase):
    @staticmethod
    def decode_normal(pixel: np.ndarray) -> np.ndarray:
        decoded = pixel.astype(np.float64) / 255.0 * 2.0 - 1.0
        return decoded / np.linalg.norm(decoded)

    def test_srgb_round_trip_preserves_representative_colors(self) -> None:
        colors = np.array(
            [
                [0.0, 0.0, 0.0],
                [1.0, 1.0, 1.0],
                [0.5, 0.5, 0.5],
                [0.13, 0.47, 0.91],
            ],
            dtype=np.float32,
        )

        round_trip = generate.linear_to_srgb(generate.srgb_to_linear(colors))

        np.testing.assert_allclose(round_trip, colors, rtol=0.0, atol=1e-6)

    def test_tint_offsets_are_converted_to_linear_light_before_mixing(self) -> None:
        delta = np.array([-0.025, 0.008, 0.060], dtype=np.float32)

        converted = generate.srgb_delta_to_linear(delta)
        expected = generate.srgb_to_linear(generate.TINT_SRGB_PIVOT + delta) - generate.srgb_to_linear(
            np.asarray(generate.TINT_SRGB_PIVOT, dtype=np.float32),
        )

        np.testing.assert_allclose(converted, expected, rtol=0.0, atol=1e-7)
        self.assertFalse(np.allclose(converted, delta))

    def test_source_chroma_is_derived_in_linear_light(self) -> None:
        linear_rgb = np.array([[[0.08, 0.18, 0.25]]], dtype=np.float32)
        luminance = np.sum(linear_rgb * np.array([0.2126, 0.7152, 0.0722]), axis=2)

        chroma = generate.linear_chroma(linear_rgb, luminance)

        expected = np.clip(linear_rgb - luminance[:, :, None], -0.07, 0.07)
        np.testing.assert_allclose(chroma, expected, rtol=0.0, atol=1e-7)

    def test_linear_grade_is_finite_and_bounded(self) -> None:
        source = np.array(
            [
                [[np.nan, -1.0, np.inf], [0.1, 0.4, 0.9]],
                [[0.9, 0.2, 0.05], [2.0, 0.5, 0.0]],
            ],
            dtype=np.float32,
        )

        graded = generate.color_grade_linear(source)

        self.assertTrue(np.all(np.isfinite(graded)))
        self.assertGreaterEqual(float(graded.min()), generate.LINEAR_OUTPUT_MIN)
        self.assertLessEqual(float(graded.max()), generate.LINEAR_OUTPUT_MAX)

    def test_final_encoding_is_finite_and_bounded(self) -> None:
        linear = np.array([[[-np.inf, np.nan, np.inf], [0.0031308, 0.5, 1.5]]], dtype=np.float32)

        encoded = generate.encode_linear_rgb(linear)

        self.assertTrue(np.all(np.isfinite(encoded)))
        self.assertGreaterEqual(float(encoded.min()), 0.0)
        self.assertLessEqual(float(encoded.max()), 1.0)

    def test_final_encoding_quantizes_linear_rgb_to_eight_bits(self) -> None:
        linear = np.array([[[0.0, 0.18, 1.0], [0.0031308, 0.5, 0.75]]], dtype=np.float32)

        encoded = generate.quantize_srgb8(generate.encode_linear_rgb(linear))
        expected = np.rint(generate.linear_to_srgb(linear) * 255.0).astype(np.uint8)

        self.assertEqual(np.uint8, encoded.dtype)
        np.testing.assert_array_equal(encoded, expected)

    def test_color_grade_crosses_final_encoding_boundary_once(self) -> None:
        source = np.full((2, 4, 3), 0.18, dtype=np.float32)

        with mock.patch.object(
            generate,
            "encode_linear_rgb",
            wraps=generate.encode_linear_rgb,
        ) as encoding_boundary:
            first = generate.color_grade(source)

        encoding_boundary.assert_called_once()
        second = generate.color_grade(source)
        np.testing.assert_array_equal(first, second)

    def test_output_dimensions_require_two_to_one_ratio(self) -> None:
        generate.validate_output_dimensions(8, 4)

        with self.assertRaisesRegex(ValueError, "2:1"):
            generate.validate_output_dimensions(8, 5)

    def test_longitude_derivative_wraps_across_seam(self) -> None:
        height = np.zeros((4, 8), dtype=np.float32)
        height[:, -1] = 4.0
        height[:, 1] = 10.0

        longitude, _ = generate.height_derivatives(height)

        np.testing.assert_allclose(longitude[:, 0], 3.0)

    def test_latitude_derivative_clamps_instead_of_wrapping_poles(self) -> None:
        height = np.zeros((4, 8), dtype=np.float32)
        height[0, :] = 2.0
        height[1, :] = 5.0
        height[-1, :] = 100.0

        _, latitude = generate.height_derivatives(height)

        np.testing.assert_allclose(latitude[0, :], 3.0)

    def test_flat_height_produces_neutral_normal_map(self) -> None:
        normal = generate.normal_map_from_height(np.full((4, 8), 42.0, dtype=np.float32))

        expected = np.empty_like(normal)
        expected[:, :, 0:2] = 128
        expected[:, :, 2] = 255
        np.testing.assert_array_equal(expected, normal)

    def test_height_slopes_encode_against_increasing_u_and_southward_v(self) -> None:
        u_ramp = np.tile(np.arange(8, dtype=np.float32) * 1_000_000.0, (4, 1))
        v_ramp = np.tile(np.arange(4, dtype=np.float32)[:, None] * 1_000_000.0, (1, 8))

        positive_u = self.decode_normal(generate.normal_map_from_height(u_ramp, strength=4.0)[2, 3])
        negative_u = self.decode_normal(generate.normal_map_from_height(-u_ramp, strength=4.0)[2, 3])
        positive_v = self.decode_normal(generate.normal_map_from_height(v_ramp, strength=4.0)[2, 3])
        negative_v = self.decode_normal(generate.normal_map_from_height(-v_ramp, strength=4.0)[2, 3])
        diagonal = self.decode_normal(generate.normal_map_from_height(u_ramp + v_ramp, strength=4.0)[2, 3])

        self.assertLess(positive_u[0], 0.0)
        self.assertGreater(negative_u[0], 0.0)
        self.assertLess(positive_v[1], 0.0)
        self.assertGreater(negative_v[1], 0.0)
        self.assertLess(diagonal[0], 0.0)
        self.assertLess(diagonal[1], 0.0)

    def test_decoded_normals_are_finite_and_unit_length_within_rgb8_tolerance(self) -> None:
        y, x = np.mgrid[0:16, 0:32].astype(np.float32)
        height = 300_000.0 * np.sin(x * 0.7) + 200_000.0 * np.cos(y * 0.4)

        encoded = generate.normal_map_from_height(height)
        decoded = encoded.astype(np.float64) / 255.0 * 2.0 - 1.0
        lengths = np.linalg.norm(decoded, axis=2)

        self.assertTrue(np.all(np.isfinite(decoded)))
        np.testing.assert_allclose(lengths, 1.0, rtol=0.0, atol=0.007)

    def test_normal_encoding_is_finite_and_bounded(self) -> None:
        height = np.tile(np.arange(8, dtype=np.float32), (4, 1))

        normal = generate.normal_map_from_height(height, strength=4.0)

        self.assertEqual(np.uint8, normal.dtype)
        self.assertGreaterEqual(int(normal.min()), 0)
        self.assertLessEqual(int(normal.max()), 255)
        self.assertTrue(np.any(normal[:, :, 0] != 128))

    def test_png_output_is_deterministic(self) -> None:
        pixels = np.arange(8 * 4 * 3, dtype=np.uint8).reshape((4, 8, 3))
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.png"
            second = Path(directory) / "second.png"

            generate.save_png(first, pixels)
            generate.save_png(second, pixels)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            with Image.open(first) as image:
                self.assertEqual((8, 4), image.size)
                self.assertEqual("RGB", image.mode)

    def test_invalid_height_dimensions_have_clear_error(self) -> None:
        with self.assertRaisesRegex(ValueError, "2:1 single-channel"):
            generate.normal_map_from_height(np.zeros((4, 7), dtype=np.float32))


if __name__ == "__main__":
    unittest.main()
