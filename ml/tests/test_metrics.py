import math
import unittest

from driftzero_ml.metrics import (
    BlackoutMetrics,
    evaluate_blackout,
    haversine_m,
    path_length_m,
    summarize_blackouts,
)


class MetricsTests(unittest.TestCase):
    def test_haversine_known_equatorial_degree(self) -> None:
        actual = haversine_m((0.0, 0.0), (0.0, 1.0))
        self.assertTrue(math.isclose(actual, 111_195.08, rel_tol=1e-5))

    def test_haversine_rejects_invalid_coordinate(self) -> None:
        with self.assertRaisesRegex(ValueError, "latitude"):
            haversine_m((91.0, 0.0), (0.0, 0.0))

    def test_evaluate_blackout_drift_ratio(self) -> None:
        truth = [(0.0, 0.0), (0.0, 0.0005), (0.0, 0.001)]
        estimate = [(0.0, 0.0), (0.0, 0.0005), (0.0001, 0.001)]
        metrics = evaluate_blackout(estimate, truth)
        self.assertTrue(math.isclose(metrics.truth_path_length_m, 111.195, rel_tol=1e-4))
        self.assertTrue(math.isclose(metrics.endpoint_error_m, 11.1195, rel_tol=1e-4))
        self.assertTrue(math.isclose(metrics.drift_ratio or 0.0, 0.1, rel_tol=1e-4))

    def test_stationary_path_has_no_ratio(self) -> None:
        metrics = evaluate_blackout([(12.0, 77.0)], [(12.0, 77.0)])
        self.assertIsNone(metrics.drift_ratio)
        self.assertEqual(metrics.endpoint_error_m, 0.0)

    def test_path_length_requires_data(self) -> None:
        with self.assertRaisesRegex(ValueError, "at least one"):
            path_length_m([])

    def test_summarize_keeps_ineligible_count(self) -> None:
        rows = [
            BlackoutMetrics(5.0, 100.0, 0.05, 2.0, 5.0, 10),
            BlackoutMetrics(1.0, 0.0, None, 0.5, 1.0, 10),
            BlackoutMetrics(20.0, 100.0, 0.2, 10.0, 20.0, 10),
        ]
        summary = summarize_blackouts(rows)
        self.assertEqual(summary["interval_count"], 3)
        self.assertEqual(summary["ratio_eligible_count"], 2)
        self.assertTrue(math.isclose(float(summary["drift_ratio_p95"]), 0.2))


if __name__ == "__main__":
    unittest.main()
