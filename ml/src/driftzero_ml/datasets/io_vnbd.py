"""IO-VNBD smartphone tables. Official SIH26168 reference dataset."""

from __future__ import annotations

import csv
import math
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Sequence

from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M
from driftzero_ml.gnss_truth import (
    SPEED_UNIT_MPS,
    column_speed_to_mps,
    infer_speed_unit,
)

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
_ALT = ("gps altitude",)
_SPEED = ("gps speed",)
_ACCURACY = ("gps accuracy",)
_TIME_MS = ("time since start",)
_SAT = ("gps satellites",)
_GRAV = ("gravity x", "gravity y", "gravity z")
_ORIENT = ("gps orientation",)
_MS_TO_NS = 1_000_000
# Header says Kmh. Unique-fix finite difference on the local checkout is ~1.0
# times the column, so the published numbers are metres per second.
_KMH_TO_MPS = 1.0 / 3.6
DECLARED_SPEED_UNIT = SPEED_UNIT_MPS


@dataclass(frozen=True)
class SmartphoneRow:
    """One inspected smartphone sample. Gyro labels stay as published.

    Extra GNSS and gravity fields are diagnostics. They are not student features.
    Missing sensors stay None and are listed in quality_flags. No silent zeros.
    """

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
    altitude_m: float | None = None
    accuracy_m: float | None = None
    satellites_raw: str | None = None
    gravity_x: float | None = None
    gravity_y: float | None = None
    gravity_z: float | None = None
    gps_orientation_deg: float | None = None
    speed_unit: str = DECLARED_SPEED_UNIT
    timestamp_source: str = "time_since_start_ms"
    quality_flags: tuple[str, ...] = ()


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


def load_smartphone_csv(
    path: Path,
    *,
    speed_unit: str | None = None,
) -> list[SmartphoneRow]:
    """Map inspected headers to IMU/GNSS fields. Fail if accel columns are absent.

    `speed_unit` is the declared unit of `GPS SPEED (Kmh)`. IO-VNBD's published
    column is metres per second. The loader checks unique-fix finite difference
    against that declaration and raises if they disagree.
    """

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
    alt = _match(fields, _ALT[0])
    speed = _match(fields, _SPEED[0])
    accuracy = _match(fields, _ACCURACY[0])
    time_ms = _match(fields, _TIME_MS[0])
    satellites = _match(fields, _SAT[0])
    grav_x = _match(fields, _GRAV[0])
    grav_y = _match(fields, _GRAV[1])
    grav_z = _match(fields, _GRAV[2])
    orient = _match(fields, _ORIENT[0])
    declared_unit = speed_unit or DECLARED_SPEED_UNIT
    text = read_table_text(resolved)
    reader = csv.DictReader(text.splitlines())
    rows: list[SmartphoneRow] = []
    for index, raw in enumerate(reader):
        raw = {((key or "").strip()): (value or "") for key, value in raw.items()}
        stamp_ms = _optional_float(raw, time_ms)
        flags: list[str] = []
        if stamp_ms is None:
            timestamp_ns = index * 100_000_000
            timestamp_source = "row_index_10hz_fallback"
            flags.append("timestamp_ms_missing")
        else:
            timestamp_ns = int(stamp_ms * _MS_TO_NS)
            timestamp_source = "time_since_start_ms"
        gyro_yaw = _optional_float(raw, yaw)
        gyro_pitch = _optional_float(raw, pitch)
        gyro_roll = _optional_float(raw, roll)
        if gyro_yaw is None or gyro_pitch is None or gyro_roll is None:
            flags.append("gyro_incomplete")
        latitude = _optional_float(raw, lat)
        longitude = _optional_float(raw, lon)
        speed_kmh = _optional_float(raw, speed)
        if latitude is None or longitude is None:
            flags.append("gnss_position_missing")
        if speed_kmh is None:
            flags.append("gnss_speed_missing")
        rows.append(
            SmartphoneRow(
                trip_id=resolved.stem,
                timestamp_ns=timestamp_ns,
                ax=float(raw[ax]),
                ay=float(raw[ay]),
                az=float(raw[az]),
                gyro_yaw=gyro_yaw,
                gyro_pitch=gyro_pitch,
                gyro_roll=gyro_roll,
                latitude_deg=latitude,
                longitude_deg=longitude,
                speed_kmh=speed_kmh,
                altitude_m=_optional_float(raw, alt),
                accuracy_m=_optional_float(raw, accuracy),
                satellites_raw=_optional_text(raw, satellites),
                gravity_x=_optional_float(raw, grav_x),
                gravity_y=_optional_float(raw, grav_y),
                gravity_z=_optional_float(raw, grav_z),
                gps_orientation_deg=_optional_float(raw, orient),
                speed_unit=declared_unit,
                timestamp_source=timestamp_source,
                quality_flags=tuple(flags),
            )
        )
    if not rows:
        raise ValueError(f"{resolved} has no data rows")
    return _apply_speed_unit(rows, declared_unit, resolved)


