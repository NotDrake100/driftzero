import unittest

from math import cos, radians, sin

from driftzero_ml.features.phone_align import (
    estimate_alignment,
    heading_gyro_radps,
    rotate_vector,
    select_heading_gyro,
    vertical_gyro_radps,
)
from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M


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
        self.assertAlmostEqual(rate or 0.0, -0.3, places=3)

    def test_upright_phone_uses_y_as_vertical(self) -> None:
        grav = [(0.0, 9.7, 0.2)] * 8
        alignment = estimate_alignment(grav, trip_id="S-upright")
        self.assertEqual(alignment.dominant_device_axis, "y")
        rate = vertical_gyro_radps((0.0, 0.4, 0.0), alignment)
        self.assertAlmostEqual(rate or 0.0, -0.4, places=2)

    def test_heading_gyro_picks_pitch_when_course_tracks_it(self) -> None:
        gyros: list[tuple[int, float, float, float]] = []
        fixes: list[tuple[int, float, float]] = []
        lat = 52.0
        lon = -1.7
        heading = 0.0
        rate = 0.12
        for index in range(24):
            stamp = index * 1_000_000_000
            fixes.append((stamp, lat, lon))
            for step in range(10):
                gyros.append((stamp + step * 100_000_000, 0.01, rate, -0.02))
            north = 12.0 * cos(heading)
            east = 12.0 * sin(heading)
            lat += (north / EARTH_MEAN_RADIUS_M) * (180.0 / 3.141592653589793)
            lon += (east / (EARTH_MEAN_RADIUS_M * cos(radians(lat)))) * (180.0 / 3.141592653589793)
            heading += rate
        pick = select_heading_gyro(gyros, fixes)
        self.assertIsNotNone(pick)
        assert pick is not None
        self.assertEqual(pick.axis, "pitch")
        self.assertGreater(pick.correlation, 0.0)
        self.assertGreater(abs(pick.correlation), 0.25)
        self.assertAlmostEqual(pick.sign, -1.0)
        self.assertAlmostEqual(heading_gyro_radps((0.01, rate, -0.02), pick), -rate)


if __name__ == "__main__":
    unittest.main()
