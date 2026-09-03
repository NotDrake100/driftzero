import unittest
from pathlib import Path

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import FEATURE_NAMES, GRAVITY_MPS2, ImuSample
from driftzero_ml.features.residual_imu import HOLD_EXTRA_NAMES, hold_forward_accel_and_omega_z
from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.student.residual import (
    ALLOWED_GNSS_FEATURE,
    RESIDUAL_FEATURE_NAMES,
    assert_residual_feature_names,
    fit_residual_ridge,
    residual_windows_for_trip,
    ridge_beats_persist,
    synthetic_speed_residual_trip,
    windows_by_split,
)
from driftzero_ml.student.train_residual import default_synthetic_trips, run_experiment


class ResidualImuTests(unittest.TestCase):
    def test_forward_accel_sign_and_vertical_gyro(self) -> None:
        still = [
            ImuSample(
                timestamp_ns=i * 100_000_000,
                ax=0.0,
                ay=0.0,
                az=GRAVITY_MPS2,
                gx=0.0,
                gy=0.0,
                gz=0.0,
            )
            for i in range(20)
        ]
        hold_start = still[-1].timestamp_ns
        hold = [
            ImuSample(
                timestamp_ns=hold_start + (i + 1) * 100_000_000,
                ax=2.0,
                ay=0.0,
                az=GRAVITY_MPS2,
                gx=0.0,
                gy=0.0,
                gz=0.05,
            )
            for i in range(12)
        ]
        forward, omega = hold_forward_accel_and_omega_z(still + hold, hold_start_ns=hold_start)
        self.assertGreater(forward, 0.4)
        self.assertGreater(omega, 0.02)
        self.assertLess(omega, 0.08)

    def test_hold_extras_exclude_gnss(self) -> None:
        self.assertEqual(set(HOLD_EXTRA_NAMES) & GNSS_KEYS, set())


