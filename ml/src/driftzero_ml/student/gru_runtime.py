"""Stdlib causal GRU forward. Matches PyTorch nn.GRU + Linear, no torch import.

Used to score and document the exported motion_student_v2 JSON. Kotlin should
copy these equations. TimesFM is not referenced.
"""

from __future__ import annotations

import json
import math
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from driftzero_ml.features.causal_imu import MAX_SPEED_MPS
from driftzero_ml.learned_imu import LOG_SIGMA_MAX, LOG_SIGMA_MIN

GRU_SCHEMA = "driftzero.causal_gru.v1"
INPUT_SIZE = 6
ALIGN_LEN = 16
HIDDEN = 32
LAYERS = 1
HEAD_NAMES: tuple[str, ...] = (
    "speed_raw",
    "stop_logit",
    "log_speed_variance",
    "yaw_rate_radps",
    "log_yaw_variance",
    "ronin_dx_m",
    "ronin_dy_m",
    "tlio_log_sigma_xy",
)
HEAD_DIM = len(HEAD_NAMES)
BUMP_LOGVAR_INFLATE = 1.5


@dataclass(frozen=True)
class GruMotionPrediction:
    forward_speed_mps: float
    stop_logit: float
    log_speed_variance: float
    yaw_rate_radps: float
    log_yaw_variance: float
    dx_m: float
    dy_m: float
    log_sigma_xy: float
    bump_gated: bool


@dataclass(frozen=True)
class CausalGruStudent:
    """One-layer unidirectional GRU. Weights are float lists."""

    hidden: int
    align_len: int
    input_mean: tuple[float, ...]
    input_std: tuple[float, ...]
    weight_ih: tuple[tuple[float, ...], ...]
    weight_hh: tuple[tuple[float, ...], ...]
    bias_ih: tuple[float, ...]
    bias_hh: tuple[float, ...]
    head_weight: tuple[tuple[float, ...], ...]
    head_bias: tuple[float, ...]
    feature_names: tuple[str, ...] = HEAD_NAMES

    def infer(
        self,
        sequence: Sequence[Sequence[float]],
        *,
        bump: bool = False,
        gate_bump: bool = False,
    ) -> GruMotionPrediction:
        if not sequence:
            raise ValueError("sequence must not be empty")
        hidden = [0.0] * self.hidden
        for row in _left_pad(sequence, self.align_len, INPUT_SIZE):
            if len(row) != INPUT_SIZE:
                raise ValueError(f"each timestep must have {INPUT_SIZE} channels")
            normed = [
                (value - self.input_mean[i]) / self.input_std[i]
                for i, value in enumerate(row)
            ]
            hidden = _gru_step(normed, hidden, self.weight_ih, self.weight_hh, self.bias_ih, self.bias_hh)
        raw = _affine(hidden, self.head_weight, self.head_bias)
        if len(raw) != HEAD_DIM:
            raise ValueError(f"head must emit {HEAD_DIM} values")
        speed = min(MAX_SPEED_MPS, max(0.0, _softplus(raw[0])))
        log_speed = _clamp(raw[2], LOG_SIGMA_MIN, LOG_SIGMA_MAX)
        log_yaw = _clamp(raw[4], LOG_SIGMA_MIN, LOG_SIGMA_MAX)
        gated = bool(gate_bump and bump)
        if gated:
            log_speed = _clamp(log_speed + BUMP_LOGVAR_INFLATE, LOG_SIGMA_MIN, LOG_SIGMA_MAX)
            log_yaw = _clamp(log_yaw + BUMP_LOGVAR_INFLATE, LOG_SIGMA_MIN, LOG_SIGMA_MAX)
        return GruMotionPrediction(
            forward_speed_mps=speed,
            stop_logit=raw[1],
            log_speed_variance=log_speed,
            yaw_rate_radps=raw[3],
            log_yaw_variance=log_yaw,
            dx_m=raw[5],
            dy_m=raw[6],
            log_sigma_xy=_clamp(raw[7], LOG_SIGMA_MIN, LOG_SIGMA_MAX),
            bump_gated=gated,
        )


