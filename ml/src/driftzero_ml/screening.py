"""IO-VNBD screening bundle: locked blackouts, plots, and held-out tables."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
from collections import defaultdict
from math import atan2, cos, exp, pi, radians, sin
from pathlib import Path
from statistics import mean, median
from typing import Callable, Sequence

from driftzero_ml.baselines import constant_velocity_baseline, freeze_baseline, persist_course_baseline
from driftzero_ml.blackout import GNSS_KEYS, BlackoutInterval, assert_no_gnss_leakage, mask_gnss_records
from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.datasets.io_vnbd import load_smartphone_csv
from driftzero_ml.eval_iovnbd_blackout import (
    EVAL_SPLITS,
    SEED,
    _gnss_history,
    _positive_dt_pair,
    _time_ordered,
    alignment_from_records,
    attach_alignment,
    choose_blackout,
    choose_distance_blackout,
    course_rad,
    eval_record,
    last_speed_mps,
    offset_m,
    screening_csv_paths,
    student_speed_fn,
    write_blackout_interval_ids,
)
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    MIN_SAMPLES,
    STOP_ZUPT,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.gnss_truth import TruthGateConfig, assess_truth, score_epochs, seed_heading_rad
from driftzero_ml.io_vnbd import IOVNBDMissing, assign_grouped_trip_splits
from driftzero_ml.learned_imu import hacf_sequence
from driftzero_ml.metrics import (
    EARTH_MEAN_RADIUS_M,
    BlackoutMetrics,
    circular_mae_rad,
    evaluate_blackout,
    path_heading_rad,
    path_length_m,
    wrap_heading_error_rad,
)
from driftzero_ml.student.gru_runtime import CausalGruStudent, load_gru_student
from driftzero_ml.student.heads import zupt_accel_infer
from driftzero_ml.student.linear import LinearMotionStudent, load_linear_student

SYSTEMS = (
    "freeze",
    "persist",
    "cv",
    "filter_only",
    "zupt_accel",
    "linear",
    "gru",
    "gru_bump",
)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _git_hash(repo: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repo, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


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


def locked_blackouts(records: Sequence[dict], trip_id: str) -> list[BlackoutInterval]:
    """Mid 20 s plus official 50 m and 1 km checks. Suites may overlap in time."""

    out: list[BlackoutInterval] = []
    mid = choose_blackout(records)
    if mid is not None:
        out.append(BlackoutInterval(mid.start_ns, mid.end_ns, f"{trip_id}:mid"))
    short = choose_distance_blackout(
        records, target_m=50.0, start_frac=0.40, interval_id=f"{trip_id}:d50", max_ns=30_000_000_000
    )
    long = choose_distance_blackout(
        records, target_m=1000.0, start_frac=0.20, interval_id=f"{trip_id}:d1000", max_ns=180_000_000_000
    )
    for extra in (short, long):
        if extra is not None:
            out.append(extra)
    return out


def zupt_speed_fn(imu_records: Sequence[dict]) -> Callable[[dict, float], float]:
    samples = records_to_imu_samples(imu_records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}

    def speed_at(row: dict, prior: float) -> float:
        index = by_t.get(int(row["timestamp_ns"]))
        if index is None:
            return prior
        window = trim_causal_window(samples[: index + 1], int(row["timestamp_ns"]))
        if len(window) < MIN_SAMPLES:
            return prior
        heads = zupt_accel_infer(window)
        if heads.idle or heads.stop_probability >= STOP_ZUPT:
            return 0.0
        return heads.forward_speed_mps

    return speed_at


def gru_speed_fn(
    student: CausalGruStudent,
    imu_records: Sequence[dict],
    *,
    gate_bump: bool,
) -> Callable[[dict, float], float]:
    samples = records_to_imu_samples(imu_records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}

    def speed_at(row: dict, prior: float) -> float:
        index = by_t.get(int(row["timestamp_ns"]))
        if index is None:
            return prior
        window = trim_causal_window(samples[: index + 1], int(row["timestamp_ns"]))
        if len(window) < MIN_SAMPLES:
            return prior
        feats = extract_causal_imu_features(window)
        pred = student.infer(hacf_sequence(window), bump=feats.bump, gate_bump=gate_bump)
        if gate_bump and feats.bump:
            return prior
        if _logistic(pred.stop_logit) >= STOP_ZUPT:
            return 0.0
        return pred.forward_speed_mps

    return speed_at


def gru_yaw_fn(student: CausalGruStudent, imu_records: Sequence[dict]) -> Callable[[dict], float | None]:
    samples = records_to_imu_samples(imu_records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}

    def yaw_at(row: dict) -> float | None:
        index = by_t.get(int(row["timestamp_ns"]))
        if index is None:
            return row.get("gx")
        window = trim_causal_window(samples[: index + 1], int(row["timestamp_ns"]))
        if len(window) < MIN_SAMPLES:
            return row.get("gx")
        return student.infer(hacf_sequence(window)).yaw_rate_radps

    return yaw_at


def _coast(
    history: Sequence[dict],
    blackout_rows: Sequence[dict],
    speed_at: Callable[[dict, float], float],
    yaw_at: Callable[[dict], float | None] | None = None,
) -> tuple[list[tuple[float, float]], list[float], list[float]]:
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
    points: list[tuple[float, float]] = []
    speeds: list[float] = []
    headings: list[float] = []
    for row in blackout_rows:
        now = int(row["timestamp_ns"])
        dt = (now - last_t) / 1_000_000_000.0
        if dt < 0.0:
            raise ValueError("blackout timestamps must be non-decreasing")
        yaw = yaw_at(row) if yaw_at is not None else row.get("gyro_vertical_radps", row.get("gx"))
        if yaw is not None and dt > 0.0:
            heading += float(yaw) * dt
        speed = max(0.0, speed_at(row, speed0))
        east = speed * sin(heading) * dt
        north = speed * cos(heading) * dt
        lat, lon = offset_m(lat, lon, north, east)
        points.append((lat, lon))
        speeds.append(speed)
        headings.append(heading % (2.0 * pi))
        last_t = now
        speed0 = speed
    return points, speeds, headings


def _truth_motion(original_by_t: dict[int, dict], blackout_rows: Sequence[dict]) -> tuple[list[float], list[float]]:
    speeds: list[float] = []
    headings: list[float] = []
    last = None
    for row in blackout_rows:
        src = original_by_t[int(row["timestamp_ns"])]
        if "gnss_speed_mps" in src and src["gnss_speed_mps"] is not None:
            speeds.append(float(src["gnss_speed_mps"]))
        elif last is not None:
            dt = (int(src["timestamp_ns"]) - int(last["timestamp_ns"])) / 1_000_000_000.0
            if dt > 0 and "latitude_deg" in src and "latitude_deg" in last:
                north = radians(float(src["latitude_deg"]) - float(last["latitude_deg"])) * EARTH_MEAN_RADIUS_M
                east = (
                    radians(float(src["longitude_deg"]) - float(last["longitude_deg"]))
                    * EARTH_MEAN_RADIUS_M
                    * cos(radians((float(last["latitude_deg"]) + float(src["latitude_deg"])) / 2.0))
                )
                speeds.append((north * north + east * east) ** 0.5 / dt)
            else:
                speeds.append(0.0)
        else:
            speeds.append(0.0)
        if last is not None and "latitude_deg" in src and "latitude_deg" in last:
            headings.append(course_rad(last, src) % (2.0 * pi))
        else:
            headings.append(0.0)
        last = src
    return speeds, headings


def score_interval(
    records: Sequence[dict],
    interval: BlackoutInterval,
    linear: LinearMotionStudent,
    gru: CausalGruStudent | None,
) -> dict | None:
    records = _time_ordered(records)
    history = _gnss_history(records, interval.start_ns)
    if _positive_dt_pair(history) is None:
        return None
    masked = mask_gnss_records(records, [interval])
    assert_no_gnss_leakage(masked)
    blackout_rows = [row for row in masked if row.get("gnss_masked")]
    if len(blackout_rows) < 8:
        return None
    original_by_t = {int(row["timestamp_ns"]): row for row in records}
    truth: list[tuple[float, float]] = []
    for row in blackout_rows:
        src = original_by_t[int(row["timestamp_ns"])]
        if "latitude_deg" not in src or "longitude_deg" not in src:
            return None
        leaked = GNSS_KEYS.intersection(row.keys())
        if leaked:
            raise AssertionError(f"blackout IMU row still has GNSS keys: {sorted(leaked)}")
        truth.append((float(src["latitude_deg"]), float(src["longitude_deg"])))
    horizon = [int(row["timestamp_ns"]) for row in blackout_rows]
    last_fix = (float(history[-1]["latitude_deg"]), float(history[-1]["longitude_deg"]))
    pair = _positive_dt_pair(history)
    assert pair is not None
    heading0 = seed_heading_rad(history)
    if heading0 is None:
        return None
    speed0 = last_speed_mps(pair[0], pair[1])
    dts = []
    last_t = int(pair[1]["timestamp_ns"])
    for stamp in horizon:
        dts.append((stamp - last_t) / 1_000_000_000.0)
        last_t = stamp
    cv_hist = [
        (float(pair[0]["latitude_deg"]), float(pair[0]["longitude_deg"]), int(pair[0]["timestamp_ns"])),
        (float(pair[1]["latitude_deg"]), float(pair[1]["longitude_deg"]), int(pair[1]["timestamp_ns"])),
    ]
    truth_speed, truth_heading = _truth_motion(original_by_t, blackout_rows)
    traces: dict[str, list[tuple[float, float]]] = {
        "freeze": freeze_baseline(last_fix, len(horizon)),
        "persist": persist_course_baseline(last_fix, heading0, speed0, dts),
        "cv": constant_velocity_baseline(cv_hist, horizon),
    }
    traces["filter_only"], fo_speed, fo_head = _coast(history, blackout_rows, lambda _r, prior: prior)
    traces["zupt_accel"], z_speed, z_head = _coast(history, blackout_rows, zupt_speed_fn(masked))
    traces["linear"], lin_speed, lin_head = _coast(history, blackout_rows, student_speed_fn(linear, masked))
    speeds = {
        "freeze": [0.0] * len(horizon),
        "persist": [speed0] * len(horizon),
        "cv": [speed0] * len(horizon),
        "filter_only": fo_speed,
        "zupt_accel": z_speed,
        "linear": lin_speed,
    }
    headings = {
        "freeze": [heading0] * len(horizon),
        "persist": [heading0] * len(horizon),
        "cv": [heading0] * len(horizon),
        "filter_only": fo_head,
        "zupt_accel": z_head,
        "linear": lin_head,
    }
    if gru is not None:
        traces["gru"], g_speed, g_head = _coast(
            history, blackout_rows, gru_speed_fn(gru, masked, gate_bump=False), gru_yaw_fn(gru, masked)
        )
        traces["gru_bump"], gb_speed, gb_head = _coast(
            history, blackout_rows, gru_speed_fn(gru, masked, gate_bump=True)
        )
        speeds["gru"] = g_speed
        speeds["gru_bump"] = gb_speed
        headings["gru"] = g_head
        headings["gru_bump"] = gb_head
    epochs = set(score_epochs(records, interval.start_ns, interval.end_ns))
    keep = [int(row["timestamp_ns"]) in epochs for row in blackout_rows]
    if sum(1 for flag in keep if flag) < 2:
        return None
    truth = [point for point, flag in zip(truth, keep) if flag]
    suite = interval.interval_id.rsplit(":", 1)[-1]
    gate = {
        "mid": TruthGateConfig(),
        "d50": TruthGateConfig(min_path_length_m=40.0, min_unique_fixes=3),
        "d1000": TruthGateConfig(min_path_length_m=800.0, min_unique_fixes=8),
    }.get(suite, TruthGateConfig())
    duration = (interval.end_ns - interval.start_ns) / 1_000_000_000.0
    checked = assess_truth(truth, duration_s=duration, coverage=1.0, config=gate)
    if not checked.accepted:
        return None
    metrics: dict[str, BlackoutMetrics] = {}
    extras: dict[str, dict] = {}
    for name, trace in traces.items():
        kept_trace = [point for point, flag in zip(trace, keep) if flag]
        kept_speed = [value for value, flag in zip(speeds[name], keep) if flag]
        kept_head = [value for value, flag in zip(headings[name], keep) if flag]
        kept_truth_speed = [value for value, flag in zip(truth_speed, keep) if flag]
        kept_truth_head = [value for value, flag in zip(truth_heading, keep) if flag]
        metrics[name] = evaluate_blackout(kept_trace, truth)
        extras[name] = {
            "speed_mae_mps": mean(abs(a - b) for a, b in zip(kept_speed, kept_truth_speed)),
            "heading_mae_rad": circular_mae_rad(kept_head, kept_truth_head),
        }
        traces[name] = kept_trace
    return {
        "interval_id": interval.interval_id,
        "start_ns": interval.start_ns,
        "end_ns": interval.end_ns,
        "truth": truth,
        "traces": traces,
        "metrics": metrics,
        "extras": extras,
        "truth_speed": truth_speed,
    }


def summarize(rows: Sequence[BlackoutMetrics]) -> dict[str, float | int]:
    errors = [row.endpoint_error_m for row in rows]
    paths = [row.truth_path_length_m for row in rows]
    ratios = [row.drift_ratio for row in rows if row.drift_ratio is not None]
    along = [abs(row.along_track_error_m) for row in rows if row.along_track_error_m is not None]
    cross = [abs(row.cross_track_error_m) for row in rows if row.cross_track_error_m is not None]
    out: dict[str, float | int] = {
        "interval_count": len(rows),
        "endpoint_p50_m": median(errors),
        "endpoint_p90_m": _nearest_rank(errors, 0.90),
        "endpoint_p95_m": _nearest_rank(errors, 0.95),
        "endpoint_worst_m": max(errors),
        "path_length_p50_m": median(paths),
        "ratio_eligible": len(ratios),
    }
    if ratios:
        out["drift_ratio_p50"] = median(ratios)
        out["drift_ratio_p90"] = _nearest_rank(ratios, 0.90)
        out["drift_ratio_p95"] = _nearest_rank(ratios, 0.95)
        out["drift_ratio_worst"] = max(ratios)
    if along:
        out["along_abs_p50_m"] = median(along)
        out["cross_abs_p50_m"] = median(cross)
    return out


def _plot_interval(path: Path, scored: dict, title: str) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    truth = scored["truth"]
    fig, ax = plt.subplots(figsize=(8, 8))
    ax.plot([p[1] for p in truth], [p[0] for p in truth], "k-", linewidth=2.0, label="hidden truth, score only")
    styles = {
        "filter_only": ("0.35", "--", "filter-only DR"),
        "linear": ("0.15", ":", "linear student"),
        "gru": ("0.0", "-", "learned GRU"),
        "gru_bump": ("0.0", "-.", "GRU + bump gate"),
    }
    for name, (color, ls, label) in styles.items():
        if name not in scored["traces"]:
            continue
        pts = scored["traces"][name]
        ax.plot([p[1] for p in pts], [p[0] for p in pts], color=color, linestyle=ls, linewidth=1.6, label=label)
    ax.set_xlabel("longitude (deg)")
    ax.set_ylabel("latitude (deg)")
    ax.set_title(title)
    ax.set_aspect("equal", adjustable="datalim")
    ax.legend(frameon=False)
    ax.grid(True, linestyle=":", color="0.8")
    fig.tight_layout()
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=140)
    plt.close(fig)


def _failure_note(scored: dict, records: Sequence[dict]) -> str:
    truth = scored["truth"]
    speeds = [row.get("gnss_speed_mps") for row in records if row.get("gnss_speed_mps") is not None]
    heading = path_heading_rad(truth) if len(truth) >= 2 else 0.0
    turn = 0.0
    if len(truth) >= 3:
        h0 = path_heading_rad(truth[: max(2, len(truth) // 3)])
        turn = abs(wrap_heading_error_rad(heading, h0))
    stop_frac = 0.0
    if speeds:
        stop_frac = mean(1.0 if s < 1.0 else 0.0 for s in speeds)
    bumps = 0
    samples = records_to_imu_samples(records)
    if len(samples) >= MIN_SAMPLES:
        feats = extract_causal_imu_features(samples[-min(len(samples), 40) :])
        bumps = 1 if feats.bump else 0
    bits = []
    if stop_frac > 0.25:
        bits.append(f"stop-go ({stop_frac:.2f} of GNSS samples under 1 m/s)")
    if turn > 1.2:
        bits.append(f"large heading change ({turn:.2f} rad)")
    if bumps:
        bits.append("late-window bump / high vibration")
    path_m = scored["metrics"]["filter_only"].truth_path_length_m
    bits.append(f"truth path {path_m:.1f} m")
    return "; ".join(bits) if bits else "no tagged physical marker beyond endpoint error"


def run(repo: Path, out_dir: Path) -> dict:
    root = repo / "data" / "raw" / "io_vnbd"
    linear_path = repo / "models" / "motion_student_v1" / "linear.json"
    gru_path = repo / "models" / "motion_student_v2" / "gru.json"
    tables = screening_csv_paths(root)
    linear = load_linear_student(linear_path)
    gru = load_gru_student(gru_path) if gru_path.is_file() else None
    assignments = {row.trip_id: row.split for row in assign_grouped_trip_splits([p.stem for p in tables], seed=SEED)}
    per_interval: list[dict] = []
    per_trip: dict[str, dict] = {}
    by_system: dict[str, list[BlackoutMetrics]] = defaultdict(list)
    extras_by_system: dict[str, list[float]] = defaultdict(list)
    heading_by_system: dict[str, list[float]] = defaultdict(list)
    plot_candidates: list[tuple[str, dict]] = []
    locked_ids: list[str] = []
    for path in tables:
        split = assignments[path.stem]
        if split not in EVAL_SPLITS:
            continue
        try:
            records = [eval_record(row) for row in load_smartphone_csv(path)]
        except ValueError:
            continue
        records = _time_ordered(records)
        aligned = alignment_from_records(records, path.stem)
        if aligned is not None:
            records = attach_alignment(records, aligned)
        intervals = locked_blackouts(records, path.stem)
        trip_rows: list[dict] = []
        for interval in intervals:
            locked_ids.append(interval.interval_id)
            try:
                scored = score_interval(records, interval, linear, gru)
            except ValueError:
                continue
            if scored is None:
                continue
            scored["trip_id"] = path.stem
            scored["split"] = split
            per_interval.append(scored)
            trip_rows.append(scored)
            for name, metrics in scored["metrics"].items():
                by_system[name].append(metrics)
                extras_by_system[name].append(scored["extras"][name]["speed_mae_mps"])
                heading_by_system[name].append(scored["extras"][name]["heading_mae_rad"])
            if interval.interval_id.endswith(":mid"):
                plot_candidates.append((path.stem, scored))
        if trip_rows:
            per_trip[path.stem] = {"split": split, "intervals": [row["interval_id"] for row in trip_rows]}
    if not per_interval:
        return {"skipped": True, "reason": "no held-out interval scored"}
    systems = {name: summarize(rows) for name, rows in by_system.items()}
    for name, maes in extras_by_system.items():
        systems[name]["speed_mae_p50"] = median(maes)
        systems[name]["heading_mae_rad_p50"] = median(heading_by_system[name])
    out_dir.mkdir(parents=True, exist_ok=True)
    _write_csvs(out_dir, per_interval, systems)
    plot_dir = out_dir / "plots"
    plot_paths = []
    plot_candidates.sort(key=lambda item: (0 if item[0] == "S-Vta2" else 1, item[0]))
    for trip_id, scored in plot_candidates[:3]:
        dest = plot_dir / f"{trip_id}_{scored['interval_id'].split(':')[-1]}.png"
        _plot_interval(dest, scored, f"{trip_id} mid blackout")
        plot_paths.append(str(dest.relative_to(out_dir)))
    worst = sorted(
        [row for row in per_interval if "gru" in row["metrics"] or "linear" in row["metrics"]],
        key=lambda row: (row["metrics"].get("gru") or row["metrics"]["linear"]).endpoint_error_m,
        reverse=True,
    )[:3]
    failures = []
    for row in worst:
        records = [eval_record(item) for item in load_smartphone_csv(next(p for p in tables if p.stem == row["trip_id"]))]
        failures.append(
            {
                "interval_id": row["interval_id"],
                "trip_id": row["trip_id"],
                "endpoint_gru_m": None if "gru" not in row["metrics"] else row["metrics"]["gru"].endpoint_error_m,
                "endpoint_linear_m": row["metrics"]["linear"].endpoint_error_m,
                "note": _failure_note(row, records),
            }
        )
    manifest = repo / "data" / "manifests" / "io_vnbd_screening_v1.yaml"
    if locked_ids:
        write_blackout_interval_ids(manifest, locked_ids)
    payload = {
        "skipped": False,
        "seed": SEED,
        "git_commit": _git_hash(repo),
        "manifest_sha256": _file_sha256(manifest),
        "interval_count": len(per_interval),
        "trip_count": len(per_trip),
        "systems": systems,
        "plot_paths": plot_paths,
        "failures": failures,
        "blackout_interval_ids": locked_ids,
        "gru_present": gru is not None,
    }
    (out_dir / "metrics_summary.json").write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def _write_csvs(out_dir: Path, per_interval: list[dict], systems: dict) -> None:
    interval_path = out_dir / "metrics_per_interval.csv"
    with interval_path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "interval_id",
                "trip_id",
                "split",
                "system",
                "endpoint_error_m",
                "truth_path_length_m",
                "drift_ratio",
                "along_track_error_m",
                "cross_track_error_m",
                "speed_mae_mps",
                "heading_mae_rad",
            ]
        )
        for row in per_interval:
            for name, metrics in row["metrics"].items():
                writer.writerow(
                    [
                        row["interval_id"],
                        row["trip_id"],
                        row["split"],
                        name,
                        f"{metrics.endpoint_error_m:.6f}",
                        f"{metrics.truth_path_length_m:.6f}",
                        "" if metrics.drift_ratio is None else f"{metrics.drift_ratio:.6f}",
                        "" if metrics.along_track_error_m is None else f"{metrics.along_track_error_m:.6f}",
                        "" if metrics.cross_track_error_m is None else f"{metrics.cross_track_error_m:.6f}",
                        f"{row['extras'][name]['speed_mae_mps']:.6f}",
                        f"{row['extras'][name]['heading_mae_rad']:.6f}",
                    ]
                )
    trip_path = out_dir / "metrics_per_trip.csv"
    by_trip: dict[str, dict[str, list[BlackoutMetrics]]] = defaultdict(lambda: defaultdict(list))
    for row in per_interval:
        for name, metrics in row["metrics"].items():
            by_trip[row["trip_id"]][name].append(metrics)
    with trip_path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["trip_id", "system", "intervals", "endpoint_p50_m", "drift_ratio_p50"])
        for trip_id, systems_rows in sorted(by_trip.items()):
            for name, rows in systems_rows.items():
                ratios = [m.drift_ratio for m in rows if m.drift_ratio is not None]
                writer.writerow(
                    [
                        trip_id,
                        name,
                        len(rows),
                        f"{median([m.endpoint_error_m for m in rows]):.6f}",
                        "" if not ratios else f"{median(ratios):.6f}",
                    ]
                )


def write_summary(path: Path, payload: dict, versions: dict, commands: list[str]) -> None:
    lines = [
        "# IO-VNBD screening v1",
        "",
        "Learned student versus filter-only and other causal baselines on held-out IO-VNBD trips.",
        "GNSS is score-only after the blackout mask. No TimesFM. No vehicle ECU.",
        "",
        "## Reproduce",
        "",
    ]
    for cmd in commands:
        lines.append(f"`{cmd}`")
        lines.append("")
    lines.extend(
        [
            "## Run identity",
            "",
            f"- git commit: `{payload['git_commit']}`",
            f"- seed: `{payload['seed']}`",
            f"- data manifest sha256: `{payload['manifest_sha256']}`",
            f"- intervals: {payload['interval_count']}",
            f"- held-out trips: {payload['trip_count']}",
            f"- python: {versions.get('python')}",
            f"- numpy: {versions.get('numpy')}",
            f"- torch: {versions.get('torch')}",
            f"- matplotlib: {versions.get('matplotlib')}",
            f"- pyyaml: {versions.get('pyyaml')}",
            "",
            "## Held-out table",
            "",
            "| System | drift p50 | drift p90 | drift p95 | drift worst | endpoint p50 m | speed MAE p50 | heading MAE p50 rad |",
            "|---|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for name in SYSTEMS:
        if name not in payload["systems"]:
            continue
        row = payload["systems"][name]

        def fmt(key: str, digits: int = 4) -> str:
            if key not in row:
                return "n/a"
            return f"{float(row[key]):.{digits}f}"

        lines.append(
            f"| {name} | {fmt('drift_ratio_p50')} | {fmt('drift_ratio_p90')} | "
            f"{fmt('drift_ratio_p95')} | {fmt('drift_ratio_worst')} | {fmt('endpoint_p50_m', 2)} | "
            f"{fmt('speed_mae_p50', 3)} | {fmt('heading_mae_rad_p50', 3)} |"
        )
    lines.extend(["", "## Plots", ""])
    for plot in payload.get("plot_paths") or []:
        lines.append(f"- `{plot}`")
    lines.extend(["", "## Worst trajectories", ""])
    for row in payload.get("failures") or []:
        lines.append(
            f"- `{row['interval_id']}`: GRU endpoint {row['endpoint_gru_m']}, "
            f"linear {row['endpoint_linear_m']}. {row['note']}."
        )
    lines.extend(
        [
            "",
            "## SIH gate",
            "",
            "The official aggregate gate is median drift ratio under 0.10. The 5 m / 50 m and 100 m / 1 km figures are scenario checks, not substitutes.",
            "",
        ]
    )
    path.write_text("\n".join(lines) + "\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Write the IO-VNBD screening bundle")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("results/io_vnbd_screening_v1"))
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    try:
        payload = run(repo, args.out)
    except (IOVNBDMissing, DatasetLfsMissing, DatasetMissing) as error:
        print(json.dumps({"skipped": True, "reason": str(error)}))
        return 0
    versions = {"python": __import__("sys").version.split()[0]}
    for name in ("numpy", "torch", "matplotlib", "yaml"):
        try:
            mod = __import__(name)
            versions[name if name != "yaml" else "pyyaml"] = getattr(mod, "__version__", "present")
        except ImportError:
            versions[name if name != "yaml" else "pyyaml"] = "missing"
    commands = [
        "PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.datasets.inventory --repo .",
        "PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.eval_iovnbd_blackout --repo . --out results/io_vnbd_blackout_eval.md",
        "PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.student.train --seed 26168 --out models/motion_student_v1",
        "PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.student.train_gru --repo . --out models/motion_student_v2",
        "PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.screening --repo . --out results/io_vnbd_screening_v1",
    ]
    if not payload.get("skipped"):
        write_summary(args.out / "summary.md", payload, versions, commands)
    print(json.dumps({k: payload[k] for k in payload if k != "systems"}, indent=2))
    if payload.get("systems"):
        print(json.dumps(payload["systems"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
