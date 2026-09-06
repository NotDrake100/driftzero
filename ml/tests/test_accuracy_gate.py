import unittest

from driftzero_ml.accuracy_gate import assess
from driftzero_ml.screening import GATED_INTERVAL_IDS


class AccuracyGateTest(unittest.TestCase):
    def report(self):
        return {"per_interval": [
            {"interval_id": key, "metrics": {"endpoint_error_m": 9, "truth_path_length_m": 100}}
            for key in sorted(GATED_INTERVAL_IDS)
        ]}

    def test_complete_evidence_passes_but_one_ten_percent_failure_does_not(self):
        report = self.report()
        self.assertTrue(assess(report)["passed"])
        report["per_interval"][0]["metrics"]["endpoint_error_m"] = 10
        result = assess(report)
        self.assertLess(result["drift_p50"], 0.1)
        self.assertFalse(result["passed"])

    def test_missing_duplicate_and_nonfinite_evidence_fail(self):
        for change in ("missing", "duplicate", "nan", "failure"):
            report = self.report()
            if change == "missing":
                report["per_interval"].pop()
            elif change == "duplicate":
                report["per_interval"].append(report["per_interval"][0])
            elif change == "nan":
                report["per_interval"][0]["metrics"]["endpoint_error_m"] = float("nan")
            else:
                report["failures"] = ["replay crashed"]
            self.assertFalse(assess(report)["passed"], change)

    def test_diagnostic_only_report_is_rejected_even_if_ratios_pass(self):
        report = self.report()
        self.assertTrue(assess(report)["passed"])
        labeled = dict(report)
        labeled["diagnostic_only"] = True
        labeled["label"] = "DIAGNOSTIC_ONLY"
        result = assess(labeled)
        self.assertFalse(result["passed"])
        self.assertIn(
            "DIAGNOSTIC_ONLY report is not candidate evidence",
            result["invalid_evidence"],
        )
        row_flagged = self.report()
        row_flagged["per_interval"][0]["diagnostic_only"] = True
        self.assertFalse(assess(row_flagged)["passed"])
        substitution = self.report()
        substitution["reference_substitution"] = {"variant": "ref_joint"}
        self.assertFalse(assess(substitution)["passed"])
        oracle = self.report()
        oracle["oracle"] = True
        self.assertFalse(assess(oracle)["passed"])
        classified = self.report()
        classified["evidence_class"] = "DIAGNOSTIC_ONLY"
        self.assertFalse(assess(classified)["passed"])
