"""Score Pune phone-drive trip logs. Not IO-VNBD.

GNSS inside a Hold window is score-only. The harness never invents a
median drift, p50, or summary table. Missing logs abort before any
metrics file is written.
"""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from driftzero_ml.contracts import validate_navigation_state
from driftzero_ml.metrics import BlackoutMetrics, evaluate_blackout, haversine_m, path_length_m

NS_PER_S = 1_000_000_000
ALIGN_MAX_DT_NS = 150_000_000
PATH_TARGETS_M = (50.0, 1000.0)
HELD_FLAG = "gnss_held"
OFFICIAL_DIR = Path("results") / "pune_v1"


class PuneEvalError(ValueError):
    """A Pune log is missing, empty, or cannot be scored without invention."""


@dataclass(frozen=True)
class GnssFix:
    timestamp_ns: int
    latitude_deg: float
    longitude_deg: float
    speed_mps: float | None
    bearing_rad: float | None
    held: bool

    @property
    def latlon(self) -> tuple[float, float]:
        return (self.latitude_deg, self.longitude_deg)


@dataclass(frozen=True)
class HoldWindow:
    start_ns: int
    end_ns: int
    interval_id: str


@dataclass(frozen=True)
class IntervalScore:
    trip_id: str
    interval_id: str
    start_ns: int
    end_ns: int
    metrics: BlackoutMetrics
    dataset: str


def measured_rate_hz(timestamps_ns: Sequence[int]) -> dict[str, float | int | None]:
    """Median and mean Hz from consecutive positive timestamp deltas."""

    stamps = [int(t) for t in timestamps_ns]
    dts = [b - a for a, b in itertools.pairwise(stamps) if b > a]
    if not dts:
        return {
            "sample_count": len(stamps),
            "positive_dt_count": 0,
            "median_hz": None,
            "mean_hz": None,
            "median_dt_ns": None,
        }
    ordered = sorted(dts)
    mid = len(ordered) // 2
    if len(ordered) % 2 == 1:
        median_dt = ordered[mid]
    else:
        median_dt = (ordered[mid - 1] + ordered[mid]) // 2
    mean_dt = sum(dts) / len(dts)
    return {
        "sample_count": len(stamps),
        "positive_dt_count": len(dts),
        "median_hz": NS_PER_S / median_dt,
        "mean_hz": NS_PER_S / mean_dt,
        "median_dt_ns": median_dt,
    }


def discover_trips(logs: Path) -> list[Path]:
    if not logs.exists():
        raise PuneEvalError(f"log directory does not exist: {logs}")
    if not logs.is_dir():
        raise PuneEvalError(f"log path is not a directory: {logs}")
    if (logs / "sensors.jsonl").is_file():
        return [logs]
    trips = sorted(
        path
        for path in logs.iterdir()
        if path.is_dir() and (path / "sensors.jsonl").is_file()
    )
    if not trips:
        raise PuneEvalError(
            f"no Pune trip logs at {logs}. Need sensors.jsonl from a recorded "
            "Android trip. Refusing to write invented metrics."
        )
    return trips


def load_jsonl_objects(path: Path) -> list[dict]:
    if not path.is_file():
        raise PuneEvalError(f"missing file: {path}")
    rows: list[dict] = []
    for line_no, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as error:
            raise PuneEvalError(f"{path}:{line_no}: invalid JSON") from error
    return rows


def split_sensor_log(rows: Sequence[dict]) -> tuple[dict | None, list[dict]]:
    header = None
    frames: list[dict] = []
    for obj in rows:
        if "declared_rate_hz" in obj and "kind" not in obj:
            header = obj
            continue
        frames.append(obj)
    return header, frames


def extract_gnss(frames: Sequence[dict]) -> list[GnssFix]:
    fixes: list[GnssFix] = []
    for obj in frames:
        if obj.get("kind") != "gnss_fix":
            continue
        payload = obj.get("payload")
        if not isinstance(payload, dict):
            raise PuneEvalError("gnss_fix missing payload")
        flags = []
        quality = obj.get("quality")
        if isinstance(quality, dict):
            raw_flags = quality.get("flags") or []
            if isinstance(raw_flags, list):
                flags = [str(item) for item in raw_flags]
        stamp = obj.get("timestamp_ns")
        if not isinstance(stamp, int) or isinstance(stamp, bool) or stamp < 0:
            raise PuneEvalError("gnss_fix timestamp_ns must be a non-negative integer")
        lat = payload.get("latitude_deg")
        lon = payload.get("longitude_deg")
        if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
            raise PuneEvalError("gnss_fix payload needs latitude_deg and longitude_deg")
        speed = payload.get("speed_mps")
        bearing = payload.get("bearing_rad")
        fixes.append(
            GnssFix(
                timestamp_ns=stamp,
                latitude_deg=float(lat),
                longitude_deg=float(lon),
                speed_mps=float(speed) if isinstance(speed, (int, float)) else None,
                bearing_rad=float(bearing) if isinstance(bearing, (int, float)) else None,
                held=HELD_FLAG in flags,
            )
        )
    return fixes


