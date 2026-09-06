"""Campaign manifest must track the locked suite, not rewrite it."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.screening import GATED_INTERVAL_IDS

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "results" / "cursor_campaign" / "v1" / "manifest.json"


class AccuracyCampaignTest(unittest.TestCase):
    def test_manifest_preserves_locked_ids_and_exposed_groups(self):
        payload = json.loads(MANIFEST.read_text())
        self.assertEqual(payload["pins"]["locked_interval_count"], 35)
        self.assertEqual(payload["pins"]["io_vnbd_commit"], "118939602e3422d47b8ab0807b623751c3ac135b")
        self.assertFalse(payload["claim"]["median_target_passed"])
        self.assertFalse(payload["claim"]["all_interval_target_passed"])
        freeze = payload.get("split_freeze") or {}
        if freeze:
            locked = set(payload["exposed_groups"]["locked_session_groups"])
            train = set(freeze["train_session_groups"])
            hold = set(freeze["fresh_holdout_session_groups"])
            self.assertTrue(train.isdisjoint(locked))
            self.assertTrue(hold.isdisjoint(locked))
            self.assertTrue(train.isdisjoint(hold))
            self.assertFalse(freeze.get("c_may_train"))
        locked = {session_group_id(key.split(":")[0]) for key in GATED_INTERVAL_IDS}
        self.assertEqual(set(payload["exposed_groups"]["locked_session_groups"]), locked)
        self.assertEqual(len(GATED_INTERVAL_IDS), 35)

    def test_development_groups_match_recorded_manifest(self):
        recorded = json.loads(
            (ROOT / "results" / "accuracy_v3_20260906" / "development" / "manifest.json").read_text()
        )
        campaign = json.loads(MANIFEST.read_text())
        expected = {session_group_id(trip) for trip in recorded["trips"]}
        self.assertEqual(set(campaign["exposed_groups"]["development_session_groups"]), expected)
        self.assertTrue(set(recorded["interval_ids"]).isdisjoint(GATED_INTERVAL_IDS))
