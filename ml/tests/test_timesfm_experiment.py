import tempfile
import unittest
from importlib.util import find_spec
from pathlib import Path

from driftzero_ml.blackout import BlackoutInterval, assert_no_gnss_leakage, mask_gnss_records
from driftzero_ml.timesfm_adapter import timesfm_is_installed, validate_context
from driftzero_ml.timesfm25 import (
    COV_ABS_OMEGA_Z,
    COV_FORWARD_ACCEL,
    COV_STOP_FLAG,
    HORIZON_COVARIATE_POLICY,
    apply_xreg_plus_forecast,
    pack_causal_speed_covariates,
    write_teacher_targets,
    xreg_plus_timesfm_inputs,
)
from driftzero_ml.timesfm_experiment import (
    const_accel_forecast,
    fill_numeric,
    hold_after,
    mae,
    persist_forecast,
    picp_interval,
    rmse,
    scenario_name,
)


class TimesFMExperimentHelpersTests(unittest.TestCase):
    def test_persist_holds_last_value(self) -> None:
        self.assertEqual(persist_forecast(4.2, 3), [4.2, 4.2, 4.2])

    def test_const_accel_ramps_and_clips_at_zero(self) -> None:
        rising = const_accel_forecast(1.0, 10.0, 2, dt_s=0.1)
        self.assertAlmostEqual(rising[0], 2.0)
        self.assertAlmostEqual(rising[1], 3.0)
        stopped = const_accel_forecast(0.2, -10.0, 3, dt_s=0.1)
        self.assertEqual(stopped, [0.0, 0.0, 0.0])

    def test_hold_after_freezes_pre_blackout_speed(self) -> None:
        held = hold_after([1.0, 2.0, 3.0, 9.0, 8.0], 2)
        self.assertEqual(held, [1.0, 2.0, 2.0, 2.0, 2.0])

    def test_fill_numeric_is_causal_then_backfills_prefix(self) -> None:
        self.assertEqual(fill_numeric([None, 4.0, None, 5.0]), [4.0, 4.0, 4.0, 5.0])

    def test_picp_and_error_helpers(self) -> None:
        self.assertAlmostEqual(mae([1.0, 3.0], [1.0, 1.0]), 1.0)
        self.assertAlmostEqual(rmse([1.0, 3.0], [1.0, 1.0]), (2.0**2 / 2.0) ** 0.5)
        self.assertAlmostEqual(picp_interval([1.0, 2.0, 5.0], [0.0, 0.0, 0.0], [2.0, 2.0, 2.0]), 2.0 / 3.0)

    def test_scenario_stop_go_vs_cruising(self) -> None:
        self.assertEqual(scenario_name([0.2, 0.4, 0.1, 8.0]), "stop_go")
        self.assertEqual(scenario_name([12.0, 11.0, 10.0, 9.0]), "cruising")

    def test_masked_context_has_no_gnss_keys(self) -> None:
        rows = [
            {"timestamp_ns": 0, "ax": 1.0, "gnss_speed_mps": 8.0, "latitude_deg": 19.0},
            {"timestamp_ns": 10, "ax": 1.1, "gnss_speed_mps": 9.0, "latitude_deg": 19.1},
        ]
        masked = mask_gnss_records(rows, [BlackoutInterval(10, 20, "gap")])
        assert_no_gnss_leakage(masked)
        self.assertNotIn("gnss_speed_mps", masked[1])
        self.assertNotIn("latitude_deg", masked[1])
        speeds = hold_after([8.0, 9.0], 1)
        self.assertEqual(speeds[1], 8.0)
        validate_context([speeds, [0.1, 0.1]])

    def test_timesfm_import_is_optional(self) -> None:
        self.assertEqual(timesfm_is_installed(), find_spec("timesfm") is not None)
        if not timesfm_is_installed():
            self.skipTest("timesfm not installed")


