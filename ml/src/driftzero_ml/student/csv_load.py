"""Load a local IMU CSV without assuming IO-VNBD column names."""

from __future__ import annotations

import csv
from collections.abc import Mapping
from pathlib import Path

_AX = ("ax", "acc_x", "accel_x", "acceleration_x", "a_x")
_AY = ("ay", "acc_y", "accel_y", "acceleration_y", "a_y")
_AZ = ("az", "acc_z", "accel_z", "acceleration_z", "a_z")
_GX = ("gx", "gyro_x", "wx", "omega_x")
_GY = ("gy", "gyro_y", "wy", "omega_y")
_GZ = ("gz", "gyro_z", "wz", "omega_z")
_T = ("timestamp_ns", "t_ns", "time_ns")
_SPEED = ("speed_mps", "speed", "velocity_mps")
_STOPPED = ("stopped", "is_stopped", "idle")
_TRIP = ("trip_id", "trip", "sequence_id")


def load_imu_csv(path: Path) -> list[dict]:
    """Inspect headers, then map known IMU aliases. GNSS columns are dropped."""

    with path.open(newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ValueError(f"{path} has no header row")
        fields = list(reader.fieldnames)
        ax = _match(fields, _AX)
        ay = _match(fields, _AY)
        az = _match(fields, _AZ)
        if ax is None or ay is None or az is None:
            raise ValueError(
                f"{path} is missing accelerometer columns. Observed: {', '.join(fields)}"
            )
        gx = _match(fields, _GX)
        gy = _match(fields, _GY)
        gz = _match(fields, _GZ)
        stamp = _match(fields, _T)
        speed = _match(fields, _SPEED)
        stopped = _match(fields, _STOPPED)
        trip = _match(fields, _TRIP)
        rows: list[dict] = []
        for index, raw in enumerate(reader):
            item: dict = {
                "ax": float(raw[ax]),
                "ay": float(raw[ay]),
                "az": float(raw[az]),
                "timestamp_ns": _timestamp(raw, stamp, index),
                "trip_id": raw[trip] if trip else path.stem,
            }
            if gx:
                item["gx"] = float(raw[gx])
            if gy:
                item["gy"] = float(raw[gy])
            if gz:
                item["gz"] = float(raw[gz])
            if speed and raw.get(speed) not in (None, ""):
                item["speed_mps"] = float(raw[speed])
            if stopped and raw.get(stopped) not in (None, ""):
                item["stopped"] = float(raw[stopped])
            rows.append(item)
    if not rows:
        raise ValueError(f"{path} has no data rows")
    return rows


def _match(fields: list[str], aliases: tuple[str, ...]) -> str | None:
    lower = {name.lower(): name for name in fields}
    for alias in aliases:
        if alias in lower:
            return lower[alias]
    return None


def _timestamp(raw: Mapping[str, str], column: str | None, index: int) -> int:
    if column is None or raw.get(column) in (None, ""):
        return index * 20_000_000
    value = raw[column]
    if "." in value:
        raise ValueError("timestamp_ns must be an integer nanosecond count")
    stamp = int(value)
    if stamp < 0:
        raise ValueError("timestamp_ns must be non-negative")
    return stamp
