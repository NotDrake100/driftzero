"""Pune eval harness. Synthetic fixture is labeled. Empty input must refuse."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from driftzero_ml.eval_pune import (
    PuneEvalError,
    discover_trips,
    main,
    measured_rate_hz,
)


def _state(sequence: int, stamp: int, lat: float, lon: float) -> dict:
    return {
        "schema_version": "1.0.0",
        "sequence": sequence,
        "timestamp_ns": stamp,
        "mode": "DEAD_RECKONING",
        "position": {"latitude_deg": lat, "longitude_deg": lon},
        "motion": {"speed_mps": 10.0, "heading_rad": 1.5707963267948966},
        "uncertainty": {"horizontal_95_m": 12.0, "heading_95_rad": 0.2, "is_calibrated": True},
        "gnss_health": {"score": 0.2, "last_trusted_fix_age_s": 5.0, "risk_flags": ["blackout"]},
        "map_match": {"status": "NO_MAP", "confidence": 0.0},
        "health": {
            "sensor_ok": True,
            "model_ok": True,
            "filter_ok": True,
            "map_ok": False,
            "flags": ["dead_reckoning", "gps_held"],
        },
        "provenance": {
            "core_version": "test",
            "config_hash": "a" * 64,
        },
    }


def _gnss(sequence: int, stamp: int, lat: float, lon: float) -> dict:
    return {
        "schema_version": "1.0.0",
        "source_id": "synthetic",
        "sequence": sequence,
        "timestamp_ns": stamp,
        "clock_domain": "android_elapsed_realtime",
        "kind": "gnss_fix",
        "quality": {
            "available": True,
            "accuracy_code": 2,
            "flags": ["gnss_held"],
        },
        "payload": {
            "latitude_deg": lat,
            "longitude_deg": lon,
            "horizontal_accuracy_m": 5.0,
            "provider_time_ms": stamp // 1_000_000,
            "speed_mps": 10.0,
            "bearing_rad": 1.5707963267948966,
        },
    }


def _accel(sequence: int, stamp: int) -> dict:
    return {
        "schema_version": "1.0.0",
        "source_id": "synthetic",
        "sequence": sequence,
        "timestamp_ns": stamp,
        "clock_domain": "android_elapsed_realtime",
        "kind": "accelerometer",
        "quality": {"available": True, "accuracy_code": 2, "flags": []},
        "payload": {
            "x": 0.0,
            "y": 0.0,
            "z": 9.8,
            "unit": "m/s^2",
            "frame": "android_device",
        },
    }


def _write_synthetic_trip(root: Path) -> Path:
    """Labeled synthetic 50 m east path. Estimate ends 5 m north. Ratio 0.10."""

    trip = root / "synthetic-d50"
    trip.mkdir(parents=True)
    deg_m = 111_195.08
    lon_step = 10.0 / deg_m
    lat_err = 5.0 / deg_m
    sensors: list[dict] = [
        {
            "declared_rate_hz": 100.0,
            "requested_sensor_delay": "SENSOR_DELAY_FASTEST",
            "clock_domain": "android_elapsed_realtime",
            "frame": "android_device",
            "source_id": "synthetic",
        }
    ]
    states: list[dict] = []
    seq = 0
    last_ns = 0
    for index in range(6):
        stamp = index * 1_000_000_000
        last_ns = stamp
        lon = index * lon_step
        sensors.append(_accel(seq, stamp))
        seq += 1
        if index > 0:
            sensors.append(_accel(seq, stamp + 10_000_000))
            seq += 1
        sensors.append(_gnss(seq, stamp, 0.0, lon))
        seq += 1
        est_lat = lat_err if index == 5 else 0.0
        states.append(_state(index, stamp, est_lat, lon))
    (trip / "sensors.jsonl").write_text("\n".join(json.dumps(row) for row in sensors) + "\n")
    (trip / "states.jsonl").write_text("\n".join(json.dumps(row) for row in states) + "\n")
    (trip / "manifest.json").write_text(
        json.dumps(
            {
                "id": "synthetic-d50",
                "dataset": "synthetic",
                "hold_intervals": [{"start_ns": 0, "end_ns": last_ns}],
                "requested_sensor_delay": "SENSOR_DELAY_FASTEST",
            }
        )
        + "\n"
    )
    return trip


class EvalPuneTests(unittest.TestCase):
    def test_measured_rate_from_ten_ms_deltas(self) -> None:
        stamps = [i * 10_000_000 for i in range(11)]
        rate = measured_rate_hz(stamps)
        self.assertEqual(rate["sample_count"], 11)
        self.assertAlmostEqual(float(rate["median_hz"] or 0.0), 100.0, places=6)

    def test_empty_logs_refuse_and_write_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logs = Path(tmp) / "empty"
            logs.mkdir()
            out = Path(tmp) / "out"
            with self.assertRaises(PuneEvalError):
                discover_trips(logs)
            code = main(["--logs", str(logs), "--out", str(out), "--synthetic"])
            self.assertEqual(code, 2)
            self.assertFalse((out / "metrics_summary.json").exists())
            self.assertFalse((out / "README.md").exists())

    def test_synthetic_fifty_metre_ratio_is_one_tenth(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trip = _write_synthetic_trip(root / "logs")
            out = root / "scored"
            code = main(["--logs", str(trip), "--out", str(out), "--synthetic"])
            self.assertEqual(code, 0)
            summary = json.loads((out / "metrics_summary.json").read_text())
            self.assertEqual(summary["dataset"], "synthetic")
            self.assertTrue(summary["not_io_vnbd"])
            hold = next(row for row in summary["intervals"] if row["interval_id"] == "hold_1")
            self.assertAlmostEqual(hold["truth_path_length_m"], 50.0, places=1)
            self.assertAlmostEqual(hold["endpoint_error_m"], 5.0, places=1)
            self.assertAlmostEqual(hold["drift_ratio"] or 0.0, 0.10, places=2)
            d50 = next(row for row in summary["intervals"] if row["interval_id"] == "hold_1_d50")
            self.assertAlmostEqual(d50["drift_ratio"] or 0.0, 0.10, places=2)
            readme = (out / "README.md").read_text()
            self.assertIn("not IO-VNBD", readme)
            self.assertIn("synthetic", readme)

    def test_refuses_synthetic_into_official_pune_dir(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            official = repo / "results" / "pune_v1"
            official.mkdir(parents=True)
            logs = repo / "logs"
            logs.mkdir()
            code = main(
                [
                    "--logs",
                    str(logs),
                    "--out",
                    str(official),
                    "--repo",
                    str(repo),
                    "--synthetic",
                ]
            )
            self.assertEqual(code, 2)
            self.assertFalse((official / "metrics_summary.json").exists())


if __name__ == "__main__":
    unittest.main()
