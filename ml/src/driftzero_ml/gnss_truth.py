"""Score-only GNSS helpers. Unique fixes, heading seed, speed unit, truth gate."""

from __future__ import annotations

import itertools
from collections.abc import Sequence
from dataclasses import dataclass
from math import atan2, cos, pi, radians
from statistics import median

from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M, haversine_m, path_length_m

MIN_HEADING_MOTION_M = 10.0
MIN_COAST_SEED_SPEED_MPS = 0.4
SPEED_UNIT_MPS = "m/s"
SPEED_UNIT_KMH = "km/h"
RATIO_BAND = (0.70, 1.30)
MIN_UNIT_PAIRS = 8
MAX_PLAUSIBLE_SPEED_MPS = 55.0
MIN_UNIQUE_FIXES = 3
MIN_PATH_LENGTH_M = 20.0
MAX_FIX_HOP_M = 200.0
MIN_SENSOR_COVERAGE = 0.99


@dataclass(frozen=True)
class TruthGateConfig:
    max_plausible_speed_mps: float = MAX_PLAUSIBLE_SPEED_MPS
    min_unique_fixes: int = MIN_UNIQUE_FIXES
    min_path_length_m: float = MIN_PATH_LENGTH_M
    max_fix_hop_m: float = MAX_FIX_HOP_M
    min_sensor_coverage: float = MIN_SENSOR_COVERAGE


@dataclass(frozen=True)
class TruthGateResult:
    accepted: bool
    reasons: tuple[str, ...]
    unique_fixes: int
    path_length_m: float
    implied_speed_mps: float | None
    max_hop_m: float
    coverage: float


@dataclass(frozen=True)
class SpeedUnitReport:
    unit: str
    median_fd_over_column: float | None
    pair_count: int
    verified: bool


def unique_fix_indices(records: Sequence[dict]) -> list[int]:
    """Indices where lat/lon exist and differ from the previous GNSS position."""

    out: list[int] = []
    last: tuple[float, float] | None = None
    for index, row in enumerate(records):
        if "latitude_deg" not in row or "longitude_deg" not in row:
            continue
        pos = (float(row["latitude_deg"]), float(row["longitude_deg"]))
        if last is None or pos != last:
            out.append(index)
            last = pos
    return out


def unique_fix_records(records: Sequence[dict]) -> list[dict]:
    return [records[i] for i in unique_fix_indices(records)]


def unique_fix_median_spacing_s(records: Sequence[dict] | Sequence[int]) -> float | None:
    """Median seconds between consecutive unique GNSS fixes. None if fewer than 2."""

    if records and isinstance(records[0], dict):
        stamps = [int(row["timestamp_ns"]) for row in unique_fix_records(records)]  # type: ignore[index]
    else:
        stamps = [int(item) for item in records]
    dts: list[float] = []
    for earlier, later in itertools.pairwise(stamps):
        dt = (later - earlier) / 1_000_000_000.0
        if dt > 0.0:
            dts.append(dt)
    if not dts:
        return None
    return median(dts)


def course_rad(a: dict, b: dict) -> float | None:
    """North-zero, east-positive heading. None if the segment is shorter than 1 mm."""

    lat0, lon0 = float(a["latitude_deg"]), float(a["longitude_deg"])
    lat1, lon1 = float(b["latitude_deg"]), float(b["longitude_deg"])
    north = radians(lat1 - lat0) * EARTH_MEAN_RADIUS_M
    east = radians(lon1 - lon0) * EARTH_MEAN_RADIUS_M * cos(radians((lat0 + lat1) / 2.0))
    if north * north + east * east < 1e-6:
        return None
    return atan2(east, north)


def orientation_rad(row: dict) -> float | None:
    if "gnss_bearing_rad" in row and row["gnss_bearing_rad"] is not None:
        return float(row["gnss_bearing_rad"]) % (2.0 * pi)
    if "gps_orientation_deg" in row and row["gps_orientation_deg"] is not None:
        return radians(float(row["gps_orientation_deg"])) % (2.0 * pi)
    return None


