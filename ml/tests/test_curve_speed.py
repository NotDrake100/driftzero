import unittest

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.curve_speed import (
    ACCEL_LAT_SIGMA_MPS2,
    GYRO_SIGMA_RADPS,
    OMEGA_MIN_RADPS,
    VAR_CURVE_FLOOR,
    CurveObservation,
    ForwardAxis,
    blend_speeds,
    curve_variance_mps2,
    estimate_forward_axis,
    observe_curve_speeds,
    persist_curve_speed_fn,
)
from driftzero_ml.features.phone_align import estimate_alignment


class CurveSpeedTests(unittest.TestCase):
    def test_circular_motion_recovers_speed(self) -> None:
        alignment = estimate_alignment([(0.0, 0.0, 9.81)] * 16, trip_id="circle")
        forward = ForwardAxis(1.0, 0.0, "fixture", 16, ())
        speed = 10.0
        omega = 0.20
        a_lat = speed * omega
        rows = []
        for index in range(20):
            stamp = index * 100_000_000
            # Gravity alignment with +Z up maps device (ax, ay) to (-ay, ax).
            rows.append(
                {
                    "timestamp_ns": stamp,
                    "ax": a_lat,
                    "ay": 0.0,
                    "az": 9.81,
                    "gx": 0.0,
                    "gy": 0.0,
                    "gz": -omega,
                    "gyro_vertical_radps": omega,
                }
            )
        observed = observe_curve_speeds(rows, alignment, forward)
        valid = [row for row in observed if row.valid]
        self.assertGreaterEqual(len(valid), 10)
        for row in valid:
            self.assertIsNotNone(row.v_mps)
            self.assertAlmostEqual(row.v_mps or 0.0, speed, places=1)

    def test_straight_line_is_invalid(self) -> None:
        alignment = estimate_alignment([(0.0, 0.0, 9.81)] * 16, trip_id="straight")
        forward = ForwardAxis(1.0, 0.0, "fixture", 16, ())
        rows = [
            {
                "timestamp_ns": i * 100_000_000,
                "ax": 0.2,
                "ay": 0.0,
                "az": 9.81,
                "gx": 0.0,
                "gy": 0.0,
                "gz": 0.0,
                "gyro_vertical_radps": 0.0,
            }
            for i in range(12)
        ]
        observed = observe_curve_speeds(rows, alignment, forward)
        self.assertTrue(all(not row.valid for row in observed))
        self.assertTrue(all("omega_below_threshold" in row.reasons for row in observed))

    def test_hard_brake_is_invalid(self) -> None:
        alignment = estimate_alignment([(0.0, 0.0, 9.81)] * 16, trip_id="brake")
        forward = ForwardAxis(1.0, 0.0, "fixture", 16, ())
        rows = [
            {
                "timestamp_ns": i * 100_000_000,
                "ax": -4.0,
                "ay": 2.0,
                "az": 9.81,
                "gx": 0.0,
                "gy": 0.0,
                "gz": -0.2,
                "gyro_vertical_radps": 0.2,
            }
            for i in range(12)
        ]
        observed = observe_curve_speeds(rows, alignment, forward)
        self.assertTrue(all("hard_brake" in row.reasons for row in observed))
        self.assertTrue(all(not row.valid for row in observed))

    def test_sign_inconsistency_is_invalid(self) -> None:
        alignment = estimate_alignment([(0.0, 0.0, 9.81)] * 16, trip_id="sign")
        forward = ForwardAxis(1.0, 0.0, "fixture", 16, ())
        rows = [
            {
                "timestamp_ns": i * 100_000_000,
                "ax": 0.0,
                "ay": -2.0,
                "az": 9.81,
                "gx": 0.0,
                "gy": 0.0,
                "gz": -0.2,
                "gyro_vertical_radps": 0.2,
            }
            for i in range(12)
        ]
        observed = observe_curve_speeds(rows, alignment, forward)
        self.assertTrue(all("sign_inconsistent" in row.reasons for row in observed))

    def test_blend_is_inverse_variance(self) -> None:
        blended = blend_speeds(10.0, 4.0, 20.0, 1.0)
        self.assertAlmostEqual(blended, 18.0)

    def test_curve_variance_uses_documented_sigmas(self) -> None:
        omega = 0.2
        a_lat = 2.0
        expected = (ACCEL_LAT_SIGMA_MPS2**2) / (omega**2)
        expected += (a_lat**2 * GYRO_SIGMA_RADPS**2) / (omega**4)
        expected = max(VAR_CURVE_FLOOR, expected)
        self.assertAlmostEqual(curve_variance_mps2(a_lat, omega), expected)

    def test_forward_axis_from_accel_events(self) -> None:
        alignment = estimate_alignment([(0.0, 0.0, 9.81)] * 16, trip_id="fwd")
        rows = []
        speed = 2.0
        for index in range(24):
            speed += 0.08
            rows.append(
                {
                    "timestamp_ns": index * 100_000_000,
                    "ax": 0.05,
                    "ay": -1.2,
                    "az": 9.81,
                    "gx": 0.0,
                    "gy": 0.0,
                    "gz": 0.0,
                    "gyro_vertical_radps": 0.0,
                    "gnss_speed_mps": speed,
                    "latitude_deg": 52.0,
                    "longitude_deg": -1.7,
                }
            )
        axis = estimate_forward_axis(rows, alignment, rows[-1]["timestamp_ns"] + 1)
        self.assertIsNotNone(axis)
        assert axis is not None
        self.assertEqual(axis.source, "gnss_accel_events")
        self.assertGreater(axis.ux, 0.9)
        self.assertLess(abs(axis.uy), 0.3)

    def test_observe_is_causal(self) -> None:
        alignment = estimate_alignment([(0.0, 0.0, 9.81)] * 16, trip_id="causal")
        forward = ForwardAxis(1.0, 0.0, "fixture", 16, ())
        prefix = [
            {
                "timestamp_ns": i * 100_000_000,
                "ax": 0.0,
                "ay": 2.0,
                "az": 9.81,
                "gx": 0.0,
                "gy": 0.0,
                "gz": -0.2,
                "gyro_vertical_radps": 0.2,
            }
            for i in range(8)
        ]
        first = observe_curve_speeds(prefix, alignment, forward)
        later = list(prefix)
        later.append(
            {
                "timestamp_ns": 8 * 100_000_000,
                "ax": 0.0,
                "ay": 80.0,
                "az": 9.81,
                "gx": 0.0,
                "gy": 0.0,
                "gz": -0.2,
                "gyro_vertical_radps": 0.2,
            }
        )
        second = observe_curve_speeds(later, alignment, forward)
        self.assertEqual(len(first), 8)
        for left, right in zip(first, second[:8]):
            self.assertEqual(left.valid, right.valid)
            self.assertAlmostEqual(left.a_lat_mps2, right.a_lat_mps2, places=9)
            if left.v_mps is not None and right.v_mps is not None:
                self.assertAlmostEqual(left.v_mps, right.v_mps, places=9)

    def test_persist_curve_holds_then_resets(self) -> None:
        observations = [
            CurveObservation(0, None, 0.0, 0.0, 0.0, 0.0, None, False, ("omega_below_threshold",)),
            CurveObservation(100, 8.5, 1.7, 0.1, 0.2, 0.4, 2.0, True, ()),
            CurveObservation(200, None, 0.0, 0.0, 0.0, 0.0, None, False, ("omega_below_threshold",)),
        ]
        speed_at = persist_curve_speed_fn(observations)
        self.assertEqual(speed_at({"timestamp_ns": 0}, 5.0), 5.0)
        self.assertEqual(speed_at({"timestamp_ns": 100}, 5.0), 8.5)
        self.assertEqual(speed_at({"timestamp_ns": 200}, 8.5), 8.5)

    def test_omega_threshold_matches_spec(self) -> None:
        self.assertAlmostEqual(OMEGA_MIN_RADPS, 0.05)

    def test_no_gnss_keys_on_observation_fields(self) -> None:
        leaked = {"v_mps", "a_lat_mps2", "omega_z_radps"} & GNSS_KEYS
        self.assertEqual(leaked, set())


if __name__ == "__main__":
    unittest.main()