def extract_imu_timestamps(frames: Sequence[dict], kind: str) -> list[int]:
    stamps: list[int] = []
    for obj in frames:
        if obj.get("kind") != kind:
            continue
        stamp = obj.get("timestamp_ns")
        if not isinstance(stamp, int) or isinstance(stamp, bool) or stamp < 0:
            raise PuneEvalError(f"{kind} timestamp_ns must be a non-negative integer")
        stamps.append(stamp)
    return stamps


def load_states(path: Path) -> list[dict]:
    if not path.is_file():
        raise PuneEvalError(f"missing states JSONL: {path}")
    rows = []
    for obj in load_jsonl_objects(path):
        validate_navigation_state(obj)
        rows.append(obj)
    if not rows:
        raise PuneEvalError(f"{path} has no NavigationState rows")
    return rows


def hold_windows(manifest: dict, trip_id: str) -> list[HoldWindow]:
    raw = manifest.get("hold_intervals")
    if not isinstance(raw, list) or not raw:
        raise PuneEvalError(
            f"{trip_id}: no hold_intervals in manifest. Hold GNSS on the drive "
            "so 1 Hz GNSS is score-only. Refusing to invent a mask."
        )
    windows: list[HoldWindow] = []
    for index, row in enumerate(raw):
        if not isinstance(row, dict):
            raise PuneEvalError(f"{trip_id}: hold_intervals[{index}] is not an object")
        start = row.get("start_ns")
        end = row.get("end_ns")
        if not isinstance(start, int) or not isinstance(end, int) or end < start:
            raise PuneEvalError(f"{trip_id}: hold_intervals[{index}] needs ordered start_ns/end_ns")
        windows.append(HoldWindow(start, end, f"hold_{index + 1}"))
    return windows


def in_window(timestamp_ns: int, start_ns: int, end_ns: int) -> bool:
    return start_ns <= timestamp_ns <= end_ns


def assert_held_flagged(fixes: Sequence[GnssFix], windows: Sequence[HoldWindow], trip_id: str) -> None:
    for fix in fixes:
        if any(in_window(fix.timestamp_ns, w.start_ns, w.end_ns) for w in windows) and not fix.held:
            raise PuneEvalError(
                f"{trip_id}: GNSS at {fix.timestamp_ns} is inside Hold but missing "
                f"{HELD_FLAG}. Score-only flag is required."
            )


def path_window(
    fixes: Sequence[GnssFix],
    *,
    target_m: float,
    start_ns: int,
    end_ns: int,
    interval_id: str,
) -> HoldWindow | None:
    inside = [fix for fix in fixes if in_window(fix.timestamp_ns, start_ns, end_ns)]
    if len(inside) < 2 or target_m <= 0:
        return None
    acc = 0.0
    last = inside[0].latlon
    for row in inside[1:]:
        acc += haversine_m(last, row.latlon)
        last = row.latlon
        if acc + 1e-12 >= target_m:
            return HoldWindow(inside[0].timestamp_ns, row.timestamp_ns, interval_id)
    return None


def nearest_state(states: Sequence[dict], stamp_ns: int, max_dt_ns: int = ALIGN_MAX_DT_NS) -> dict | None:
    best = None
    best_dt = None
    for row in states:
        dt = abs(int(row["timestamp_ns"]) - stamp_ns)
        if best_dt is None or dt < best_dt:
            best = row
            best_dt = dt
    if best is None or best_dt is None or best_dt > max_dt_ns:
        return None
    return best


