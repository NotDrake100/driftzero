"""D evaluation guards: leakage, parity, and blocked selection."""

from __future__ import annotations

import unittest

from driftzero_ml.accuracy_gate import assess
from driftzero_ml.eval_checks import (
    HOLDOUT_OPEN,
    LOCKED_CONFIRMATION_OPEN,
    MATRIX,
    SELECTION_OPEN,
    assert_holdout_closed,
    assert_selection_blocked,
    b_map_overlay_rejected,
    load_split,
    locked_ids_unchanged,
    preregistration_payload,
    reject_as_candidate,
    scoring_ratio,
)
from driftzero_ml.screening import GATED_INTERVAL_IDS


class EvalChecksTests(unittest.TestCase):
    def test_locked_interval_set_is_unchanged(self) -> None:
        self.assertEqual(len(GATED_INTERVAL_IDS), 35)
        self.assertTrue(locked_ids_unchanged())

    def test_selection_and_holdout_stay_closed(self) -> None:
        self.assertFalse(SELECTION_OPEN)
        self.assertFalse(LOCKED_CONFIRMATION_OPEN)
        self.assertFalse(HOLDOUT_OPEN)
        assert_selection_blocked()
        self.assertFalse(any(row["selectable"] for row in MATRIX.values()))
        self.assertEqual(MATRIX["map_only"]["role"], "rejected_b")
        split = load_split()
        with self.assertRaisesRegex(ValueError, "fresh holdout"):
            assert_holdout_closed(["S-Vta4"], split)
        assert_holdout_closed(["S-M"], split)

    def test_diagnostic_and_oracle_reports_are_not_candidates(self) -> None:
        rows = [
            {"interval_id": key, "metrics": {"endpoint_error_m": 1.0, "truth_path_length_m": 100.0}}
            for key in sorted(GATED_INTERVAL_IDS)
        ]
        for payload in (
            {"label": "DIAGNOSTIC_ONLY", "per_interval": rows},
            {"evidence_class": "DIAGNOSTIC_ONLY", "per_interval": rows},
            {"oracle": True, "per_interval": rows},
            {"reference_substitution": True, "per_interval": rows},
            {"diagnostic_only": True, "per_interval": rows},
        ):
            reasons = reject_as_candidate(payload)
            self.assertTrue(reasons, payload)
            result = assess(payload)
            self.assertFalse(result["passed"])
            self.assertTrue(result["invalid_evidence"])

    def test_scoring_parity_recomputes_ratio(self) -> None:
        self.assertAlmostEqual(scoring_ratio(9.0, 100.0), 0.09)
        self.assertGreaterEqual(scoring_ratio(10.0, 100.0), 0.10)
        with self.assertRaises(ValueError):
            scoring_ratio(1.0, 0.0)
        with self.assertRaises(ValueError):
            scoring_ratio(-1.0, 100.0)

    def test_b_overlay_is_not_selected(self) -> None:
        decision = b_map_overlay_rejected()
        self.assertEqual(decision["status"], "rejection")
        self.assertFalse(decision["locked_confirmation"])
        self.assertFalse(decision["android_defaults"])

    def test_preregistration_keeps_matrix_blocked(self) -> None:
        payload = preregistration_payload()
        self.assertEqual(payload["status"], "checks_prepared")
        self.assertFalse(payload["selection_open"])
        self.assertFalse(payload["locked_confirmation_open"])
        self.assertFalse(payload["holdout_open"])
        self.assertTrue(payload["locked_ids_match_split"])
        self.assertTrue(payload["diagnostic_only_rejected"])
        self.assertEqual(payload["locked_interval_count"], 35)
        self.assertIn("S-Vtb3", payload["excluded_session_groups"])


if __name__ == "__main__":
    unittest.main()
