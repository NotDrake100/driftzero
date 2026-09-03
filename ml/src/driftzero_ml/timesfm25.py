"""TimesFM 2.5 desktop helpers. Not imported by the phone.

2.5 is univariate. Covariates use the official XReg contract (context plus
horizon) with a numpy ridge stand-in for `forecast_with_covariates`, which
needs jax via timesfm[xreg]. Horizon IMU is the last causal sample held.
Future IMU is never used.

Apache-2.0 weights. Distillation targets are train-split only.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Callable, Sequence

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import STOP_ZUPT

CHECKPOINT = "google/timesfm-2.5-200m-pytorch"
REVISION = "1d952420fba87f3c6dee4f240de0f1a0fbc790e3"
PACKAGE_VERSION = "3.0.1"
LICENSE_WEIGHTS = "Apache-2.0"
CONTEXT_STEPS = 640
HORIZON_STEPS = 50
PATCH = 32
OUTPUT_PATCH = 128
MODEL_MAX_CONTEXT = 16384
# Hugging Face layout: mean, then 0.1..0.9. Point is index 5 (0.5).
Q_MEAN = 0
Q_P10 = 1
Q68_LO_INDEX = 2
Q68_HI_INDEX = 8
Q80_LO_INDEX = 1
Q80_HI_INDEX = 9
POINT_INDEX = 5
# Measured 2026-09-03 via huggingface_hub get_hf_file_metadata.
CHECKPOINT_BYTES = 925_181_104
TEACHER_STRIDE = 50
TEACHER_SCHEMA_VERSION = "1"
RIDGE = 1e-2
COV_FORWARD_ACCEL = "forward_accel_mps2"
COV_ABS_OMEGA_Z = "abs_omega_z_radps"
COV_STOP_FLAG = "stop_flag"
SPEED_COVARIATE_NAMES = (COV_FORWARD_ACCEL, COV_ABS_OMEGA_Z, COV_STOP_FLAG)
HORIZON_COVARIATE_POLICY = "last_causal_hold"

if set(SPEED_COVARIATE_NAMES) & GNSS_KEYS:
    raise RuntimeError("TimesFM 2.5 covariates must not use GNSS field names")

TEACHER_SCHEMA = {
    "schema_version": TEACHER_SCHEMA_VERSION,
    "artifact": "npz",
    "split": "train",
    "seed": "26168",
    "checkpoint": CHECKPOINT,
    "revision": REVISION,
    "license_weights": LICENSE_WEIGHTS,
    "target": "timesfm_speed_mps",
    "units": {
        "timesfm_speed_mps": "m/s",
        "last_speed_mps": "m/s",
        "forward_accel_mps2": "m/s^2",
        "abs_omega_z_radps": "rad/s",
        "stop_flag": "1 if linear stop_prob >= 0.65 else 0",
        "origin_ns": "integer nanoseconds",
    },
    "arrays": {
        "trip_id": "U, shape [N]",
        "origin_index": "int32, shape [N]",
        "origin_ns": "int64, shape [N]",
        "last_speed_mps": "float32, shape [N]",
        "timesfm_speed_mps": "float32, shape [N, horizon_steps], point forecast",
        "forward_accel_mps2": "float32, shape [N], causal IMU at origin-1",
        "abs_omega_z_radps": "float32, shape [N], causal IMU at origin-1",
        "stop_flag": "float32, shape [N], causal at origin-1",
    },
    "horizon_covariate_policy": HORIZON_COVARIATE_POLICY,
    "phone": False,
}


class TimesFM25PreflightError(RuntimeError):
    """Raised when the 2.5 checkpoint cannot be downloaded or loaded."""


def binary_stop_flag(stop_prob: Sequence[float], threshold: float = STOP_ZUPT) -> list[float]:
    if threshold <= 0.0 or threshold >= 1.0:
        raise ValueError("stop threshold must be in (0, 1)")
    return [1.0 if value >= threshold else 0.0 for value in stop_prob]


def abs_omega_z_series(yaw_gyro: Sequence[float]) -> list[float]:
    return [abs(value) for value in yaw_gyro]


def pack_causal_speed_covariates(
    forward_accel: Sequence[float],
    abs_omega_z: Sequence[float],
    stop_flag: Sequence[float],
    *,
    end_index: int,
    horizon_steps: int,
    context_steps: int = CONTEXT_STEPS,
) -> dict[str, list[float]]:
    """Pack TimesFM 2.5 XReg dynamic numerical covariates.

    Past is causal IMU on [end_index - context, end_index). Horizon copies the
    last causal sample. That is not future IMU.
    """

    n = len(forward_accel)
    if not (len(abs_omega_z) == n and len(stop_flag) == n):
        raise ValueError("covariate series must be the same length")
    if n < 1:
        raise ValueError("covariate series must not be empty")
    if end_index < 1 or end_index > n:
        raise ValueError("end_index out of range")
    if horizon_steps < 1:
        raise ValueError("horizon_steps must be at least 1")
    if context_steps < 1:
        raise ValueError("context_steps must be at least 1")
    start = max(0, end_index - context_steps)
    packed: dict[str, list[float]] = {}
    for name, series in (
        (COV_FORWARD_ACCEL, forward_accel),
        (COV_ABS_OMEGA_Z, abs_omega_z),
        (COV_STOP_FLAG, stop_flag),
    ):
        past = [float(value) for value in series[start:end_index]]
        if not past:
            raise ValueError("need at least one causal covariate sample")
        held = past[-1]
        packed[name] = past + [held] * horizon_steps
    return packed


def _ridge_weights(rows: Sequence[Sequence[float]], targets: Sequence[float], l2: float) -> list[float]:
    if len(rows) != len(targets) or not rows:
        raise ValueError("ridge needs aligned non-empty rows")
    width = len(rows[0]) + 1
    gram = [[0.0] * width for _ in range(width)]
    rhs = [0.0] * width
    for row, target in zip(rows, targets):
        if len(row) != width - 1:
            raise ValueError("ragged ridge rows")
        extended = [1.0, *row]
        for i in range(width):
            rhs[i] += extended[i] * target
            for j in range(width):
                gram[i][j] += extended[i] * extended[j]
    for i in range(width):
        gram[i][i] += l2
    aug = [gram[i][:] + [rhs[i]] for i in range(width)]
    for i in range(width):
        pivot = max(range(i, width), key=lambda r: abs(aug[r][i]))
        aug[i], aug[pivot] = aug[pivot], aug[i]
        diag = aug[i][i]
        if abs(diag) < 1e-12:
            raise ValueError("singular ridge system")
        scale = 1.0 / diag
        for col in range(i, width + 1):
            aug[i][col] *= scale
        for row in range(width):
            if row == i:
                continue
            factor = aug[row][i]
            for col in range(i, width + 1):
                aug[row][col] -= factor * aug[i][col]
    return [aug[i][width] for i in range(width)]


def _ridge_apply(weights: Sequence[float], row: Sequence[float]) -> float:
    acc = weights[0]
    for weight, value in zip(weights[1:], row):
        acc += weight * value
    return acc


def xreg_plus_timesfm_inputs(
    target: Sequence[float],
    packed_covariates: dict[str, Sequence[float]],
    *,
    ridge: float = RIDGE,
) -> tuple[list[float], list[float], list[float]]:
    """Official `xreg + timesfm` split, numpy ridge, no jax.

    packed_covariates values are context + horizon, length len(target) + H.
    Returns (residual_context, xreg_horizon, xreg_context).
    """

    names = SPEED_COVARIATE_NAMES
    if tuple(packed_covariates.keys()) != names and set(packed_covariates) != set(names):
        missing = [name for name in names if name not in packed_covariates]
        extra = [name for name in packed_covariates if name not in names]
        raise ValueError(f"covariates must be {names}, missing={missing}, extra={extra}")
    n = len(target)
    if n < 2:
        raise ValueError("xreg needs at least two context samples")
    lengths = {name: len(packed_covariates[name]) for name in names}
    if len(set(lengths.values())) != 1:
        raise ValueError("packed covariates must share one length")
    total = next(iter(lengths.values()))
    if total <= n:
        raise ValueError("packed covariates must be context plus horizon")
    horizon = total - n
    rows = []
    for index in range(n):
        rows.append([float(packed_covariates[name][index]) for name in names])
    weights = _ridge_weights(rows, [float(v) for v in target], ridge)
    xreg_context = [_ridge_apply(weights, row) for row in rows]
    residual = [float(value) - fit for value, fit in zip(target, xreg_context)]
    xreg_horizon = []
    for step in range(horizon):
        row = [float(packed_covariates[name][n + step]) for name in names]
        xreg_horizon.append(_ridge_apply(weights, row))
    return residual, xreg_horizon, xreg_context


def apply_xreg_plus_forecast(
    target: Sequence[float],
    packed_covariates: dict[str, Sequence[float]],
    forecast_fn: Callable[[Sequence[float], int], Sequence[float]],
    *,
    ridge: float = RIDGE,
) -> list[float]:
    """Fit XReg, forecast residuals with `forecast_fn`, add horizon XReg."""

    residual, xreg_horizon, _context = xreg_plus_timesfm_inputs(
        target, packed_covariates, ridge=ridge
    )
    point = list(forecast_fn(residual, len(xreg_horizon)))
    if len(point) != len(xreg_horizon):
        raise ValueError("forecast horizon must match xreg horizon")
    return [pred + add for pred, add in zip(point, xreg_horizon)]


def write_teacher_targets(
    path: Path,
    rows: Sequence[dict],
    meta: dict,
) -> None:
    """Write train-only TimesFM 2.5 speed forecasts as an npz distillation file."""

    if not rows:
        raise ValueError("teacher rows must not be empty")
    bad = [row.get("split") for row in rows if row.get("split") != "train"]
    if bad:
        raise ValueError("teacher targets must be train split only")
    horizon = len(rows[0]["timesfm_speed_mps"])
    if horizon < 1:
        raise ValueError("timesfm_speed_mps must be a non-empty horizon")
    for row in rows:
        if len(row["timesfm_speed_mps"]) != horizon:
            raise ValueError("ragged timesfm_speed_mps horizon")
        for name in SPEED_COVARIATE_NAMES:
            if name not in row:
                raise ValueError(f"missing {name} on teacher row")
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        import numpy as np
    except ImportError as error:
        raise RuntimeError("numpy is required to write teacher targets") from error

    payload = {
        "trip_id": np.array([str(row["trip_id"]) for row in rows], dtype="U64"),
        "origin_index": np.array([int(row["origin_index"]) for row in rows], dtype="int32"),
        "origin_ns": np.array([int(row["origin_ns"]) for row in rows], dtype="int64"),
        "last_speed_mps": np.array([float(row["last_speed_mps"]) for row in rows], dtype="float32"),
        "timesfm_speed_mps": np.array(
            [list(row["timesfm_speed_mps"]) for row in rows], dtype="float32"
        ),
        "forward_accel_mps2": np.array(
            [float(row[COV_FORWARD_ACCEL]) for row in rows], dtype="float32"
        ),
        "abs_omega_z_radps": np.array(
            [float(row[COV_ABS_OMEGA_Z]) for row in rows], dtype="float32"
        ),
        "stop_flag": np.array([float(row[COV_STOP_FLAG]) for row in rows], dtype="float32"),
    }
    sidecar = {
        **TEACHER_SCHEMA,
        **meta,
        "n_windows": len(rows),
        "horizon_steps": horizon,
        "npz_keys": sorted(payload),
    }
    np.savez_compressed(path, **payload)
    schema_path = path.with_suffix(".schema.json")
    schema_path.write_text(json.dumps(sidecar, indent=2) + "\n")
    md_path = path.with_name("teacher_targets_schema.md")
    md_path.write_text(_teacher_schema_markdown(sidecar))


def _teacher_schema_markdown(meta: dict) -> str:
    lines = [
        "# TimesFM 2.5 train teacher targets",
        "",
        "Desktop only. Train split only. Not a phone artifact.",
        "",
        f"- file: `{meta.get('file', 'teacher_targets.npz')}`",
        f"- schema_version: {meta.get('schema_version')}",
        f"- checkpoint: `{meta.get('checkpoint')}` revision `{meta.get('revision')}`",
        f"- license_weights: {meta.get('license_weights')}",
        f"- seed: {meta.get('seed')}",
        f"- n_windows: {meta.get('n_windows')}",
        f"- horizon_steps: {meta.get('horizon_steps')}",
        f"- context_steps: {meta.get('context_steps')}",
        f"- stride: {meta.get('stride')}",
        "",
        "Join key is `(trip_id, origin_ns)`. `timesfm_speed_mps` is the distillation",
        "target (student -> TimesFM 2.5 point forecast). Train GNSS is not stored here.",
        "Horizon IMU covariates used at eval are last-causal-hold, not future IMU.",
        "",
    ]
    return "\n".join(lines)


def disk_free_bytes(path: Path) -> int:
    stat = os.statvfs(path)
    return int(stat.f_bavail) * int(stat.f_frsize)


def darwin_available_ram_bytes() -> int | None:
    """Free plus inactive plus speculative pages. None if vm_stat is missing."""

    import subprocess

    try:
        raw = subprocess.check_output(["vm_stat"], text=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    page = 16384
    fields: dict[str, int] = {}
    for line in raw.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        digits = "".join(ch for ch in value if ch.isdigit())
        if digits:
            fields[key.strip()] = int(digits)
    if "page size of" in raw:
        for token in raw.split():
            if token.isdigit() and int(token) in {4096, 16384}:
                page = int(token)
                break
    free = fields.get("Pages free", 0)
    inactive = fields.get("Pages inactive", 0)
    spec = fields.get("Pages speculative", 0)
    purge = fields.get("Pages purgeable", 0)
    return (free + inactive + spec + purge) * page


def preflight_checkpoint(cache_root: Path, dest_volume: Path) -> dict:
    """Check disk and RAM before downloading 2.5 weights. Does not download."""

    free = disk_free_bytes(dest_volume)
    ram = darwin_available_ram_bytes()
    need = CHECKPOINT_BYTES + 400_000_000
    report = {
        "checkpoint": CHECKPOINT,
        "revision": REVISION,
        "checkpoint_bytes": CHECKPOINT_BYTES,
        "disk_free_bytes": free,
        "ram_available_bytes": ram,
        "need_bytes": need,
    }
    if free < need:
        raise TimesFM25PreflightError(
            f"not enough disk for {CHECKPOINT}: free {free} bytes, need {need} "
            f"(checkpoint {CHECKPOINT_BYTES} plus 400 MB buffer)"
        )
    if ram is not None and ram < 400_000_000:
        raise TimesFM25PreflightError(
            f"available RAM {ram} bytes is below 400 MB. Loading 2.5 (925 MB "
            "weights) would likely swap-thrash or die. Free memory and retry."
        )
    report["cache_root"] = str(cache_root)
    return report


def slice_quantiles(quantiles: Sequence[Sequence[float]], steps: int, lo: int, hi: int) -> tuple[list[float], list[float]]:
    if steps < 1:
        raise ValueError("steps must be at least 1")
    low = [float(quantiles[step][lo]) for step in range(steps)]
    high = [float(quantiles[step][hi]) for step in range(steps)]
    return low, high


class TimesFM25Forecaster:
    """Univariate TimesFM 2.5 wrapper. Imported only when the experiment runs."""

    def __init__(self, device: str) -> None:
        from driftzero_ml.timesfm_adapter import timesfm_is_installed

        if not timesfm_is_installed():
            raise RuntimeError("timesfm is not installed")
        import timesfm
        import torch

        if not hasattr(timesfm, "TimesFM_2p5_200M_torch"):
            raise RuntimeError("timesfm package has no TimesFM_2p5_200M_torch")
        self.device = device
        print(f"loading TimesFM 2.5 on {device}", flush=True)
        self._model = timesfm.TimesFM_2p5_200M_torch.from_pretrained(
            CHECKPOINT,
            revision=REVISION,
            torch_compile=False,
        )
        if device == "mps":
            self._model.model.device = torch.device("mps")
            self._model.model.to("mps")
        elif device == "cpu":
            self._model.model.device = torch.device("cpu")
            self._model.model.to("cpu")
        self._model.compile(
            timesfm.ForecastConfig(
                max_context=CONTEXT_STEPS,
                max_horizon=OUTPUT_PATCH,
                normalize_inputs=True,
                per_core_batch_size=1,
                use_continuous_quantile_head=True,
                force_flip_invariance=False,
                infer_is_positive=False,
                fix_quantile_crossing=True,
                return_backcast=False,
            )
        )

    def _forecast_raw(
        self,
        context: Sequence[float],
        horizon: int,
    ) -> tuple[list[float], list[list[float]], float]:
        import numpy as np

        if len(context) < 1:
            raise ValueError("speed context must not be empty")
        if horizon < 1:
            raise ValueError("horizon must be at least 1")
        series = np.asarray(context, dtype=np.float32)
        t0 = time.perf_counter()
        point, quantiles = self._model.forecast(horizon=horizon, inputs=[series])
        elapsed = time.perf_counter() - t0
        values = [float(value) for value in point[0][:horizon]]
        q_rows = [[float(value) for value in quantiles[0][step]] for step in range(horizon)]
        return values, q_rows, elapsed

    def forecast_speed(
        self,
        context: Sequence[float],
        horizon: int = HORIZON_STEPS,
    ) -> tuple[list[float], list[list[float]], float]:
        values, q_rows, elapsed = self._forecast_raw(context, horizon)
        return [max(0.0, value) for value in values], q_rows, elapsed

    def forecast_speed_xreg(
        self,
        context: Sequence[float],
        packed_covariates: dict[str, Sequence[float]],
        horizon: int = HORIZON_STEPS,
    ) -> tuple[list[float], list[list[float]], float]:
        residual, xreg_horizon, _fit = xreg_plus_timesfm_inputs(context, packed_covariates)
        if len(xreg_horizon) < horizon:
            raise ValueError("packed covariates horizon shorter than requested horizon")
        values, quantiles, elapsed = self._forecast_raw(residual, horizon)
        shifted = [max(0.0, pred + add) for pred, add in zip(values, xreg_horizon[:horizon])]
        q_rows = []
        for step, add in enumerate(xreg_horizon[:horizon]):
            q_rows.append([value + add for value in quantiles[step]])
        return shifted, q_rows, elapsed
