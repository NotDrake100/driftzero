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
    MAX_SAMPLES,
    MIN_SAMPLES,
    STOP_ZUPT,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.gnss_truth import TruthGateConfig, assess_truth, score_epochs, seed_heading_rad, unique_fix_median_spacing_s
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
from driftzero_ml.curve_speed import (
    estimate_forward_axis,
    linear_curve_speed_fn,
    observe_curve_speeds,
    persist_curve_speed_fn,
)
from driftzero_ml.selfcal_speed import (
    ROLL_WINDOW_NS,
    budget_bucket,
    calibration_budget_s,
    fit_affine_correction,
    fit_selfcal,
    fit_selfcal_precomputed,
    linear_selfcal_speed_fn,
    persist_selfcal_speed_fn,
    vibration_bands,
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
PHYSICS_SYSTEMS = (
    "persist_curve",
    "linear_curve",
    "persist_selfcal",
    "linear_selfcal",
)
GATED_INTERVAL_IDS = frozenset(
    {
        "S-S1:d1000",
        "S-S1:mid",
        "S-S3a:d1000",
        "S-S3b:d1000",
        "S-S3b:mid",
        "S-S3c:mid",
        "S-Vfa01:mid",
        "S-Vta12:mid",
        "S-Vta15:mid",
        "S-Vta17:d1000",
        "S-Vta17:mid",
        "S-Vta1a:d1000",
        "S-Vta1a:d50",
        "S-Vta1a:mid",
        "S-Vta1b:d50",
        "S-Vta1b:mid",
        "S-Vta20:mid",
        "S-Vta22:d1000",
        "S-Vta22:mid",
        "S-Vta24:mid",
        "S-Vta25:mid",
        "S-Vta2:d1000",
        "S-Vta2:d50",
        "S-Vta2:mid",
        "S-Vtb1:mid",
        "S-Vtb6:mid",
        "S-Vtb8:mid",
        "S-Vtb9:mid",
        "S-Vw16a:d1000",
        "S-Vw16a:mid",
        "S-Vw16b:d1000",
        "S-Vw16b:mid",
        "S-Vw5:mid",
        "S-Y1:d1000",
        "S-Y1:mid",
    }
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
    *,
    physics: bool = False,
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
    physics_diag: dict | None = None
    if physics:
        physics_diag = _attach_physics(
            records,
            interval,
            history,
            blackout_rows,
            masked,
            linear,
            traces,
            speeds,
            headings,
        )
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
    payload = {
        "interval_id": interval.interval_id,
        "start_ns": interval.start_ns,
        "end_ns": interval.end_ns,
        "truth": truth,
        "traces": traces,
        "metrics": metrics,
        "extras": extras,
        "truth_speed": truth_speed,
    }
    if physics_diag is not None:
        payload["physics"] = physics_diag
    return payload


def _student_speed_var(
    student: LinearMotionStudent,
    imu_records: Sequence[dict],
) -> Callable[[dict], tuple[float, float, bool] | None]:
    samples = records_to_imu_samples(imu_records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}

    def infer(row: dict) -> tuple[float, float, bool] | None:
        stamp = int(row["timestamp_ns"])
        index = by_t.get(stamp)
        if index is None:
            return None
        window = trim_causal_window(samples[max(0, index + 1 - MAX_SAMPLES) : index + 1], stamp)
        if len(window) < MIN_SAMPLES:
            return None
        features = extract_causal_imu_features(window)
        speed, stop_logit, log_var = student.infer(features.vector)
        return speed, exp(log_var), _logistic(stop_logit) >= STOP_ZUPT

    return infer


def _hold_heading(_row: dict) -> float | None:
    return None


def _attach_physics(
    records: Sequence[dict],
    interval: BlackoutInterval,
    history: Sequence[dict],
    blackout_rows: Sequence[dict],
    masked: Sequence[dict],
    linear: LinearMotionStudent,
    traces: dict,
    speeds: dict,
    headings: dict,
) -> dict:
    """Add the four physics coasts. Forward axis and self-cal freeze at mask start."""

    trip_id = str(records[0].get("trip_id", "unknown")) if records else "unknown"
    alignment = alignment_from_records(records, trip_id)
    pre = [row for row in records if int(row["timestamp_ns"]) < interval.start_ns]
    forward = estimate_forward_axis(pre, alignment, interval.start_ns)
    curve_obs = observe_curve_speeds(blackout_rows, alignment, forward)
    valid_n = sum(1 for row in curve_obs if row.valid)
    budget_s = calibration_budget_s(records, interval.start_ns)
    selfcal = fit_selfcal(records, interval.start_ns)
    infer_all = _student_speed_var(linear, records)
    infer_mask = _student_speed_var(linear, masked)

    def student_speed_only(row: dict) -> float | None:
        got = infer_all(row)
        if got is None:
            return None
        speed, _var, stopped = got
        return 0.0 if stopped else speed

    affine = fit_affine_correction(records, interval.start_ns, student_speed_only)
    traces["persist_curve"], pc_speed, pc_head = _coast(
        history, blackout_rows, persist_curve_speed_fn(curve_obs), _hold_heading
    )
    traces["linear_curve"], lc_speed, lc_head = _coast(
        history, blackout_rows, linear_curve_speed_fn(infer_mask, curve_obs)
    )
    traces["persist_selfcal"], ps_speed, ps_head = _coast(
        history, blackout_rows, persist_selfcal_speed_fn(selfcal, masked), _hold_heading
    )
    def student_speed_mask(row: dict) -> float | None:
        got = infer_mask(row)
        if got is None:
            return None
        speed, _var, stopped = got
        return 0.0 if stopped else speed

    traces["linear_selfcal"], ls_speed, ls_head = _coast(
        history, blackout_rows, linear_selfcal_speed_fn(student_speed_mask, affine)
    )
    speeds["persist_curve"] = pc_speed
    speeds["linear_curve"] = lc_speed
    speeds["persist_selfcal"] = ps_speed
    speeds["linear_selfcal"] = ls_speed
    headings["persist_curve"] = pc_head
    headings["linear_curve"] = lc_head
    headings["persist_selfcal"] = ps_head
    headings["linear_selfcal"] = ls_head
    return {
        "forward_source": None if forward is None else forward.source,
        "forward_n": 0 if forward is None else forward.sample_count,
        "curve_valid_n": valid_n,
        "curve_n": len(curve_obs),
        "curve_valid_frac": 0.0 if not curve_obs else valid_n / len(curve_obs),
        "cal_budget_s": budget_s,
        "cal_bucket": budget_bucket(budget_s),
        "selfcal_n": 0 if selfcal is None else selfcal.n_fit,
        "selfcal_span_s": 0.0 if selfcal is None else selfcal.used_span_s,
        "affine_scale": affine.scale,
        "affine_bias": affine.bias,
        "affine_n": affine.n_fit,
        "affine_linear_std": affine.linear_std,
    }


def _precompute_trip(records: Sequence[dict], linear: LinearMotionStudent) -> dict:
    """One causal feature pass per trip. Used by the physics-only runner."""

    samples = records_to_imu_samples(records)
    student_at: dict[int, tuple[float, float, bool]] = {}
    selfcal_at: dict[int, tuple[float, ...]] = {}
    for index, sample in enumerate(samples):
        window = trim_causal_window(samples[max(0, index + 1 - MAX_SAMPLES) : index + 1], sample.timestamp_ns)
        if len(window) < MIN_SAMPLES:
            continue
        feats = extract_causal_imu_features(window)
        selfcal_at[sample.timestamp_ns] = feats.vector + vibration_bands(window)
        speed, stop_logit, log_var = linear.infer(feats.vector)
        student_at[sample.timestamp_ns] = (speed, exp(log_var), _logistic(stop_logit) >= STOP_ZUPT)
    return {"student": student_at, "selfcal": selfcal_at}


def _score_physics_only(
    records: Sequence[dict],
    interval: BlackoutInterval,
    linear: LinearMotionStudent,
    cache: dict,
) -> dict | None:
    """Gate the locked interval, then coast only the four physics systems."""

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
    heading0 = seed_heading_rad(history)
    if heading0 is None:
        return None
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
    trip_id = str(records[0].get("trip_id", "unknown"))
    alignment = alignment_from_records(records, trip_id)
    pre = [row for row in records if int(row["timestamp_ns"]) < interval.start_ns]
    forward = estimate_forward_axis(pre, alignment, interval.start_ns)
    curve_obs = observe_curve_speeds(blackout_rows, alignment, forward)
    valid_n = sum(1 for row in curve_obs if row.valid)
    budget_s = calibration_budget_s(records, interval.start_ns)
    window_start = interval.start_ns - ROLL_WINDOW_NS
    fit_x: list[tuple[float, ...]] = []
    fit_y: list[float] = []
    fit_t: list[int] = []
    for row in records:
        stamp = int(row["timestamp_ns"])
        if stamp >= interval.start_ns or stamp < window_start:
            continue
        if row.get("gnss_speed_mps") is None:
            continue
        vec = cache["selfcal"].get(stamp)
        if vec is None:
            continue
        fit_x.append(vec)
        fit_y.append(max(0.0, float(row["gnss_speed_mps"])))
        fit_t.append(stamp)
    selfcal = fit_selfcal_precomputed(fit_x, fit_y, fit_t)

    def student_speed_only(row: dict) -> float | None:
        got = cache["student"].get(int(row["timestamp_ns"]))
        if got is None:
            return None
        speed, _var, stopped = got
        return 0.0 if stopped else speed

    affine = fit_affine_correction(records, interval.start_ns, student_speed_only)

    def linear_infer(row: dict) -> tuple[float, float, bool] | None:
        return cache["student"].get(int(row["timestamp_ns"]))

    def selfcal_speed(row: dict, prior: float) -> float:
        if selfcal is None:
            return prior
        vec = cache["selfcal"].get(int(row["timestamp_ns"]))
        if vec is None:
            return prior
        return selfcal.infer(vec)

    traces: dict[str, list[tuple[float, float]]] = {}
    speeds: dict[str, list[float]] = {}
    headings: dict[str, list[float]] = {}
    traces["persist_curve"], speeds["persist_curve"], headings["persist_curve"] = _coast(
        history, blackout_rows, persist_curve_speed_fn(curve_obs), _hold_heading
    )
    traces["linear_curve"], speeds["linear_curve"], headings["linear_curve"] = _coast(
        history, blackout_rows, linear_curve_speed_fn(linear_infer, curve_obs)
    )
    traces["persist_selfcal"], speeds["persist_selfcal"], headings["persist_selfcal"] = _coast(
        history, blackout_rows, selfcal_speed, _hold_heading
    )
    traces["linear_selfcal"], speeds["linear_selfcal"], headings["linear_selfcal"] = _coast(
        history, blackout_rows, linear_selfcal_speed_fn(student_speed_only, affine)
    )
    truth_speed, truth_heading = _truth_motion(original_by_t, blackout_rows)
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
        "physics": {
            "forward_source": None if forward is None else forward.source,
            "forward_n": 0 if forward is None else forward.sample_count,
            "curve_valid_n": valid_n,
            "curve_n": len(curve_obs),
            "curve_valid_frac": 0.0 if not curve_obs else valid_n / len(curve_obs),
            "cal_budget_s": budget_s,
            "cal_bucket": budget_bucket(budget_s),
            "selfcal_n": 0 if selfcal is None else selfcal.n_fit,
            "selfcal_span_s": 0.0 if selfcal is None else selfcal.used_span_s,
            "affine_scale": affine.scale,
            "affine_bias": affine.bias,
            "affine_n": affine.n_fit,
            "affine_linear_std": affine.linear_std,
        },
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
    trip_spacing: dict[str, float | None] = {}
    for path in tables:
        split = assignments[path.stem]
        if split not in EVAL_SPLITS:
            continue
        try:
            records = [eval_record(row) for row in load_smartphone_csv(path)]
        except ValueError:
            continue
        records = _time_ordered(records)
        trip_spacing[path.stem] = unique_fix_median_spacing_s(records)
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
    try:
        from driftzero_ml.eval_slices import build_slices_from_screening_dir, write_slices_markdown

        _, markdown = build_slices_from_screening_dir(out_dir, spacing_by_trip=trip_spacing)
        write_slices_markdown(out_dir / "slices.md", markdown)
    except FileNotFoundError:
        pass
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
    scored_ids = [row["interval_id"] for row in per_interval]
    manifest = repo / "data" / "manifests" / "io_vnbd_screening_v1.yaml"
    if manifest.is_file() and "blackout_interval_ids: []\n" in manifest.read_text():
        write_blackout_interval_ids(manifest, sorted(GATED_INTERVAL_IDS))
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
        "blackout_interval_ids": sorted(GATED_INTERVAL_IDS),
        "generated_interval_ids": locked_ids,
        "scored_interval_ids": scored_ids,
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


def _table_row(name: str, row: dict) -> str:
    def fmt(key: str, digits: int = 4) -> str:
        if key not in row:
            return "n/a"
        return f"{float(row[key]):.{digits}f}"

    return (
        f"| {name} | {fmt('drift_ratio_p50')} | {fmt('drift_ratio_p90')} | "
        f"{fmt('drift_ratio_p95')} | {fmt('drift_ratio_worst')} | {fmt('endpoint_p50_m', 2)} | "
        f"{fmt('speed_mae_p50', 3)} | {fmt('heading_mae_rad_p50', 3)} |"
    )


def insert_physics_summary_rows(path: Path, systems: dict) -> None:
    """Insert or replace the four physics rows. Does not rewrite the rest of the file."""

    if not path.is_file():
        raise FileNotFoundError(path)
    text = path.read_text()
    lines = text.splitlines()
    new_rows = [_table_row(name, systems[name]) for name in PHYSICS_SYSTEMS if name in systems]
    if len(new_rows) != len(PHYSICS_SYSTEMS):
        missing = [name for name in PHYSICS_SYSTEMS if name not in systems]
        raise ValueError(f"missing physics systems: {missing}")
    names = set(PHYSICS_SYSTEMS)
    out: list[str] = []
    replaced = 0
    for line in lines:
        key = line.split("|")[1].strip() if line.startswith("| ") and line.count("|") >= 3 else ""
        if key in names:
            out.append(new_rows[PHYSICS_SYSTEMS.index(key)])
            replaced += 1
        else:
            out.append(line)
    if replaced == len(PHYSICS_SYSTEMS):
        path.write_text("\n".join(out) + "\n")
        return
    if replaced:
        raise ValueError(f"{path} has a partial physics table ({replaced} rows)")
    insert_after = None
    for index, line in enumerate(out):
        if line.startswith("| timesfm_coast |"):
            insert_after = index
        elif insert_after is None and line.startswith("| linear |") and not line.startswith("| linear_"):
            insert_after = index
    if insert_after is None:
        raise ValueError(f"{path} has no linear row to insert after")
    out[insert_after + 1 : insert_after + 1] = new_rows
    path.write_text("\n".join(out) + "\n")


def _merge_interval_csv(path: Path, per_interval: list[dict]) -> None:
    keep: list[list[str]] = []
    if path.is_file():
        with path.open(newline="") as handle:
            reader = csv.reader(handle)
            header = next(reader)
            for row in reader:
                if len(row) >= 4 and row[3] in PHYSICS_SYSTEMS:
                    continue
                keep.append(row)
    else:
        header = [
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
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(keep)
        for row in per_interval:
            for name in PHYSICS_SYSTEMS:
                metrics = row["metrics"][name]
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


def _merge_trip_csv(path: Path, per_interval: list[dict]) -> None:
    by_trip: dict[str, dict[str, list[BlackoutMetrics]]] = defaultdict(lambda: defaultdict(list))
    for row in per_interval:
        for name in PHYSICS_SYSTEMS:
            by_trip[row["trip_id"]][name].append(row["metrics"][name])
    keep: list[list[str]] = []
    if path.is_file():
        with path.open(newline="") as handle:
            reader = csv.reader(handle)
            header = next(reader)
            for row in reader:
                if len(row) >= 2 and row[1] in PHYSICS_SYSTEMS:
                    continue
                keep.append(row)
    else:
        header = ["trip_id", "system", "intervals", "endpoint_p50_m", "drift_ratio_p50"]
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(keep)
        for trip_id, systems_rows in sorted(by_trip.items()):
            for name, rows in systems_rows.items():
                ratios = [item.drift_ratio for item in rows if item.drift_ratio is not None]
                writer.writerow(
                    [
                        trip_id,
                        name,
                        len(rows),
                        f"{median([item.endpoint_error_m for item in rows]):.6f}",
                        "" if not ratios else f"{median(ratios):.6f}",
                    ]
                )


def _persist_metrics_from_csv(path: Path) -> dict[str, BlackoutMetrics]:
    out: dict[str, BlackoutMetrics] = {}
    if not path.is_file():
        return out
    with path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            if row.get("system") != "persist":
                continue
            drift = None if not row.get("drift_ratio") else float(row["drift_ratio"])
            out[row["interval_id"]] = BlackoutMetrics(
                endpoint_error_m=float(row["endpoint_error_m"]),
                truth_path_length_m=float(row["truth_path_length_m"]),
                drift_ratio=drift,
                mean_position_error_m=None,
                max_position_error_m=None,
                sample_count=0,
            )
    return out


def run_physics(repo: Path, out_dir: Path) -> dict:
    """Score the four physics systems on the locked 35 gated intervals only."""

    root = repo / "data" / "raw" / "io_vnbd"
    linear_path = repo / "models" / "motion_student_v1" / "linear.json"
    tables = screening_csv_paths(root)
    linear = load_linear_student(linear_path)
    assignments = {row.trip_id: row.split for row in assign_grouped_trip_splits([p.stem for p in tables], seed=SEED)}
    per_interval: list[dict] = []
    extras_by_system: dict[str, list[float]] = defaultdict(list)
    heading_by_system: dict[str, list[float]] = defaultdict(list)
    by_system: dict[str, list[BlackoutMetrics]] = defaultdict(list)
    physics_rows: list[dict] = []
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
        print(f"physics cache {path.stem}", flush=True)
        cache = _precompute_trip(records, linear)
        for interval in locked_blackouts(records, path.stem):
            if interval.interval_id not in GATED_INTERVAL_IDS:
                continue
            try:
                scored = _score_physics_only(records, interval, linear, cache)
            except ValueError:
                continue
            if scored is None:
                continue
            scored["trip_id"] = path.stem
            scored["split"] = split
            per_interval.append(scored)
            physics_rows.append({"interval_id": interval.interval_id, "trip_id": path.stem, **scored.get("physics", {})})
            for name in PHYSICS_SYSTEMS:
                by_system[name].append(scored["metrics"][name])
                extras_by_system[name].append(scored["extras"][name]["speed_mae_mps"])
                heading_by_system[name].append(scored["extras"][name]["heading_mae_rad"])
    if len(per_interval) != len(GATED_INTERVAL_IDS):
        return {
            "skipped": True,
            "reason": f"physics scored {len(per_interval)} intervals, expected {len(GATED_INTERVAL_IDS)}",
            "interval_count": len(per_interval),
        }
    systems = {name: summarize(rows) for name, rows in by_system.items()}
    for name, maes in extras_by_system.items():
        systems[name]["speed_mae_p50"] = median(maes)
        systems[name]["heading_mae_rad_p50"] = median(heading_by_system[name])
        systems[name]["speed_mae_mean"] = mean(maes)
    out_dir.mkdir(parents=True, exist_ok=True)
    _merge_interval_csv(out_dir / "metrics_per_interval.csv", per_interval)
    _merge_trip_csv(out_dir / "metrics_per_trip.csv", per_interval)
    summary_json = out_dir / "metrics_summary.json"
    payload = json.loads(summary_json.read_text()) if summary_json.is_file() else {}
    payload.setdefault("systems", {})
    payload["systems"].update(systems)
    payload["physics_interval_count"] = len(per_interval)
    payload["physics_seed"] = SEED
    summary_json.write_text(json.dumps(payload, indent=2) + "\n")
    (out_dir / "physics_metrics.json").write_text(
        json.dumps(
            {
                "seed": SEED,
                "interval_count": len(per_interval),
                "systems": systems,
                "intervals": physics_rows,
            },
            indent=2,
        )
        + "\n"
    )
    persist_map = _persist_metrics_from_csv(out_dir / "metrics_per_interval.csv")
    for scored in per_interval:
        persist = persist_map.get(scored["interval_id"])
        if persist is not None:
            scored["metrics"]["persist"] = persist
    insert_physics_summary_rows(out_dir / "summary.md", systems)
    # physics_notes.md is written from artifacts plus a separate validation pass.
    # Reloading S-Y1 / S-S1 here would add tens of minutes after scoring.
    return {
        "skipped": False,
        "seed": SEED,
        "interval_count": len(per_interval),
        "systems": systems,
        "per_interval": per_interval,
        "physics_rows": physics_rows,
    }



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
    for name in SYSTEMS + PHYSICS_SYSTEMS:
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
    parser.add_argument(
        "--physics-only",
        action="store_true",
        help="Score persist_curve, linear_curve, persist_selfcal, linear_selfcal on the locked 35 intervals",
    )
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    if args.physics_only:
        payload = run_physics(repo, args.out)
        print(json.dumps({k: payload[k] for k in payload if k not in {"per_interval", "tables", "assignments", "linear", "physics_rows"}}, indent=2))
        if payload.get("systems"):
            print(json.dumps(payload["systems"], indent=2))
        return 0
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