class TimesFM25CovariateAndTeacherTests(unittest.TestCase):
    def test_pack_holds_last_causal_imu_not_future(self) -> None:
        packed = pack_causal_speed_covariates(
            [1.0, 2.0, 3.0, 99.0],
            [0.1, 0.2, 0.3, 7.0],
            [0.0, 0.0, 1.0, 0.0],
            end_index=3,
            horizon_steps=2,
            context_steps=8,
        )
        self.assertEqual(HORIZON_COVARIATE_POLICY, "last_causal_hold")
        self.assertEqual(packed[COV_FORWARD_ACCEL], [1.0, 2.0, 3.0, 3.0, 3.0])
        self.assertEqual(packed[COV_ABS_OMEGA_Z], [0.1, 0.2, 0.3, 0.3, 0.3])
        self.assertEqual(packed[COV_STOP_FLAG], [0.0, 0.0, 1.0, 1.0, 1.0])
        self.assertNotIn(99.0, packed[COV_FORWARD_ACCEL])
        self.assertNotIn(7.0, packed[COV_ABS_OMEGA_Z])

    def test_pack_truncates_to_context_window(self) -> None:
        accel = [float(i) for i in range(10)]
        packed = pack_causal_speed_covariates(
            accel,
            [0.0] * 10,
            [0.0] * 10,
            end_index=9,
            horizon_steps=1,
            context_steps=4,
        )
        self.assertEqual(packed[COV_FORWARD_ACCEL], [5.0, 6.0, 7.0, 8.0, 8.0])

    def test_xreg_recovers_linear_speed_from_causal_accel(self) -> None:
        accel = [0.0, 1.0, 2.0, 3.0, 4.0]
        target = [5.0 + 2.0 * value for value in accel]
        packed = pack_causal_speed_covariates(
            accel,
            [0.0] * 5,
            [0.0] * 5,
            end_index=5,
            horizon_steps=3,
            context_steps=8,
        )
        residual, xreg_horizon, xreg_context = xreg_plus_timesfm_inputs(target, packed)
        for value in residual:
            self.assertAlmostEqual(value, 0.0, delta=0.05)
        for value in xreg_horizon:
            self.assertAlmostEqual(value, 13.0, delta=0.2)
        for pred, truth in zip(xreg_context, target):
            self.assertAlmostEqual(pred, truth, delta=0.2)

        def zero_forecast(_residual, horizon: int) -> list[float]:
            return [0.0] * horizon

        combined = apply_xreg_plus_forecast(target, packed, zero_forecast)
        self.assertEqual(len(combined), 3)
        for value in combined:
            self.assertAlmostEqual(value, 13.0, delta=0.2)

    def test_teacher_writer_train_only_tiny_series(self) -> None:
        if find_spec("numpy") is None:
            self.skipTest("numpy not installed")
        rows = [
            {
                "trip_id": "S-toy",
                "split": "train",
                "origin_index": 4,
                "origin_ns": 400_000_000,
                "last_speed_mps": 3.5,
                "timesfm_speed_mps": [3.6, 3.7, 3.8],
                COV_FORWARD_ACCEL: 0.2,
                COV_ABS_OMEGA_Z: 0.01,
                COV_STOP_FLAG: 0.0,
            },
            {
                "trip_id": "S-toy",
                "split": "train",
                "origin_index": 6,
                "origin_ns": 600_000_000,
                "last_speed_mps": 4.0,
                "timesfm_speed_mps": [4.1, 4.0, 3.9],
                COV_FORWARD_ACCEL: -0.1,
                COV_ABS_OMEGA_Z: 0.2,
                COV_STOP_FLAG: 1.0,
            },
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "teacher_targets.npz"
            write_teacher_targets(path, rows, {"seed": "26168", "stride": 2, "context_steps": 4})
            self.assertTrue(path.is_file())
            schema = path.with_suffix(".schema.json")
            self.assertTrue(schema.is_file())
            self.assertIn("Apache-2.0", schema.read_text())
            import numpy as np

            loaded = np.load(path)
            self.assertEqual(list(loaded["trip_id"]), ["S-toy", "S-toy"])
            self.assertEqual(loaded["timesfm_speed_mps"].shape, (2, 3))
            self.assertAlmostEqual(float(loaded["timesfm_speed_mps"][0, 0]), 3.6, places=5)
            self.assertEqual(int(loaded["origin_ns"][1]), 600_000_000)

        eval_row = dict(rows[0])
        eval_row["split"] = "locked_test"
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "train split only"):
                write_teacher_targets(Path(tmp) / "bad.npz", [eval_row], {})


if __name__ == "__main__":
    unittest.main()