def score_window(
    states: Sequence[dict],
    truth: Sequence[GnssFix],
    start_ns: int,
    end_ns: int,
) -> BlackoutMetrics:
    window_truth = [fix for fix in truth if in_window(fix.timestamp_ns, start_ns, end_ns)]
    if len(window_truth) < 2:
        raise PuneEvalError("need at least two GNSS truth fixes in the mask")
    estimate: list[tuple[float, float]] = []
    aligned: list[tuple[float, float]] = []
    for fix in window_truth:
        state = nearest_state(states, fix.timestamp_ns)
        if state is None:
            continue
        pos = state["position"]
        estimate.append((float(pos["latitude_deg"]), float(pos["longitude_deg"])))
        aligned.append(fix.latlon)
    if len(aligned) < 2:
        raise PuneEvalError("not enough NavigationState rows aligned to GNSS truth")
    path = path_length_m([fix.latlon for fix in window_truth])
    endpoint = haversine_m(estimate[-1], window_truth[-1].latlon)
    ratio = endpoint / path if path >= 1.0 else None
    paired = evaluate_blackout(estimate, aligned)
    return BlackoutMetrics(
        endpoint_error_m=endpoint,
        truth_path_length_m=path,
        drift_ratio=ratio,
        mean_position_error_m=paired.mean_position_error_m,
        max_position_error_m=paired.max_position_error_m,
        sample_count=len(aligned),
        along_track_error_m=paired.along_track_error_m,
        cross_track_error_m=paired.cross_track_error_m,
    )


def evaluate_trip(trip_dir: Path, *, dataset: str) -> tuple[list[IntervalScore], dict]:
    trip_id = trip_dir.name
    header, frames = split_sensor_log(load_jsonl_objects(trip_dir / "sensors.jsonl"))
    if not frames:
        raise PuneEvalError(f"{trip_id}: sensors.jsonl has no frames")
    manifest_path = trip_dir / "manifest.json"
    if not manifest_path.is_file():
        raise PuneEvalError(f"{trip_id}: missing manifest.json")
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, dict):
        raise PuneEvalError(f"{trip_id}: manifest.json must be an object")
    windows = hold_windows(manifest, trip_id)
    fixes = extract_gnss(frames)
    if len(fixes) < 2:
        raise PuneEvalError(f"{trip_id}: need GNSS fixes with lat/lon, speed_mps, bearing_rad")
    assert_held_flagged(fixes, windows, trip_id)
    states = load_states(trip_dir / "states.jsonl")
    scores: list[IntervalScore] = []
    for hold in windows:
        scores.append(
            IntervalScore(
                trip_id=trip_id,
                interval_id=hold.interval_id,
                start_ns=hold.start_ns,
                end_ns=hold.end_ns,
                metrics=score_window(states, fixes, hold.start_ns, hold.end_ns),
                dataset=dataset,
            )
        )
        for target in PATH_TARGETS_M:
            label = f"{hold.interval_id}_d{int(target)}"
            cut = path_window(
                fixes,
                target_m=target,
                start_ns=hold.start_ns,
                end_ns=hold.end_ns,
                interval_id=label,
            )
            if cut is None:
                continue
            scores.append(
                IntervalScore(
                    trip_id=trip_id,
                    interval_id=cut.interval_id,
                    start_ns=cut.start_ns,
                    end_ns=cut.end_ns,
                    metrics=score_window(states, fixes, cut.start_ns, cut.end_ns),
                    dataset=dataset,
                )
            )
    accel = measured_rate_hz(extract_imu_timestamps(frames, "accelerometer"))
    gyro = measured_rate_hz(extract_imu_timestamps(frames, "gyroscope"))
    meta = {
        "trip_id": trip_id,
        "dataset": dataset,
        "requested_sensor_delay": (header or {}).get("requested_sensor_delay")
        or manifest.get("requested_sensor_delay"),
        "declared_rate_hz": (header or {}).get("declared_rate_hz") or manifest.get("declared_rate_hz"),
        "measured_accel_hz": accel["median_hz"],
        "measured_gyro_hz": gyro["median_hz"],
        "accel_sample_count": accel["sample_count"],
        "gyro_sample_count": gyro["sample_count"],
        "hold_count": len(windows),
        "gnss_fix_count": len(fixes),
    }
    return scores, meta


