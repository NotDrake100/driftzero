"""Replay mask bounds. No raw IO-VNBD rows."""

from __future__ import annotations

import unittest

from driftzero_ml.eval_kotlin_replay import replay_mask_ns


class ReplayMaskTests(unittest.TestCase):
    def test_keeps_interval_start_fix(self) -> None:
        start = 2503320000000
        end = 2532321000001
        mask_start, mask_end = replay_mask_ns(start, end)
        self.assertEqual(mask_start, start + 1)
        self.assertEqual(mask_end, end - 1)
        self.assertGreater(mask_start, start)
        self.assertLess(mask_end, end)

    def test_empty_window_unchanged(self) -> None:
        self.assertEqual(replay_mask_ns(10, 10), (10, 10))


if __name__ == "__main__":
    unittest.main()