def seed_heading_rad(
    history: Sequence[dict],
    *,
    min_motion_m: float = MIN_HEADING_MOTION_M,
) -> float | None:
    """Heading from the last >= min_motion_m of unique GNSS motion, else GPS course.

    Does not use the last two rows when they share a position (that seeds due north).
    Persist scoring calls this on history with t < blackout start_ns. A 0 m/s unique
    sitting on the mask start is not in that history. Use persist_coast_seed to skip
    a trailing 0 m/s unique when the caller passed a wider window.
    """

    if min_motion_m <= 0:
        raise ValueError("min_motion_m must be positive")
    fixes = unique_fix_records(history)
    if len(fixes) >= 2:
        acc = 0.0
        end = fixes[-1]
        for index in range(len(fixes) - 2, -1, -1):
            acc += haversine_m(
                (float(fixes[index]["latitude_deg"]), float(fixes[index]["longitude_deg"])),
                (float(fixes[index + 1]["latitude_deg"]), float(fixes[index + 1]["longitude_deg"])),
            )
            if acc >= min_motion_m:
                heading = course_rad(fixes[index], end)
                if heading is not None:
                    return heading
    if fixes:
        bearing = orientation_rad(fixes[-1])
        if bearing is not None:
            return bearing
    if history:
        return orientation_rad(history[-1])
    return None


@dataclass(frozen=True)
class PersistCoastSeed:
    latitude_deg: float
    longitude_deg: float
    timestamp_ns: int
    speed_mps: float
    heading_rad: float


def persist_coast_seed(
    history: Sequence[dict],
    *,
    before_ns: int | None = None,
    min_speed_mps: float = MIN_COAST_SEED_SPEED_MPS,
    min_motion_m: float = MIN_HEADING_MOTION_M,
) -> PersistCoastSeed | None:
    """Last unique with column speed and a 10 m course, strictly before before_ns.

    Skips a trailing 0 m/s unique. Persist scoring uses t < start_ns so that
    unique is already absent. Kotlin YAW_SPEED_HOLD uses this pick when the
    mask-start unique is 0 m/s (S-Vw16b).
    """

    if min_speed_mps < 0.0:
        raise ValueError("min_speed_mps must be >= 0")
    if min_motion_m <= 0.0:
        raise ValueError("min_motion_m must be positive")
    fixes = unique_fix_records(history)
    if before_ns is not None:
        fixes = [row for row in fixes if int(row["timestamp_ns"]) < before_ns]
    for end in range(len(fixes) - 1, -1, -1):
        row = fixes[end]
        speed = _column_speed_mps(row)
        if speed is None or speed < min_speed_mps:
            continue
        heading = _ten_metre_course_rad(fixes, end, min_motion_m)
        if heading is None:
            continue
        return PersistCoastSeed(
            latitude_deg=float(row["latitude_deg"]),
            longitude_deg=float(row["longitude_deg"]),
            timestamp_ns=int(row["timestamp_ns"]),
            speed_mps=speed,
            heading_rad=heading,
        )
    return None


def _column_speed_mps(row: dict) -> float | None:
    for key in ("gnss_speed_mps", "speed_mps"):
        if key in row and row[key] is not None:
            return float(row[key])
    return None


def _ten_metre_course_rad(fixes: Sequence[dict], end: int, min_motion_m: float) -> float | None:
    if end < 1:
        return None
    acc = 0.0
    dest = fixes[end]
    for index in range(end - 1, -1, -1):
        acc += haversine_m(
            (float(fixes[index]["latitude_deg"]), float(fixes[index]["longitude_deg"])),
            (float(fixes[index + 1]["latitude_deg"]), float(fixes[index + 1]["longitude_deg"])),
        )
        if acc >= min_motion_m:
            return course_rad(fixes[index], dest)
    return None


def column_speed_to_mps(raw: float, unit: str) -> float:
    if unit == SPEED_UNIT_MPS:
        return raw
    if unit == SPEED_UNIT_KMH:
        return raw / 3.6
    raise ValueError(f"unsupported speed unit: {unit}")


