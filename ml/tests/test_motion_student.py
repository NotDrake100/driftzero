import json
import tempfile
import unittest
from pathlib import Path

from driftzero_ml.features.causal_imu import GRAVITY_MPS2, ImuSample, records_to_imu_samples, trim_causal_window
from driftzero_ml.student.csv_load import load_imu_csv
from driftzero_ml.student.gru import TorchUnavailable, require_torch, torch_is_installed
from driftzero_ml.student.heads import coast_speed_mps, zupt_accel_infer
from driftzero_ml.student.linear import fit_linear_motion_student, zero_speed_baseline
from driftzero_ml.student.synthetic import synthetic_trip
from driftzero_ml.student.train import labeled_windows, train_from_trips


class MotionStudentTests(unittest.TestCase):
    def test_heuristic_idle_is_zupt(self) -> None:
        samples = [
            ImuSample(i * 20_000_000, 0.0, 0.0, GRAVITY_MPS2, 0.0, 0.0, 0.0)
            for i in range(40)
        ]
        heads = zupt_accel_infer(samples)
        self.assertTrue(heads.idle)
        self.assertEqual(heads.forward_speed_mps, 0.0)
        self.assertEqual(coast_speed_mps(11.0, heads), 0.0)

    def test_zero_speed_baseline_is_freeze(self) -> None:
        self.assertEqual(zero_speed_baseline(3), [0.0, 0.0, 0.0])

    def test_linear_fits_trip_windows_not_future_labels(self) -> None:
        trip = synthetic_trip("idle", duration_s=1.5, hz=50, seed=3, trip_id="idle-a")
        windows = labeled_windows(trip)
        self.assertGreater(len(windows), 10)
        samples = records_to_imu_samples(trip)
        first_vector, first_speed, _stop, _seq, _h = windows[0]
        prefix = trim_causal_window(samples[:8], samples[7].timestamp_ns)
        self.assertEqual(len(prefix), 8)
        self.assertEqual(first_speed, 0.0)
        student = fit_linear_motion_student(
            [row[0] for row in windows],
            [row[1] for row in windows],
            [row[2] for row in windows],
        )
        speed, stop_logit, log_var = student.infer(first_vector)
        self.assertGreaterEqual(speed, 0.0)
        self.assertTrue(log_var == log_var)
        self.assertTrue(stop_logit == stop_logit)

    def test_train_script_compares_freeze_on_synthetic_trips(self) -> None:
        trips = {}
        for kind in ("idle", "cruise"):
            for copy in range(4):
                trip_id = f"{kind}-{copy}"
                trips[trip_id] = synthetic_trip(
                    kind, duration_s=1.2, hz=50, seed=10 + copy, trip_id=trip_id
                )
        result = train_from_trips(trips, seed="motion-test")
        report = result["report"]
        self.assertGreater(report["train_windows"], 0)
        self.assertIn("torch_available", report)
        for metrics in report["splits"].values():
            self.assertIn("freeze_speed_mae", metrics)
            self.assertIn("linear_speed_mae", metrics)

    def test_csv_loader_drops_gnss_columns(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "imu.csv"
            path.write_text(
                "timestamp_ns,ax,ay,az,gx,gy,gz,latitude_deg,speed_mps,stopped\n"
                "0,0,0,9.81,0,0,0,18.5,0,1\n"
                "20000000,0,0,9.81,0,0,0,18.5,0,1\n"
            )
            rows = load_imu_csv(path)
            self.assertNotIn("latitude_deg", rows[0])
            self.assertEqual(rows[0]["ax"], 0.0)
            self.assertEqual(rows[0]["speed_mps"], 0.0)

    def test_gru_boundary_does_not_import_torch(self) -> None:
        if torch_is_installed():
            require_torch()
            return
        with self.assertRaises(TorchUnavailable):
            require_torch()

    def test_train_report_is_json(self) -> None:
        trips = {
            "idle-0": synthetic_trip("idle", duration_s=1.2, hz=50, seed=1, trip_id="idle-0"),
            "idle-1": synthetic_trip("idle", duration_s=1.2, hz=50, seed=2, trip_id="idle-1"),
            "cruise-0": synthetic_trip("cruise", duration_s=1.2, hz=50, seed=3, trip_id="cruise-0"),
            "cruise-1": synthetic_trip("cruise", duration_s=1.2, hz=50, seed=4, trip_id="cruise-1"),
            "stop-0": synthetic_trip("stop", duration_s=1.2, hz=50, seed=5, trip_id="stop-0"),
            "bump-0": synthetic_trip("bump", duration_s=1.2, hz=50, seed=6, trip_id="bump-0"),
        }
        result = train_from_trips(trips, seed="json-check")
        dumped = json.dumps(result["report"])
        self.assertIn("freeze_speed_mae", dumped)


if __name__ == "__main__":
    unittest.main()
