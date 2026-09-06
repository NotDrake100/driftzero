"""Synthetic IO-VNBD unit and quality-flag checks. No raw dataset rows."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from driftzero_ml.datasets.io_vnbd import (
    _MS_TO_NS,
    load_smartphone_csv,
    smartphone_to_odometry,
    to_imu_records,
)
from driftzero_ml.gnss_truth import SPEED_UNIT_KMH, SPEED_UNIT_MPS

_HEADER = (
    "GPS LATITUDE (degrees),GPS LONGITUDE (degrees),GPS ALTITUDE (m),"
    "GPS SPEED (Kmh),GPS ACCURACY (m),GPS SATELLITES IN RANGE,"
    "TIME SINCE START (ms),ACCELEROMETER X (m/s2),ACCELEROMETER Y (m/s2),"
    "ACCELEROMETER Z (m/s2),GRAVITY X (m/s2),GRAVITY Y (m/s2),GRAVITY Z (m/s2),"
    "GYROSCOPE Yaw (rad/s),GYROSCOPE Pitch (rad/s),GYROSCOPE Roll (rad/s)"
)


def _write(folder: str, name: str, rows: list[str]) -> Path:
    path = Path(folder) / name
    path.write_text(_HEADER + "\n" + "\n".join(rows) + "\n")
    return path


class IoVnbdUnitTests(unittest.TestCase):
    def test_ms_convert_and_missing_sensors_are_flagged(self) -> None:
        rows = [
            "52.0,-1.5,80.0,10.0,4.0,8 / 8,1000,0.1,0.2,9.8,0.0,0.0,9.8,0.01,-0.02,0.03",
            ",,, ,,,2000,0.1,0.2,9.8,0.0,0.0,9.8,,,",
        ]
        with tempfile.TemporaryDirectory() as folder:
            loaded = load_smartphone_csv(_write(folder, "S-unit.csv", rows))
        self.assertEqual(loaded[0].timestamp_ns, 1000 * _MS_TO_NS)
        self.assertEqual(loaded[0].speed_unit, SPEED_UNIT_MPS)
        self.assertIn("speed_unit_unverified", loaded[0].quality_flags)
        records = to_imu_records(loaded)
        self.assertAlmostEqual(records[0]["speed_mps"], 10.0)
        self.assertNotIn("gx", records[1])
        self.assertIn("gyro_incomplete", records[1]["quality_flags"])
        self.assertNotIn("speed_mps", records[1])
        odom = smartphone_to_odometry([loaded[0], loaded[0]])
        self.assertAlmostEqual(odom[0]["speed_mps"], 10.0)

    def test_unique_fix_ratio_accepts_metres_per_second(self) -> None:
        # 10 m north in 1 s. Column 10 matches m/s, not km/h.
        # Need 8 pairs. Repeat the 10 m/s step.
        lat = 52.0
        built = []
        for index in range(9):
            built.append(
                f"{lat:.7f},-1.5,80.0,10.0,4.0,8 / 8,{index * 1000},0.1,0.2,9.8,0.0,0.0,9.8,0.0,0.0,0.0"
            )
            lat += 0.0000899
        with tempfile.TemporaryDirectory() as folder:
            loaded = load_smartphone_csv(_write(folder, "S-mps.csv", built))
        self.assertEqual(loaded[0].speed_unit, SPEED_UNIT_MPS)
        self.assertIn("speed_unit_verified_mps", loaded[0].quality_flags)
        self.assertAlmostEqual(to_imu_records(loaded)[0]["speed_mps"], 10.0)

    def test_unique_fix_ratio_accepts_declared_kmh(self) -> None:
        lat = 52.0
        built = []
        for index in range(9):
            built.append(
                f"{lat:.7f},-1.5,80.0,36.0,4.0,8 / 8,{index * 1000},0.1,0.2,9.8,0.0,0.0,9.8,0.0,0.0,0.0"
            )
            lat += 0.0000899
        with tempfile.TemporaryDirectory() as folder:
            loaded = load_smartphone_csv(
                _write(folder, "S-kmh.csv", built),
                speed_unit=SPEED_UNIT_KMH,
            )
        self.assertEqual(loaded[0].speed_unit, SPEED_UNIT_KMH)
        self.assertAlmostEqual(to_imu_records(loaded)[0]["speed_mps"], 10.0)

    def test_unique_fix_ratio_rejects_wrong_declared_unit(self) -> None:
        lat = 52.0
        built = []
        for index in range(9):
            built.append(
                f"{lat:.7f},-1.5,80.0,36.0,4.0,8 / 8,{index * 1000},0.1,0.2,9.8,0.0,0.0,9.8,0.0,0.0,0.0"
            )
            lat += 0.0000899
        with tempfile.TemporaryDirectory() as folder:
            path = _write(folder, "S-bad.csv", built)
            with self.assertRaisesRegex(ValueError, "disagrees"):
                load_smartphone_csv(path, speed_unit=SPEED_UNIT_MPS)

    def test_missing_timestamp_is_flagged_not_invented_as_truth(self) -> None:
        header = (
            "TIME SINCE START (ms),ACCELEROMETER X (m/s2),ACCELEROMETER Y (m/s2),"
            "ACCELEROMETER Z (m/s2)"
        )
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "S-notime.csv"
            path.write_text(header + "\n,0.0,0.0,9.8\n")
            loaded = load_smartphone_csv(path)
        self.assertEqual(loaded[0].timestamp_source, "row_index_10hz_fallback")
        self.assertIn("timestamp_ms_missing", loaded[0].quality_flags)


if __name__ == "__main__":
    unittest.main()
