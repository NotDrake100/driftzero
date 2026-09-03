"""Replay mask bounds. No raw IO-VNBD rows."""

from __future__ import annotations

import unittest
from pathlib import Path

from driftzero_ml.eval_kotlin_replay import (
    locked_gated_interval_ids,
    replay_cli_args,
    replay_mask_ns,
    slim_replay_header,
)
from driftzero_ml.screening import GATED_INTERVAL_IDS


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

    def test_replay_cli_args_append_coast_mode(self) -> None:
        args = replay_cli_args(
            Path("/bin/navigation-core"),
            Path("/tmp/in.jsonl"),
            Path("/tmp/out.jsonl"),
            11,
            21,
            extra_args=("--coast-mode=yaw_speed_hold",),
        )
        self.assertEqual(args[-1], "--coast-mode=yaw_speed_hold")
        self.assertIn("--mask-start-ns", args)

    def test_slim_replay_header_keeps_four_keys(self) -> None:
        slim = slim_replay_header(
            {
                "declared_rate_hz": 10.0,
                "clock_domain": "elapsed_realtime_ns",
                "frame": "unspecified",
                "source_id": "S-Vta2",
                "heading_gyro": {"axis": "pitch"},
                "notes": ["x"],
            }
        )
        self.assertEqual(
            set(slim),
            {"declared_rate_hz", "clock_domain", "frame", "source_id"},
        )

    def test_locked_gated_ids_fall_back_to_screening_set(self) -> None:
        missing = Path("/tmp/does-not-exist-metrics_per_interval.csv")
        ids = locked_gated_interval_ids(missing)
        self.assertEqual(len(ids), 35)
        self.assertEqual(set(ids), set(GATED_INTERVAL_IDS))
        self.assertIn("S-Vta2:d50", ids)
        self.assertIn("S-S1:mid", ids)


if __name__ == "__main__":
    unittest.main()