def infer_speed_unit(
    records: Sequence[dict],
    *,
    column_key: str = "speed_column",
    declared: str = SPEED_UNIT_MPS,
) -> SpeedUnitReport:
    """Compare finite-difference m/s between unique fixes to the published column."""

    fixes = unique_fix_records(records)
    ratios: list[float] = []
    for earlier, later in itertools.pairwise(fixes):
        if column_key not in later or later[column_key] is None:
            continue
        column = float(later[column_key])
        dt = (int(later["timestamp_ns"]) - int(earlier["timestamp_ns"])) / 1_000_000_000.0
        if dt <= 0.0 or column <= 0.2:
            continue
        dist = haversine_m(
            (float(earlier["latitude_deg"]), float(earlier["longitude_deg"])),
            (float(later["latitude_deg"]), float(later["longitude_deg"])),
        )
        fd = dist / dt
        if fd <= 0.2:
            continue
        ratios.append(fd / column)
    if len(ratios) < MIN_UNIT_PAIRS:
        return SpeedUnitReport(declared, None, len(ratios), False)
    mid = median(ratios)
    inferred = _unit_from_ratio(mid)
    if inferred != declared:
        raise ValueError(
            f"GPS speed column unit {inferred} (median fd/column={mid:.4f} on "
            f"{len(ratios)} unique-fix pairs) disagrees with declared {declared}"
        )
    return SpeedUnitReport(inferred, mid, len(ratios), True)


def _unit_from_ratio(median_ratio: float) -> str:
    lo, hi = RATIO_BAND
    if lo <= median_ratio <= hi:
        return SPEED_UNIT_MPS
    if lo / 3.6 <= median_ratio <= hi / 3.6:
        return SPEED_UNIT_KMH
    raise ValueError(
        f"GPS speed column matches neither m/s nor km/h (median fd/column={median_ratio:.4f})"
    )


def assess_truth(
    truth: Sequence[tuple[float, float]],
    *,
    duration_s: float,
    coverage: float,
    config: TruthGateConfig | None = None,
) -> TruthGateResult:
    cfg = config or TruthGateConfig()
    reasons: list[str] = []
    if coverage + 1e-12 < cfg.min_sensor_coverage:
        reasons.append(f"sensor_coverage {coverage:.3f} < {cfg.min_sensor_coverage}")
    if len(truth) < 1:
        reasons.append("no truth points")
        return TruthGateResult(False, tuple(reasons), 0, 0.0, None, 0.0, coverage)
    unique: list[tuple[float, float]] = []
    for point in truth:
        if not unique or point != unique[-1]:
            unique.append(point)
    path = path_length_m(truth)
    hops = [
        haversine_m(a, b) for a, b in itertools.pairwise(unique)
    ]
    max_hop = max(hops) if hops else 0.0
    implied = path / duration_s if duration_s > 0 else None
    if len(unique) < cfg.min_unique_fixes:
        reasons.append(f"unique_fixes {len(unique)} < {cfg.min_unique_fixes}")
    if path + 1e-12 < cfg.min_path_length_m:
        reasons.append(f"path {path:.3f} m < {cfg.min_path_length_m} m")
    if implied is not None and implied > cfg.max_plausible_speed_mps:
        reasons.append(f"implied_speed {implied:.3f} m/s > {cfg.max_plausible_speed_mps}")
    if max_hop > cfg.max_fix_hop_m:
        reasons.append(f"max_hop {max_hop:.1f} m > {cfg.max_fix_hop_m} m")
    return TruthGateResult(
        accepted=not reasons,
        reasons=tuple(reasons),
        unique_fixes=len(unique),
        path_length_m=path,
        implied_speed_mps=implied,
        max_hop_m=max_hop,
        coverage=coverage,
    )


def snap_to_fresh_fix(
    records: Sequence[dict],
    timestamp_ns: int,
    *,
    toward: str,
) -> int | None:
    """Nearest unique-fix timestamp at or after (toward='end') or at or before start."""

    stamps = [int(records[i]["timestamp_ns"]) for i in unique_fix_indices(records)]
    if not stamps:
        return None
    if toward == "start":
        candidates = [s for s in stamps if s <= timestamp_ns]
        return candidates[-1] if candidates else None
    if toward == "end":
        candidates = [s for s in stamps if s >= timestamp_ns]
        return candidates[0] if candidates else None
    raise ValueError("toward must be 'start' or 'end'")


def score_epochs(records: Sequence[dict], start_ns: int, end_ns: int) -> list[int]:
    """Unique-fix timestamps inside [start_ns, end_ns)."""

    return [
        int(records[i]["timestamp_ns"])
        for i in unique_fix_indices(records)
        if start_ns <= int(records[i]["timestamp_ns"]) < end_ns
    ]