class ResidualStudentTests(unittest.TestCase):
    def test_feature_names_allow_only_t0_speed(self) -> None:
        assert_residual_feature_names(RESIDUAL_FEATURE_NAMES)
        self.assertEqual(set(RESIDUAL_FEATURE_NAMES) & GNSS_KEYS, set())
        self.assertIn(ALLOWED_GNSS_FEATURE, RESIDUAL_FEATURE_NAMES)
        self.assertTrue(set(FEATURE_NAMES).issubset(RESIDUAL_FEATURE_NAMES))
        with self.assertRaises(AssertionError):
            assert_residual_feature_names(RESIDUAL_FEATURE_NAMES + ("latitude_deg",))
        with self.assertRaises(AssertionError):
            assert_residual_feature_names(RESIDUAL_FEATURE_NAMES + ("gnss_speed_mps",))

    def test_windows_are_causal_and_use_unique_fix_labels(self) -> None:
        trip = synthetic_speed_residual_trip(trip_id="hold-a", duration_s=36.0, hz=10, accel_mps2=-0.5)
        spiked = [dict(row) for row in trip]
        last = spiked[-1]
        spiked.append(
            {
                **last,
                "timestamp_ns": last["timestamp_ns"] + 100_000_000,
                "ax": 40.0,
                "speed_mps": 99.0,
                "latitude_deg": last["latitude_deg"] + 0.01,
            }
        )
        windows = residual_windows_for_trip(trip, "hold-a")
        spiked_windows = residual_windows_for_trip(spiked, "hold-a")
        self.assertGreater(len(windows), 8)
        one_s = [row for row in windows if row.horizon_s == 1.0]
        self.assertGreater(len(one_s), 2)
        for row in one_s:
            self.assertAlmostEqual(row.elapsed_s, 1.0, delta=0.51)
            self.assertLess(row.t0_ns, row.t_ns)
            expected = row.v_gnss_mps - row.v_last_mps
            self.assertAlmostEqual(row.delta_v_mps, expected)
            self.assertEqual(row.features[-1], row.v_last_mps)
            self.assertEqual(len(row.features), len(RESIDUAL_FEATURE_NAMES))
        matched = [
            (a.delta_v_mps, b.delta_v_mps)
            for a, b in zip(windows, spiked_windows)
            if a.t_ns == b.t_ns and a.horizon_s == b.horizon_s and a.t_ns < last["timestamp_ns"]
        ]
        self.assertGreater(len(matched), 4)
        for left, right in matched:
            self.assertAlmostEqual(left, right)

    def test_session_group_split_not_row_split(self) -> None:
        trips = {
            "S-S3a": synthetic_speed_residual_trip(trip_id="S-S3a", duration_s=20.0, seed=1),
            "S-S3b": synthetic_speed_residual_trip(trip_id="S-S3b", duration_s=20.0, seed=2),
            "S-Vta1a": synthetic_speed_residual_trip(trip_id="S-Vta1a", duration_s=20.0, seed=3),
            "S-Vta1b": synthetic_speed_residual_trip(trip_id="S-Vta1b", duration_s=20.0, seed=4),
            "S-Vta2": synthetic_speed_residual_trip(trip_id="S-Vta2", duration_s=20.0, seed=5),
            "S-Y1": synthetic_speed_residual_trip(trip_id="S-Y1", duration_s=20.0, seed=6),
        }
        grouped, assignments, digest = windows_by_split(trips, seed="26168")
        self.assertEqual(len(digest), 64)
        self.assertEqual(assignments["S-S3a"], assignments["S-S3b"])
        self.assertEqual(assignments["S-Vta1a"], assignments["S-Vta1b"])
        self.assertEqual(session_group_id("S-S3a"), "S-S3")
        seen: dict[str, str] = {}
        for split, rows in grouped.items():
            for row in rows:
                if row.trip_id in seen:
                    self.assertEqual(seen[row.trip_id], split)
                seen[row.trip_id] = split
        self.assertEqual(len(seen), len(trips))

    def test_ridge_recovers_deceleration_better_than_persist_on_synth(self) -> None:
        trip = synthetic_speed_residual_trip(
            trip_id="brake",
            duration_s=40.0,
            accel_mps2=-0.6,
            v0=18.0,
        )
        windows = [row for row in residual_windows_for_trip(trip, "brake") if row.horizon_s == 5.0]
        self.assertGreater(len(windows), 8)
        train = windows[: len(windows) // 2]
        test = windows[len(windows) // 2 :]
        model = fit_residual_ridge(train, seed="26168", horizon_s=5.0)
        persist = sum(abs(row.delta_v_mps) for row in test) / len(test)
        ridge = sum(abs(model.infer_delta(row.features)[0] - row.delta_v_mps) for row in test) / len(test)
        self.assertLess(ridge, persist)

    def test_ridge_gate_requires_clear_margin(self) -> None:
        locked = {
            5.0: {
                "n": 80,
                "persist": {"mae_mps": 1.0},
                "ridge_residual": {"mae_mps": 0.95},
            },
            10.0: {
                "n": 80,
                "persist": {"mae_mps": 2.0},
                "ridge_residual": {"mae_mps": 1.5},
            },
        }
        ok, _reason = ridge_beats_persist(locked)
        self.assertFalse(ok)
        locked[5.0]["ridge_residual"]["mae_mps"] = 0.8
        ok, reason = ridge_beats_persist(locked)
        self.assertTrue(ok)
        self.assertIn("10%", reason)

    def test_train_script_records_seed_and_does_not_need_linear_json(self) -> None:
        trips = default_synthetic_trips(seed=7)
        payload = run_experiment(
            trips,
            seed="26168",
            linear_path=Path("models/motion_student_v1/missing.json"),
            source="synthetic",
        )
        self.assertEqual(payload["seed"], "26168")
        self.assertEqual(len(payload["split_hash"]), 64)
        self.assertFalse(payload["linear_json_overwritten"])
        self.assertIn("1", payload["by_horizon"])
        locked = payload["by_horizon"]["1"]["locked_test"]
        if locked.get("n", 0):
            self.assertIn("persist", locked)
            self.assertIn("ridge_residual", locked)
            self.assertIn("decay_to_mean", locked)
            self.assertNotIn("linear_absolute", locked)


if __name__ == "__main__":
    unittest.main()
