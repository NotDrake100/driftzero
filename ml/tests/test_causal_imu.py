import unittest

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    GRAVITY_MPS2,
    ImuSample,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)


class CausalImuTests(unittest.TestCase):
    def test_feature_names_exclude_gnss(self) -> None:
        leaked = set(FEATURE_NAMES) & GNSS_KEYS
        self.assertEqual(leaked, set())

    def test_trim_drops_future_samples(self) -> None:
        rows = (
            ImuSample(0, 0.0, 0.0, GRAVITY_MPS2),
            ImuSample(1_000_000_000, 0.0, 0.0, GRAVITY_MPS2),
            ImuSample(2_000_000_000, 1.0, 0.0, GRAVITY_MPS2),
        )
        kept = trim_causal_window(rows, end_ns=1_000_000_000)
        self.assertEqual([row.timestamp_ns for row in kept], [0, 1_000_000_000])

    def test_records_ignore_gnss_keys(self) -> None:
        records = [
            {
                "timestamp_ns": 0,
                "ax": 0.0,
                "ay": 0.0,
                "az": GRAVITY_MPS2,
                "gx": 0.0,
                "gy": 0.0,
                "gz": 0.0,
                "latitude_deg": 18.5,
                "gnss_speed_mps": 22.0,
                "satellites_used": 14,
            }
        ]
        samples = records_to_imu_samples(records)
        self.assertEqual(len(samples), 1)
        self.assertEqual(samples[0].ax, 0.0)
        features = extract_causal_imu_features(samples)
        self.assertNotIn("latitude_deg", FEATURE_NAMES)
        self.assertEqual(len(features.vector), len(FEATURE_NAMES))

    def test_idle_window_sets_idle_flag(self) -> None:
        samples = [
            ImuSample(
                timestamp_ns=i * 20_000_000,
                ax=0.0,
                ay=0.0,
                az=GRAVITY_MPS2,
                gx=0.0,
                gy=0.0,
                gz=0.0,
            )
            for i in range(40)
        ]
        features = extract_causal_imu_features(samples)
        self.assertTrue(features.idle)
        self.assertFalse(features.bump)

    def test_extract_rejects_empty_window(self) -> None:
        with self.assertRaisesRegex(ValueError, "empty"):
            extract_causal_imu_features([])


if __name__ == "__main__":
    unittest.main()
