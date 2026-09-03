"""Desktop TimesFM motion experiment. Not imported by the phone.

Causal and leak-free: GNSS after a blackout start is score-only. TimesFM stays
in this module. Distillation from 3.0 outputs is not attempted (license).
TimesFM 2.5 (Apache-2.0) may write train-only teacher targets.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from math import exp, sqrt
from pathlib import Path
from statistics import mean, median
from typing import Sequence

from driftzero_ml.blackout import (
    GNSS_KEYS,
    BlackoutInterval,
    assert_no_gnss_leakage,
    mask_gnss_records,
)
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
    eval_record,
    screening_csv_paths,
)
from driftzero_ml.features.causal_imu import (
    MIN_SAMPLES,
    STOP_ZUPT,
    causal_gravity_vectors,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.features.phone_align import rotate_vector
from driftzero_ml.io_vnbd import IOVNBDMissing, assign_grouped_trip_splits
from driftzero_ml.learned_imu import hacf_sequence
from driftzero_ml.gnss_truth import score_epochs
from driftzero_ml.metrics import circular_mae_rad, evaluate_blackout
from driftzero_ml.screening import _coast, _truth_motion, locked_blackouts, score_interval, summarize
from driftzero_ml.student.gru_runtime import CausalGruStudent, load_gru_student
from driftzero_ml.student.linear import LinearMotionStudent, load_linear_student
from driftzero_ml.timesfm_adapter import timesfm_is_installed, validate_context
from driftzero_ml.timesfm25 import (
    CHECKPOINT as CHECKPOINT_25,
    CONTEXT_STEPS as CONTEXT_STEPS_25,
    COV_ABS_OMEGA_Z,
    COV_FORWARD_ACCEL,
    COV_STOP_FLAG,
    HORIZON_COVARIATE_POLICY,
    HORIZON_STEPS as HORIZON_STEPS_25,
    LICENSE_WEIGHTS as LICENSE_WEIGHTS_25,
    MODEL_MAX_CONTEXT as MODEL_MAX_CONTEXT_25,
    PACKAGE_VERSION as PACKAGE_VERSION_25,
    Q68_HI_INDEX as Q68_HI_25,
    Q68_LO_INDEX as Q68_LO_25,
    Q80_HI_INDEX as Q80_HI_25,
    Q80_LO_INDEX as Q80_LO_25,
    REVISION as REVISION_25,
    TEACHER_STRIDE,
    TimesFM25Forecaster,
    TimesFM25PreflightError,
    abs_omega_z_series,
    binary_stop_flag,
    pack_causal_speed_covariates,
    preflight_checkpoint,
    slice_quantiles as slice_quantiles_25,
    write_teacher_targets,
)

RATE_HZ = 10
DT_S = 0.1
CONTEXT_STEPS = 640
MODEL_MAX_CONTEXT = 15360
HORIZON_STEPS = 50
HORIZON_SLICES = (10, 20, 50)
PATCH = 32
CHECKPOINT = "google/timesfm-3.0-pytorch"
REVISION = "43046b85ec22d584a13f8098c2ed39c889e129c2"
PACKAGE_VERSION = "3.0.1"
QUANTILES = (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)
# 3.0 emits 0.1..0.9. 0.2..0.8 is the nearest 68% band. 95% is not emitted.
Q68_LO_INDEX = 1
Q68_HI_INDEX = 7
Q80_LO_INDEX = 0
Q80_HI_INDEX = 8
COVARIATE_NAMES = (
    "ax_veh",
    "ay_veh",
    "az_veh",
    "gx_veh",
    "gy_veh",
    "gz_veh",
    "vibration_energy",
    "linear_speed_mps",
    "stop_probability",
    "accepted_gnss_speed_mps",
)
TARGET_NAMES = ("forward_speed_mps", "yaw_rate_radps")
LOCKED_INTERVAL_IDS = (
    "S-S1:mid",
    "S-S1:d1000",
    "S-S3a:d1000",
    "S-S3b:mid",
    "S-S3b:d1000",
    "S-S3c:mid",
    "S-Vfa01:mid",
    "S-Vta12:mid",
    "S-Vta15:mid",
    "S-Vta17:mid",
    "S-Vta17:d1000",
    "S-Vta1a:mid",
    "S-Vta1a:d50",
    "S-Vta1a:d1000",
    "S-Vta1b:mid",
    "S-Vta1b:d50",
    "S-Vta2:mid",
    "S-Vta2:d50",
    "S-Vta2:d1000",
    "S-Vta20:mid",
    "S-Vta22:mid",
    "S-Vta22:d1000",
    "S-Vta24:mid",
    "S-Vta25:mid",
    "S-Vtb1:mid",
    "S-Vtb6:mid",
    "S-Vtb8:mid",
    "S-Vtb9:mid",
    "S-Vw16a:mid",
    "S-Vw16a:d1000",
    "S-Vw16b:mid",
    "S-Vw16b:d1000",
    "S-Vw5:mid",
    "S-Y1:mid",
    "S-Y1:d1000",
)
STOP_GO_SPEED_MPS = 1.0
STOP_GO_FRACTION = 0.25
LOCKED_TRIPS = frozenset(item.split(":")[0] for item in LOCKED_INTERVAL_IDS)

if set(COVARIATE_NAMES) & GNSS_KEYS:
    raise RuntimeError("TimesFM covariates must not use GNSS field names")


def logistic(value: float) -> float:
    if value > 40.0:
        return 1.0
    if value < -40.0:
        return 0.0
    return 1.0 / (1.0 + exp(-value))


def mae(predicted: Sequence[float], target: Sequence[float]) -> float:
    if not predicted or len(predicted) != len(target):
        raise ValueError("mae needs non-empty aligned series")
    return mean(abs(a - b) for a, b in zip(predicted, target))


def rmse(predicted: Sequence[float], target: Sequence[float]) -> float:
    if not predicted or len(predicted) != len(target):
        raise ValueError("rmse needs non-empty aligned series")
    return sqrt(mean((a - b) ** 2 for a, b in zip(predicted, target)))


def picp_interval(truth: Sequence[float], low: Sequence[float], high: Sequence[float]) -> float:
    if not truth or not (len(truth) == len(low) == len(high)):
        raise ValueError("picp needs non-empty aligned series")
    hits = 0
    for value, lo, hi in zip(truth, low, high):
        if lo > hi:
            raise ValueError("interval low must be <= high")
        if lo <= value <= hi:
            hits += 1
    return hits / len(truth)


def mean_width(low: Sequence[float], high: Sequence[float]) -> float:
    if not low or len(low) != len(high):
        raise ValueError("width needs non-empty aligned series")
    return mean(hi - lo for lo, hi in zip(low, high))


def persist_forecast(last: float, steps: int) -> list[float]:
    if steps < 1:
        raise ValueError("steps must be at least 1")
    return [last] * steps


def const_accel_forecast(last_speed: float, accel_mps2: float, steps: int, dt_s: float = DT_S) -> list[float]:
    if steps < 1:
        raise ValueError("steps must be at least 1")
    if dt_s <= 0.0:
        raise ValueError("dt_s must be positive")
    out = []
    speed = last_speed
    for _ in range(steps):
        speed = max(0.0, speed + accel_mps2 * dt_s)
        out.append(speed)
    return out


def hold_after(values: Sequence[float], start_index: int) -> list[float]:
    """Copy values, then hold values[start_index - 1] from start_index onward."""

    if start_index < 0 or start_index > len(values):
        raise ValueError("start_index out of range")
    out = list(values)
    if start_index >= len(out):
        return out
    held = out[start_index - 1] if start_index > 0 else out[0]
    for index in range(start_index, len(out)):
        out[index] = held
    return out


def fill_numeric(values: Sequence[float | None]) -> list[float]:
    """Causal LOCF, then backfill a leading gap with the first real value."""

    out: list[float | None] = list(values)
    last: float | None = None
    for index, value in enumerate(out):
        if value is None:
            out[index] = last
        else:
            last = value
    first = next((value for value in out if value is not None), 0.0)
    return [first if value is None else value for value in out]


def scenario_name(truth_speed: Sequence[float]) -> str:
    if not truth_speed:
        raise ValueError("truth_speed must not be empty")
    stopped = mean(1.0 if value < STOP_GO_SPEED_MPS else 0.0 for value in truth_speed)
    return "stop_go" if stopped >= STOP_GO_FRACTION else "cruising"


def _nearest_rank(values: Sequence[float], probability: float) -> float:
    ordered = sorted(values)
    if probability == 0:
        return ordered[0]
    rank = max(1, int(probability * len(ordered) + 0.999999999))
    return ordered[min(rank - 1, len(ordered) - 1)]


def _git_hash(repo: Path) -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def _device_name() -> str:
    try:
        import torch

        if torch.backends.mps.is_available():
            return "mps"
    except ImportError:
        pass
    return "cpu"


@dataclass
class TripChannels:
    trip_id: str
    split: str
    stamps: tuple[int, ...]
    records: list[dict]
    ax: list[float]
    ay: list[float]
    az: list[float]
    gx: list[float]
    gy: list[float]
    gz: list[float]
    vibration: list[float]
    linear_speed: list[float]
    stop_prob: list[float]
    gnss_speed: list[float]
    yaw_gyro: list[float]
    gru: CausalGruStudent | None

    def index_at(self, timestamp_ns: int) -> int | None:
        stamps = self.stamps
        lo, hi = 0, len(stamps)
        while lo < hi:
            mid = (lo + hi) // 2
            if stamps[mid] < timestamp_ns:
                lo = mid + 1
            else:
                hi = mid
        if lo < len(stamps) and stamps[lo] >= timestamp_ns:
            return lo
        return None

    def gru_at(self, index: int) -> tuple[float, float] | None:
        if self.gru is None:
            return None
        samples = records_to_imu_samples(self.records)
        window = trim_causal_window(samples[: index + 1], self.stamps[index])
        if len(window) < MIN_SAMPLES:
            return None
        feats = extract_causal_imu_features(window)
        pred = self.gru.infer(hacf_sequence(window), bump=feats.bump, gate_bump=False)
        speed = 0.0 if logistic(pred.stop_logit) >= STOP_ZUPT else pred.forward_speed_mps
        return speed, pred.yaw_rate_radps


def _ridge_fit(rows: Sequence[Sequence[float]], targets: Sequence[float], l2: float = 1e-2) -> list[float]:
    width = len(rows[0]) + 1
    gram = [[0.0] * width for _ in range(width)]
    rhs = [0.0] * width
    for row, target in zip(rows, targets):
        extended = [1.0, *row]
        for i in range(width):
            rhs[i] += extended[i] * target
            for j in range(width):
                gram[i][j] += extended[i] * extended[j]
    for i in range(width):
        gram[i][i] += l2
    n = width
    aug = [gram[i][:] + [rhs[i]] for i in range(n)]
    for i in range(n):
        pivot = max(range(i, n), key=lambda row: abs(aug[row][i]))
        aug[i], aug[pivot] = aug[pivot], aug[i]
        diag = aug[i][i]
        if abs(diag) < 1e-12:
            raise ValueError("singular ridge system")
        scale = 1.0 / diag
        for col in range(i, n + 1):
            aug[i][col] *= scale
        for row in range(n):
            if row == i:
                continue
            factor = aug[row][i]
            for col in range(i, n + 1):
                aug[row][col] -= factor * aug[i][col]
    return [aug[i][n] for i in range(n)]


def ridge_predict(weights: Sequence[float], row: Sequence[float]) -> float:
    acc = weights[0]
    for weight, value in zip(weights[1:], row):
        acc += weight * value
    return acc


def _feature_row(channels: TripChannels, index: int, gnss_speed: Sequence[float]) -> list[float]:
    return [
        channels.ax[index],
        channels.ay[index],
        channels.az[index],
        channels.gx[index],
        channels.gy[index],
        channels.gz[index],
        channels.vibration[index],
        channels.linear_speed[index],
        channels.stop_prob[index],
        gnss_speed[index],
        gnss_speed[index],
        channels.yaw_gyro[index],
    ]


def build_trip_channels(
    records: Sequence[dict],
    *,
    trip_id: str,
    split: str,
    linear: LinearMotionStudent,
    gru: CausalGruStudent | None,
) -> TripChannels:
    records = _time_ordered(records)
    samples = records_to_imu_samples(records)
    gravity = causal_gravity_vectors(samples)
    alignment = alignment_from_records(records, trip_id)
    rotation = alignment.rotation if alignment is not None else ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0))
    ax, ay, az, gx, gy, gz, vib, yaw = [], [], [], [], [], [], [], []
    for sample, grav in zip(samples, gravity):
        accel = rotate_vector((sample.ax, sample.ay, sample.az), rotation)
        gyro_in = (
            0.0 if sample.gx is None else sample.gx,
            0.0 if sample.gy is None else sample.gy,
            0.0 if sample.gz is None else sample.gz,
        )
        gyro = rotate_vector(gyro_in, rotation)
        ax.append(accel[0])
        ay.append(accel[1])
        az.append(accel[2])
        gx.append(gyro[0])
        gy.append(gyro[1])
        gz.append(gyro[2])
        dx, dy, dz = sample.ax - grav[0], sample.ay - grav[1], sample.az - grav[2]
        vib.append(sqrt(dx * dx + dy * dy + dz * dz))
        yaw.append(float(records[len(yaw)].get("gyro_vertical_radps", gyro[2])))
    linear_speed: list[float | None] = [None] * len(samples)
    stop_prob: list[float | None] = [None] * len(samples)
    # Train only needs student values at ridge origins. Eval needs every sample
    # as a TimesFM covariate. GRU is evaluated later at forecast origins only.
    stride = 20 if split == "train" else 2
    for index, sample in enumerate(samples):
        if index % stride != 0:
            continue
        window = trim_causal_window(samples[: index + 1], sample.timestamp_ns)
        if len(window) < MIN_SAMPLES:
            continue
        feats = extract_causal_imu_features(window)
        speed, stop_logit, _log_var = linear.infer(feats.vector)
        linear_speed[index] = 0.0 if logistic(stop_logit) >= STOP_ZUPT else speed
        stop_prob[index] = logistic(stop_logit)
    gnss_raw: list[float | None] = []
    for row in records:
        if "gnss_speed_mps" in row and row["gnss_speed_mps"] is not None:
            gnss_raw.append(max(0.0, float(row["gnss_speed_mps"])))
        else:
            gnss_raw.append(None)
    return TripChannels(
        trip_id=trip_id,
        split=split,
        stamps=tuple(int(row["timestamp_ns"]) for row in records),
        records=list(records),
        ax=ax,
        ay=ay,
        az=az,
        gx=gx,
        gy=gy,
        gz=gz,
        vibration=vib,
        linear_speed=fill_numeric(linear_speed),
        stop_prob=fill_numeric(stop_prob),
        gnss_speed=fill_numeric(gnss_raw),
        yaw_gyro=yaw,
        gru=gru,
    )


def context_matrices(
    channels: TripChannels,
    end_index: int,
    *,
    speed_series: Sequence[float],
    gnss_held: Sequence[float],
    context_steps: int = CONTEXT_STEPS,
) -> tuple[list[list[float]], list[list[float]]]:
    """Variate-first target and past-only covariate matrices ending at end_index exclusive."""

    if end_index < 1:
        raise ValueError("need at least one context sample")
    start = max(0, end_index - context_steps)
    sl = slice(start, end_index)
    targets = [list(speed_series[sl]), list(channels.yaw_gyro[sl])]
    cov = [
        list(channels.ax[sl]),
        list(channels.ay[sl]),
        list(channels.az[sl]),
        list(channels.gx[sl]),
        list(channels.gy[sl]),
        list(channels.gz[sl]),
        list(channels.vibration[sl]),
        list(channels.linear_speed[sl]),
        list(channels.stop_prob[sl]),
        list(gnss_held[sl]),
    ]
    validate_context(targets)
    validate_context(cov)
    if set(COVARIATE_NAMES) & GNSS_KEYS:
        raise RuntimeError("covariate names include GNSS keys")
    return targets, cov


class TimesFMForecaster:
    """Thin wrapper. Imported only when the experiment runs."""

    def __init__(self, device: str) -> None:
        if not timesfm_is_installed():
            raise RuntimeError("timesfm is not installed")
        from timesfm3 import ModelConfig, TimesFM3Evaluator

        self._evaluator = TimesFM3Evaluator(
            ModelConfig(
                checkpoint_path=CHECKPOINT,
                revision=REVISION,
                per_core_batch_size=1,
                device=device,
                local_files_only=True,
            )
        )

    def forecast(
        self,
        targets: Sequence[Sequence[float]],
        covariates: Sequence[Sequence[float]],
        horizon: int = HORIZON_STEPS,
    ) -> tuple[tuple[tuple[float, ...], ...], tuple[tuple[tuple[float, ...], ...], ...], float]:
        import numpy as np

        t0 = time.perf_counter()
        outputs = list(
            self._evaluator.predict_batch(
                contexts=[np.asarray(targets, dtype=np.float32)],
                horizon=horizon,
                past_only_covariates=[np.asarray(covariates, dtype=np.float32)],
                return_quantiles=True,
                use_symmetric_averaging=False,
                make_positive=False,
            )
        )
        elapsed = time.perf_counter() - t0
        if not outputs or outputs[0].forecast is None:
            raise RuntimeError("TimesFM returned no forecast")
        point = tuple(tuple(float(x) for x in row) for row in outputs[0].forecast)
        if outputs[0].quantiles is None:
            raise RuntimeError("TimesFM returned no quantiles")
        quantiles = tuple(
            tuple(tuple(float(x) for x in step) for step in variate)
            for variate in outputs[0].quantiles
        )
        return point, quantiles, elapsed


def _slice_quantiles(quantiles: Sequence[Sequence[Sequence[float]]], variate: int, steps: int, lo: int, hi: int) -> tuple[list[float], list[float]]:
    low = [quantiles[variate][step][lo] for step in range(steps)]
    high = [quantiles[variate][step][hi] for step in range(steps)]
    return low, high


def _window_metrics(
    pred: Sequence[float],
    truth: Sequence[float],
    *,
    low68: Sequence[float] | None = None,
    high68: Sequence[float] | None = None,
    low80: Sequence[float] | None = None,
    high80: Sequence[float] | None = None,
    infer_s: float | None = None,
) -> dict[str, float | None]:
    row: dict[str, float | None] = {
        "n": float(len(pred)),
        "mae": mae(pred, truth),
        "rmse": rmse(pred, truth),
        "picp_68": None,
        "width_68": None,
        "picp_95": None,
        "width_95": None,
        "picp_80": None,
        "width_80": None,
        "infer_s": infer_s,
    }
    if low68 is not None and high68 is not None:
        row["picp_68"] = picp_interval(truth, low68, high68)
        row["width_68"] = mean_width(low68, high68)
    if low80 is not None and high80 is not None:
        row["picp_80"] = picp_interval(truth, low80, high80)
        row["width_80"] = mean_width(low80, high80)
    return row


def _baseline_speed(
    name: str,
    channels: TripChannels,
    origin: int,
    steps: int,
    gnss_held: Sequence[float],
    ridge_speed: Sequence[Sequence[float]] | None,
) -> list[float]:
    last = gnss_held[origin - 1]
    if name == "persist":
        return persist_forecast(last, steps)
    if name == "const_accel":
        look = max(0, origin - RATE_HZ)
        dt = max(DT_S, (channels.stamps[origin - 1] - channels.stamps[look]) / 1_000_000_000.0)
        accel = (gnss_held[origin - 1] - gnss_held[look]) / dt
        return const_accel_forecast(last, accel, steps)
    if name == "linear":
        return persist_forecast(channels.linear_speed[origin - 1], steps)
    if name == "gru":
        heads = channels.gru_at(origin - 1)
        if heads is None:
            raise ValueError("gru weights missing")
        return persist_forecast(heads[0], steps)
    if name == "ridge":
        if ridge_speed is None:
            raise ValueError("ridge is not fit")
        feats = _feature_row(channels, origin - 1, gnss_held)
        return [max(0.0, ridge_predict(ridge_speed[step], feats)) for step in range(steps)]
    raise ValueError(name)


def _baseline_yaw(
    name: str,
    channels: TripChannels,
    origin: int,
    steps: int,
    gnss_held: Sequence[float],
    ridge_yaw: Sequence[Sequence[float]] | None,
) -> list[float]:
    last = channels.yaw_gyro[origin - 1]
    if name in {"persist", "const_accel", "linear"}:
        return persist_forecast(last, steps)
    if name == "gru":
        heads = channels.gru_at(origin - 1)
        if heads is None:
            raise ValueError("gru weights missing")
        return persist_forecast(heads[1], steps)
    if name == "ridge":
        if ridge_yaw is None:
            raise ValueError("ridge is not fit")
        feats = _feature_row(channels, origin - 1, gnss_held)
        return [ridge_predict(ridge_yaw[step], feats) for step in range(steps)]
    raise ValueError(name)


def fit_ridge(
    train_channels: Sequence[TripChannels],
    *,
    stride: int = 20,
) -> tuple[list[list[float]], list[list[float]]]:
    rows: list[list[float]] = []
    speed_y: list[list[float]] = [[] for _ in range(HORIZON_STEPS)]
    yaw_y: list[list[float]] = [[] for _ in range(HORIZON_STEPS)]
    for channels in train_channels:
        last = len(channels.stamps) - HORIZON_STEPS
        if last < MIN_SAMPLES:
            continue
        for origin in range(CONTEXT_STEPS, last, stride):
            rows.append(_feature_row(channels, origin - 1, channels.gnss_speed))
            for step in range(HORIZON_STEPS):
                speed_y[step].append(channels.gnss_speed[origin + step])
                yaw_y[step].append(channels.yaw_gyro[origin + step])
    if len(rows) < 8:
        raise ValueError("not enough train windows to fit ridge")
    speed_w = [_ridge_fit(rows, speed_y[step]) for step in range(HORIZON_STEPS)]
    yaw_w = [_ridge_fit(rows, yaw_y[step]) for step in range(HORIZON_STEPS)]
    return speed_w, yaw_w


def roll_timesfm_coast(
    channels: TripChannels,
    interval: BlackoutInterval,
    forecaster: TimesFMForecaster,
) -> dict:
    start = channels.index_at(interval.start_ns)
    end = channels.index_at(interval.end_ns)
    if start is None:
        raise ValueError(f"no sample at or after {interval.start_ns}")
    if end is None:
        end = len(channels.stamps)
    if end - start < 8:
        raise ValueError("blackout shorter than 8 samples")
    masked = mask_gnss_records(channels.records, [interval])
    assert_no_gnss_leakage(masked)
    blackout_rows = [row for row in masked if row.get("gnss_masked")]
    if len(blackout_rows) != end - start:
        raise ValueError(
            f"blackout row count {len(blackout_rows)} != index span {end - start}"
        )
    leaked = GNSS_KEYS.intersection(masked[start].keys()) if start < len(masked) else set()
    if leaked:
        raise AssertionError(f"masked origin still has GNSS keys: {sorted(leaked)}")
    gnss_held = hold_after(channels.gnss_speed, start)
    speed_ctx = list(gnss_held)
    speed_hat = [0.0] * (end - start)
    yaw_hat = [0.0] * (end - start)
    infer_times: list[float] = []
    forecast_rows: list[dict] = []
    original_by_t = {int(row["timestamp_ns"]): row for row in channels.records}
    truth_speed, truth_yaw = _truth_motion(original_by_t, blackout_rows)
    origin = start
    while origin < end:
        targets, cov = context_matrices(channels, origin, speed_series=speed_ctx, gnss_held=gnss_held)
        point, quantiles, elapsed = forecaster.forecast(targets, cov, HORIZON_STEPS)
        infer_times.append(elapsed)
        take = min(HORIZON_STEPS, end - origin)
        for step in range(take):
            speed_ctx[origin + step] = max(0.0, point[0][step])
            speed_hat[origin - start + step] = max(0.0, point[0][step])
            yaw_hat[origin - start + step] = point[1][step]
        remaining = end - origin
        for steps in HORIZON_SLICES:
            if remaining < steps:
                continue
            sl = slice(origin - start, origin - start + steps)
            truth_s = truth_speed[sl]
            truth_y = truth_yaw[sl]
            pred_s = [max(0.0, point[0][k]) for k in range(steps)]
            pred_y = [point[1][k] for k in range(steps)]
            lo68_s, hi68_s = _slice_quantiles(quantiles, 0, steps, Q68_LO_INDEX, Q68_HI_INDEX)
            lo80_s, hi80_s = _slice_quantiles(quantiles, 0, steps, Q80_LO_INDEX, Q80_HI_INDEX)
            lo68_y, hi68_y = _slice_quantiles(quantiles, 1, steps, Q68_LO_INDEX, Q68_HI_INDEX)
            lo80_y, hi80_y = _slice_quantiles(quantiles, 1, steps, Q80_LO_INDEX, Q80_HI_INDEX)
            forecast_rows.append(
                {
                    "interval_id": interval.interval_id,
                    "trip_id": channels.trip_id,
                    "split": channels.split,
                    "scenario": scenario_name(truth_s),
                    "origin_ns": channels.stamps[origin],
                    "horizon_steps": steps,
                    "infer_s": elapsed,
                    "timesfm_speed": pred_s,
                    "timesfm_yaw": pred_y,
                    "truth_speed": truth_s,
                    "truth_yaw": truth_y,
                    "q68_speed": (lo68_s, hi68_s),
                    "q80_speed": (lo80_s, hi80_s),
                    "q68_yaw": (lo68_y, hi68_y),
                    "q80_yaw": (lo80_y, hi80_y),
                    "origin": origin,
                }
            )
        origin += take
    by_t_speed = {channels.stamps[start + i]: speed_hat[i] for i in range(end - start)}
    by_t_yaw = {channels.stamps[start + i]: yaw_hat[i] for i in range(end - start)}

    def speed_at(row: dict, _prior: float) -> float:
        return by_t_speed.get(int(row["timestamp_ns"]), _prior)

    def yaw_at(row: dict) -> float | None:
        stamp = int(row["timestamp_ns"])
        if stamp in by_t_yaw:
            return by_t_yaw[stamp]
        return row.get("gyro_vertical_radps", row.get("gx"))

    return {
        "speed_hat": speed_hat,
        "yaw_hat": yaw_hat,
        "forecast_rows": forecast_rows,
        "infer_times": infer_times,
        "speed_at": speed_at,
        "yaw_at": yaw_at,
        "blackout_rows": blackout_rows,
        "start": start,
        "end": end,
    }


def _plot_forecast(path: Path, stamps_s: Sequence[float], truth: Sequence[float], pred: Sequence[float], ylabel: str, title: str) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(8, 4))
    ax.plot(stamps_s, truth, color="0.1", linewidth=2.0, label="hidden truth, score only")
    ax.plot(stamps_s, pred, color="0.35", linewidth=1.6, linestyle="--", label="TimesFM point")
    ax.set_xlabel("seconds after blackout start")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend(frameon=False)
    ax.grid(True, linestyle=":", color="0.8")
    fig.tight_layout()
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=140)
    plt.close(fig)


def _plot_coast(path: Path, scored: dict, title: str) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    truth = scored["truth"]
    fig, ax = plt.subplots(figsize=(8, 8))
    ax.plot([p[1] for p in truth], [p[0] for p in truth], "k-", linewidth=2.0, label="hidden truth, score only")
    styles = {
        "persist": ("0.45", "--", "persist"),
        "linear": ("0.25", ":", "linear student"),
        "timesfm_coast": ("0.0", "-", "timesfm_coast"),
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


def _write_horizon_csv(path: Path, rows: Sequence[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "split",
        "scenario",
        "horizon_s",
        "horizon_steps",
        "system",
        "target",
        "windows",
        "n",
        "mae",
        "rmse",
        "picp_68",
        "width_68",
        "picp_95",
        "width_95",
        "picp_80",
        "width_80",
        "infer_s",
    ]
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    key: "" if row.get(key) is None else row[key]
                    for key in fields
                }
            )


def _write_screening_csv(
    path: Path,
    systems: dict,
    names: Sequence[str] = ("persist", "linear", "timesfm_coast"),
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "system",
        "interval_count",
        "drift_ratio_p50",
        "drift_ratio_p90",
        "drift_ratio_p95",
        "drift_ratio_worst",
        "endpoint_p50_m",
        "speed_mae_p50",
        "heading_mae_rad_p50",
    ]
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for name in names:
            if name not in systems:
                continue
            row = systems[name]
            writer.writerow({"system": name, **{key: row.get(key, "") for key in fields if key != "system"}})


def _insert_screening_row(summary_path: Path, systems: dict) -> None:
    if "timesfm_coast" not in systems:
        raise ValueError("timesfm_coast summary missing")
    row = systems["timesfm_coast"]

    def fmt(key: str, digits: int) -> str:
        if key not in row:
            return "n/a"
        return f"{float(row[key]):.{digits}f}"

    line = (
        f"| timesfm_coast | {fmt('drift_ratio_p50', 4)} | {fmt('drift_ratio_p90', 4)} | "
        f"{fmt('drift_ratio_p95', 4)} | {fmt('drift_ratio_worst', 4)} | {fmt('endpoint_p50_m', 2)} | "
        f"{fmt('speed_mae_p50', 3)} | {fmt('heading_mae_rad_p50', 3)} |"
    )
    text = summary_path.read_text()
    if "| timesfm_coast |" in text:
        updated = []
        for existing in text.splitlines():
            if existing.startswith("| timesfm_coast |"):
                updated.append(line)
            else:
                updated.append(existing)
        summary_path.write_text("\n".join(updated) + "\n")
        return
    needle = "| linear |"
    lines = text.splitlines()
    out = []
    inserted = False
    for existing in lines:
        out.append(existing)
        if not inserted and existing.startswith(needle):
            out.append(line)
            inserted = True
    if not inserted:
        raise ValueError(f"{summary_path} has no linear row to insert after")
    summary_path.write_text("\n".join(out) + "\n")


def _write_config(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "schema_version: \"1.0.0\"",
        "experiment_id: timesfm3_zero_shot_motion_v1",
        "research_only: true",
        f"run_date: \"{payload['run_date']}\"",
        f"git_commit: \"{payload['git_commit']}\"",
        f"seed: \"{payload['seed']}\"",
        "model:",
        "  provider: google",
        f"  checkpoint: {CHECKPOINT}",
        f"  revision: {REVISION}",
        f"  package: timesfm=={PACKAGE_VERSION}",
        "  runtime: pytorch",
        f"  device: {payload['device']}",
        "  license_weights: timesfm-non-commercial-license-v1.0",
        "forecast:",
        f"  sampling_rate_hz: {RATE_HZ}",
        f"  context_steps: {CONTEXT_STEPS}",
        f"  context_s: {CONTEXT_STEPS / RATE_HZ}",
        f"  model_max_context_steps: {MODEL_MAX_CONTEXT}",
        f"  horizon_steps: [{', '.join(str(x) for x in HORIZON_SLICES)}]",
        "  targets: [forward_speed_mps, yaw_rate_radps]",
        f"  quantiles: [{', '.join(str(x) for x in QUANTILES)}]",
        "  picp_68_band: [0.2, 0.8]",
        "  picp_95: not_emitted_by_timesfm_3_0",
        "distillation: not_attempted",
        "production:",
        "  timesfm_dependency_allowed: false",
        "",
    ]
    path.write_text("\n".join(lines))


def write_timesfm_summary(path: Path, payload: dict) -> None:
    coast = payload["systems"]["timesfm_coast"]
    persist = payload["systems"]["persist"]
    linear = payload["systems"]["linear"]
    decided = float(coast["drift_ratio_p50"])
    persist_p50 = float(persist["drift_ratio_p50"])
    linear_p50 = float(linear["drift_ratio_p50"])
    beats_linear = decided < linear_p50
    beats_persist = decided < persist_p50
    if not beats_persist:
        verdict = "reject"
        why = (
            f"timesfm_coast drift p50 {decided:.4f} is worse than persist {persist_p50:.4f} "
            f"on {payload['interval_count']} gated intervals."
        )
    elif not beats_linear:
        verdict = "reject"
        why = (
            f"timesfm_coast drift p50 {decided:.4f} beats persist {persist_p50:.4f} but not "
            f"linear {linear_p50:.4f}."
        )
    else:
        verdict = "keep_as_desktop_teacher_only"
        why = (
            f"timesfm_coast drift p50 {decided:.4f} beats persist {persist_p50:.4f} and "
            f"linear {linear_p50:.4f}. 3.0 still cannot ship."
        )
    if not beats_persist:
        distillation = (
            "not attempted, reason: timesfm_coast lost to persist on the 35 gated "
            "intervals. 3.0 distillation is also ambiguous under the weight license."
        )
    elif not beats_linear:
        distillation = (
            "not attempted, reason: timesfm_coast did not beat linear. 3.0 "
            "distillation is also ambiguous under the weight license."
        )
    else:
        distillation = (
            "not attempted, reason: 3.0 weight license is ambiguous for a shippable "
            "student. Use TimesFM 2.5 if a teacher step is ever justified."
        )
    lines = [
        "# TimesFM 3 zero-shot IO-VNBD experiment",
        "",
        "Desktop only. GNSS after mask start is score-only. No TimesFM in apps/ or packages/.",
        "",
        "## Commands",
        "",
        "`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m pip install 'timesfm[torch]==3.0.1'`",
        "",
        "`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.timesfm_experiment --repo . --out results/timesfm`",
        "",
        "`PYTHONPATH=ml/src python3 -m unittest discover -s ml/tests -v`",
        "",
        "## Versions",
        "",
        f"- git commit: `{payload['git_commit']}`",
        f"- seed: `{payload['seed']}`",
        f"- timesfm: {PACKAGE_VERSION}",
        f"- checkpoint: `{CHECKPOINT}` revision `{REVISION}`",
        f"- device: {payload['device']}",
        f"- python: {payload['versions']['python']}",
        f"- numpy: {payload['versions']['numpy']}",
        f"- torch: {payload['versions']['torch']}",
        f"- matplotlib: {payload['versions']['matplotlib']}",
        f"- pyyaml: {payload['versions']['pyyaml']}",
        f"- wall time s: {payload['wall_s']:.1f}",
        f"- context: {CONTEXT_STEPS} steps ({CONTEXT_STEPS / RATE_HZ:.0f} s). Model max is {MODEL_MAX_CONTEXT}.",
        f"- horizons: 1 s, 2 s, 5 s. Rolling coast uses {HORIZON_STEPS} steps.",
        f"- intervals: {payload['interval_count']}",
        f"- forecast windows: {payload['forecast_window_count']}",
        "",
        "## License",
        "",
        "3.0 weights are timesfm-non-commercial-license-v1.0. Research desktop use is allowed. "
        "Shipping 3.0 or using it with end users is not. Distillation from 3.0 is treated as ambiguous.",
        "",
        "## timesfm_coast vs persist and linear",
        "",
        f"- persist drift p50: {persist_p50:.4f}, endpoint p50 m: {float(persist['endpoint_p50_m']):.2f}",
        f"- linear drift p50: {linear_p50:.4f}, endpoint p50 m: {float(linear['endpoint_p50_m']):.2f}",
        f"- timesfm_coast drift p50: {decided:.4f}, endpoint p50 m: {float(coast['endpoint_p50_m']):.2f}",
        "",
        "## Distillation",
        "",
        distillation,
        "",
        "## Verdict",
        "",
        f"{verdict}. {why} The deciding number is timesfm_coast drift ratio p50 = {decided:.4f}.",
        "",
        "## Failures",
        "",
    ]
    failures = payload.get("failures") or ["none"]
    for item in failures:
        lines.append(f"- {item}")
    lines.append("")
    path.write_text("\n".join(lines))
    payload["verdict"] = verdict
    payload["deciding_number"] = decided
    payload["distillation"] = distillation


def run(repo: Path, out_dir: Path) -> dict:
    started = time.perf_counter()
    root = repo / "data" / "raw" / "io_vnbd"
    linear = load_linear_student(repo / "models" / "motion_student_v1" / "linear.json")
    gru_path = repo / "models" / "motion_student_v2" / "gru.json"
    gru = load_gru_student(gru_path) if gru_path.is_file() else None
    tables = screening_csv_paths(root)
    assignments = {
        row.trip_id: row.split
        for row in assign_grouped_trip_splits([path.stem for path in tables], seed=SEED)
    }
    device = _device_name()
    print(f"loading TimesFM 3.0 on {device}", flush=True)
    forecaster = TimesFMForecaster(device)
    train_channels: list[TripChannels] = []
    eval_channels: dict[str, TripChannels] = {}
    load_failures: list[str] = []
    for path in tables:
        split = assignments[path.stem]
        if split in EVAL_SPLITS and path.stem not in LOCKED_TRIPS:
            continue
        try:
            records = [eval_record(row) for row in load_smartphone_csv(path)]
        except ValueError as error:
            load_failures.append(f"{path.stem}: {error}")
            continue
        records = _time_ordered(records)
        aligned = alignment_from_records(records, path.stem)
        if aligned is not None:
            records = attach_alignment(records, aligned)
        print(f"channels {path.stem} {split}", flush=True)
        built = build_trip_channels(records, trip_id=path.stem, split=split, linear=linear, gru=gru)
        if split == "train":
            train_channels.append(built)
        if split in EVAL_SPLITS:
            eval_channels[path.stem] = built
    print(f"fitting ridge on {len(train_channels)} train trips", flush=True)
    ridge_speed, ridge_yaw = fit_ridge(train_channels)
    by_system: dict[str, list] = defaultdict(list)
    extras: dict[str, list[float]] = defaultdict(list)
    heading: dict[str, list[float]] = defaultdict(list)
    forecast_rows: list[dict] = []
    scored_intervals: list[dict] = []
    failures: list[str] = []
    wanted = set(LOCKED_INTERVAL_IDS)
    found: set[str] = set()
    plot_source: dict | None = None
    for trip_id, channels in sorted(eval_channels.items()):
        for interval in locked_blackouts(channels.records, trip_id):
            if interval.interval_id not in wanted:
                continue
            print(f"interval {interval.interval_id}", flush=True)
            try:
                scored = score_interval(channels.records, interval, linear, gru)
            except ValueError as error:
                failures.append(f"{interval.interval_id}: score_interval {error}")
                continue
            if scored is None:
                failures.append(f"{interval.interval_id}: score_interval returned none")
                continue
            try:
                rolled = roll_timesfm_coast(channels, interval, forecaster)
            except Exception as error:
                failures.append(f"{interval.interval_id}: timesfm {error}")
                continue
            history = _gnss_history(channels.records, interval.start_ns)
            if _positive_dt_pair(history) is None:
                failures.append(f"{interval.interval_id}: no heading seed")
                continue
            points, speeds, heads = _coast(
                history,
                rolled["blackout_rows"],
                rolled["speed_at"],
                rolled["yaw_at"],
            )
            epochs = set(score_epochs(channels.records, interval.start_ns, interval.end_ns))
            keep = [int(row["timestamp_ns"]) in epochs for row in rolled["blackout_rows"]]
            kept_points = [pt for pt, flag in zip(points, keep) if flag]
            kept_speed = [value for value, flag in zip(speeds, keep) if flag]
            kept_head = [value for value, flag in zip(heads, keep) if flag]
            truth = scored["truth"]
            if len(kept_points) != len(truth):
                failures.append(
                    f"{interval.interval_id}: coast/truth length {len(kept_points)} vs {len(truth)}"
                )
                continue
            metrics = evaluate_blackout(kept_points, truth)
            original_by_t = {int(row["timestamp_ns"]): row for row in channels.records}
            truth_speed, truth_heading = _truth_motion(original_by_t, rolled["blackout_rows"])
            kept_truth_speed = [value for value, flag in zip(truth_speed, keep) if flag]
            kept_truth_head = [value for value, flag in zip(truth_heading, keep) if flag]
            extra = {
                "speed_mae_mps": mean(abs(a - b) for a, b in zip(kept_speed, kept_truth_speed)),
                "heading_mae_rad": circular_mae_rad(kept_head, kept_truth_head),
            }
            scored["traces"]["timesfm_coast"] = kept_points
            scored["metrics"]["timesfm_coast"] = metrics
            scored["extras"]["timesfm_coast"] = extra
            found.add(interval.interval_id)
            scored_intervals.append(scored)
            forecast_rows.extend(rolled["forecast_rows"])
            for name, item in scored["metrics"].items():
                if name not in {"persist", "linear", "timesfm_coast"}:
                    continue
                by_system[name].append(item)
                extras[name].append(scored["extras"][name]["speed_mae_mps"])
                heading[name].append(scored["extras"][name]["heading_mae_rad"])
            if interval.interval_id == "S-Vta2:mid":
                plot_source = {
                    "scored": scored,
                    "rolled": rolled,
                    "channels": channels,
                    "interval": interval,
                    "keep": keep,
                    "truth_speed": truth_speed,
                    "truth_yaw": truth_heading,
                }
    missing = [item for item in LOCKED_INTERVAL_IDS if item not in found]
    if missing:
        failures.append(f"missing locked intervals: {', '.join(missing)}")
    if "timesfm_coast" not in by_system:
        raise RuntimeError("no timesfm_coast interval scored")
    systems = {name: summarize(rows) for name, rows in by_system.items()}
    for name, values in extras.items():
        systems[name]["speed_mae_p50"] = median(values)
        systems[name]["heading_mae_rad_p50"] = median(heading[name])
    # Rebuild forecast-window GNSS hold from each interval start, then baselines.
    cleaned_forecasts = []
    for row in forecast_rows:
        channels = eval_channels[row["trip_id"]]
        interval_start = None
        for interval in locked_blackouts(channels.records, channels.trip_id):
            if interval.interval_id == row["interval_id"]:
                interval_start = channels.index_at(interval.start_ns)
                break
        if interval_start is None:
            continue
        row = dict(row)
        row["_blackout_start"] = interval_start
        cleaned_forecasts.append(row)
    horizon_rows = _aggregate_forecasts_fixed(cleaned_forecasts, ridge_speed, ridge_yaw, eval_channels)
    versions = {"python": sys.version.split()[0]}
    for name, key in (("numpy", "numpy"), ("torch", "torch"), ("matplotlib", "matplotlib"), ("yaml", "pyyaml")):
        try:
            versions[key] = getattr(__import__(name), "__version__", "present")
        except ImportError:
            versions[key] = "missing"
    try:
        import timesfm as timesfm_mod

        versions["timesfm"] = getattr(timesfm_mod, "__version__", PACKAGE_VERSION)
    except ImportError:
        versions["timesfm"] = "missing"
    out_dir.mkdir(parents=True, exist_ok=True)
    _write_horizon_csv(out_dir / "metrics_per_horizon.csv", horizon_rows)
    _write_screening_csv(out_dir / "screening_row.csv", systems)
    plots = []
    if plot_source is not None:
        rolled = plot_source["rolled"]
        keep = plot_source["keep"]
        stamps = [
            (plot_source["channels"].stamps[rolled["start"] + i] - plot_source["interval"].start_ns)
            / 1_000_000_000.0
            for i, flag in enumerate(keep)
            if flag
        ]
        # Align forecast series to score epochs. Coast length matches keep.
        pred_speed = [v for v, flag in zip(rolled["speed_hat"], keep) if flag]
        pred_yaw = [v for v, flag in zip(rolled["yaw_hat"], keep) if flag]
        truth_s = [v for v, flag in zip(plot_source["truth_speed"], keep) if flag]
        truth_y = [v for v, flag in zip(plot_source["truth_yaw"], keep) if flag]
        if stamps and len(stamps) == len(truth_s) == len(pred_speed):
            speed_path = out_dir / "plots" / "S-Vta2_mid_speed.png"
            yaw_path = out_dir / "plots" / "S-Vta2_mid_yaw.png"
            coast_path = out_dir / "plots" / "S-Vta2_mid_coast.png"
            _plot_forecast(speed_path, stamps, truth_s, pred_speed, "speed (m/s)", "S-Vta2 mid. speed. truth is score only")
            _plot_forecast(yaw_path, stamps, truth_y, pred_yaw, "yaw rate (rad/s)", "S-Vta2 mid. yaw. truth is score only")
            _plot_coast(coast_path, plot_source["scored"], "S-Vta2 mid blackout. truth is score only")
            plots = [
                str(speed_path.relative_to(out_dir)),
                str(yaw_path.relative_to(out_dir)),
                str(coast_path.relative_to(out_dir)),
            ]
    payload = {
        "run_date": "2026-09-03",
        "git_commit": _git_hash(repo),
        "seed": SEED,
        "device": device,
        "interval_count": len(by_system["timesfm_coast"]),
        "forecast_window_count": len(cleaned_forecasts),
        "systems": systems,
        "versions": versions,
        "wall_s": time.perf_counter() - started,
        "failures": failures or ["none"],
        "plots": plots,
        "locked_found": sorted(found),
    }
    _write_config(out_dir / "config.yaml", payload)
    write_timesfm_summary(out_dir / "summary.md", payload)
    screening_summary = repo / "results" / "io_vnbd_screening_v1" / "summary.md"
    if screening_summary.is_file():
        _insert_screening_row(screening_summary, systems)
    (out_dir / "run_payload.json").write_text(json.dumps({k: v for k, v in payload.items() if k != "systems"}, indent=2) + "\n")
    return payload


def _aggregate_forecasts_fixed(
    rows: Sequence[dict],
    ridge_speed: Sequence[Sequence[float]],
    ridge_yaw: Sequence[Sequence[float]],
    channels_by_trip: dict[str, TripChannels],
) -> list[dict]:
    systems = ["timesfm", "persist", "const_accel", "linear", "ridge"]
    if any(channels_by_trip[row["trip_id"]].gru is not None for row in rows):
        systems.append("gru")
    grouped: dict[tuple, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[(row["split"], row["scenario"], row["horizon_steps"])].append(row)
        grouped[(row["split"], "all", row["horizon_steps"])].append(row)
        grouped[("held_out_all", row["scenario"], row["horizon_steps"])].append(row)
        grouped[("held_out_all", "all", row["horizon_steps"])].append(row)
    out: list[dict] = []
    for (split, scenario, steps), bucket in sorted(grouped.items()):
        for system in systems:
            speed_pred: list[float] = []
            speed_truth: list[float] = []
            yaw_pred: list[float] = []
            yaw_truth: list[float] = []
            lo68_s: list[float] = []
            hi68_s: list[float] = []
            lo80_s: list[float] = []
            hi80_s: list[float] = []
            lo68_y: list[float] = []
            hi68_y: list[float] = []
            lo80_y: list[float] = []
            hi80_y: list[float] = []
            infer: list[float] = []
            for row in bucket:
                channels = channels_by_trip[row["trip_id"]]
                origin = channels.index_at(int(row["origin_ns"]))
                if origin is None or origin < 1:
                    continue
                gnss_held = hold_after(channels.gnss_speed, int(row["_blackout_start"]))
                if system == "timesfm":
                    pred_s = row["timesfm_speed"]
                    pred_y = row["timesfm_yaw"]
                    lo68_s.extend(row["q68_speed"][0])
                    hi68_s.extend(row["q68_speed"][1])
                    lo80_s.extend(row["q80_speed"][0])
                    hi80_s.extend(row["q80_speed"][1])
                    lo68_y.extend(row["q68_yaw"][0])
                    hi68_y.extend(row["q68_yaw"][1])
                    lo80_y.extend(row["q80_yaw"][0])
                    hi80_y.extend(row["q80_yaw"][1])
                    infer.append(float(row["infer_s"]))
                else:
                    pred_s = _baseline_speed(system, channels, origin, steps, gnss_held, ridge_speed)
                    pred_y = _baseline_yaw(system, channels, origin, steps, gnss_held, ridge_yaw)
                speed_pred.extend(pred_s)
                speed_truth.extend(row["truth_speed"])
                yaw_pred.extend(pred_y)
                yaw_truth.extend(row["truth_yaw"])
            if not speed_pred:
                continue
            speed_stats = _window_metrics(
                speed_pred,
                speed_truth,
                low68=lo68_s or None,
                high68=hi68_s or None,
                low80=lo80_s or None,
                high80=hi80_s or None,
                infer_s=mean(infer) if infer else None,
            )
            yaw_stats = _window_metrics(
                yaw_pred,
                yaw_truth,
                low68=lo68_y or None,
                high68=hi68_y or None,
                low80=lo80_y or None,
                high80=hi80_y or None,
                infer_s=mean(infer) if infer else None,
            )
            for target, stats in (("forward_speed_mps", speed_stats), ("yaw_rate_radps", yaw_stats)):
                out.append(
                    {
                        "split": split,
                        "scenario": scenario,
                        "horizon_s": steps / RATE_HZ,
                        "horizon_steps": steps,
                        "system": system,
                        "target": target,
                        **stats,
                        "windows": len(bucket),
                    }
                )
    return out


def roll_timesfm25_coast(
    channels: TripChannels,
    interval: BlackoutInterval,
    forecaster: TimesFM25Forecaster,
) -> dict:
    """Univariate 2.5 speed coast plus XReg speed coast. Yaw is held last gyro."""

    start = channels.index_at(interval.start_ns)
    end = channels.index_at(interval.end_ns)
    if start is None:
        raise ValueError(f"no sample at or after {interval.start_ns}")
    if end is None:
        end = len(channels.stamps)
    if end - start < 8:
        raise ValueError("blackout shorter than 8 samples")
    masked = mask_gnss_records(channels.records, [interval])
    assert_no_gnss_leakage(masked)
    blackout_rows = [row for row in masked if row.get("gnss_masked")]
    if len(blackout_rows) != end - start:
        raise ValueError(
            f"blackout row count {len(blackout_rows)} != index span {end - start}"
        )
    leaked = GNSS_KEYS.intersection(masked[start].keys()) if start < len(masked) else set()
    if leaked:
        raise AssertionError(f"masked origin still has GNSS keys: {sorted(leaked)}")
    gnss_held = hold_after(channels.gnss_speed, start)
    speed_ctx = list(gnss_held)
    speed_ctx_x = list(gnss_held)
    speed_hat = [0.0] * (end - start)
    speed_hat_x = [0.0] * (end - start)
    last_yaw = channels.yaw_gyro[start - 1] if start > 0 else channels.yaw_gyro[0]
    yaw_hat = [last_yaw] * (end - start)
    infer_times: list[float] = []
    forecast_rows: list[dict] = []
    original_by_t = {int(row["timestamp_ns"]): row for row in channels.records}
    truth_speed, truth_yaw = _truth_motion(original_by_t, blackout_rows)
    stop_flag = binary_stop_flag(channels.stop_prob)
    abs_omega = abs_omega_z_series(channels.yaw_gyro)
    origin = start
    while origin < end:
        ctx_start = max(0, origin - CONTEXT_STEPS_25)
        target = speed_ctx[ctx_start:origin]
        target_x = speed_ctx_x[ctx_start:origin]
        packed = pack_causal_speed_covariates(
            channels.ax,
            abs_omega,
            stop_flag,
            end_index=origin,
            horizon_steps=HORIZON_STEPS_25,
            context_steps=CONTEXT_STEPS_25,
        )
        point, quantiles, elapsed = forecaster.forecast_speed(target, HORIZON_STEPS_25)
        point_x, quantiles_x, elapsed_x = forecaster.forecast_speed_xreg(
            target_x, packed, HORIZON_STEPS_25
        )
        infer_times.append(elapsed + elapsed_x)
        take = min(HORIZON_STEPS_25, end - origin)
        for step in range(take):
            speed_ctx[origin + step] = point[step]
            speed_ctx_x[origin + step] = point_x[step]
            speed_hat[origin - start + step] = point[step]
            speed_hat_x[origin - start + step] = point_x[step]
        remaining = end - origin
        for steps in HORIZON_SLICES:
            if remaining < steps:
                continue
            sl = slice(origin - start, origin - start + steps)
            truth_s = truth_speed[sl]
            truth_y = truth_yaw[sl]
            pred_s = point[:steps]
            pred_sx = point_x[:steps]
            pred_y = persist_forecast(last_yaw, steps)
            lo68_s, hi68_s = slice_quantiles_25(quantiles, steps, Q68_LO_25, Q68_HI_25)
            lo80_s, hi80_s = slice_quantiles_25(quantiles, steps, Q80_LO_25, Q80_HI_25)
            lo68_x, hi68_x = slice_quantiles_25(quantiles_x, steps, Q68_LO_25, Q68_HI_25)
            lo80_x, hi80_x = slice_quantiles_25(quantiles_x, steps, Q80_LO_25, Q80_HI_25)
            forecast_rows.append(
                {
                    "interval_id": interval.interval_id,
                    "trip_id": channels.trip_id,
                    "split": channels.split,
                    "scenario": scenario_name(truth_s),
                    "origin_ns": channels.stamps[origin],
                    "horizon_steps": steps,
                    "infer_s": elapsed + elapsed_x,
                    "timesfm_speed": pred_s,
                    "timesfm_xreg_speed": pred_sx,
                    "timesfm_yaw": pred_y,
                    "truth_speed": truth_s,
                    "truth_yaw": truth_y,
                    "q68_speed": (lo68_s, hi68_s),
                    "q80_speed": (lo80_s, hi80_s),
                    "q68_xreg": (lo68_x, hi68_x),
                    "q80_xreg": (lo80_x, hi80_x),
                    "origin": origin,
                }
            )
        origin += take
    by_t_speed = {channels.stamps[start + i]: speed_hat[i] for i in range(end - start)}
    by_t_speed_x = {channels.stamps[start + i]: speed_hat_x[i] for i in range(end - start)}
    by_t_yaw = {channels.stamps[start + i]: yaw_hat[i] for i in range(end - start)}

    def speed_at(row: dict, _prior: float) -> float:
        return by_t_speed.get(int(row["timestamp_ns"]), _prior)

    def speed_at_x(row: dict, _prior: float) -> float:
        return by_t_speed_x.get(int(row["timestamp_ns"]), _prior)

    def yaw_at(row: dict) -> float | None:
        stamp = int(row["timestamp_ns"])
        if stamp in by_t_yaw:
            return by_t_yaw[stamp]
        return row.get("gyro_vertical_radps", row.get("gx"))

    return {
        "speed_hat": speed_hat,
        "speed_hat_xreg": speed_hat_x,
        "yaw_hat": yaw_hat,
        "forecast_rows": forecast_rows,
        "infer_times": infer_times,
        "speed_at": speed_at,
        "speed_at_xreg": speed_at_x,
        "yaw_at": yaw_at,
        "blackout_rows": blackout_rows,
        "start": start,
        "end": end,
    }


def _plot_coast25(path: Path, scored: dict, title: str) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    truth = scored["truth"]
    fig, ax = plt.subplots(figsize=(8, 8))
    ax.plot([p[1] for p in truth], [p[0] for p in truth], "k-", linewidth=2.0, label="hidden truth, score only")
    styles = {
        "persist": ("0.45", "--", "persist"),
        "linear": ("0.25", ":", "linear student"),
        "timesfm_coast": ("0.0", "-", "timesfm_2.5_coast"),
        "timesfm_xreg_coast": ("0.15", "-.", "timesfm_2.5_xreg_coast"),
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


def _write_config_25(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "schema_version: \"1.0.0\"",
        "experiment_id: timesfm25_zero_shot_speed_v1",
        "research_only: true",
        "phone: false",
        f"run_date: \"{payload['run_date']}\"",
        f"git_commit: \"{payload['git_commit']}\"",
        f"seed: \"{payload['seed']}\"",
        "model:",
        "  provider: google",
        f"  checkpoint: {CHECKPOINT_25}",
        f"  revision: {REVISION_25}",
        f"  package: timesfm=={PACKAGE_VERSION_25}",
        "  runtime: pytorch",
        f"  device: {payload['device']}",
        f"  license_weights: {LICENSE_WEIGHTS_25}",
        "forecast:",
        f"  sampling_rate_hz: {RATE_HZ}",
        f"  context_steps: {CONTEXT_STEPS_25}",
        f"  context_s: {CONTEXT_STEPS_25 / RATE_HZ}",
        f"  model_max_context_steps: {MODEL_MAX_CONTEXT_25}",
        f"  horizon_steps: [{', '.join(str(x) for x in HORIZON_SLICES)}]",
        "  targets: [forward_speed_mps]",
        "  yaw_in_coast: last_causal_gyro_held",
        "  univariate: true",
        "covariates:",
        "  api: xreg_plus_timesfm",
        "  implementation: numpy_ridge_stand_in",
        "  official_forecast_with_covariates: skipped_needs_jax",
        f"  horizon_policy: {HORIZON_COVARIATE_POLICY}",
        "  names: [forward_accel_mps2, abs_omega_z_radps, stop_flag]",
        "production:",
        "  timesfm_dependency_allowed: false",
        "",
    ]
    path.write_text("\n".join(lines))


def _format_timesfm25_section(payload: dict) -> str:
    coast = payload["systems"]["timesfm_coast"]
    persist = payload["systems"]["persist"]
    linear = payload["systems"]["linear"]
    xreg = payload["systems"].get("timesfm_xreg_coast")
    decided = float(coast["drift_ratio_p50"])
    persist_p50 = float(persist["drift_ratio_p50"])
    linear_p50 = float(linear["drift_ratio_p50"])
    beats_persist = decided < persist_p50
    beats_linear = decided < linear_p50
    if not beats_persist:
        verdict = "reject"
        why = (
            f"timesfm_2.5_coast drift p50 {decided:.4f} is worse than persist {persist_p50:.4f} "
            f"on {payload['interval_count']} gated intervals."
        )
    elif not beats_linear:
        verdict = "reject"
        why = (
            f"timesfm_2.5_coast drift p50 {decided:.4f} beats persist {persist_p50:.4f} but not "
            f"linear {linear_p50:.4f}."
        )
    else:
        verdict = "keep_as_desktop_teacher_only"
        why = (
            f"timesfm_2.5_coast drift p50 {decided:.4f} beats persist {persist_p50:.4f} and "
            f"linear {linear_p50:.4f}. TimesFM still cannot run on the phone."
        )
    xreg_line = "not scored"
    if xreg is not None:
        xreg_line = (
            f"timesfm_2.5_xreg_coast drift p50: {float(xreg['drift_ratio_p50']):.4f}, "
            f"endpoint p50 m: {float(xreg['endpoint_p50_m']):.2f}, "
            f"speed mae p50: {float(xreg.get('speed_mae_p50', float('nan'))):.3f}"
        )
    payload["verdict"] = verdict
    payload["deciding_number"] = decided
    lines = [
        "## TimesFM 2.5 zero-shot speed (Apache-2.0)",
        "",
        "Desktop only. Same 35 locked intervals, seed 26168, same masks, same scoring. "
        "Does not overwrite the 3.0 artifacts above. TimesFM does not run on the phone.",
        "",
        "### Commands",
        "",
        "`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.timesfm_experiment --family 2.5 --repo . --out results/timesfm/v2.5`",
        "",
        "### Versions",
        "",
        f"- git commit: `{payload['git_commit']}`",
        f"- seed: `{payload['seed']}`",
        f"- timesfm: {PACKAGE_VERSION_25}",
        f"- checkpoint: `{CHECKPOINT_25}` revision `{REVISION_25}`",
        f"- device: {payload['device']}",
        f"- python: {payload['versions']['python']}",
        f"- numpy: {payload['versions']['numpy']}",
        f"- torch: {payload['versions']['torch']}",
        f"- wall time s: {payload['wall_s']:.1f}",
        f"- download s: {float(payload.get('download_s', 0.0)):.1f}",
        f"- context: {CONTEXT_STEPS_25} steps ({CONTEXT_STEPS_25 / RATE_HZ:.0f} s). Model max is {MODEL_MAX_CONTEXT_25}.",
        f"- horizons: 1 s, 2 s, 5 s. Rolling coast uses {HORIZON_STEPS_25} steps.",
        f"- intervals: {payload['interval_count']}",
        f"- forecast windows: {payload['forecast_window_count']}",
        f"- teacher windows: {payload.get('teacher_window_count', 0)}",
        "",
        "### License",
        "",
        "2.5 weights are Apache-2.0 and redistributable. 3.0 weights are "
        "timesfm-non-commercial-license-v1.0, non-commercial, not shippable. "
        "Neither checkpoint is on the phone.",
        "",
        "### timesfm_2.5_coast vs persist and linear",
        "",
        f"- persist drift p50: {persist_p50:.4f}, endpoint p50 m: {float(persist['endpoint_p50_m']):.2f}, speed mae p50: {float(persist.get('speed_mae_p50', float('nan'))):.3f}",
        f"- linear drift p50: {linear_p50:.4f}, endpoint p50 m: {float(linear['endpoint_p50_m']):.2f}",
        f"- timesfm_2.5_coast drift p50: {decided:.4f}, endpoint p50 m: {float(coast['endpoint_p50_m']):.2f}, speed mae p50: {float(coast.get('speed_mae_p50', float('nan'))):.3f}",
        f"- {xreg_line}",
        "",
        "Univariate 2.5 forecasts speed only. Coast yaw is the last causal gyro held, "
        "not a TimesFM yaw head. XReg feeds causal forward accel, |omega_z|, and stop "
        f"flag. Horizon covariates use {HORIZON_COVARIATE_POLICY} (not future IMU). "
        "Official `forecast_with_covariates` was not called because timesfm[xreg] "
        "needs jax, which is not in the train venv. The numpy ridge follows the same "
        "`xreg + timesfm` order.",
        "",
        "### Distillation",
        "",
        payload.get("distillation", "see teacher_targets.npz"),
        "",
        "### Verdict",
        "",
        f"{verdict}. {why} The deciding number is timesfm_2.5_coast drift ratio p50 = {decided:.4f} vs persist 0.5168.",
        "",
        "### Failures",
        "",
    ]
    failures = payload.get("failures") or ["none"]
    for item in failures:
        lines.append(f"- {item}")
    lines.append("")
    return "\n".join(lines)


def append_timesfm25_summary(path: Path, payload: dict) -> None:
    section = _format_timesfm25_section(payload)
    text = path.read_text() if path.is_file() else "# TimesFM IO-VNBD experiment\n"
    marker = "## TimesFM 2.5 zero-shot speed"
    if marker in text:
        text = text[: text.index(marker)].rstrip() + "\n\n" + section
    else:
        text = text.rstrip() + "\n\n" + section
    path.write_text(text if text.endswith("\n") else text + "\n")


def _aggregate_forecasts_25(
    rows: Sequence[dict],
    ridge_speed: Sequence[Sequence[float]],
    ridge_yaw: Sequence[Sequence[float]],
    channels_by_trip: dict[str, TripChannels],
) -> list[dict]:
    systems = ["timesfm", "timesfm_xreg", "persist", "const_accel", "linear", "ridge"]
    if any(channels_by_trip[row["trip_id"]].gru is not None for row in rows):
        systems.append("gru")
    grouped: dict[tuple, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[(row["split"], row["scenario"], row["horizon_steps"])].append(row)
        grouped[(row["split"], "all", row["horizon_steps"])].append(row)
        grouped[("held_out_all", row["scenario"], row["horizon_steps"])].append(row)
        grouped[("held_out_all", "all", row["horizon_steps"])].append(row)
    out: list[dict] = []
    for (split, scenario, steps), bucket in sorted(grouped.items()):
        for system in systems:
            speed_pred: list[float] = []
            speed_truth: list[float] = []
            yaw_pred: list[float] = []
            yaw_truth: list[float] = []
            lo68_s: list[float] = []
            hi68_s: list[float] = []
            lo80_s: list[float] = []
            hi80_s: list[float] = []
            infer: list[float] = []
            for row in bucket:
                channels = channels_by_trip[row["trip_id"]]
                origin = channels.index_at(int(row["origin_ns"]))
                if origin is None or origin < 1:
                    continue
                gnss_held = hold_after(channels.gnss_speed, int(row["_blackout_start"]))
                if system == "timesfm":
                    pred_s = row["timesfm_speed"]
                    pred_y = row["timesfm_yaw"]
                    lo68_s.extend(row["q68_speed"][0])
                    hi68_s.extend(row["q68_speed"][1])
                    lo80_s.extend(row["q80_speed"][0])
                    hi80_s.extend(row["q80_speed"][1])
                    infer.append(float(row["infer_s"]))
                elif system == "timesfm_xreg":
                    pred_s = row["timesfm_xreg_speed"]
                    pred_y = row["timesfm_yaw"]
                    lo68_s.extend(row["q68_xreg"][0])
                    hi68_s.extend(row["q68_xreg"][1])
                    lo80_s.extend(row["q80_xreg"][0])
                    hi80_s.extend(row["q80_xreg"][1])
                    infer.append(float(row["infer_s"]))
                else:
                    pred_s = _baseline_speed(system, channels, origin, steps, gnss_held, ridge_speed)
                    pred_y = _baseline_yaw(system, channels, origin, steps, gnss_held, ridge_yaw)
                speed_pred.extend(pred_s)
                speed_truth.extend(row["truth_speed"])
                yaw_pred.extend(pred_y)
                yaw_truth.extend(row["truth_yaw"])
            if not speed_pred:
                continue
            speed_stats = _window_metrics(
                speed_pred,
                speed_truth,
                low68=lo68_s or None,
                high68=hi68_s or None,
                low80=lo80_s or None,
                high80=hi80_s or None,
                infer_s=mean(infer) if infer else None,
            )
            yaw_stats = _window_metrics(yaw_pred, yaw_truth, infer_s=mean(infer) if infer else None)
            for target, stats in (("forward_speed_mps", speed_stats), ("yaw_rate_radps", yaw_stats)):
                out.append(
                    {
                        "split": split,
                        "scenario": scenario,
                        "horizon_s": steps / RATE_HZ,
                        "horizon_steps": steps,
                        "system": system,
                        "target": target,
                        **stats,
                        "windows": len(bucket),
                    }
                )
    return out


def _teacher_windows(
    train_channels: Sequence[TripChannels],
    forecaster: TimesFM25Forecaster,
    *,
    stride: int = TEACHER_STRIDE,
) -> list[dict]:
    rows: list[dict] = []
    stop_cache = {id(ch): binary_stop_flag(ch.stop_prob) for ch in train_channels}
    omega_cache = {id(ch): abs_omega_z_series(ch.yaw_gyro) for ch in train_channels}
    for channels in train_channels:
        last = len(channels.stamps) - HORIZON_STEPS_25
        if last < CONTEXT_STEPS_25:
            continue
        stop_flag = stop_cache[id(channels)]
        abs_omega = omega_cache[id(channels)]
        for origin in range(CONTEXT_STEPS_25, last, stride):
            target = channels.gnss_speed[origin - CONTEXT_STEPS_25 : origin]
            point, _quantiles, _elapsed = forecaster.forecast_speed(target, HORIZON_STEPS_25)
            rows.append(
                {
                    "trip_id": channels.trip_id,
                    "split": "train",
                    "origin_index": origin,
                    "origin_ns": channels.stamps[origin],
                    "last_speed_mps": channels.gnss_speed[origin - 1],
                    "timesfm_speed_mps": point,
                    COV_FORWARD_ACCEL: channels.ax[origin - 1],
                    COV_ABS_OMEGA_Z: abs_omega[origin - 1],
                    COV_STOP_FLAG: stop_flag[origin - 1],
                }
            )
            if len(rows) % 50 == 0:
                print(f"teacher windows {len(rows)}", flush=True)
    return rows


def run_2p5(repo: Path, out_dir: Path, summary_path: Path) -> dict:
    started = time.perf_counter()
    cache_root = Path.home() / ".cache" / "huggingface"
    print("preflight TimesFM 2.5", flush=True)
    preflight = preflight_checkpoint(cache_root, Path.home())
    print(json.dumps(preflight), flush=True)
    download_t0 = time.perf_counter()
    from huggingface_hub import hf_hub_download

    hf_hub_download(
        repo_id=CHECKPOINT_25,
        filename="model.safetensors",
        revision=REVISION_25,
    )
    download_s = time.perf_counter() - download_t0
    print(f"checkpoint ready in {download_s:.1f} s", flush=True)
    root = repo / "data" / "raw" / "io_vnbd"
    linear = load_linear_student(repo / "models" / "motion_student_v1" / "linear.json")
    gru_path = repo / "models" / "motion_student_v2" / "gru.json"
    gru = load_gru_student(gru_path) if gru_path.is_file() else None
    tables = screening_csv_paths(root)
    assignments = {
        row.trip_id: row.split
        for row in assign_grouped_trip_splits([path.stem for path in tables], seed=SEED)
    }
    device = _device_name()
    try:
        forecaster = TimesFM25Forecaster(device)
        smoke, _, smoke_s = forecaster.forecast_speed([float(i % 7) for i in range(64)], 10)
        print(f"smoke forecast {smoke[:3]} in {smoke_s:.2f} s", flush=True)
    except Exception as error:
        if device == "mps":
            print(f"MPS load failed ({error}); retrying CPU", flush=True)
            device = "cpu"
            forecaster = TimesFM25Forecaster(device)
            smoke, _, smoke_s = forecaster.forecast_speed([float(i % 7) for i in range(64)], 10)
            print(f"smoke forecast {smoke[:3]} in {smoke_s:.2f} s", flush=True)
        else:
            raise
    train_channels: list[TripChannels] = []
    eval_channels: dict[str, TripChannels] = {}
    load_failures: list[str] = []
    for path in tables:
        split = assignments[path.stem]
        if split in EVAL_SPLITS and path.stem not in LOCKED_TRIPS:
            continue
        try:
            records = [eval_record(row) for row in load_smartphone_csv(path)]
        except ValueError as error:
            load_failures.append(f"{path.stem}: {error}")
            continue
        records = _time_ordered(records)
        aligned = alignment_from_records(records, path.stem)
        if aligned is not None:
            records = attach_alignment(records, aligned)
        print(f"channels {path.stem} {split}", flush=True)
        built = build_trip_channels(records, trip_id=path.stem, split=split, linear=linear, gru=gru)
        if split == "train":
            train_channels.append(built)
        if split in EVAL_SPLITS:
            eval_channels[path.stem] = built
    print(f"fitting ridge on {len(train_channels)} train trips", flush=True)
    ridge_speed, ridge_yaw = fit_ridge(train_channels)
    by_system: dict[str, list] = defaultdict(list)
    extras: dict[str, list[float]] = defaultdict(list)
    heading: dict[str, list[float]] = defaultdict(list)
    forecast_rows: list[dict] = []
    scored_intervals: list[dict] = []
    failures: list[str] = list(load_failures)
    wanted = set(LOCKED_INTERVAL_IDS)
    found: set[str] = set()
    plot_source: dict | None = None
    for trip_id, channels in sorted(eval_channels.items()):
        for interval in locked_blackouts(channels.records, trip_id):
            if interval.interval_id not in wanted:
                continue
            print(f"interval {interval.interval_id}", flush=True)
            try:
                scored = score_interval(channels.records, interval, linear, gru)
            except ValueError as error:
                failures.append(f"{interval.interval_id}: score_interval {error}")
                continue
            if scored is None:
                failures.append(f"{interval.interval_id}: score_interval returned none")
                continue
            try:
                rolled = roll_timesfm25_coast(channels, interval, forecaster)
            except Exception as error:
                failures.append(f"{interval.interval_id}: timesfm25 {error}")
                continue
            history = _gnss_history(channels.records, interval.start_ns)
            if _positive_dt_pair(history) is None:
                failures.append(f"{interval.interval_id}: no heading seed")
                continue
            original_by_t = {int(row["timestamp_ns"]): row for row in channels.records}
            truth_speed, truth_heading = _truth_motion(original_by_t, rolled["blackout_rows"])
            epochs = set(score_epochs(channels.records, interval.start_ns, interval.end_ns))
            keep = [int(row["timestamp_ns"]) in epochs for row in rolled["blackout_rows"]]
            truth = scored["truth"]
            for name, speed_fn in (
                ("timesfm_coast", rolled["speed_at"]),
                ("timesfm_xreg_coast", rolled["speed_at_xreg"]),
            ):
                points, speeds, heads = _coast(
                    history,
                    rolled["blackout_rows"],
                    speed_fn,
                    rolled["yaw_at"],
                )
                kept_points = [pt for pt, flag in zip(points, keep) if flag]
                kept_speed = [value for value, flag in zip(speeds, keep) if flag]
                kept_head = [value for value, flag in zip(heads, keep) if flag]
                if len(kept_points) != len(truth):
                    failures.append(
                        f"{interval.interval_id}: {name} coast/truth length {len(kept_points)} vs {len(truth)}"
                    )
                    continue
                metrics = evaluate_blackout(kept_points, truth)
                kept_truth_speed = [value for value, flag in zip(truth_speed, keep) if flag]
                kept_truth_head = [value for value, flag in zip(truth_heading, keep) if flag]
                extra = {
                    "speed_mae_mps": mean(abs(a - b) for a, b in zip(kept_speed, kept_truth_speed)),
                    "heading_mae_rad": circular_mae_rad(kept_head, kept_truth_head),
                }
                scored["traces"][name] = kept_points
                scored["metrics"][name] = metrics
                scored["extras"][name] = extra
            if "timesfm_coast" not in scored["metrics"]:
                continue
            found.add(interval.interval_id)
            scored_intervals.append(scored)
            forecast_rows.extend(rolled["forecast_rows"])
            for name, item in scored["metrics"].items():
                if name not in {"persist", "linear", "timesfm_coast", "timesfm_xreg_coast"}:
                    continue
                by_system[name].append(item)
                extras[name].append(scored["extras"][name]["speed_mae_mps"])
                heading[name].append(scored["extras"][name]["heading_mae_rad"])
            if interval.interval_id == "S-Vta2:mid":
                plot_source = {
                    "scored": scored,
                    "rolled": rolled,
                    "channels": channels,
                    "interval": interval,
                    "keep": keep,
                    "truth_speed": truth_speed,
                    "truth_yaw": truth_heading,
                }
    missing = [item for item in LOCKED_INTERVAL_IDS if item not in found]
    if missing:
        failures.append(f"missing locked intervals: {', '.join(missing)}")
    if "timesfm_coast" not in by_system:
        raise RuntimeError("no timesfm_coast interval scored")
    systems = {name: summarize(rows) for name, rows in by_system.items()}
    for name, values in extras.items():
        systems[name]["speed_mae_p50"] = median(values)
        systems[name]["heading_mae_rad_p50"] = median(heading[name])
    cleaned_forecasts = []
    for row in forecast_rows:
        channels = eval_channels[row["trip_id"]]
        interval_start = None
        for interval in locked_blackouts(channels.records, channels.trip_id):
            if interval.interval_id == row["interval_id"]:
                interval_start = channels.index_at(interval.start_ns)
                break
        if interval_start is None:
            continue
        row = dict(row)
        row["_blackout_start"] = interval_start
        cleaned_forecasts.append(row)
    horizon_rows = _aggregate_forecasts_25(cleaned_forecasts, ridge_speed, ridge_yaw, eval_channels)
    print(f"teacher targets on {len(train_channels)} train trips", flush=True)
    teacher_rows = _teacher_windows(train_channels, forecaster, stride=TEACHER_STRIDE)
    versions = {"python": sys.version.split()[0]}
    for name, key in (("numpy", "numpy"), ("torch", "torch"), ("matplotlib", "matplotlib"), ("yaml", "pyyaml")):
        try:
            versions[key] = getattr(__import__(name), "__version__", "present")
        except ImportError:
            versions[key] = "missing"
    try:
        import timesfm as timesfm_mod

        versions["timesfm"] = getattr(timesfm_mod, "__version__", PACKAGE_VERSION_25)
    except ImportError:
        versions["timesfm"] = "missing"
    out_dir.mkdir(parents=True, exist_ok=True)
    distillation = (
        f"wrote {len(teacher_rows)} train windows to teacher_targets.npz. "
        "Apache-2.0 2.5 point forecasts only. Residual-student owner can distill "
        "student -> timesfm forecast. This run did not train a student."
    )
    if not teacher_rows:
        distillation = "teacher file not written: no train windows met context and horizon."
        failures.append("no teacher windows")
    else:
        write_teacher_targets(
            out_dir / "teacher_targets.npz",
            teacher_rows,
            {
                "file": "teacher_targets.npz",
                "git_commit": _git_hash(repo),
                "seed": SEED,
                "device": device,
                "context_steps": CONTEXT_STEPS_25,
                "stride": TEACHER_STRIDE,
                "variant": "univariate_zero_shot",
            },
        )
    _write_horizon_csv(out_dir / "metrics_per_horizon.csv", horizon_rows)
    _write_screening_csv(
        out_dir / "screening_row.csv",
        systems,
        names=("persist", "linear", "timesfm_coast", "timesfm_xreg_coast"),
    )
    plots = []
    if plot_source is not None:
        rolled = plot_source["rolled"]
        keep = plot_source["keep"]
        stamps = [
            (plot_source["channels"].stamps[rolled["start"] + i] - plot_source["interval"].start_ns)
            / 1_000_000_000.0
            for i, flag in enumerate(keep)
            if flag
        ]
        pred_speed = [v for v, flag in zip(rolled["speed_hat"], keep) if flag]
        pred_yaw = [v for v, flag in zip(rolled["yaw_hat"], keep) if flag]
        truth_s = [v for v, flag in zip(plot_source["truth_speed"], keep) if flag]
        truth_y = [v for v, flag in zip(plot_source["truth_yaw"], keep) if flag]
        if stamps and len(stamps) == len(truth_s) == len(pred_speed):
            speed_path = out_dir / "plots" / "S-Vta2_mid_speed.png"
            yaw_path = out_dir / "plots" / "S-Vta2_mid_yaw.png"
            coast_path = out_dir / "plots" / "S-Vta2_mid_coast.png"
            _plot_forecast(speed_path, stamps, truth_s, pred_speed, "speed (m/s)", "S-Vta2 mid. TimesFM 2.5 speed. truth is score only")
            _plot_forecast(yaw_path, stamps, truth_y, pred_yaw, "yaw rate (rad/s)", "S-Vta2 mid. held gyro yaw. truth is score only")
            _plot_coast25(coast_path, plot_source["scored"], "S-Vta2 mid blackout. TimesFM 2.5. truth is score only")
            plots = [
                str(speed_path.relative_to(out_dir)),
                str(yaw_path.relative_to(out_dir)),
                str(coast_path.relative_to(out_dir)),
            ]
    payload = {
        "run_date": "2026-09-03",
        "git_commit": _git_hash(repo),
        "seed": SEED,
        "device": device,
        "interval_count": len(by_system["timesfm_coast"]),
        "forecast_window_count": len(cleaned_forecasts),
        "teacher_window_count": len(teacher_rows),
        "systems": systems,
        "versions": versions,
        "wall_s": time.perf_counter() - started,
        "download_s": download_s,
        "failures": failures or ["none"],
        "plots": plots,
        "locked_found": sorted(found),
        "distillation": distillation,
        "preflight": preflight,
    }
    _write_config_25(out_dir / "config.yaml", payload)
    (out_dir / "summary.md").write_text(
        "# TimesFM 2.5 zero-shot IO-VNBD experiment\n\n"
        "Desktop only. GNSS after mask start is score-only. No TimesFM in apps/ or packages/.\n\n"
        + _format_timesfm25_section(payload)
    )
    append_timesfm25_summary(summary_path, payload)
    (out_dir / "run_payload.json").write_text(
        json.dumps({k: v for k, v in payload.items() if k != "systems"}, indent=2) + "\n"
    )
    (out_dir / "LICENSE_NOTES.md").write_text(
        "# TimesFM 2.5 license\n\n"
        f"`{CHECKPOINT_25}` revision `{REVISION_25}` is Apache-2.0. Redistributable. "
        "TimesFM 3.0 weights are timesfm-non-commercial-license-v1.0 and cannot ship. "
        "Neither model runs on the phone.\n"
    )
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="TimesFM desktop zero-shot IO-VNBD experiment")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--family", choices=("3.0", "2.5"), default="3.0")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    if args.out is None:
        args.out = Path("results/timesfm/v2.5") if args.family == "2.5" else Path("results/timesfm")
    out = args.out if args.out.is_absolute() else repo / args.out
    try:
        if args.family == "2.5":
            parent = repo / "results" / "timesfm"
            if out.resolve() == parent.resolve():
                out = parent / "v2.5"
            payload = run_2p5(repo, out, parent / "summary.md")
        else:
            payload = run(repo, out)
    except TimesFM25PreflightError as error:
        print(json.dumps({"skipped": True, "reason": str(error)}))
        return 2
    except (IOVNBDMissing, DatasetLfsMissing, DatasetMissing) as error:
        print(json.dumps({"skipped": True, "reason": str(error)}))
        return 0
    print(json.dumps({k: payload[k] for k in payload if k != "systems"}, indent=2))
    print(json.dumps(payload["systems"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
