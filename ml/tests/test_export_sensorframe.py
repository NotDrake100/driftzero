"""Synthetic SensorFrame export checks. No raw IO-VNBD rows."""

from __future__ import annotations

import itertools
import math
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from driftzero_ml.contracts import validate_sensor_frame
from driftzero_ml.datasets.io_vnbd import SmartphoneRow
from driftzero_ml.export_sensorframe import (
    FLAG_HEADING_PICK_WEAK,
    FLAG_HEADING_VERTICAL,
    FLAG_HOLD_LAST,
    FLAG_REWIND,
    MAX_INTEGRATE_S,
    export_sensor_frames,
    heading_gyro_from_rows,
    hold_last_imu_upsample,
    imu_gap_count,
    write_sensorframe_jsonl,
    write_truth_jsonl,
)
from driftzero_ml.gnss_truth import SPEED_UNIT_MPS
from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M


def _row(
    stamp_ns: int,
    *,
    ax: float = 0.1,
    ay: float = 0.2,
    az: float = 9.8,
    gyro: tuple[float | None, float | None, float | None] = (0.01, -0.02, 0.03),
    lat: float | None = 52.0,
    lon: float | None = -1.7,
    speed: float | None = 10.0,
    accuracy: float | None = 4.0,
    grav: tuple[float, float, float] = (0.0, 0.0, 9.8),
    flags: tuple[str, ...] = (),
    trip_id: str = "S-synth",
) -> SmartphoneRow:
    return SmartphoneRow(
        trip_id=trip_id,
        timestamp_ns=stamp_ns,
        ax=ax,
        ay=ay,
        az=az,
        gyro_yaw=gyro[0],
        gyro_pitch=gyro[1],
        gyro_roll=gyro[2],
        latitude_deg=lat,
        longitude_deg=lon,
        speed_kmh=speed,
        altitude_m=80.0,
        accuracy_m=accuracy,
        gravity_x=grav[0],
        gravity_y=grav[1],
        gravity_z=grav[2],
        gps_orientation_deg=0.0,
        speed_unit=SPEED_UNIT_MPS,
        quality_flags=flags,
    )


def _period_rows(count: int, period_ns: int = 100_000_000) -> list[SmartphoneRow]:
    rows = []
    lat = 52.0
    for index in range(count):
        # Unique fix every fifth sample. Other rows repeat the last position.
        if index % 5 == 0:
            lat = 52.0 + index * 0.00002
        rows.append(
            _row(
                index * period_ns,
                ax=0.1,
                ay=9.8,
                az=0.2,
                lat=lat,
                lon=-1.7,
                grav=(0.0, 9.7, 0.2),
            )
        )
    return rows


