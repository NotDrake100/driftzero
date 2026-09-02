import json
import sys
import tempfile
import unittest
from pathlib import Path

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import GRAVITY_MPS2, ImuSample
from driftzero_ml.learned_imu import (
    HEAD_DIM,
    IMU_CHANNELS,
    LINEAR_DP_SCHEMA,
    LINEAR_FEATURE_NAMES,
    MAG_KEYS,
    decode_raw_heads,
    default_synthetic_odometry,
    hacf_rotation,
    hacf_sequence,
    ionet_polar_loss,
    left_pad_sequence,
    load_linear_displacement_student,
    main,
    odometry_windows,
    probe_learned_imu_datasets,
    rotate_into_hacf,
    ronin_strided_mse,
    synthetic_vehicle_odometry,
    tlio_gaussian_nll,
    train_from_odometry,
    train_torch_student,
)
from driftzero_ml.student.gru import TorchUnavailable, require_torch, torch_is_installed


class LearnedImuTests(unittest.TestCase):
    def test_channels_exclude_gnss_and_magnetometer(self) -> None:
        self.assertEqual(set(IMU_CHANNELS) & GNSS_KEYS, set())
        self.assertEqual(set(LINEAR_FEATURE_NAMES) & GNSS_KEYS, set())
        self.assertEqual(set(IMU_CHANNELS) & MAG_KEYS, set())
        self.assertEqual(len(IMU_CHANNELS), 6)
        self.assertEqual(HEAD_DIM, 10)

    def test_module_does_not_import_timesfm(self) -> None:
        leaked = [name for name in sys.modules if name == "timesfm" or name.startswith("timesfm.")]
        self.assertEqual(leaked, [])

    def test_level_phone_hacf_is_identity(self) -> None:
        rotation = hacf_rotation((0.0, 0.0, GRAVITY_MPS2))
        mapped = rotate_into_hacf((0.0, 0.0, GRAVITY_MPS2), rotation)
        self.assertAlmostEqual(mapped[0], 0.0, places=6)
        self.assertAlmostEqual(mapped[1], 0.0, places=6)
        self.assertAlmostEqual(mapped[2], GRAVITY_MPS2, places=6)
        self.assertAlmostEqual(rotation[0][0], 1.0, places=6)
        self.assertAlmostEqual(rotation[1][1], 1.0, places=6)

    def test_tilted_gravity_becomes_hacf_z(self) -> None:
        mapped = rotate_into_hacf((GRAVITY_MPS2, 0.0, 0.0), hacf_rotation((GRAVITY_MPS2, 0.0, 0.0)))
        self.assertAlmostEqual(mapped[0], 0.0, places=6)
        self.assertAlmostEqual(mapped[1], 0.0, places=6)
        self.assertAlmostEqual(mapped[2], GRAVITY_MPS2, places=6)

    def test_sequence_is_six_axis_and_ignores_gnss_mag_columns(self) -> None:
        records = [
            {
                "timestamp_ns": i * 20_000_000,
                "ax": 0.0,
                "ay": 0.0,
                "az": GRAVITY_MPS2,
                "gx": 0.0,
                "gy": 0.0,
                "gz": 0.0,
                "latitude_deg": 18.52,
                "gnss_speed_mps": 11.0,
                "mx": 22.0,
                "my": -4.0,
                "mz": 38.0,
                "pose_x_m": 0.0,
                "pose_y_m": 0.0,
                "pose_z_m": 0.0,
                "heading_rad": 0.0,
                "speed_mps": 0.0,
                "stopped": 1.0,
            }
            for i in range(40)
        ]
        windows = odometry_windows(records)
        self.assertGreater(len(windows), 5)
        self.assertEqual(len(windows[0].sequence[0]), 6)
        self.assertAlmostEqual(windows[0].dx_m, 0.0, places=6)
        self.assertAlmostEqual(windows[0].dy_m, 0.0, places=6)

    def test_left_pad_is_causal(self) -> None:
        seq = [[1.0, 0.0, GRAVITY_MPS2, 0.0, 0.0, 0.0]]
        padded = left_pad_sequence(seq, length=4)
        self.assertEqual(len(padded), 4)
        self.assertEqual(padded[0], [0.0] * 6)
        self.assertEqual(padded[-1][2], GRAVITY_MPS2)

    def test_cruise_window_has_forward_ronin_displacement(self) -> None:
        trip = synthetic_vehicle_odometry("cruise", duration_s=2.0, hz=50, seed=7, trip_id="c")
        windows = odometry_windows(trip)
        self.assertGreater(len(windows), 10)
        late = windows[-1]
        self.assertGreater(late.dx_m, 8.0)
        self.assertLess(abs(late.dy_m), 1.5)
        self.assertGreater(late.speed_mps, 10.0)

    def test_paper_losses_are_finite(self) -> None:
        pred = decode_raw_heads((1.0, 0.2, 0.0, -1.0, -1.0, -1.0, 2.0, 0.0, -1.0, 0.1))
        self.assertTrue(ronin_strided_mse(pred.dx_m, pred.dy_m, 1.1, 0.1) < 1.0)
        self.assertTrue(tlio_gaussian_nll(pred, 1.0, 0.2, 0.0) == tlio_gaussian_nll(pred, 1.0, 0.2, 0.0))
        self.assertGreaterEqual(ionet_polar_loss(pred, 1.0, 0.2, 0.1), 0.0)
        self.assertAlmostEqual(pred.delta_length_m, (1.0**2 + 0.2**2) ** 0.5, places=6)

    def test_linear_student_fits_trip_splits_on_synthetic(self) -> None:
        trips = default_synthetic_odometry(seed=11)
        result = train_from_odometry(trips, seed="learned-imu-test")
        report = result["report"]
        self.assertGreater(report["train_windows"], 0)
        self.assertIn("ronin_dp_xy", report["heads"])
        self.assertIn("tlio_dz_log_sigma", report["heads"])
        self.assertTrue(report["splits"])
        for metrics in report["splits"].values():
            self.assertIn("ronin_dp_xy_mae_m", metrics)
            self.assertIn("tlio_nll", metrics)
            self.assertIn("freeze_dp_xy_mae_m", metrics)

    def test_probe_finds_nothing_in_empty_tree(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            probes = probe_learned_imu_datasets(Path(folder))
        names = {row.name for row in probes}
        self.assertEqual(names, {"ronin", "oxiod", "io_vnbd", "ridi"})
        self.assertTrue(all(not row.present for row in probes))
        self.assertTrue(all(row.url.startswith("http") for row in probes))

    def test_train_script_writes_report_without_datasets(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / "out"
            code = main(["--seed", "4", "--out", str(out), "--data-root", folder])
            self.assertEqual(code, 0)
            report = json.loads((out / "train_report.json").read_text())
            self.assertEqual(report["source"], "synthetic")
            self.assertFalse(report["timesfm"])
            self.assertFalse(report["apk"])
            self.assertFalse(report["magnetometer_in_features"])
            self.assertTrue((out / "linear_dp.json").is_file())
            dumped = json.loads((out / "linear_dp.json").read_text())
            self.assertEqual(dumped["schema"], LINEAR_DP_SCHEMA)
            loaded = load_linear_displacement_student(out / "linear_dp.json")
            self.assertEqual(loaded.feature_names, tuple(dumped["feature_names"]))
            self.assertEqual(len(loaded.weights), HEAD_DIM)

    def test_speed_only_trips_do_not_invent_pose(self) -> None:
        samples = [
            ImuSample(i * 20_000_000, 0.0, 0.0, GRAVITY_MPS2, 0.0, 0.0, 0.0)
            for i in range(20)
        ]
        sequence = hacf_sequence(samples)
        self.assertEqual(len(sequence[0]), 6)
        self.assertAlmostEqual(sequence[0][2], GRAVITY_MPS2, places=5)

    def test_torch_boundary(self) -> None:
        if torch_is_installed():
            require_torch()
            return
        with self.assertRaises(TorchUnavailable):
            require_torch()
        note = train_torch_student({"idle-0": synthetic_vehicle_odometry("idle", duration_s=1.0)}, Path("."))
        self.assertIn("skipped", note)


if __name__ == "__main__":
    unittest.main()
