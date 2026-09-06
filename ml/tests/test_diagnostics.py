"""Synthetic diagnostics, leakage, and split-freeze tests. No raw IO-VNBD rows."""

from __future__ import annotations

import math
import unittest

from driftzero_ml.accuracy_gate import assess
from driftzero_ml.diagnostics import (
    DEVELOPMENT_SESSION_GROUPS,
    EXCLUDED_SESSION_GROUPS,
    SCREENING_TRIPS,
    SEED,
    SUBSTITUTION_VARIANTS,
    EpochMotion,
    attribute_interval,
    audit_records,
    build_epoch_motion,
    compare_to_archived,
    destination_latlon,
    follow_polyline,
    freeze_split_manifest,
    locked_session_groups,
    reconstruct_path,
    reference_derivatives,
    score_substitution,
    stamp_diagnostic,
)
from driftzero_ml.gnss_truth import score_epochs
from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.metrics import haversine_m
from driftzero_ml.screening import GATED_INTERVAL_IDS


def _fix(stamp_ns: int, lat: float, lon: float, speed: float = 10.0) -> dict:
    return {
        "timestamp_ns": stamp_ns,
        "latitude_deg": lat,
        "longitude_deg": lon,
        "gnss_speed_mps": speed,
        "ax": 0.1,
        "ay": 0.0,
        "az": 9.8,
        "gx": 0.0,
        "gy": 0.0,
        "gz": 0.0,
    }


class SplitManifestTests(unittest.TestCase):
    def test_locked_siblings_never_train_or_fresh(self) -> None:
        manifest = freeze_split_manifest()
        locked = locked_session_groups()
        self.assertEqual(locked, {session_group_id(key.split(":")[0]) for key in GATED_INTERVAL_IDS})
        self.assertEqual(manifest["seed"], SEED)
        self.assertTrue(manifest["diagnostic_only"])
        for row in manifest["assignments"]:
            if row["session_group_id"] in locked:
                self.assertEqual(row["role"], "locked_confirmation")
                self.assertTrue(row["previously_exposed"])
                self.assertFalse(row["fresh"])
            if row["session_group_id"] in DEVELOPMENT_SESSION_GROUPS:
                self.assertEqual(row["role"], "development")
                self.assertFalse(row["fresh"])
            if row["session_group_id"] in EXCLUDED_SESSION_GROUPS:
                self.assertEqual(row["role"], "excluded")
                self.assertFalse(row["fresh"])
            if row["fresh"]:
                self.assertEqual(row["role"], "fresh_holdout")
                self.assertFalse(row["previously_exposed"])
        train = set(manifest["train_session_groups"])
        self.assertTrue(train.isdisjoint(locked))
        self.assertTrue(train.isdisjoint(DEVELOPMENT_SESSION_GROUPS))
        self.assertTrue(train.isdisjoint(EXCLUDED_SESSION_GROUPS))
        self.assertIn("S-S3", locked)
        self.assertNotIn("S-S3", train)
        self.assertNotIn("S-S3", manifest["fresh_holdout_session_groups"])

    def test_manifest_is_deterministic_and_covers_screening_stems(self) -> None:
        a = freeze_split_manifest()
        b = freeze_split_manifest(list(SCREENING_TRIPS))
        self.assertEqual(a, b)
        assigned = {row["trip_id"] for row in a["assignments"]}
        self.assertEqual(assigned, set(SCREENING_TRIPS))
        self.assertIn("S-Vtb3", EXCLUDED_SESSION_GROUPS)