def write_bundle(
    out: Path,
    scores: Sequence[IntervalScore],
    metas: Sequence[dict],
    *,
    dataset: str,
    logs: Path,
) -> None:
    out.mkdir(parents=True, exist_ok=True)
    csv_path = out / "metrics_per_interval.csv"
    json_path = out / "metrics_summary.json"
    readme_path = out / "README.md"
    fieldnames = [
        "trip_id",
        "interval_id",
        "dataset",
        "start_ns",
        "end_ns",
        "truth_path_length_m",
        "endpoint_error_m",
        "drift_ratio",
        "sample_count",
    ]
    with csv_path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in scores:
            writer.writerow(
                {
                    "trip_id": row.trip_id,
                    "interval_id": row.interval_id,
                    "dataset": row.dataset,
                    "start_ns": row.start_ns,
                    "end_ns": row.end_ns,
                    "truth_path_length_m": f"{row.metrics.truth_path_length_m:.6f}",
                    "endpoint_error_m": f"{row.metrics.endpoint_error_m:.6f}",
                    "drift_ratio": ""
                    if row.metrics.drift_ratio is None
                    else f"{row.metrics.drift_ratio:.6f}",
                    "sample_count": row.metrics.sample_count,
                }
            )
    ratios = [row.metrics.drift_ratio for row in scores if row.metrics.drift_ratio is not None]
    summary = {
        "dataset": dataset,
        "not_io_vnbd": True,
        "logs": str(logs),
        "interval_count": len(scores),
        "trip_count": len(metas),
        "trips": list(metas),
        "intervals": [
            {
                "trip_id": row.trip_id,
                "interval_id": row.interval_id,
                "dataset": row.dataset,
                **row.metrics.to_dict(),
                "start_ns": row.start_ns,
                "end_ns": row.end_ns,
            }
            for row in scores
        ],
    }
    if ratios:
        ordered = sorted(ratios)
        mid = len(ordered) // 2
        if len(ordered) % 2 == 1:
            p50 = ordered[mid]
        else:
            p50 = 0.5 * (ordered[mid - 1] + ordered[mid])
        summary["drift_ratio_p50"] = p50
    json_path.write_text(json.dumps(summary, indent=2) + "\n")
    readme_path.write_text(_readme(dataset, logs, scores, metas))


def _readme(
    dataset: str,
    logs: Path,
    scores: Sequence[IntervalScore],
    metas: Sequence[dict],
) -> str:
    lines = [
        "# Pune v1",
        "",
        "This bundle is **not IO-VNBD**. Do not mix these rows with",
        "`results/io_vnbd_screening_v1/summary.md`.",
        "",
        f"Dataset label: `{dataset}`.",
        f"Logs: `{logs}`.",
        "",
        "GNSS inside Hold is score-only. IMU timestamps are integer nanoseconds.",
        "Accel is m/s^2. Gyro is rad/s. GNSS speed is m/s. Course is bearing_rad.",
        "Measured IMU Hz comes from timestamp deltas. `declared_rate_hz` is the",
        "preferred request, not a measured Hertz.",
        "",
        f"Trips scored: {len(metas)}. Intervals scored: {len(scores)}.",
        "",
    ]
    if dataset == "synthetic":
        lines.append("These numbers are from a labeled synthetic fixture, not a Pune drive.")
        lines.append("")
    for row in scores:
        ratio = "n/a" if row.metrics.drift_ratio is None else f"{row.metrics.drift_ratio:.4f}"
        lines.append(
            f"- `{row.trip_id}` `{row.interval_id}` path "
            f"{row.metrics.truth_path_length_m:.2f} m endpoint "
            f"{row.metrics.endpoint_error_m:.2f} m drift_ratio {ratio}"
        )
    lines.append("")
    return "\n".join(lines)


def official_out(out: Path, repo: Path) -> bool:
    return out.resolve() == (repo / OFFICIAL_DIR).resolve()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Score Pune trip logs. Aborts if logs are missing. Not IO-VNBD."
    )
    parser.add_argument("--logs", type=Path, required=True, help="trip dir or parent of trip-* dirs")
    parser.add_argument("--out", type=Path, required=True, help="output directory")
    parser.add_argument("--repo", type=Path, default=Path("."), help="repository root")
    parser.add_argument(
        "--synthetic",
        action="store_true",
        help="label the run synthetic. Refuses results/pune_v1.",
    )
    args = parser.parse_args(argv)
    dataset = "synthetic" if args.synthetic else "pune_phone"
    if args.synthetic and official_out(args.out, args.repo):
        print(
            "refusing to write synthetic metrics into results/pune_v1",
            file=sys.stderr,
        )
        return 2
    try:
        trips = discover_trips(args.logs)
        scores: list[IntervalScore] = []
        metas: list[dict] = []
        for trip in trips:
            trip_scores, meta = evaluate_trip(trip, dataset=dataset)
            scores.extend(trip_scores)
            metas.append(meta)
        if not scores:
            raise PuneEvalError("no scoreable Hold windows")
    except PuneEvalError as error:
        print(str(error), file=sys.stderr)
        return 2
    write_bundle(args.out, scores, metas, dataset=dataset, logs=args.logs)
    print(f"wrote {args.out / 'metrics_summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
