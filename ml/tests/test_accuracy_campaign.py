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
            self.assertNotIn("S-Vtb3", train)
            self.assertNotIn("S-Vtb3", hold)
            self.assertTrue(freeze.get("c_may_train"))
            self.assertFalse(freeze.get("holdout_open", False))
        self.assertGreaterEqual(payload["version"], 16)
        self.assertEqual(
            payload["pins"]["campaign_base_commit"],
            "60c280d5dd1eaf1b98ae1614a360bbf936f67953",
        )
        learned = payload["tasks"]["C"]
        self.assertEqual(learned["status"], "merged_research_rejection")
        self.assertFalse(learned["holdout_open"])
        self.assertFalse(learned["preregistration"]["split_pin"]["holdout_open"])
        self.assertTrue(learned["preregistration"]["split_pin"]["ids_match_campaign_freeze"])
        self.assertFalse(learned["preregistration"]["split_pin"]["measured_candidate"])
        self.assertTrue(payload["tasks"]["D"]["split_pin"]["ids_match_campaign_freeze"])
        self.assertTrue(payload["tasks"]["D"]["split_pin"]["shared_files_match_pr20"])
        self.assertFalse(payload["tasks"]["D"]["selection_open"])
        self.assertFalse(learned["real_data_run"]["measured_candidate"])
        self.assertEqual(
            learned["real_data_run"]["merged_commit"],
            "b80a2aa25ae94ae62d92a373d264ff3f45efc2e8",
        )
        self.assertEqual(learned["long_outage_run"]["status"], "rejected")
        self.assertFalse(learned["long_outage_run"]["measured_candidate"])
        self.assertFalse(learned["long_outage_run"]["fresh_holdout_open"])
        self.assertFalse(learned["long_outage_run"]["locked_confirmation"])
        self.assertIsNone(learned["long_outage_run"]["selected"])
        self.assertEqual(learned["integrated_run"]["status"], "rejected")
        self.assertFalse(learned["integrated_run"]["measured_candidate"])
        self.assertFalse(learned["integrated_run"]["fresh_holdout_open"])
        self.assertIsNone(learned["integrated_run"]["selected"])
        self.assertTrue(learned["integrated_run"]["owner_archive"]["selection_match_verified"])
        self.assertFalse(learned["integrated_run"]["owner_archive"]["estimator_changed"])
        self.assertFalse(learned["integrated_run"]["owner_archive"]["measured_candidate"])
        self.assertFalse(learned["integrated_run"]["owner_archive"]["draft"])
        self.assertEqual(
            learned["integrated_run"]["owner_archive"]["merged_commit"],
            "60c280d5dd1eaf1b98ae1614a360bbf936f67953",
        )
        self.assertEqual(
            json.loads((ROOT / "results" / "long_motion_20260910" / "horizon" / "selection.json").read_text()),
            json.loads((ROOT / "results" / "cursor_campaign" / "v1" / "long_outage_v4" / "selection.json").read_text()),
        )
        self.assertEqual(
            json.loads((ROOT / "results" / "long_motion_20260910" / "integrated" / "selection.json").read_text()),
            json.loads((ROOT / "results" / "cursor_campaign" / "v1" / "integrated_v5" / "selection.json").read_text()),
        )
        verified = json.loads(
            (ROOT / "results" / "cursor_campaign" / "v1" / "long_outage_v4" / "verified_summary.json").read_text()
        )
        self.assertEqual(verified["status"], "rejected")
        self.assertIsNone(verified["selected"])
        self.assertFalse(verified["fresh_holdout_open"])
        self.assertEqual(
            verified["candidates"]["gru_8"]["p50"],
            learned["long_outage_run"]["best_median"]["median"],
        )
        integrated = json.loads(
            (ROOT / "results" / "cursor_campaign" / "v1" / "integrated_v5" / "verified_summary.json").read_text()
        )
        self.assertEqual(integrated["status"], "rejected")
        self.assertIsNone(integrated["selected"])
        self.assertFalse(integrated["fresh_holdout_open"])
        self.assertEqual(
            integrated["candidates"]["mlp_16"]["p50"],
            learned["integrated_run"]["best_median"]["median"],
        )
        self.assertFalse(payload["tasks"]["D"]["selection_open"])
        self.assertFalse(payload["tasks"]["D"]["locked_confirmation_open"])
        self.assertFalse(payload["tasks"]["D"]["holdout_open"])
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
