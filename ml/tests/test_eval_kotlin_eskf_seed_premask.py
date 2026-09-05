"""Persist-like seed eval extra args. No IO-VNBD rows."""

from __future__ import annotations

import unittest

from driftzero_ml.eval_kotlin_eskf_seed_premask import extra_args_for


class EvalKotlinEskfSeedPremaskTests(unittest.TestCase):
    def test_extra_args_are_v5a_not_kitchen_sink(self) -> None:
        args = extra_args_for({"fallback": False})
        self.assertIn("--coast-mode=yaw_speed_hold", args)
        self.assertIn("--weak-heading-policy=hold_course", args)
        self.assertIn("--heading-pick-quality=accepted", args)
        joined = " ".join(args)
        self.assertNotIn("coast-latch", joined)
        self.assertNotIn("coast-honest-p", joined)
        self.assertNotIn("student-forward-speed", joined)
        self.assertNotIn("gnss-reseed", joined)
        self.assertNotIn("coast-stop-detect", joined)
        self.assertNotIn("coast-speed-decay", joined)
        weak = extra_args_for({"fallback": True})
        self.assertIn("--heading-pick-quality=weak", weak)


if __name__ == "__main__":
    unittest.main()
