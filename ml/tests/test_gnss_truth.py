"""Heading seed, stale-truth scoring, and truth-gate regressions. Synthetic only."""

from __future__ import annotations

import math
import unittest

from driftzero_ml.eval_iovnbd_blackout import choose_blackout, score_trip
from driftzero_ml.features.causal_imu import FEATURE_NAMES
from driftzero_ml.gnss_truth import (
    TruthGateConfig,
    assess_truth,
    course_rad,
    score_epochs,
    seed_heading_rad,
    unique_fix_median_spacing_s,
)
from driftzero_ml.student.linear import LinearMotionStudent


def _east_then_hold() -> list[dict]:
    """Move ~15 m east, then repeat the last fix. Last two rows share a position."""

    rows = []
    lon = -1.7
    for index in range(20):
        lon = -1.7 + index * 0.00016
        rows.append(
            {
                "timestamp_ns": index * 100_000_000,
                "latitude_deg": 52.0,
                "longitude_deg": lon,
                "gnss_speed_mps": 8.9,
            }
        )
    last = rows[-1]
    for extra in range(8):
        rows.append(
            {
                "timestamp_ns": (20 + extra) * 100_000_000,
                "latitude_deg": last["latitude_deg"],
                "longitude_deg": last["longitude_deg"],
                "gnss_speed_mps": 8.9,
            }
        )
    return rows


class HeadingSeedTests(unittest.TestCase):
    def test_repeated_last_fix_does_not_seed_due_north(self) -> None:
        history = _east_then_hold()
        pair_heading = course_rad(history[-2], history[-1])
        self.assertIsNone(pair_heading)
        heading = seed_heading_rad(history)
        self.assertIsNotNone(heading)
        # East is +pi/2. Allow a few degrees.
        self.assertTrue(abs((heading or 0.0) - math.pi / 2.0) < 0.15)

    def test_orientation_is_used_when_motion_is_short(self) -> None:
        history = [
            {
                "timestamp_ns": 0,
                "latitude_deg": 52.0,
                "longitude_deg": -1.7,
                "gps_orientation_deg": 90.0,
            },
            {
                "timestamp_ns": 100_000_000,
                "latitude_deg": 52.0,
                "longitude_deg": -1.7,
                "gps_orientation_deg": 90.0,
            },
        ]
        heading = seed_heading_rad(history)
        self.assertIsNotNone(heading)
        self.assertTrue(abs((heading or 0.0) - math.pi / 2.0) < 1e-9)

    def test_unique_fix_median_spacing(self) -> None:
        rows = [
            {"timestamp_ns": 0, "latitude_deg": 52.0, "longitude_deg": -1.7},
            {"timestamp_ns": 1_000_000_000, "latitude_deg": 52.0001, "longitude_deg": -1.7},
            {"timestamp_ns": 2_000_000_000, "latitude_deg": 52.0002, "longitude_deg": -1.7},
            {"timestamp_ns": 11_000_000_000, "latitude_deg": 52.0003, "longitude_deg": -1.7},
        ]
        self.assertAlmostEqual(unique_fix_median_spacing_s(rows) or 0.0, 1.0)
        self.assertAlmostEqual(unique_fix_median_spacing_s([0, 9_000_000_000, 18_000_000_000]) or 0.0, 9.0)
        self.assertIsNone(unique_fix_median_spacing_s([0]))


class StaleTruthTests(unittest.TestCase):
    def test_score_epochs_skip_repeated_positions(self) -> None:
        records = _east_then_hold()
        epochs = score_epochs(records, 0, records[-1]["timestamp_ns"] + 1)
        self.assertEqual(len(epochs), 20)
        self.assertLess(len(epochs), len(records))

    def test_choose_blackout_ends_on_a_unique_fix(self) -> None:
        records = []
        for index in range(80):
            stamp = index * 100_000_000
            moved = index % 10 == 0
            lat = 52.0 + (index // 10) * 0.00008
            records.append(
                {
                    "timestamp_ns": stamp,
                    "ax": 0.0,
                    "ay": 0.0,
                    "az": 9.81,
                    "gx": 0.0,
                    "latitude_deg": lat,
                    "longitude_deg": -1.7,
                    "gnss_speed_mps": 8.9,
                    "trip_id": "synth",
                }
            )
        interval = choose_blackout(records)
        self.assertIsNotNone(interval)
        epochs = score_epochs(records, interval.start_ns, interval.end_ns)
        self.assertGreaterEqual(len(epochs), 2)
        by_t = {int(row["timestamp_ns"]): row for row in records}
        positions = [
            (by_t[t]["latitude_deg"], by_t[t]["longitude_deg"]) for t in epochs
        ]
        unique = [positions[0]]
        for pos in positions[1:]:
            if pos != unique[-1]:
                unique.append(pos)
        self.assertEqual(len(unique), len(positions))


class TruthGateTests(unittest.TestCase):
    def test_rejects_implausible_speed_and_short_path(self) -> None:
        jump = assess_truth([(0.0, 0.0), (2.0, 0.0)], duration_s=20.0, coverage=1.0)
        self.assertFalse(jump.accepted)
        self.assertTrue(any("implied_speed" in item or "max_hop" in item for item in jump.reasons))
        parked = assess_truth([(52.0, -1.7)] * 40, duration_s=20.0, coverage=1.0)
        self.assertFalse(parked.accepted)
        self.assertTrue(any("path" in item for item in parked.reasons))

    def test_accepts_plausible_mid_interval(self) -> None:
        truth = [(52.0, -1.7), (52.001, -1.7), (52.002, -1.7)]
        ok = assess_truth(truth, duration_s=20.0, coverage=1.0, config=TruthGateConfig())
        self.assertTrue(ok.accepted, ok.reasons)

    def test_score_trip_returns_four_systems_on_moving_fresh_fixes(self) -> None:
        records = []
        for index in range(80):
            records.append(
                {
                    "timestamp_ns": index * 100_000_000,
                    "ax": 0.0,
                    "ay": 0.0,
                    "az": 9.81,
                    "gx": 0.0,
                    "gy": 0.0,
                    "gz": 0.0,
                    "trip_id": "synth",
                    "latitude_deg": 52.0 + index * 0.00001,
                    "longitude_deg": -1.7,
                    "gnss_speed_mps": 1.1,
                }
            )
        student = LinearMotionStudent(
            weights_speed=tuple([0.0] * (len(FEATURE_NAMES) + 1)),
            weights_stop=tuple([-4.0] + [0.0] * len(FEATURE_NAMES)),
            weights_logvar=tuple([-1.0] + [0.0] * len(FEATURE_NAMES)),
            feature_names=FEATURE_NAMES,
        )
        metrics = score_trip(records, student)
        self.assertIsNotNone(metrics)
        self.assertEqual(set(metrics), {"freeze", "cv", "filter_only", "speed_student"})


if __name__ == "__main__":
    unittest.main()
