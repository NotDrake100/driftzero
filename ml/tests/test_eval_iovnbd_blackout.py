import tempfile
import unittest
from pathlib import Path

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.eval_iovnbd_blackout import (
    choose_blackout,
    run,
    score_trip,
    write_markdown,
)
from driftzero_ml.student.linear import LinearMotionStudent
from driftzero_ml.features.causal_imu import FEATURE_NAMES


class EvalIovnbdBlackoutTests(unittest.TestCase):
    def test_score_trip_masks_gnss_and_returns_four_systems(self) -> None:
        records = _moving_records(80)
        student = LinearMotionStudent(
            weights_speed=tuple([0.0] * (len(FEATURE_NAMES) + 1)),
            weights_stop=tuple([-4.0] + [0.0] * len(FEATURE_NAMES)),
            weights_logvar=tuple([-1.0] + [0.0] * len(FEATURE_NAMES)),
            feature_names=FEATURE_NAMES,
        )
        metrics = score_trip(records, student)
        self.assertIsNotNone(metrics)
        self.assertEqual(set(metrics), {"freeze", "cv", "filter_only", "speed_student"})
        self.assertTrue(metrics["freeze"].endpoint_error_m >= 0.0)
        self.assertTrue(set(FEATURE_NAMES).isdisjoint(GNSS_KEYS))

    def test_choose_blackout_leaves_preamble(self) -> None:
        records = _moving_records(80)
        interval = choose_blackout(records)
        self.assertIsNotNone(interval)
        self.assertGreater(interval.start_ns, records[0]["timestamp_ns"])
        self.assertLessEqual(interval.end_ns, records[-1]["timestamp_ns"] + 1)

    def test_missing_root_skips(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            payload = run(Path(folder))
        self.assertTrue(payload["skipped"])
        self.assertIn("IO-VNBD", payload["reason"])

    def test_markdown_skip_reason(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "out.md"
            write_markdown(path, {"skipped": True, "reason": "need 51 tables"})
            text = path.read_text()
        self.assertIn("Skipped", text)
        self.assertIn("51", text)
        self.assertNotIn("linear_dp beat", text.lower())


def _moving_records(count: int) -> list[dict]:
    rows = []
    for index in range(count):
        stamp = index * 100_000_000
        rows.append(
            {
                "timestamp_ns": stamp,
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
    return rows


if __name__ == "__main__":
    unittest.main()
