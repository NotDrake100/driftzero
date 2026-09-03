import json
import tempfile
import unittest
from pathlib import Path

from driftzero_ml.eval_halo_picp import halo_picp, load_states_jsonl, load_truth_jsonl


def _state(sequence: int, stamp: int, lat: float, lon: float, radius: float) -> dict:
    return {
        "schema_version": "1.0.0",
        "sequence": sequence,
        "timestamp_ns": stamp,
        "mode": "DEAD_RECKONING",
        "position": {"latitude_deg": lat, "longitude_deg": lon},
        "motion": {"speed_mps": 8.0, "heading_rad": 0.0},
        "uncertainty": {
            "horizontal_95_m": radius,
            "heading_95_rad": 0.2,
            "is_calibrated": True,
        },
        "gnss_health": {
            "score": 0.2,
            "last_trusted_fix_age_s": 5.0,
            "risk_flags": ["blackout"],
        },
        "map_match": {"status": "NO_MAP", "confidence": 0.0},
        "health": {
            "sensor_ok": True,
            "model_ok": True,
            "filter_ok": True,
            "map_ok": False,
            "flags": ["dead_reckoning"],
        },
        "provenance": {"core_version": "test", "config_hash": "a" * 64},
    }


class HaloPicpTests(unittest.TestCase):
    def test_synthetic_states_cover_when_error_inside_halo(self) -> None:
        truth = []
        inside = []
        outside = []
        for index in range(6):
            stamp = index * 1_000_000_000
            lat = 52.0 + index * 0.0002
            truth.append({"timestamp_ns": stamp, "latitude_deg": lat, "longitude_deg": -1.7})
            inside.append(_state(index, stamp, lat, -1.7, 12.0))
            outside.append(_state(index, stamp, lat + 0.001, -1.7, 5.0))
        covered = halo_picp(inside, truth)
        missed = halo_picp(outside, truth)
        self.assertEqual(covered.n, 6)
        self.assertEqual(covered.picp95, 1.0)
        self.assertEqual(missed.picp95, 0.0)
        self.assertGreater(missed.median_error_m, 5.0)
        with tempfile.TemporaryDirectory() as folder:
            states_path = Path(folder) / "states.jsonl"
            truth_path = Path(folder) / "truth.jsonl"
            states_path.write_text("\n".join(json.dumps(row) for row in inside) + "\n")
            truth_path.write_text("\n".join(json.dumps(row) for row in truth) + "\n")
            report = halo_picp(load_states_jsonl(states_path), load_truth_jsonl(truth_path))
            self.assertEqual(report.picp95, 1.0)


if __name__ == "__main__":
    unittest.main()
