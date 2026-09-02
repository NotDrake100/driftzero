import json
import tempfile
import unittest
from pathlib import Path

from driftzero_ml.contracts import ContractError
from driftzero_ml.eval_navstate import load_navigation_state_jsonl, score_states_against_truth


def _state(sequence: int, stamp: int, lat: float, lon: float) -> dict:
    return {
        "schema_version": "1.0.0",
        "sequence": sequence,
        "timestamp_ns": stamp,
        "mode": "DEAD_RECKONING",
        "position": {"latitude_deg": lat, "longitude_deg": lon},
        "motion": {"speed_mps": 8.0, "heading_rad": 0.0},
        "uncertainty": {"horizontal_95_m": 12.0, "heading_95_rad": 0.2, "is_calibrated": True},
        "gnss_health": {"score": 0.2, "last_trusted_fix_age_s": 5.0, "risk_flags": ["blackout"]},
        "map_match": {"status": "NO_MAP", "confidence": 0.0},
        "health": {
            "sensor_ok": True,
            "model_ok": True,
            "filter_ok": True,
            "map_ok": False,
            "flags": ["dead_reckoning"],
        },
        "provenance": {
            "core_version": "test",
            "config_hash": "a" * 64,
        },
    }


class EvalNavstateTests(unittest.TestCase):
    def test_jsonl_scores_fresh_fix_epochs(self) -> None:
        truth = []
        states = []
        for index in range(12):
            stamp = index * 1_000_000_000
            lat = 52.0 + index * 0.0002
            truth.append(
                {
                    "timestamp_ns": stamp,
                    "latitude_deg": lat,
                    "longitude_deg": -1.7,
                }
            )
            states.append(_state(index, stamp, lat, -1.7))
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "states.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in states) + "\n")
            loaded = load_navigation_state_jsonl(path)
        metrics = score_states_against_truth(loaded, truth, 2_000_000_000, 10_000_000_000)
        self.assertEqual(metrics.sample_count, 8)
        self.assertAlmostEqual(metrics.endpoint_error_m, 0.0, places=6)

    def test_rejects_unknown_field(self) -> None:
        row = _state(0, 0, 52.0, -1.7)
        row["extra"] = True
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "bad.jsonl"
            path.write_text(json.dumps(row) + "\n")
            with self.assertRaises(ContractError):
                load_navigation_state_jsonl(path)


if __name__ == "__main__":
    unittest.main()
