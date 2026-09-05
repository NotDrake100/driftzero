#!/usr/bin/env python3
"""Score an emulator GNSS-gap fixture. Not SIH screening. Not IO-VNBD.

path_m is the GPX polyline across the first timestamp jump (emitted fixes
only). error_m is the last fused/DR pose before resume versus the first
resume fix. ratio = error_m / path_m. The official gate is ratio < 0.10.

Numbers are emulator-fixture measurements. Do not invent metres.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

EARTH_RADIUS_M = 6_371_000.0
RATIO_GATE = 0.10
POSE_RE = re.compile(
    r"pose\s+(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)"
    r"(?:\s+r95=(\S+))?(?:\s+speed=(\S+))?(?:\s+lamp=(\S+))?"
)
TRKPT_RE = re.compile(
    r'<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"\s*>\s*<time>([^<]+)</time>',
    re.IGNORECASE | re.DOTALL,
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gpx", required=True, help="emitted-fix GPX from drive_route.py")
    parser.add_argument("--pose-log", default=None, help="logcat text containing DriftZeroPose lines")
    parser.add_argument("--states", default=None, help="TripRecorder states.jsonl")
    parser.add_argument("--coast-lat", type=float, default=None)
    parser.add_argument("--coast-lon", type=float, default=None)
    parser.add_argument("--out", default=None, help="write score JSON here")
    return parser.parse_args(argv)


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Match Wgs84.distanceMetres: equirectangular, mean radius 6371000 m."""
    mean_lat = math.radians((lat1 + lat2) / 2.0)
    north = math.radians(lat2 - lat1) * EARTH_RADIUS_M
    east = math.radians(lon2 - lon1) * EARTH_RADIUS_M * math.cos(mean_lat)
    return math.hypot(north, east)


def parse_iso_epoch_s(text: str) -> float:
    match = re.fullmatch(
        r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?Z",
        text.strip(),
    )
    if match is None:
        raise SystemExit(f"GPX time is not UTC ISO-8601: {text}")
    year, month, day, hour, minute, second = (int(match.group(i)) for i in range(1, 7))
    frac = match.group(7)
    frac_s = 0.0 if not frac else int(frac.ljust(9, "0")[:9]) / 1_000_000_000.0

    def leap(y: int) -> bool:
        return (y % 4 == 0 and y % 100 != 0) or y % 400 == 0

    days = 0
    for y in range(1970, year):
        days += 366 if leap(y) else 365
    month_days = [31, 29 if leap(year) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    days += sum(month_days[: month - 1])
    days += day - 1
    return days * 86400.0 + hour * 3600.0 + minute * 60.0 + second + frac_s


def parse_gpx(path: Path) -> list[tuple[float, float, float]]:
    text = path.read_text(encoding="utf-8")
    rows = []
    for match in TRKPT_RE.finditer(text):
        lat = float(match.group(1))
        lon = float(match.group(2))
        epoch = parse_iso_epoch_s(match.group(3))
        rows.append((lat, lon, epoch))
    if not rows:
        raise SystemExit(f"no trkpt rows in {path}")
    return rows


def first_gap(
    points: list[tuple[float, float, float]],
    min_gap_s: float = 1.5,
) -> tuple[int, float, float]:
    for i in range(1, len(points)):
        dt = points[i][2] - points[i - 1][2]
        if dt > min_gap_s:
            path_m = distance_m(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
            return i, dt, path_m
    raise SystemExit("GPX has no timestamp jump larger than 1.5 s")


def parse_pose_log(path: Path) -> list[tuple[float, float, str | None]]:
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = POSE_RE.search(line)
        if match is None:
            continue
        rows.append((float(match.group(1)), float(match.group(2)), match.group(5)))
    return rows


def parse_states(path: Path) -> list[tuple[int, float, float, str]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        pos = obj.get("position") or {}
        ts = obj.get("timestamp_ns")
        lat = pos.get("latitude_deg")
        lon = pos.get("longitude_deg")
        mode = obj.get("mode")
        if ts is None or lat is None or lon is None:
            continue
        rows.append((int(ts), float(lat), float(lon), str(mode)))
    return rows


def pick_coast(args: argparse.Namespace) -> tuple[float, float, str]:
    if args.coast_lat is not None and args.coast_lon is not None:
        return args.coast_lat, args.coast_lon, "cli"
    if args.states:
        states = parse_states(Path(args.states))
        if not states:
            raise SystemExit("states.jsonl has no rows")
        # TripRecorder clock is elapsed-realtime, not GPX wall time. The last
        # coasting mode is the fused/DR sample during the emit gap.
        coast_rows = [
            row for row in states if row[3] in ("DEAD_RECKONING", "LOW_CONFIDENCE", "GNSS_DEGRADED")
        ]
        picked = coast_rows[-1] if coast_rows else states[-1]
        return picked[1], picked[2], f"states:{picked[3]}"
    if args.pose_log:
        poses = parse_pose_log(Path(args.pose_log))
        if not poses:
            raise SystemExit("pose log has no DriftZeroPose lines")
        coast_rows = [row for row in poses if row[2] in ("DEAD_RECKONING", "LOW_CONFIDENCE", "ASSISTED")]
        picked = coast_rows[-1] if coast_rows else poses[-1]
        return picked[0], picked[1], f"pose-log:{picked[2] or 'unknown'}"
    raise SystemExit("set --coast-lat/--coast-lon or --pose-log or --states")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    gpx = parse_gpx(Path(args.gpx))
    gap_index, gap_s, path_m = first_gap(gpx)
    if path_m <= 0.0:
        raise SystemExit("path_m is 0")
    coast_lat, coast_lon, source = pick_coast(args)
    truth_lat, truth_lon, _ = gpx[gap_index]
    error_m = distance_m(coast_lat, coast_lon, truth_lat, truth_lon)
    ratio = error_m / path_m
    last_lat, last_lon, _ = gpx[gap_index - 1]
    payload = {
        "label": "emulator fixture",
        "not": ["SIH screening", "IO-VNBD", "phone accuracy"],
        "gap_start_gpx_index": gap_index - 1,
        "gap_s": gap_s,
        "last_pre_gap": {"lat": last_lat, "lon": last_lon},
        "resume": {"lat": truth_lat, "lon": truth_lon},
        "coast": {"lat": coast_lat, "lon": coast_lon, "source": source},
        "path_m": path_m,
        "error_m": error_m,
        "ratio": ratio,
        "gate": RATIO_GATE,
        "met_0_10": ratio < RATIO_GATE,
    }
    text = json.dumps(payload, indent=2, sort_keys=True)
    print(text)
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        sys.exit(0)
