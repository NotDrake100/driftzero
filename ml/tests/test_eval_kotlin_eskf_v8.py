"""v8 extra-args and leak-free unique-spacing helper."""

from __future__ import annotations

import unittest
from pathlib import Path

from driftzero_ml.eval_kotlin_eskf_v8 import extra_args_for, premask_unique_median_spacing_s
from driftzero_ml.gnss_truth import unique_fix_median_spacing_s


class EvalKotlinEskfV8Tests(unittest.TestCase):
    def test_extra_args_hold_course_reseed_and_median_gate(self) -> None:
        args = extra_args_for(
            {"fallback": False},
            reseed_s=8.0,
            min_median_s=8.0,
            honest_p=True,
        )
        self.assertIn("--weak-heading-policy=hold_course", args)
        self.assertIn("--heading-pick-quality=accepted", args)
        self.assertIn("--gnss-reseed-after-s=8.0", args)
        self.assertIn("--gnss-reseed-min-median-unique-s=8.0", args)
        self.assertIn("--coast-honest-p", args)
        self.assertNotIn("--coast-stop-detect", args)
        self.assertNotIn("--student-forward-speed", args)
        self.assertNotIn("--gnss-reseed-while-fused", args)
        no_honest = extra_args_for(
            {"fallback": False},
            reseed_s=8.0,
            min_median_s=8.0,
            honest_p=False,
        )
        self.assertNotIn("--coast-honest-p", no_honest)

    def test_extra_args_weak_heading_pick(self) -> None:
        args = extra_args_for({"fallback": True}, reseed_s=8.0, min_median_s=8.0, honest_p=False)
        self.assertIn("--heading-pick-quality=weak", args)

    def test_unique_fix_median_spacing_one_hz_with_one_long_hop(self) -> None:
        stamps = [i * 1_000_000_000 for i in range(16)]
        stamps.append(25_000_000_000)
        self.assertAlmostEqual(unique_fix_median_spacing_s(stamps) or 0.0, 1.0)

    def test_premask_helper_missing_file(self) -> None:
        self.assertIsNone(premask_unique_median_spacing_s(Path("/no/such/frames.jsonl"), 1))


if __name__ == "__main__":
    unittest.main()