class CoordinateTimeLeakageTests(unittest.TestCase):
    def test_destination_north_and_east(self) -> None:
        origin = (0.0, 0.0)
        north = destination_latlon(origin, 0.0, 100.0)
        east = destination_latlon(origin, math.pi / 2.0, 100.0)
        self.assertAlmostEqual(haversine_m(origin, north), 100.0, places=4)
        self.assertAlmostEqual(haversine_m(origin, east), 100.0, places=4)
        self.assertGreater(north[0], 0.0)
        self.assertAlmostEqual(north[1], 0.0, places=9)
        self.assertGreater(east[1], 0.0)
        self.assertAlmostEqual(east[0], 0.0, places=9)

    def test_score_epochs_are_half_open_and_integer(self) -> None:
        records = [_fix(0, 0.0, 0.0), _fix(1_000_000_000, 0.001, 0.0), _fix(2_000_000_000, 0.002, 0.0)]
        epochs = score_epochs(records, 0, 2_000_000_000)
        self.assertEqual(epochs, [0, 1_000_000_000])
        self.assertTrue(all(isinstance(stamp, int) for stamp in epochs))

    def test_persist_seed_cannot_use_future_or_in_window_fixes(self) -> None:
        records = [
            _fix(0, 0.0, 0.0, 8.0),
            _fix(1_000_000_000, 0.001, 0.0, 8.0),
            _fix(5_000_000_000, 0.010, 0.0, 30.0),
        ]
        start_ns = 2_000_000_000
        motion = build_epoch_motion(records, start_ns, 6_000_000_000, seed_speed_mps=8.0)
        self.assertTrue(all(row.timestamp_ns < 6_000_000_000 for row in motion))
        self.assertTrue(all(row.timestamp_ns >= start_ns for row in motion))
        future = records[-1]
        self.assertGreater(int(future["timestamp_ns"]), start_ns)
        # Hidden later speed must not become the seed speed of earlier epochs.
        self.assertEqual(motion[0].candidate_speed_mps, 8.0)

    def test_audit_flags_timestamp_rewind(self) -> None:
        records = [_fix(2_000_000_000, 0.0, 0.0), _fix(1_000_000_000, 0.001, 0.0)]
        report = audit_records(records, trip_id="S-S2")
        self.assertEqual(report["timestamp_rewinds"], 1)
        self.assertEqual(report["label"], "DIAGNOSTIC_ONLY")

    def test_sparse_reference_derivatives_are_labeled(self) -> None:
        earlier = _fix(0, 0.0, 0.0)
        later = _fix(5_000_000_000, 0.001, 0.0)
        speed, heading, speed_sparse, heading_sparse = reference_derivatives(earlier, later)
        self.assertIsNotNone(speed)
        self.assertIsNotNone(heading)
        self.assertTrue(speed_sparse)
        self.assertTrue(heading_sparse)


class SubstitutionTests(unittest.TestCase):
    def _east_epochs(self) -> tuple[tuple[float, float], list[EpochMotion]]:
        seed = (0.0, 0.0)
        epochs = []
        lon = 0.0
        for index in range(5):
            lon = index * 0.0002
            truth = (0.0, lon)
            stamp = index * 1_000_000_000
            epochs.append(
                EpochMotion(
                    timestamp_ns=stamp,
                    truth=truth,
                    dt_s=0.0 if index == 0 else 1.0,
                    candidate_speed_mps=5.0,
                    candidate_heading_rad=0.0,
                    ref_speed_mps=22.239,
                    ref_heading_rad=math.pi / 2.0,
                    ref_speed_sparse=False,
                    ref_heading_sparse=False,
                )
            )
        return seed, epochs

    def test_joint_reference_follows_truth_polyline(self) -> None:
        seed, epochs = self._east_epochs()
        estimate, notes = reconstruct_path(
            seed, epochs, use_ref_speed=True, use_ref_heading=True, use_ref_road=True
        )
        self.assertEqual(estimate[0], epochs[0].truth)
        self.assertLess(haversine_m(estimate[-1], epochs[-1].truth), 3.0)
        self.assertTrue(any("approximate" not in note for note in notes) or notes)
        self.assertTrue(any("osm_topology=unsupported" in note for note in notes))

    def test_wrong_candidate_heading_is_corrected_by_ref_heading(self) -> None:
        seed, epochs = self._east_epochs()
        wrong, _ = reconstruct_path(
            seed, epochs, use_ref_speed=True, use_ref_heading=False, use_ref_road=False
        )
        fixed, _ = reconstruct_path(
            seed, epochs, use_ref_speed=True, use_ref_heading=True, use_ref_road=False
        )
        self.assertGreater(haversine_m(wrong[-1], epochs[-1].truth), 20.0)
        self.assertLess(haversine_m(fixed[-1], epochs[-1].truth), 5.0)

    def test_all_variants_are_labeled_diagnostic_only(self) -> None:
        seed, epochs = self._east_epochs()
        for name in SUBSTITUTION_VARIANTS:
            report = score_substitution(seed, epochs, name)
            self.assertTrue(report["diagnostic_only"])
            self.assertEqual(report["label"], "DIAGNOSTIC_ONLY")

    def test_polyline_overshoot_is_flagged(self) -> None:
        pos, overshoot = follow_polyline([(0.0, 0.0), (0.0, 0.0001)], 10_000.0)
        self.assertTrue(overshoot)
        self.assertGreater(haversine_m((0.0, 0.0001), pos), 100.0)

    def test_accuracy_gate_rejects_substitution_payload(self) -> None:
        payload = stamp_diagnostic(
            {
                "reference_substitution": True,
                "per_interval": [
                    {
                        "interval_id": key,
                        "metrics": {"endpoint_error_m": 1.0, "truth_path_length_m": 100.0},
                        "label": "DIAGNOSTIC_ONLY",
                    }
                    for key in sorted(GATED_INTERVAL_IDS)
                ],
            }
        )
        result = assess(payload)
        self.assertFalse(result["passed"])
        self.assertTrue(any("DIAGNOSTIC_ONLY" in item for item in result["invalid_evidence"]))