def save_gru_student(model: CausalGruStudent, path: Path, extra: dict | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": GRU_SCHEMA,
        "hidden": model.hidden,
        "layers": LAYERS,
        "align_len": model.align_len,
        "input_size": INPUT_SIZE,
        "input_mean": list(model.input_mean),
        "input_std": list(model.input_std),
        "head_names": list(HEAD_NAMES),
        "weight_ih": [list(row) for row in model.weight_ih],
        "weight_hh": [list(row) for row in model.weight_hh],
        "bias_ih": list(model.bias_ih),
        "bias_hh": list(model.bias_hh),
        "head_weight": [list(row) for row in model.head_weight],
        "head_bias": list(model.head_bias),
        "bump_logvar_inflate": BUMP_LOGVAR_INFLATE,
        "timesfm": False,
    }
    if extra:
        payload.update(extra)
    path.write_text(json.dumps(payload) + "\n")


def load_gru_student(path: Path) -> CausalGruStudent:
    payload = json.loads(path.read_text())
    if payload.get("schema") != GRU_SCHEMA:
        raise ValueError(f"unsupported GRU schema: {payload.get('schema')}")
    return CausalGruStudent(
        hidden=int(payload["hidden"]),
        align_len=int(payload["align_len"]),
        input_mean=tuple(float(x) for x in payload["input_mean"]),
        input_std=tuple(max(1e-6, float(x)) for x in payload["input_std"]),
        weight_ih=_matrix(payload["weight_ih"]),
        weight_hh=_matrix(payload["weight_hh"]),
        bias_ih=tuple(float(x) for x in payload["bias_ih"]),
        bias_hh=tuple(float(x) for x in payload["bias_hh"]),
        head_weight=_matrix(payload["head_weight"]),
        head_bias=tuple(float(x) for x in payload["head_bias"]),
    )


def multiply_adds_per_inference(hidden: int = HIDDEN, align_len: int = ALIGN_LEN) -> int:
    """One multiply-add is one multiply plus one add. GRU + linear head."""

    # 3 gates, each: I*H + H*H + H (bias counted as add-only, omitted)
    gru = align_len * 3 * (INPUT_SIZE * hidden + hidden * hidden)
    head = hidden * HEAD_DIM
    return gru + head


def _left_pad(sequence: Sequence[Sequence[float]], length: int, width: int) -> list[list[float]]:
    rows = [list(row) for row in sequence]
    if len(rows) >= length:
        return rows[-length:]
    pad = [[0.0] * width for _ in range(length - len(rows))]
    return pad + rows


def _gru_step(
    x: Sequence[float],
    h: Sequence[float],
    weight_ih: Sequence[Sequence[float]],
    weight_hh: Sequence[Sequence[float]],
    bias_ih: Sequence[float],
    bias_hh: Sequence[float],
) -> list[float]:
    hidden = len(h)
    # PyTorch stacks reset, update, new along the first dimension.
    ih = _matvec(weight_ih, x, bias_ih)
    hh = _matvec(weight_hh, h, bias_hh)
    r = [_sigmoid(ih[i] + hh[i]) for i in range(hidden)]
    z = [_sigmoid(ih[hidden + i] + hh[hidden + i]) for i in range(hidden)]
    n = [_tanh(ih[2 * hidden + i] + r[i] * hh[2 * hidden + i]) for i in range(hidden)]
    return [(1.0 - z[i]) * n[i] + z[i] * h[i] for i in range(hidden)]


def _affine(vector: Sequence[float], weight: Sequence[Sequence[float]], bias: Sequence[float]) -> list[float]:
    return [_dot(row, vector) + b for row, b in zip(weight, bias)]


def _matvec(matrix: Sequence[Sequence[float]], vector: Sequence[float], bias: Sequence[float]) -> list[float]:
    return [_dot(row, vector) + b for row, b in zip(matrix, bias)]


def _dot(weights: Sequence[float], vector: Sequence[float]) -> float:
    return sum(a * b for a, b in zip(weights, vector))


def _sigmoid(value: float) -> float:
    if value >= 40.0:
        return 1.0
    if value <= -40.0:
        return 0.0
    return 1.0 / (1.0 + math.exp(-value))


def _tanh(value: float) -> float:
    if value >= 20.0:
        return 1.0
    if value <= -20.0:
        return -1.0
    e = math.exp(2.0 * value)
    return (e - 1.0) / (e + 1.0)


def _softplus(value: float) -> float:
    if value > 40.0:
        return value
    return math.log(1.0 + math.exp(value))


def _clamp(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


def _matrix(rows: Sequence[Sequence[object]]) -> tuple[tuple[float, ...], ...]:
    return tuple(tuple(float(item) for item in row) for row in rows)