class ExportSensorFrameTests(unittest.TestCase):
    def test_unavailable_gyro_does_not_drop_valid_gnss(self):
        rows = _period_rows(20)
        with patch("driftzero_ml.export_sensorframe._heading_rate_gyro", return_value=(None, [])):
            _, frames = export_sensor_frames(rows, mask_start_ns=1_000_000_000)
        self.assertFalse(any(row["kind"] == "gyroscope" for row in frames))
        self.assertEqual(sum(row["kind"] == "gnss_fix" for row in frames), 4)


    def test_table_rate_imu_unique_fix_gnss_and_alignment(self) -> None:
        rows = _period_rows(16)
        header, frames = export_sensor_frames(rows)
        self.assertEqual(header["clock_domain"], "dataset_declared")
        self.assertEqual(header["frame"], "unspecified")
        self.assertAlmostEqual(header["declared_rate_hz"], 10.0, places=5)
        for frame in frames:
            validate_sensor_frame(frame)
        accel = [row for row in frames if row["kind"] == "accelerometer"]
        gyro = [row for row in frames if row["kind"] == "gyroscope"]
        gnss = [row for row in frames if row["kind"] == "gnss_fix"]
        self.assertEqual(len(accel), 16)
        self.assertEqual(len(gyro), 16)
        self.assertEqual(len(gnss), 4)
        self.assertEqual(accel[0]["payload"]["unit"], "m/s^2")
        self.assertEqual(gyro[0]["payload"]["unit"], "rad/s")
        self.assertAlmostEqual(gyro[0]["payload"]["x"], 0.0, places=6)
        self.assertAlmostEqual(gyro[0]["payload"]["y"], 0.0, places=6)
        self.assertGreater(accel[0]["payload"]["z"], 9.0)
        self.assertLess(abs(accel[0]["payload"]["x"]) + abs(accel[0]["payload"]["y"]), 2.0)
        self.assertIn("speed_mps", gnss[0]["payload"])
        self.assertAlmostEqual(gnss[0]["payload"]["speed_mps"], 10.0)
        self.assertEqual({int(row["timestamp_ns"]) for row in gnss}, {0, 500_000_000, 1_000_000_000, 1_500_000_000})

    def test_blackout_and_future_gravity_cannot_change_calibration(self) -> None:
        rows = _period_rows(32)
        start = 1_600_000_000
        changed = [
            replace(row, gravity_x=9.8, gravity_y=0.0, gravity_z=0.0)
            if row.timestamp_ns >= start else row
            for row in rows
        ]
        header, original = export_sensor_frames(rows, mask_start_ns=start)
        other_header, other = export_sensor_frames(changed, mask_start_ns=start)
        self.assertEqual(original, other)
        self.assertEqual(header["gravity_calibration"], other_header["gravity_calibration"])
        self.assertEqual(header["gravity_calibration"]["source"], "pre_mask")
        self.assertEqual(header["gravity_calibration"]["sample_count"], 16)
        self.assertEqual(header["gyro_convention"], "right_handed_up_v3")

    def test_missing_gyro_and_speed_are_omitted_not_zeroed(self) -> None:
        rows = [
            _row(0, gyro=(None, None, None), speed=None, flags=("gyro_incomplete", "gnss_speed_missing")),
            _row(100_000_000, gyro=(None, None, None), lat=52.0001, speed=None, flags=("gyro_incomplete", "gnss_speed_missing")),
        ]
        # Need gravity samples for alignment. Repeat gravity-only extras.
        extra = [
            _row(200_000_000 + index * 100_000_000, lat=None, lon=None, gyro=(None, None, None), speed=None)
            for index in range(8)
        ]
        _, frames = export_sensor_frames(rows + extra)
        self.assertFalse(any(row["kind"] == "gyroscope" for row in frames))
        gnss = [row for row in frames if row["kind"] == "gnss_fix"]
        self.assertEqual(len(gnss), 2)
        self.assertNotIn("speed_mps", gnss[0]["payload"])
        self.assertIn("gnss_speed_missing", gnss[0]["quality"]["flags"])
        self.assertNotIn(0.0, [row["payload"].get("speed_mps") for row in gnss])

    def test_gap_over_max_integrate_is_not_filled(self) -> None:
        rows = _period_rows(8)
        last_ns = 7 * 100_000_000
        resume_ns = last_ns + 900_000_000
        later = [
            _row(
                resume_ns + index * 100_000_000,
                ax=0.1,
                ay=9.8,
                az=0.2,
                lat=52.01 + index * 0.00002,
                grav=(0.0, 9.7, 0.2),
            )
            for index in range(8)
        ]
        _, frames = export_sensor_frames(rows + later)
        accel_times = [int(row["timestamp_ns"]) for row in frames if row["kind"] == "accelerometer"]
        self.assertEqual(len(accel_times), 16)
        jumped = [
            later - earlier
            for earlier, later in itertools.pairwise(accel_times)
            if later - earlier > int(MAX_INTEGRATE_S * 1_000_000_000)
        ]
        self.assertEqual(len(jumped), 1)
        self.assertEqual(jumped[0], 900_000_000)
        self.assertEqual(imu_gap_count(frames), 1)
        flagged = [
            row
            for row in frames
            if row["kind"] == "accelerometer" and "imu_gap" in row["quality"]["flags"]
        ]
        self.assertEqual(len(flagged), 1)
        self.assertEqual(int(flagged[0]["timestamp_ns"]), resume_ns)
        interior = [stamp for stamp in accel_times if last_ns < stamp < resume_ns]
        self.assertEqual(interior, [])

    def test_accuracy_missing_drops_gnss_instead_of_zero_fill(self) -> None:
        rows = [_row(index * 100_000_000, accuracy=None, lat=52.0 + index * 0.0001) for index in range(10)]
        _, frames = export_sensor_frames(rows)
        self.assertFalse(any(row["kind"] == "gnss_fix" for row in frames))
        self.assertTrue(any(row["kind"] == "accelerometer" for row in frames))

    def test_hold_last_upsample_skips_filter_gaps(self) -> None:
        rows = [
            _row(0, lat=52.0),
            _row(100_000_000, lat=52.0001),
            _row(100_000_000 + 900_000_000, lat=52.0002),
        ]
        extra = [_row(2_200_000_000 + index * 100_000_000, lat=None, lon=None) for index in range(8)]
        header, frames = export_sensor_frames(rows + extra)
        held_header, held = hold_last_imu_upsample(header, frames, target_hz=100.0)
        self.assertAlmostEqual(held_header["declared_rate_hz"], 100.0)
        accel_times = [int(row["timestamp_ns"]) for row in held if row["kind"] == "accelerometer"]
        self.assertIn(10_000_000, accel_times)
        self.assertIn(90_000_000, accel_times)
        self.assertNotIn(200_000_000, accel_times)
        self.assertTrue(any("hold_last_imu" in row["quality"]["flags"] for row in held))

    def test_exported_left_and_right_turns_integrate_to_geometric_course(self):
        for course_rate in (-0.10, 0.10):
            rows = []
            speed = 10.0
            for i in range(200):
                t = float(i // 10)
                east = speed * (1.0 - math.cos(course_rate * t)) / course_rate
                north = speed * math.sin(course_rate * t) / course_rate
                lat = math.degrees(north / EARTH_MEAN_RADIUS_M)
                lon = math.degrees(east / EARTH_MEAN_RADIUS_M)
                rows.append(_row(i * 100_000_000, lat=lat, lon=lon,
                    gyro=(0.0, course_rate, 0.0), grav=(0.0, 0.0, 9.8)))
            header, frames = export_sensor_frames(rows, mask_start_ns=10_000_000_000)
            self.assertEqual(header["heading_gyro"]["axis"], "pitch")
            masked_rates = [f["payload"]["z"] for f in frames
                if f["kind"] == "gyroscope" and f["timestamp_ns"] >= 10_000_000_000]
            # Compass course from the analytical circle advances by rate * duration.
            self.assertAlmostEqual(-sum(masked_rates) * 0.1, course_rate * 10.0, places=6)

    def test_heading_axis_gyro_is_on_z_only(self) -> None:
        from math import cos, radians, sin

        rows: list[SmartphoneRow] = []
        lat = 52.0
        lon = -1.7
        heading = 0.0
        rate = 0.12
        for index in range(20):
            stamp = index * 1_000_000_000
            for step in range(10):
                rows.append(
                    _row(
                        stamp + step * 100_000_000,
                        ax=0.1,
                        ay=0.2,
                        az=9.8,
                        gyro=(0.01, rate, -0.02),
                        lat=lat,
                        lon=lon,
                        grav=(0.0, 0.0, 9.8),
                    )
                )
            north = 12.0 * cos(heading)
            east = 12.0 * sin(heading)
            lat += (north / EARTH_MEAN_RADIUS_M) * (180.0 / 3.141592653589793)
            lon += (east / (EARTH_MEAN_RADIUS_M * cos(radians(lat)))) * (180.0 / 3.141592653589793)
            heading += rate
        _, frames = export_sensor_frames(rows)
        gyro = [row for row in frames if row["kind"] == "gyroscope"]
        self.assertGreater(len(gyro), 20)
        self.assertAlmostEqual(gyro[0]["payload"]["x"], 0.0, places=6)
        self.assertAlmostEqual(gyro[0]["payload"]["y"], 0.0, places=6)
        # This synthetic course increases clockwise; right-handed +up rate is negative.
        self.assertLess(gyro[0]["payload"]["z"], 0.0)
        self.assertIn("gyro_heading_axis_pitch", gyro[0]["quality"]["flags"])

    def test_jsonl_round_trip_and_truth(self) -> None:
        rows = _period_rows(10)
        header, frames = export_sensor_frames(rows)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "frames.jsonl"
            truth = Path(folder) / "truth.jsonl"
            write_sensorframe_jsonl(path, header, frames)
            count = write_truth_jsonl(truth, rows)
            text = path.read_text().splitlines()
            self.assertIn("declared_rate_hz", text[0])
            self.assertNotIn("kind", text[0])
            self.assertGreaterEqual(count, 2)
            loaded = [__import__("json").loads(line) for line in text[1:]]
            self.assertEqual(len(loaded), len(frames))
            self.assertEqual(loaded[0]["timestamp_ns"], 0)

    def test_premask_heading_pick_does_not_use_blackout_turn(self) -> None:
        rows, mask_start_ns = _straight_then_blackout_turn()
        whole = heading_gyro_from_rows(rows)
        pre = heading_gyro_from_rows(rows, mask_start_ns=mask_start_ns)
        self.assertIsNotNone(whole.pick)
        assert whole.pick is not None
        self.assertEqual(whole.pick.axis, "pitch")
        self.assertGreater(abs(whole.pick.correlation), 0.25)
        self.assertIsNone(pre.pick)
        self.assertIn(pre.reason, {"few_hops", "no_heading_rate", "weak_corr"})
        header_whole, frames_whole = export_sensor_frames(rows)
        header_pre, frames_pre = export_sensor_frames(rows, mask_start_ns=mask_start_ns)
        self.assertEqual(header_whole["heading_gyro"]["axis"], "pitch")
        self.assertEqual(header_pre["heading_gyro"]["source"], "gravity_vertical_fallback")
        self.assertIsNone(header_pre["heading_gyro"]["axis"])
        whole_gyro = next(row for row in frames_whole if row["kind"] == "gyroscope")
        pre_gyro = next(row for row in frames_pre if row["kind"] == "gyroscope")
        self.assertIn("gyro_heading_axis_pitch", whole_gyro["quality"]["flags"])
        self.assertIn(FLAG_HEADING_PICK_WEAK, pre_gyro["quality"]["flags"])
        self.assertIn(FLAG_HEADING_VERTICAL, pre_gyro["quality"]["flags"])
        self.assertTrue(any("heading_gyro pick=" in note for note in header_pre["notes"]))
        self.assertNotEqual(
            whole_gyro["payload"]["z"],
            pre_gyro["payload"]["z"],
        )

    def test_rewind_suffix_is_flagged_in_export(self) -> None:
        prefix = [
            _row(2_000_000_000 + index * 100_000_000, lat=52.0 + index * 0.0001)
            for index in range(12)
        ]
        suffix = [
            _row(8_000_000 + index * 100_000_000, lat=53.0 + index * 0.0001)
            for index in range(10)
        ]
        header, frames = export_sensor_frames(prefix + suffix)
        self.assertIn("timestamp_rewind", header)
        self.assertEqual(header["timestamp_rewind"]["dropped_rows"], 10)
        self.assertEqual(header["timestamp_rewind"]["kept_rows"], 12)
        self.assertTrue(header["timestamp_rewind"]["suffix"])
        self.assertTrue(any("timestamp_rewind dropped 10" in note for note in header["notes"]))
        self.assertTrue(
            all(FLAG_REWIND in row["quality"]["flags"] for row in frames)
        )
        accel_times = [int(row["timestamp_ns"]) for row in frames if row["kind"] == "accelerometer"]
        self.assertEqual(len(accel_times), 12)
        self.assertLess(min(accel_times), 3_200_000_000)
        self.assertNotIn(8_000_000, accel_times)

    def test_hold_last_upsample_is_labeled_sensitivity(self) -> None:
        rows = _period_rows(10)
        header, frames = export_sensor_frames(rows)
        self.assertTrue(header["official_row"])
        self.assertFalse(header["sensitivity"])
        held_header, held = hold_last_imu_upsample(header, frames, target_hz=100.0)
        self.assertFalse(held_header["official_row"])
        self.assertTrue(held_header["sensitivity"])
        self.assertEqual(held_header["sensitivity_kind"], "hold_last_imu")
        self.assertAlmostEqual(held_header["hold_last_hz"], 100.0)
        self.assertTrue(any("sensitivity hold_last_imu" in note for note in held_header["notes"]))
        self.assertTrue(any(FLAG_HOLD_LAST in row["quality"]["flags"] for row in held))


def _straight_then_blackout_turn() -> tuple[list[SmartphoneRow], int]:
    """Straight pre-mask, 90 deg pitch-tracked turn inside the mask."""

    rows: list[SmartphoneRow] = []
    lat = 52.0
    lon = -1.7
    heading = 0.0
    turn_rate = 0.15
    straight_fixes = 16
    turn_fixes = 12
    for index in range(straight_fixes + turn_fixes):
        stamp = index * 1_000_000_000
        turning = index >= straight_fixes
        pitch = turn_rate if turning else 0.0
        for step in range(10):
            rows.append(
                _row(
                    stamp + step * 100_000_000,
                    ax=0.1,
                    ay=0.2,
                    az=9.8,
                    gyro=(0.01, pitch, -0.02),
                    lat=lat,
                    lon=lon,
                    grav=(0.0, 0.0, 9.8),
                )
            )
        north = 12.0 * math.cos(heading)
        east = 12.0 * math.sin(heading)
        lat += (north / EARTH_MEAN_RADIUS_M) * (180.0 / 3.141592653589793)
        lon += (east / (EARTH_MEAN_RADIUS_M * math.cos(math.radians(lat)))) * (180.0 / 3.141592653589793)
        if turning:
            heading += turn_rate
    return rows, straight_fixes * 1_000_000_000


if __name__ == "__main__":
    unittest.main()