class AttributionTests(unittest.TestCase):
    def test_seed_stop_gap_and_turn_evidence(self) -> None:
        records = [
            _fix(0, 0.0, 0.0, 10.0),
            _fix(1_000_000_000, 0.001, 0.0, 10.0),
            {
                "timestamp_ns": 1_600_000_000,
                "latitude_deg": 0.001,
                "longitude_deg": 0.0,
                "gnss_speed_mps": 0.0,
                "ax": 0.1,
                "ay": 0.0,
                "az": 9.8,
            },
            _fix(2_200_000_000, 0.002, 0.0, 10.0),
            _fix(3_200_000_000, 0.002, 0.002, 10.0),
        ]
        row = attribute_interval(
            "S-synth:mid",
            records,
            1_000_000_000,
            3_200_000_001,
            candidate_row={
                "metrics": {
                    "endpoint_error_m": 80.0,
                    "truth_path_length_m": 200.0,
                    "along_track_error_m": 10.0,
                    "cross_track_error_m": 70.0,
                },
                "extras": {"speed_mae_mps": 0.4, "heading_mae_rad": 0.8},
            },
        )
        self.assertEqual(row["label"], "DIAGNOSTIC_ONLY")
        self.assertGreaterEqual(row["stop_samples_speed_lt_0_4"], 1)
        self.assertIn("stop_restart", row["plausibly_closes_gap"])
        self.assertGreaterEqual(row["heading_turns_ge_30deg"], 1)
        self.assertEqual(row["road_ambiguity"]["osm_topology"], "unsupported")
        self.assertIn("heading_or_turn", row["plausibly_closes_gap"])
        self.assertIn("speed_mae_already_small", row["would_not_close_gap"])

    def test_archive_mismatch_is_reported_not_replaced(self) -> None:
        payload = {
            "summary": {
                "drift_ratio_p50": 0.99,
                "drift_ratio_p95": 2.0,
                "drift_ratio_worst": 3.0,
            },
            "per_interval": [
                {"metrics": {"endpoint_error_m": 99.0, "truth_path_length_m": 100.0}}
            ],
        }
        report = compare_to_archived(payload)
        self.assertFalse(report["matches_archive"])
        self.assertIn("drift_ratio_p50", report["mismatches"])
        self.assertEqual(report["archived"]["drift_ratio_p50"], 0.48834485395037297)
        self.assertIn("not replaced", report["note"])


if __name__ == "__main__":
    unittest.main()
