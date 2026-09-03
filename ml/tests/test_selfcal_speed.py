import unittest

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import FEATURE_NAMES, GRAVITY_MPS2
from driftzero_ml.selfcal_speed import (
    SELF_CAL_FEATURE_NAMES,
    AffineCorrection,
    budget_bucket,
    calibration_budget_s,
    fit_affine_correction,
    fit_selfcal,
    persist_selfcal_speed_fn,
    selfcal_vector,
)


class SelfCalSpeedTests(unittest.TestCase):
    def test_feature_names_exclude_gnss(self) -> None:
        self.assertEqual(set(SELF_CAL_FEATURE_NAMES) & GNSS_KEYS, set())
        self.assertTrue(set(FEATURE_NAMES).issubset(SELF_CAL_FEATURE_NAMES))
        self.assertEqual(len(SELF_CAL_FEATURE_NAMES), len(FEATURE_NAMES) + 3)

    def test_fit_recovers_vibration_speed_and_ignores_post_freeze(self) -> None:
        rows = _vib_speed_trip(speed=8.0, count=80, start_ns=0)
        leaked = _vib_speed_trip(speed=30.0, count=20, start_ns=80 * 100_000_000)
        model = fit_selfcal(rows + leaked, start_ns=80 * 100_000_000, min_fit=40)
        self.assertIsNotNone(model)
        assert model is not None
        self.assertGreaterEqual(model.n_fit, 40)
        probe = _vib_speed_trip(speed=8.0, count=16, start_ns=200 * 100_000_000)
        speed_at = persist_selfcal_speed_fn(model, probe)
        pred = speed_at(probe[-1], 0.0)
        self.assertGreater(pred, 4.0)
        self.assertLess(pred, 14.0)

    def test_too_few_samples_returns_none(self) -> None:
        rows = _vib_speed_trip(speed=5.0, count=12, start_ns=0)
        self.assertIsNone(fit_selfcal(rows, start_ns=12 * 100_000_000, min_fit=40))

    def test_affine_recovers_scale_and_bias(self) -> None:
        student = {i * 100_000_000: 4.0 + 0.05 * i for i in range(60)}
        rows = []
        for index in range(60):
            stamp = index * 100_000_000
            rows.append(
                {
                    "timestamp_ns": stamp,
                    "ax": 0.0,
                    "ay": 0.0,
                    "az": GRAVITY_MPS2,
                    "gx": 0.0,
                    "gy": 0.0,
                    "gz": 0.0,
                    "gnss_speed_mps": 2.0 * student[stamp] + 1.5,
                    "latitude_deg": 52.0,
                    "longitude_deg": -1.7,
                }
            )

        def student_at(row: dict) -> float:
            return student[int(row["timestamp_ns"])]

        fit = fit_affine_correction(rows, start_ns=60 * 100_000_000, student_speed_at=student_at)
        self.assertGreaterEqual(fit.n_fit, 20)
        self.assertAlmostEqual(fit.scale, 2.0, places=1)
        self.assertAlmostEqual(fit.bias, 1.5, places=1)
        self.assertAlmostEqual(fit.apply(5.0), 2.0 * 5.0 + 1.5, places=1)

    def test_affine_identity_when_student_is_flat(self) -> None:
        rows = [
            {
                "timestamp_ns": i * 100_000_000,
                "ax": 0.0,
                "ay": 0.0,
                "az": GRAVITY_MPS2,
                "gx": 0.0,
                "gy": 0.0,
                "gz": 0.0,
                "gnss_speed_mps": 7.0,
                "latitude_deg": 52.0,
                "longitude_deg": -1.7,
            }
            for i in range(40)
        ]
        fit = fit_affine_correction(rows, start_ns=40 * 100_000_000, student_speed_at=lambda _r: 7.0)
        self.assertEqual(fit.scale, 1.0)
        self.assertEqual(fit.bias, 0.0)

    def test_rolling_window_drops_old_labels(self) -> None:
        early = _vib_speed_trip(speed=3.0, count=50, start_ns=0)
        late = _vib_speed_trip(speed=12.0, count=50, start_ns=50 * 100_000_000)
        start = 100 * 100_000_000
        model = fit_selfcal(early + late, start_ns=start, roll_ns=40 * 100_000_000, min_fit=20)
        self.assertIsNotNone(model)
        assert model is not None
        probe = _vib_speed_trip(speed=12.0, count=16, start_ns=200 * 100_000_000)
        pred = persist_selfcal_speed_fn(model, probe)(probe[-1], 0.0)
        self.assertGreater(pred, 7.0)

    def test_budget_helpers(self) -> None:
        rows = [
            {
                "timestamp_ns": 5_000_000_000,
                "gnss_speed_mps": 4.0,
                "latitude_deg": 1.0,
                "longitude_deg": 2.0,
            }
        ]
        self.assertAlmostEqual(calibration_budget_s(rows, 65_000_000_000), 60.0)
        self.assertEqual(budget_bucket(12.0), "under_60s")
        self.assertEqual(budget_bucket(60.0), "60_to_300s")
        self.assertEqual(budget_bucket(300.0), "60_to_300s")
        self.assertEqual(budget_bucket(301.0), "over_300s")

    def test_selfcal_vector_length(self) -> None:
        from driftzero_ml.features.causal_imu import ImuSample

        samples = [
            ImuSample(i * 100_000_000, 0.2, 0.0, GRAVITY_MPS2, 0.01, 0.0, 0.02)
            for i in range(12)
        ]
        vector = selfcal_vector(samples)
        self.assertEqual(len(vector), len(SELF_CAL_FEATURE_NAMES))

    def test_missing_model_holds_prior(self) -> None:
        rows = _vib_speed_trip(speed=4.0, count=8, start_ns=0)
        speed_at = persist_selfcal_speed_fn(None, rows)
        self.assertEqual(speed_at(rows[-1], 6.1), 6.1)

    def test_affine_clip(self) -> None:
        corr = AffineCorrection(scale=4.0, bias=10.0, n_fit=20, used_span_s=10.0, linear_std=2.0)
        self.assertEqual(corr.apply(20.0), 50.0)
        corr_neg = AffineCorrection(scale=-2.0, bias=0.0, n_fit=20, used_span_s=10.0, linear_std=2.0)
        self.assertEqual(corr_neg.apply(3.0), 0.0)


def _vib_speed_trip(speed: float, count: int, start_ns: int) -> list[dict]:
    rows = []
    residual = speed / 3.5
    for index in range(count):
        stamp = start_ns + index * 100_000_000
        rows.append(
            {
                "timestamp_ns": stamp,
                "ax": residual if index % 2 == 0 else -residual,
                "ay": 0.1,
                "az": GRAVITY_MPS2,
                "gx": 0.05,
                "gy": 0.02,
                "gz": 0.03,
                "gnss_speed_mps": speed,
                "latitude_deg": 52.0 + index * 1e-6,
                "longitude_deg": -1.7,
            }
        )
    return rows


if __name__ == "__main__":
    unittest.main()