@dataclass(frozen=True)
class TimestampRewind:
    """Rows dropped because TIME SINCE START jumped backward. Do not reorder."""

    dropped_rows: int
    kept_rows: int
    source_rows: int
    first_rewind_ns: int
    suffix: bool


def trim_nondecreasing_rows(rows: Sequence[SmartphoneRow]) -> tuple[list[SmartphoneRow], TimestampRewind | None]:
    """Keep a non-decreasing prefix/stream. Report when a rewind discards rows."""

    kept: list[SmartphoneRow] = []
    last_ns: int | None = None
    dropped = 0
    first_rewind_ns: int | None = None
    kept_after_rewind = 0
    for row in rows:
        if last_ns is not None and row.timestamp_ns < last_ns:
            dropped += 1
            if first_rewind_ns is None:
                first_rewind_ns = int(row.timestamp_ns)
            continue
        if first_rewind_ns is not None:
            kept_after_rewind += 1
        kept.append(row)
        last_ns = row.timestamp_ns
    if dropped == 0 or first_rewind_ns is None:
        return kept, None
    return kept, TimestampRewind(
        dropped_rows=dropped,
        kept_rows=len(kept),
        source_rows=len(rows),
        first_rewind_ns=first_rewind_ns,
        suffix=kept_after_rewind == 0,
    )


def keep_nondecreasing_rows(rows: list[SmartphoneRow]) -> list[SmartphoneRow]:
    """Drop samples whose TIME SINCE START jumps backward. Do not reorder."""

    kept, _ = trim_nondecreasing_rows(rows)
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
            "quality_flags": list(row.quality_flags),
        }
        if row.speed_kmh is not None:
            speed = column_speed_to_mps(row.speed_kmh, row.speed_unit)
            item["speed_mps"] = speed
            item["stopped"] = float(speed < 1.0)
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
    origin_alt: float | None = None
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
        flags = list(row.quality_flags)
        if row.altitude_m is None:
            up_m = 0.0
            flags.append("pose_z_unavailable")
        else:
            if origin_alt is None:
                origin_alt = row.altitude_m
            up_m = row.altitude_m - origin_alt
        item = {
            "trip_id": row.trip_id,
            "timestamp_ns": row.timestamp_ns,
            "ax": row.ax,
            "ay": row.ay,
            "az": row.az,
            "pose_x_m": east,
            "pose_y_m": north,
            "pose_z_m": up_m,
            "heading_rad": heading,
            "quality_flags": flags,
        }
        if row.speed_kmh is not None:
            speed = column_speed_to_mps(row.speed_kmh, row.speed_unit)
            item["speed_mps"] = speed
            item["stopped"] = 1.0 if speed < 0.3 else 0.0
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


def _apply_speed_unit(
    rows: list[SmartphoneRow],
    declared_unit: str,
    path: Path,
) -> list[SmartphoneRow]:
    probe = []
    for row in rows:
        if row.latitude_deg is None or row.longitude_deg is None or row.speed_kmh is None:
            continue
        probe.append(
            {
                "timestamp_ns": row.timestamp_ns,
                "latitude_deg": row.latitude_deg,
                "longitude_deg": row.longitude_deg,
                "speed_column": row.speed_kmh,
            }
        )
    try:
        report = infer_speed_unit(probe, declared=declared_unit)
    except ValueError as error:
        raise ValueError(f"{path}: {error}") from error
    flags_extra: tuple[str, ...]
    if report.verified:
        flags_extra = ("speed_unit_verified_mps",) if report.unit == SPEED_UNIT_MPS else ("speed_unit_verified_kmh",)
    else:
        flags_extra = ("speed_unit_unverified",)
    return [
        replace(
            row,
            speed_unit=report.unit,
            quality_flags=tuple(dict.fromkeys(row.quality_flags + flags_extra)),
        )
        for row in rows
    ]


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


def _optional_text(raw: dict[str, str], column: str | None) -> str | None:
    if column is None:
        return None
    value = raw.get(column)
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None
