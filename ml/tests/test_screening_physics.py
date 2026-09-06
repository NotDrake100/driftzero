import tempfile
import unittest
from pathlib import Path

from driftzero_ml.eval_iovnbd_blackout import choose_blackout
from driftzero_ml.features.causal_imu import FEATURE_NAMES
from driftzero_ml.screening import PHYSICS_SYSTEMS, insert_physics_summary_rows, score_interval
from driftzero_ml.student.linear import LinearMotionStudent


class ScreeningPhysicsTests(unittest.TestCase):
    def test_score_interval_default_omits_physics(self) -> None:
        scored = score_interval(_moving_records(80), choose_blackout(_moving_records(80)), _student(), None)
        self.assertIsNotNone(scored)
        assert scored is not None
        for name in PHYSICS_SYSTEMS:
            self.assertNotIn(name, scored["metrics"])

    def test_score_interval_physics_adds_four_systems(self) -> None:
        records = _moving_records(80)
        interval = choose_blackout(records)
        self.assertIsNotNone(interval)
        scored = score_interval(records, interval, _student(), None, physics=True)
        self.assertIsNotNone(scored)
        assert scored is not None
        for name in PHYSICS_SYSTEMS:
            self.assertIn(name, scored["metrics"])
            self.assertIn(name, scored["extras"])
        self.assertIn("physics", scored)
        self.assertGreaterEqual(scored["physics"]["cal_budget_s"], 0.0)

    def test_insert_physics_rows_is_idempotent(self) -> None:
        systems = {
            name: {
                "drift_ratio_p50": 0.9,
                "drift_ratio_p90": 1.1,
                "drift_ratio_p95": 1.2,
                "drift_ratio_worst": 2.0,
                "endpoint_p50_m": 100.0,
                "speed_mae_p50": 4.0,
                "heading_mae_rad_p50": 0.5,
            }
            for name in PHYSICS_SYSTEMS
        }
        text = "| System | drift p50 | drift p90 | drift p95 | drift worst | endpoint p50 m | speed MAE p50 | heading MAE p50 rad |\n|---|---:|---:|---:|---:|---:|---:|---:|\n| persist | 0.5168 | 1.3887 | 19.8706 | 204.0427 | 234.47 | 3.439 | 0.673 |\n| linear | 0.7132 | 2.5454 | 9.1404 | 11.1149 | 286.98 | 5.034 | 0.724 |\n| gru | 0.7550 | 3.1158 | 19.3799 | 191.8346 | 292.76 | 4.847 | 0.741 |\n\n## SIH gate\n\nkept"
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "summary.md"
            path.write_text(text + "\n")
            insert_physics_summary_rows(path, systems)
            insert_physics_summary_rows(path, systems)
            body = path.read_text()
        self.assertEqual(body.count("| persist_curve |"), 1)
        self.assertEqual(body.count("| linear_selfcal |"), 1)
        self.assertIn("## SIH gate", body)
        self.assertIn("kept", body)
        self.assertTrue(body.index("| linear |") < body.index("| persist_curve |") < body.index("| gru |"))


def _student() -> LinearMotionStudent:
    return LinearMotionStudent(
        weights_speed=tuple([0.0] * (len(FEATURE_NAMES) + 1)),
        weights_stop=tuple([-4.0] + [0.0] * len(FEATURE_NAMES)),
        weights_logvar=tuple([-1.0] + [0.0] * len(FEATURE_NAMES)),
        feature_names=FEATURE_NAMES,
    )


def _moving_records(count: int) -> list[dict]:
    rows = []
    for index in range(count):
        stamp = index * 100_000_000
        rows.append(
            {
                "timestamp_ns": stamp,
                "ax": 0.4 if index % 4 == 0 else 0.0,
                "ay": 0.8,
                "az": 9.81,
                "gx": 0.0,
                "gy": 0.0,
                "gz": -0.15,
                "trip_id": "synth",
                "latitude_deg": 52.0 + index * 0.00001,
                "longitude_deg": -1.7,
                "gnss_speed_mps": 8.0 + 0.02 * index,
                "gravity_x": 0.0,
                "gravity_y": 0.0,
                "gravity_z": 9.81,
                "gyro_vertical_radps": 0.15,
            }
        )
    return rows


if __name__ == "__main__":
    unittest.main()
