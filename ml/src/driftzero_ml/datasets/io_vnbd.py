"""IO-VNBD smartphone tables. Official SIH26168 reference dataset."""

from __future__ import annotations

import csv
import math
from dataclasses import dataclass
from pathlib import Path

from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M

from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.datasets.lfs import FETCH_DOC, is_lfs_pointer, require_real_file
from driftzero_ml.io_vnbd.discover import inspect_delimited_table, read_table_text
from driftzero_ml.io_vnbd.locate import PREFERRED_RELATIVE, SOURCE_URL, require_local_root

FETCH_HINT = (
    f"Clone {SOURCE_URL} into data/raw/io_vnbd/ and pull Git LFS, "
    f"or curl one S-*.csv from media.githubusercontent.com. See {FETCH_DOC}."
)

# Paper Table A6 / inspected S-Vta9 headers, matched after lowercasing.
_ACCEL = ("accelerometer x", "accelerometer y", "accelerometer z")
_GYRO = ("gyroscope yaw", "gyroscope pitch", "gyroscope roll")
_LAT = ("gps latitude",)
_LON = ("gps longitude",)
_SPEED = ("gps speed",)
_TIME_MS = ("time since start",)


@dataclass(frozen=True)
class SmartphoneRow:
    """One inspected smartphone sample. Gyro labels stay as published."""

    trip_id: str
    timestamp_ns: int
    ax: float
    ay: float
    az: float
    gyro_yaw: float | None
    gyro_pitch: float | None
    gyro_roll: float | None
    latitude_deg: float | None
    longitude_deg: float | None
    speed_kmh: float | None


def find_smartphone_csvs(root: Path) -> tuple[Path, ...]:
    """S-*.csv under the local checkout, including LFS pointers."""

    resolved = require_local_root(root)
    found = tuple(sorted(p for p in resolved.rglob("S-*.csv") if p.is_file()))
    if not found:
        raise DatasetMissing(
            f"No S-*.csv under {resolved}. Expected smartphone files under "
            f"'{PREFERRED_RELATIVE}'. {FETCH_HINT}"
        )
    return found


def require_iovnbd_tables(root: Path) -> tuple[Path, ...]:
    """Return smartphone CSVs that are real payloads, not LFS pointers."""

    found = find_smartphone_csvs(root)
    real = tuple(path for path in found if not is_lfs_pointer(path))
    if not real:
        raise DatasetLfsMissing(
            f"IO-VNBD at {root.expanduser().resolve()} has {len(found)} S-*.csv "
            f"Git LFS pointer(s) and no pulled tables. {FETCH_HINT}"
        )
    return real


def screening_smartphone_tables(root: Path) -> tuple[Path, ...]:
    """Unique-stem phone tables. Prefer synchronised categorised S-*.csv.

    The official tree repeats the same S-*.csv stem in unsynchronised and
    uncategorised folders. Vehicle V-*.csv files are never returned.
    """

    real = require_iovnbd_tables(root)
    preferred = tuple(
        path
        for path in real
        if PREFERRED_RELATIVE in path.parts and "Categorised IOVNB Dataset" in path.parts
    )
    pool = preferred if len(preferred) >= 4 else real
    unique: dict[str, Path] = {}
    for path in pool:
        unique.setdefault(path.stem, path)
    if len(unique) < 4:
        for path in real:
            unique.setdefault(path.stem, path)
    return tuple(sorted(unique.values(), key=lambda path: str(path)))


def load_smartphone_csv(path: Path) -> list[SmartphoneRow]:
    """Map inspected headers to IMU/GNSS fields. Fail if accel columns are absent."""

    resolved = require_real_file(path, dataset="IO-VNBD", fetch_hint=FETCH_HINT)
    report = inspect_delimited_table(resolved)
    fields = list(report.column_names)
    ax = _match(fields, _ACCEL[0])
    ay = _match(fields, _ACCEL[1])
    az = _match(fields, _ACCEL[2])
    if ax is None or ay is None or az is None:
        raise ValueError(
            f"{resolved} is missing accelerometer X/Y/Z after inspection. "
            f"Observed: {', '.join(fields)}"
        )
    yaw = _match(fields, _GYRO[0])
    pitch = _match(fields, _GYRO[1])
    roll = _match(fields, _GYRO[2])
    lat = _match(fields, _LAT[0])
    lon = _match(fields, _LON[0])
    speed = _match(fields, _SPEED[0])
    time_ms = _match(fields, _TIME_MS[0])
    text = read_table_text(resolved)
    reader = csv.DictReader(text.splitlines())
    rows: list[SmartphoneRow] = []
    for index, raw in enumerate(reader):
        raw = {((key or "").strip()): (value or "") for key, value in raw.items()}
        stamp_ms = _optional_float(raw, time_ms)
        timestamp_ns = int(stamp_ms * 1_000_000) if stamp_ms is not None else index * 100_000_000
        rows.append(
            SmartphoneRow(
                trip_id=resolved.stem,
                timestamp_ns=timestamp_ns,
                ax=float(raw[ax]),
                ay=float(raw[ay]),
                az=float(raw[az]),
                gyro_yaw=_optional_float(raw, yaw),
                gyro_pitch=_optional_float(raw, pitch),
                gyro_roll=_optional_float(raw, roll),
                latitude_deg=_optional_float(raw, lat),
                longitude_deg=_optional_float(raw, lon),
                speed_kmh=_optional_float(raw, speed),
            )
        )
    if not rows:
        raise ValueError(f"{resolved} has no data rows")
    return rows


