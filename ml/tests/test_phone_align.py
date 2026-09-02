import unittest

from driftzero_ml.features.phone_align import estimate_alignment, rotate_vector, vertical_gyro_radps


class PhoneAlignTests(unittest.TestCase):
    def test_flat_phone_maps_z_to_vertical_without_swapping_columns(self) -> None:
        grav = [(0.01, -0.02, 9.81)] * 16
        alignment = estimate_alignment(grav, trip_id="S-synth")
        self.assertEqual(alignment.dominant_device_axis, "z")
        self.assertEqual(alignment.trip_id, "S-synth")
        rotated = rotate_vector(alignment.gravity_mean, alignment.rotation)
        self.assertAlmostEqual(abs(rotated[0]) + abs(rotated[1]), 0.0, places=5)
        self.assertGreater(rotated[2], 9.7)
        rate = vertical_gyro_radps((0.1, 0.2, 0.3), alignment)
        self.assertIsNotNone(rate)
        # Default sign is -1. Vertical is +Z, so rate is -0.3.
        self.assertAlmostEqual(rate or 0.0, -0.3, places=5)

    def test_upright_phone_uses_y_as_vertical(self) -> None:
        grav = [(0.0, 9.7, 0.2)] * 8
        alignment = estimate_alignment(grav, trip_id="S-upright")
        self.assertEqual(alignment.dominant_device_axis, "y")
        rate = vertical_gyro_radps((0.0, 0.4, 0.0), alignment)
        self.assertAlmostEqual(rate or 0.0, -0.4, places=2)


if __name__ == "__main__":
    unittest.main()
