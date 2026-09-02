"""Leak-free IO-VNBD blackout screening. GNSS is score-only after mask."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from math import atan2, cos, exp, pi, radians, sin
from pathlib import Path
from statistics import mean, median
from typing import Callable, Sequence

from driftzero_ml.baselines import constant_velocity_baseline, freeze_baseline
from driftzero_ml.blackout import (
    GNSS_KEYS,
    BlackoutInterval,
    assert_no_gnss_leakage,
    mask_gnss_records,
)
from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.datasets.io_vnbd import (
    SmartphoneRow,
    load_smartphone_csv,
    require_iovnbd_tables,
)
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    MIN_SAMPLES,
    STOP_ZUPT,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.features.phone_align import TripAlignment, estimate_alignment, vertical_gyro_radps
from driftzero_ml.gnss_truth import (
    TruthGateConfig,
    assess_truth,
    column_speed_to_mps,
    score_epochs,
    seed_heading_rad,
    snap_to_fresh_fix,
    unique_fix_records,
)
from driftzero_ml.io_vnbd import IOVNBDMissing, assign_grouped_trip_splits
from driftzero_ml.metrics import (
    EARTH_MEAN_RADIUS_M,
    BlackoutMetrics,
    evaluate_blackout,
    path_length_m,
)
from driftzero_ml.student.linear import (
    LinearMotionStudent,
    load_linear_student,
    save_linear_student,
)
from driftzero_ml.student.train import labeled_windows, mae, train_from_trips

REQUIRED_SCREENING = 51
SCREENING_DIR = "Categorised IOVNB Dataset"
SEED = "26168"
BLACKOUT_NS = 20_000_000_000
MIN_BLACKOUT_NS = 5_000_000_000
MIN_HISTORY_NS = 2_000_000_000
EVAL_SPLITS = frozenset({"validation", "public_test", "locked_test"})
SYSTEMS = ("freeze", "cv", "filter_only", "speed_student")


def screening_csv_paths(root: Path) -> tuple[Path, ...]:
    tables = require_iovnbd_tables(root)
    unique: dict[str, Path] = {}
    for path in tables:
        if SCREENING_DIR not in path.parts:
            continue
        unique.setdefault(path.stem, path)
    return tuple(sorted(unique.values(), key=lambda item: item.name))


def eval_record(row: SmartphoneRow) -> dict:
    item: dict = {
        "timestamp_ns": row.timestamp_ns,
        "ax": row.ax,
        "ay": row.ay,
        "az": row.az,
        "trip_id": row.trip_id,
    }
    if row.gyro_yaw is not None:
        item["gx"] = row.gyro_yaw
    if row.gyro_pitch is not None:
        item["gy"] = row.gyro_pitch
    if row.gyro_roll is not None:
        item["gz"] = row.gyro_roll
    if row.latitude_deg is not None:
        item["latitude_deg"] = row.latitude_deg
    if row.longitude_deg is not None:
        item["longitude_deg"] = row.longitude_deg
    if row.speed_kmh is not None:
        item["gnss_speed_mps"] = column_speed_to_mps(row.speed_kmh, row.speed_unit)
        item["speed_column"] = row.speed_kmh
    if row.gps_orientation_deg is not None:
        item["gps_orientation_deg"] = row.gps_orientation_deg
    if row.gravity_x is not None and row.gravity_y is not None and row.gravity_z is not None:
        item["gravity_x"] = row.gravity_x
        item["gravity_y"] = row.gravity_y
        item["gravity_z"] = row.gravity_z
    return item


def attach_alignment(records: Sequence[dict], alignment: TripAlignment) -> list[dict]:
    out = []
    for row in records:
        copied = dict(row)
        rate = vertical_gyro_radps(
            (row.get("gx"), row.get("gy"), row.get("gz")),
            alignment,
        )
        if rate is not None:
            copied["gyro_vertical_radps"] = rate
        out.append(copied)
    return out


def alignment_from_records(records: Sequence[dict], trip_id: str) -> TripAlignment | None:
    grav = [
        (float(row["gravity_x"]), float(row["gravity_y"]), float(row["gravity_z"]))
        for row in records
        if "gravity_x" in row and "gravity_y" in row and "gravity_z" in row
    ]
    if len(grav) < 8:
        return None
    return estimate_alignment(grav, trip_id=trip_id)


def _time_ordered(records: Sequence[dict]) -> list[dict]:
    ordered = sorted(records, key=lambda row: int(row["timestamp_ns"]))
    cleaned: list[dict] = []
    last = -1
    for row in ordered:
        stamp = int(row["timestamp_ns"])
        if stamp < last:
            continue
        cleaned.append(row)
        last = stamp
    return cleaned


def choose_blackout(records: Sequence[dict]) -> BlackoutInterval | None:
    """Mid-trip window snapped so start and end land on unique GNSS fixes."""

    if len(records) < 12:
        return None
    t0 = int(records[0]["timestamp_ns"])
    t1 = int(records[-1]["timestamp_ns"])
    span = t1 - t0
    if span < MIN_HISTORY_NS + MIN_BLACKOUT_NS:
        return None
    duration = min(BLACKOUT_NS, max(MIN_BLACKOUT_NS, int(0.25 * span)))
    raw_start = t0 + int(0.40 * span)
    start = snap_to_fresh_fix(records, raw_start, toward="end")
    if start is None:
        start = snap_to_fresh_fix(records, raw_start, toward="start")
    if start is None or start <= t0:
        return None
    raw_end = start + duration
    end = snap_to_fresh_fix(records, raw_end, toward="end")
    if end is None:
        end = snap_to_fresh_fix(records, min(t1, raw_end), toward="start")
    if end is None or end <= start:
        return None
    # Half-open interval must include the end fix for scoring.
    end_exclusive = end + 1
    if end_exclusive <= start:
        return None
    return BlackoutInterval(start, end_exclusive, "mid")


def choose_distance_blackout(
    records: Sequence[dict],
    *,
    target_m: float,
    start_frac: float,
    interval_id: str,
    max_ns: int,
) -> BlackoutInterval | None:
    """Fixed path-length interval on unique GNSS fixes only."""

    if target_m <= 0:
        raise ValueError("target_m must be positive")
    t0 = int(records[0]["timestamp_ns"])
    t1 = int(records[-1]["timestamp_ns"])
    raw_start = t0 + int(start_frac * (t1 - t0))
    start = snap_to_fresh_fix(records, raw_start, toward="end")
    if start is None:
        return None
    fixes = [
        row
        for row in unique_fix_records(records)
        if int(row["timestamp_ns"]) >= start
    ]
    if len(fixes) < 2:
        return None
    acc = 0.0
    last = (float(fixes[0]["latitude_deg"]), float(fixes[0]["longitude_deg"]))
    end_ns = None
    for row in fixes[1:]:
        cur = (float(row["latitude_deg"]), float(row["longitude_deg"]))
        acc += path_length_m([last, cur])
        last = cur
        now = int(row["timestamp_ns"])
        if acc >= target_m:
            end_ns = now
            break
        if now - start > max_ns:
            break
    if end_ns is None or end_ns <= start:
        return None
    return BlackoutInterval(start, end_ns + 1, interval_id)


def _gnss_history(records: Sequence[dict], start_ns: int) -> list[dict]:
    return [
        row
        for row in records
        if int(row["timestamp_ns"]) < start_ns
        and "latitude_deg" in row
        and "longitude_deg" in row
    ]


def _positive_dt_pair(history: Sequence[dict]) -> tuple[dict, dict] | None:
    for index in range(len(history) - 1, 0, -1):
        later = history[index]
        earlier = history[index - 1]
        if int(later["timestamp_ns"]) > int(earlier["timestamp_ns"]):
            return earlier, later
    return None


def course_rad(a: dict, b: dict) -> float:
    lat0, lon0 = float(a["latitude_deg"]), float(a["longitude_deg"])
    lat1, lon1 = float(b["latitude_deg"]), float(b["longitude_deg"])
    north = radians(lat1 - lat0) * EARTH_MEAN_RADIUS_M
    east = radians(lon1 - lon0) * EARTH_MEAN_RADIUS_M * cos(radians((lat0 + lat1) / 2.0))
    if north * north + east * east < 1e-6:
        return 0.0
    return atan2(east, north)


def last_speed_mps(a: dict, b: dict) -> float:
    if "gnss_speed_mps" in b and b["gnss_speed_mps"] is not None:
        return max(0.0, float(b["gnss_speed_mps"]))
    dt = (int(b["timestamp_ns"]) - int(a["timestamp_ns"])) / 1_000_000_000.0
    if dt <= 0.0:
        return 0.0
    north = radians(float(b["latitude_deg"]) - float(a["latitude_deg"])) * EARTH_MEAN_RADIUS_M
    east = (
        radians(float(b["longitude_deg"]) - float(a["longitude_deg"]))
        * EARTH_MEAN_RADIUS_M
        * cos(radians((float(a["latitude_deg"]) + float(b["latitude_deg"])) / 2.0))
    )
    return (north * north + east * east) ** 0.5 / dt


def offset_m(lat: float, lon: float, north: float, east: float) -> tuple[float, float]:
    dlat = (north / EARTH_MEAN_RADIUS_M) * (180.0 / pi)
    coslat = cos(radians(lat))
    denom = EARTH_MEAN_RADIUS_M * (1e-12 if abs(coslat) < 1e-12 else coslat)
    dlon = (east / denom) * (180.0 / pi)
    nlat = min(90.0, max(-90.0, lat + dlat))
    nlon = ((lon + dlon + 180.0) % 360.0) - 180.0
    return nlat, nlon


def _logistic(value: float) -> float:
    if value > 40.0:
        return 1.0
    if value < -40.0:
        return 0.0
    return 1.0 / (1.0 + exp(-value))


def _nearest_rank(values: Sequence[float], probability: float) -> float:
    ordered = sorted(values)
    if probability == 0:
        return ordered[0]
    rank = max(1, int(probability * len(ordered) + 0.999999999))
    return ordered[min(rank - 1, len(ordered) - 1)]


def summarize_system(rows: Sequence[BlackoutMetrics]) -> dict[str, float | int]:
    errors = [row.endpoint_error_m for row in rows]
    paths = [row.truth_path_length_m for row in rows]
    ratios = [row.drift_ratio for row in rows if row.drift_ratio is not None]
    out: dict[str, float | int] = {
        "interval_count": len(rows),
        "endpoint_p50_m": median(errors),
        "endpoint_p90_m": _nearest_rank(errors, 0.90),
        "path_length_p50_m": median(paths),
        "path_length_p90_m": _nearest_rank(paths, 0.90),
        "ratio_eligible": len(ratios),
    }
    if ratios:
        out["drift_ratio_p50"] = median(ratios)
        out["drift_ratio_p90"] = _nearest_rank(ratios, 0.90)
    return out


def _coast(
    history: Sequence[dict],
    blackout_rows: Sequence[dict],
    speed_at: Callable[[dict, float], float],
) -> list[tuple[float, float]]:
    pair = _positive_dt_pair(history)
    if pair is None:
        raise ValueError("need two GNSS fixes with positive dt before blackout")
    earlier, later = pair
    lat = float(later["latitude_deg"])
    lon = float(later["longitude_deg"])
    heading = seed_heading_rad(history)
    if heading is None:
        raise ValueError("cannot seed heading: no 10 m GNSS motion and no GPS orientation")
    speed0 = last_speed_mps(earlier, later)
    last_t = int(later["timestamp_ns"])
    out: list[tuple[float, float]] = []
    for row in blackout_rows:
        now = int(row["timestamp_ns"])
        dt = (now - last_t) / 1_000_000_000.0
        if dt < 0.0:
            raise ValueError("blackout timestamps must be non-decreasing")
        yaw = row.get("gyro_vertical_radps", row.get("gx"))
        if yaw is not None and dt > 0.0:
            heading += float(yaw) * dt
        speed = max(0.0, speed_at(row, speed0))
        east = speed * sin(heading) * dt
        north = speed * cos(heading) * dt
        lat, lon = offset_m(lat, lon, north, east)
        out.append((lat, lon))
        last_t = now
    return out


def student_speed_fn(
    student: LinearMotionStudent,
    imu_records: Sequence[dict],
) -> Callable[[dict, float], float]:
    samples = records_to_imu_samples(imu_records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}

    def speed_at(row: dict, _prior: float) -> float:
        stamp = int(row["timestamp_ns"])
        index = by_t.get(stamp)
        if index is None:
            return 0.0
        window = trim_causal_window(samples[: index + 1], stamp)
        if len(window) < MIN_SAMPLES:
            return 0.0
        features = extract_causal_imu_features(window)
        if set(FEATURE_NAMES) & GNSS_KEYS:
            raise RuntimeError("IMU features leaked GNSS keys")
        speed, stop_logit, _log_var = student.infer(features.vector)
        if _logistic(stop_logit) >= STOP_ZUPT:
            return 0.0
        return speed

    return speed_at


def _subsample(points: Sequence[tuple[float, float]], keep: Sequence[bool]) -> list[tuple[float, float]]:
    return [point for point, flag in zip(points, keep) if flag]


def score_interval(
    records: Sequence[dict],
    student: LinearMotionStudent,
    interval: BlackoutInterval,
    *,
    gate: TruthGateConfig | None = None,
) -> dict[str, BlackoutMetrics] | None:
    records = _time_ordered(records)
    history = _gnss_history(records, interval.start_ns)
    if _positive_dt_pair(history) is None:
        return None
    if seed_heading_rad(history) is None:
        return None
    masked = mask_gnss_records(records, [interval])
    assert_no_gnss_leakage(masked)
    blackout_rows = [row for row in masked if row.get("gnss_masked")]
    if len(blackout_rows) < 8:
        return None
    epochs = set(score_epochs(records, interval.start_ns, interval.end_ns))
    if len(epochs) < 2:
        return None
    keep = [int(row["timestamp_ns"]) in epochs for row in blackout_rows]
    truth: list[tuple[float, float]] = []
    original_by_t = {int(row["timestamp_ns"]): row for row in records}
    for row, flag in zip(blackout_rows, keep):
        src = original_by_t[int(row["timestamp_ns"])]
        leaked = GNSS_KEYS.intersection(row.keys())
        if leaked:
            raise AssertionError(f"blackout IMU row still has GNSS keys: {sorted(leaked)}")
        if not flag:
            continue
        if "latitude_deg" not in src or "longitude_deg" not in src:
            return None
        truth.append((float(src["latitude_deg"]), float(src["longitude_deg"])))
    duration = (interval.end_ns - interval.start_ns) / 1_000_000_000.0
    coverage = 1.0
    checked = assess_truth(truth, duration_s=duration, coverage=coverage, config=gate)
    if not checked.accepted:
        return None
    horizon = [int(row["timestamp_ns"]) for row in blackout_rows]
    last_fix = (float(history[-1]["latitude_deg"]), float(history[-1]["longitude_deg"]))
    freeze = _subsample(freeze_baseline(last_fix, len(horizon)), keep)
    pair = _positive_dt_pair(history)
    assert pair is not None
    cv_hist = [
        (float(pair[0]["latitude_deg"]), float(pair[0]["longitude_deg"]), int(pair[0]["timestamp_ns"])),
        (float(pair[1]["latitude_deg"]), float(pair[1]["longitude_deg"]), int(pair[1]["timestamp_ns"])),
    ]
    cv = _subsample(constant_velocity_baseline(cv_hist, horizon), keep)
    filter_only = _subsample(_coast(history, blackout_rows, lambda _row, prior: prior), keep)
    speed_student = _subsample(
        _coast(history, blackout_rows, student_speed_fn(student, masked)),
        keep,
    )
    return {
        "freeze": evaluate_blackout(freeze, truth),
        "cv": evaluate_blackout(cv, truth),
        "filter_only": evaluate_blackout(filter_only, truth),
        "speed_student": evaluate_blackout(speed_student, truth),
    }


def score_trip(
    records: Sequence[dict],
    student: LinearMotionStudent,
    *,
    gate: TruthGateConfig | None = None,
) -> dict[str, BlackoutMetrics] | None:
    records = _time_ordered(records)
    interval = choose_blackout(records)
    if interval is None:
        return None
    return score_interval(records, student, interval, gate=gate)


def maybe_retrain(
    trips: dict[str, list[dict]],
    current: LinearMotionStudent,
    out_dir: Path,
    *,
    seed: str = SEED,
    report_path: Path | None = None,
) -> dict:
    """Refit only when the current file does not already beat freeze.

    Same-seed ridge on the same windows is deterministic. Packed
    `linear.json` already beats freeze on the committed train report.
    """

    existing = _committed_beats_freeze(report_path)
    if existing:
        return {
            "retrained": False,
            "reason": (
                "linear.json already beats freeze on held-out speed MAE. "
                "Same-seed ridge will not change it."
            ),
            "held_out_mae": existing,
        }
    if not trips:
        return {"retrained": False, "reason": "no IMU trips for a refit"}
    fitted = train_from_trips(trips, seed=seed)
    new: LinearMotionStudent = fitted["student"]
    report = fitted["report"]
    old_mae = _held_out_speed_mae(current, trips, seed)
    new_mae = {
        split: float(metrics["linear_speed_mae"])
        for split, metrics in report["splits"].items()
    }
    compared = sorted(set(old_mae) & set(new_mae) & EVAL_SPLITS)
    if not compared:
        return {"retrained": False, "reason": "no held-out split overlap"}
    old_mean = mean(old_mae[name] for name in compared)
    new_mean = mean(new_mae[name] for name in compared)
    freeze_mae = {
        split: float(metrics["freeze_speed_mae"])
        for split, metrics in report["splits"].items()
        if split in compared
    }
    beats_freeze = all(new_mae[name] < freeze_mae[name] for name in compared if name in freeze_mae)
    no_regression = all(new_mae[name] <= old_mae[name] * 1.01 for name in compared)
    improved = beats_freeze and no_regression and new_mean < old_mean
    if improved:
        save_linear_student(new, out_dir / "linear.json")
        (out_dir / "train_report.json").write_text(json.dumps(report, indent=2) + "\n")
    return {
        "retrained": improved,
        "old_held_out_mae": old_mae,
        "new_held_out_mae": new_mae,
        "beats_freeze": beats_freeze,
    }


def _committed_beats_freeze(report_path: Path | None) -> dict[str, dict[str, float]] | None:
    if report_path is None or not report_path.is_file():
        return None
    payload = json.loads(report_path.read_text())
    if payload.get("speed_unit") != "m/s":
        return None
    splits = payload.get("held_out_metrics") or payload.get("splits") or {}
    held: dict[str, dict[str, float]] = {}
    for name, metrics in splits.items():
        if name not in EVAL_SPLITS:
            continue
        linear = metrics.get("linear_speed_mae")
        freeze = metrics.get("freeze_speed_mae")
        if linear is None or freeze is None:
            return None
        if float(linear) >= float(freeze):
            return None
        held[name] = {
            "linear_speed_mae": float(linear),
            "freeze_speed_mae": float(freeze),
        }
    return held or None


def _held_out_speed_mae(
    student: LinearMotionStudent,
    trips: dict[str, list[dict]],
    seed: str,
) -> dict[str, float]:
    assignments = {row.trip_id: row.split for row in assign_grouped_trip_splits(sorted(trips), seed=seed)}
    grouped: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for trip_id, records in trips.items():
        split = assignments[trip_id]
        if split not in EVAL_SPLITS:
            continue
        for vector, speed, _stopped, _seq, _heur in labeled_windows(records):
            grouped[split].append((student.infer(vector)[0], speed))
    return {split: mae([a for a, _ in rows], [b for _, b in rows]) for split, rows in grouped.items() if rows}


def write_markdown(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    skipped = payload.get("skipped")
    lines = [
        "# IO-VNBD blackout eval",
        "",
        "Leak-free GNSS mask via `driftzero_ml.blackout`. Score-only truth after inference.",
        "No TimesFM. No ECU wheel speed. `linear_dp.json` is not scored. χ² 11.345 stays on Δp.",
        "Heading is seeded from the last 10 m of unique GNSS motion, or GPS ORIENTATION.",
        "Intervals start and end on unique GNSS fixes. Scoring uses those epochs only.",
        "GPS SPEED (Kmh) is treated as m/s after a unique-fix finite-difference check.",
        "Splits are session-grouped. The 2026-09-02 table used a 3.6x-small speed label and a due-north heading seed. Those numbers are invalid.",
        "See `results/io_vnbd_screening_v1/summary.md` for the screening bundle.",
        "",
    ]
    if skipped:
        lines.append(f"Skipped. {payload['reason']}")
        path.write_text("\n".join(lines) + "\n")
        return
    lines.append(f"Screening tables: {payload['table_count']} unique categorised `S-*.csv` (need {REQUIRED_SCREENING}+).")
    lines.append(f"Intervals scored: {payload['interval_count']}. Seed `{payload['seed']}`.")
    lines.append("Held-out splits only: validation, public_test, locked_test. Session-grouped.")
    lines.append("Filter-only is last GNSS speed plus gravity-aligned vertical gyro. Not a Kotlin ESKF dump.")
    if payload.get("exclusions"):
        lines.append(f"Truth-gate exclusions: {len(payload['exclusions'])}.")
    lines.append(f"Retrain wrote new weights: {payload['retrain']['retrained']}.")
    lines.append("")
    lines.append("| System | endpoint p50 (m) | endpoint p90 (m) | path length p50 (m) | path length p90 (m) | drift ratio p50 | drift ratio p90 |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    for name in SYSTEMS:
        row = payload["systems"][name]
        def fmt(key: str) -> str:
            if key not in row:
                return "n/a"
            value = row[key]
            if key.startswith("drift"):
                return f"{float(value):.4f}"
            return f"{float(value):.2f}"
        lines.append(
            f"| {name} | {fmt('endpoint_p50_m')} | {fmt('endpoint_p90_m')} | "
            f"{fmt('path_length_p50_m')} | {fmt('path_length_p90_m')} | "
            f"{fmt('drift_ratio_p50')} | {fmt('drift_ratio_p90')} |"
        )
    lines.append("")
    lines.append("Drift ratio is endpoint error divided by truth path length when that length is at least 1 m.")
    path.write_text("\n".join(lines) + "\n")


def run(repo: Path) -> dict:
    root = repo / "data" / "raw" / "io_vnbd"
    weights = repo / "models" / "motion_student_v1" / "linear.json"
    try:
        tables = screening_csv_paths(root)
    except (IOVNBDMissing, DatasetLfsMissing, DatasetMissing) as error:
        return {"skipped": True, "reason": str(error)}
    if len(tables) < REQUIRED_SCREENING:
        return {
            "skipped": True,
            "reason": (
                f"need {REQUIRED_SCREENING} unique categorised S-*.csv under {root}, "
                f"found {len(tables)}"
            ),
            "table_count": len(tables),
        }
    if not weights.is_file():
        return {"skipped": True, "reason": f"missing {weights}"}
    student = load_linear_student(weights)
    eval_trips: dict[str, list[dict]] = {}
    assignments = {
        row.trip_id: row.split
        for row in assign_grouped_trip_splits([path.stem for path in tables], seed=SEED)
    }
    retrain = maybe_retrain(
        {},
        student,
        weights.parent,
        seed=SEED,
        report_path=weights.parent / "train_report.json",
    )
    if retrain.get("retrained"):
        student = load_linear_student(weights)
    alignments: dict[str, dict] = {}
    for path in tables:
        if assignments[path.stem] not in EVAL_SPLITS:
            continue
        rows = load_smartphone_csv(path)
        records = [eval_record(item) for item in rows]
        aligned = alignment_from_records(records, path.stem)
        if aligned is not None:
            records = attach_alignment(records, aligned)
            alignments[path.stem] = aligned.to_dict()
        eval_trips[path.stem] = records
    by_system: dict[str, list[BlackoutMetrics]] = {name: [] for name in SYSTEMS}
    interval_ids: list[str] = []
    exclusions: list[dict] = []
    scored = 0
    gates = {
        "mid": TruthGateConfig(),
        "d50": TruthGateConfig(min_path_length_m=40.0, min_unique_fixes=3),
        "d1000": TruthGateConfig(min_path_length_m=800.0, min_unique_fixes=8),
    }
    for trip_id, records in sorted(eval_trips.items()):
        candidates = []
        mid = choose_blackout(records)
        if mid is not None:
            candidates.append((BlackoutInterval(mid.start_ns, mid.end_ns, f"{trip_id}:mid"), "mid"))
        d50 = choose_distance_blackout(
            records, target_m=50.0, start_frac=0.40, interval_id=f"{trip_id}:d50", max_ns=30_000_000_000
        )
        d1000 = choose_distance_blackout(
            records, target_m=1000.0, start_frac=0.20, interval_id=f"{trip_id}:d1000", max_ns=180_000_000_000
        )
        if d50 is not None:
            candidates.append((d50, "d50"))
        if d1000 is not None:
            candidates.append((d1000, "d1000"))
        if not candidates:
            exclusions.append({"trip_id": trip_id, "reason": "no fresh-fix blackout"})
            continue
        for interval, suite in candidates:
            interval_ids.append(interval.interval_id)
            try:
                metrics = score_interval(records, student, interval, gate=gates[suite])
            except ValueError as error:
                exclusions.append({"interval_id": interval.interval_id, "reason": str(error)})
                continue
            if metrics is None:
                exclusions.append({"interval_id": interval.interval_id, "reason": "truth gate or missing heading"})
                continue
            scored += 1
            for name in SYSTEMS:
                by_system[name].append(metrics[name])
    if scored == 0:
        return {
            "skipped": True,
            "reason": "no held-out trip had a usable blackout interval",
            "table_count": len(tables),
            "retrain": retrain,
            "exclusions": exclusions,
        }
    payload = {
        "skipped": False,
        "seed": SEED,
        "split": "session_grouped",
        "table_count": len(tables),
        "interval_count": scored,
        "blackout_interval_ids": interval_ids,
        "exclusions": exclusions,
        "alignments": alignments,
        "retrain": retrain,
        "systems": {name: summarize_system(rows) for name, rows in by_system.items()},
    }
    return payload


def write_blackout_interval_ids(manifest: Path, ids: Sequence[str]) -> None:
    """Replace `blackout_interval_ids: []` with the locked list. Fixed-rule IDs only."""

    text = manifest.read_text()
    block = "blackout_interval_ids:\n" + "".join(f"  - {item}\n" for item in ids)
    if "blackout_interval_ids: []\n" not in text:
        raise ValueError(f"{manifest} does not have an empty blackout_interval_ids list")
    manifest.write_text(text.replace("blackout_interval_ids: []\n", block, 1))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="IO-VNBD leak-free blackout eval")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("results/io_vnbd_blackout_eval.md"))
    args = parser.parse_args(argv)
    payload = run(args.repo.resolve())
    write_markdown(args.out, payload)
    print(json.dumps({k: v for k, v in payload.items() if k != "systems"}, indent=2))
    if not payload.get("skipped"):
        print(json.dumps(payload["systems"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