def keep_nondecreasing_rows(rows: list[SmartphoneRow]) -> list[SmartphoneRow]:
    """Drop samples whose TIME SINCE START jumps backward. Do not reorder."""

    kept: list[SmartphoneRow] = []
    last_ns: int | None = None
    for row in rows:
        if last_ns is not None and row.timestamp_ns < last_ns:
            continue
        kept.append(row)
        last_ns = row.timestamp_ns
    return kept


def to_imu_records(rows: list[SmartphoneRow]) -> list[dict]:
    """IMU-only dicts for the student trainer. GNSS stays out of features."""

    records: list[dict] = []
    for row in keep_nondecreasing_rows(rows):
        item = {
            "trip_id": row.trip_id,
            "timestamp_ns": row.timestamp_ns,
            "ax": row.ax,
            "ay": row.ay,
            "az": row.az,
            "speed_mps": None if row.speed_kmh is None else row.speed_kmh / 3.6,
            "stopped": 0.0 if row.speed_kmh is None else float(row.speed_kmh < 1.0),
        }
        # Publication order Yaw, Pitch, Roll. Not a verified Android axis map.
        if row.gyro_yaw is not None:
            item["gx"] = row.gyro_yaw
        if row.gyro_pitch is not None:
            item["gy"] = row.gyro_pitch
        if row.gyro_roll is not None:
            item["gz"] = row.gyro_roll
        records.append(item)
    return records


def latlon_to_enu_m(origin: tuple[float, float], latitude_deg: float, longitude_deg: float) -> tuple[float, float]:
    """Local east/north metres from a trip origin. Phone GNSS, not RTK."""

    lat0, lon0 = origin
    dlat = math.radians(latitude_deg - lat0)
    dlon = math.radians(longitude_deg - lon0)
    east = EARTH_MEAN_RADIUS_M * dlon * math.cos(math.radians(lat0))
    north = EARTH_MEAN_RADIUS_M * dlat
    return east, north


def smartphone_to_odometry(rows: list[SmartphoneRow]) -> list[dict]:
    """IMU plus ENU pose from phone GNSS. GNSS keys are not stored on the record."""

    origin: tuple[float, float] | None = None
    heading = 0.0
    last_xy: tuple[float, float] | None = None
    records: list[dict] = []
    for row in keep_nondecreasing_rows(rows):
        if row.latitude_deg is None or row.longitude_deg is None:
            continue
        if origin is None:
            origin = (row.latitude_deg, row.longitude_deg)
        east, north = latlon_to_enu_m(origin, row.latitude_deg, row.longitude_deg)
        if last_xy is not None:
            dx = east - last_xy[0]
            dy = north - last_xy[1]
            if dx * dx + dy * dy > 0.25:
                heading = math.atan2(dy, dx)
        last_xy = (east, north)
        speed = 0.0 if row.speed_kmh is None else row.speed_kmh / 3.6
        item = {
            "trip_id": row.trip_id,
            "timestamp_ns": row.timestamp_ns,
            "ax": row.ax,
            "ay": row.ay,
            "az": row.az,
            "pose_x_m": east,
            "pose_y_m": north,
            "pose_z_m": 0.0,
            "heading_rad": heading,
            "speed_mps": speed,
            "stopped": 1.0 if speed < 0.3 else 0.0,
        }
        if row.gyro_yaw is not None:
            item["gx"] = row.gyro_yaw
        if row.gyro_pitch is not None:
            item["gy"] = row.gyro_pitch
        if row.gyro_roll is not None:
            item["gz"] = row.gyro_roll
        records.append(item)
    if len(records) < 2:
        raise ValueError(f"{rows[0].trip_id if rows else 'empty'} has no GNSS-labelled pose rows")
    return records


def load_odometry_trips(root: Path) -> dict[str, list[dict]]:
    """Load screening smartphone tables that have usable GNSS pose."""

    trips: dict[str, list[dict]] = {}
    for path in screening_smartphone_tables(root):
        try:
            records = smartphone_to_odometry(load_smartphone_csv(path))
        except (ValueError, DatasetMissing):
            continue
        if records:
            trips[path.stem] = records
    if not trips:
        raise DatasetMissing(f"no IO-VNBD smartphone tables with GNSS pose under {root}")
    return trips


def _match(fields: list[str], token: str) -> str | None:
    needle = token.lower()
    for name in fields:
        if needle in name.lower():
            return name
    return None


def _optional_float(raw: dict[str, str], column: str | None) -> float | None:
    if column is None:
        return None
    value = raw.get(column)
    if value is None or value.strip() == "":
        return None
    return float(value)
