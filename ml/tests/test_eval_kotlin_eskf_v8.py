"""v8 replay flags and gate checks. No raw IO-VNBD rows."""

from __future__ import annotations

import unittest

from driftzero_ml.eval_kotlin_eskf_v8 import extra_args_for, criteria


class V8ArgsTests(unittest.TestCase):
    def test_v8b_requires_sparse_spacing_and_eight_second_gap(self) -> None:
        args = extra_args_for(
            {"fallback": True},
            reseed_s=8.0,
            require_sparse=True,
            latch=False,
        )
        self.assertIn("--coast-mode=yaw_speed_hold", args)
        self.assertIn("--weak-heading-policy=hold_course", args)
        self.assertIn("--heading-pick-quality=weak", args)
        self.assertIn("--gnss-reseed-after-s=8.0", args)
        self.assertIn("--gnss-reseed-require-sparse", args)
        self.assertIn("--coast-honest-p", args)
        self.assertNotIn("--gnss-reseed-while-fused", args)
        self.assertNotIn("--coast-latch-gnss-speed", args)
        self.assertNotIn("--student-forward-speed", args)

    def test_v8a_does_not_reseed(self) -> None:
        args = extra_args_for(
            {"fallback": False},
            reseed_s=0.0,
            require_sparse=False,
            latch=False,
        )
        self.assertTrue(all(not item.startswith("--gnss-reseed-after-s") for item in args))
        self.assertNotIn("--gnss-reseed-require-sparse", args)
        self.assertIn("--heading-pick-quality=accepted", args)

    def test_v8c_adds_speed_latch(self) -> None:
        args = extra_args_for(
            {"fallback": False},
            reseed_s=8.0,
            require_sparse=True,
            latch=True,
        )
        self.assertIn("--coast-latch-gnss-speed", args)
        self.assertIn("--gnss-reseed-require-sparse", args)


class V8GateTests(unittest.TestCase):
    def test_criteria_requires_persist_and_named_intervals(self) -> None:
        payload = {
            "summary": {"drift_ratio_p50": 0.50, "endpoint_p50_m": 200.0},
            "slices": {
                "1hz": {"drift_ratio_p50": 0.25},
                "sparse": {"drift_ratio_p50": 0.55},
            },
            "named_intervals": {
                "S-Vta2:d50": {"endpoint_error_m": 12.0},
                "S-S1:mid": {"endpoint_error_m": 54.0},
            },
        }
        check = criteria(payload)
        self.assertTrue(check["all"])
        payload["summary"]["drift_ratio_p50"] = 0.52
        self.assertFalse(criteria(payload)["all"])
        payload["summary"]["drift_ratio_p50"] = 0.50
        payload["named_intervals"]["S-Vta2:d50"]["endpoint_error_m"] = 20.0
        self.assertFalse(criteria(payload)["c_vta2"])


if __name__ == "__main__":
    unittest.main()
