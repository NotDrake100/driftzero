"""Linear motion student. Stdlib only. Fit on training trips, never on rows mixed across trips."""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass
from math import exp, log
from pathlib import Path

from driftzero_ml.features.causal_imu import FEATURE_NAMES, MAX_SPEED_MPS


@dataclass(frozen=True)
class LinearMotionStudent:
    weights_speed: tuple[float, ...]
    weights_stop: tuple[float, ...]
    weights_logvar: tuple[float, ...]
    feature_names: tuple[str, ...] = FEATURE_NAMES

    def __post_init__(self) -> None:
        dim = len(self.feature_names) + 1
        for name, weights in (
            ("speed", self.weights_speed),
            ("stop", self.weights_stop),
            ("logvar", self.weights_logvar),
        ):
            if len(weights) != dim:
                raise ValueError(f"{name} weights must have length {dim}")

    def infer(self, vector: Sequence[float]) -> tuple[float, float, float]:
        if len(vector) != len(self.feature_names):
            raise ValueError("feature vector length mismatch")
        raw_speed = _dot(self.weights_speed, vector)
        stop_logit = _dot(self.weights_stop, vector)
        log_var = _dot(self.weights_logvar, vector)
        speed = min(MAX_SPEED_MPS, max(0.0, _softplus(raw_speed)))
        log_var = min(6.0, max(-8.0, log_var))
        return speed, stop_logit, log_var


def fit_linear_motion_student(
    windows: Sequence[Sequence[float]],
    speed_mps: Sequence[float],
    stopped: Sequence[float],
    *,
    l2: float = 1e-2,
) -> LinearMotionStudent:
    if not windows:
        raise ValueError("windows must not be empty")
    if not (len(windows) == len(speed_mps) == len(stopped)):
        raise ValueError("windows and labels must have the same length")
    dim = len(FEATURE_NAMES)
    for row in windows:
        if len(row) != dim:
            raise ValueError(f"each window must have {dim} features")
    speed_targets = [_inv_softplus(max(0.0, float(value))) for value in speed_mps]
    stop_targets = [_logit(float(value)) for value in stopped]
    logvar_targets = [log(max(1e-4, (float(value) * 0.15 + 0.2) ** 2)) for value in speed_mps]
    return LinearMotionStudent(
        weights_speed=tuple(_ridge(windows, speed_targets, l2)),
        weights_stop=tuple(_ridge(windows, stop_targets, l2)),
        weights_logvar=tuple(_ridge(windows, logvar_targets, l2)),
    )


def zero_speed_baseline(count: int) -> list[float]:
    """Blackout speed freeze: assume stopped. Analogous to position freeze."""

    if count < 1:
        raise ValueError("count must be at least 1")
    return [0.0] * count


def save_linear_student(model: LinearMotionStudent, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": "driftzero.linear_speed.v1",
        "feature_names": list(model.feature_names),
        "weights_speed": list(model.weights_speed),
        "weights_stop": list(model.weights_stop),
        "weights_logvar": list(model.weights_logvar),
    }
    path.write_text(json.dumps(payload, indent=2) + "\n")


def load_linear_student(path: Path) -> LinearMotionStudent:
    payload = json.loads(path.read_text())
    names = tuple(payload["feature_names"])
    if names != FEATURE_NAMES:
        raise ValueError("linear student feature_names do not match FEATURE_NAMES")
    return LinearMotionStudent(
        weights_speed=tuple(payload["weights_speed"]),
        weights_stop=tuple(payload["weights_stop"]),
        weights_logvar=tuple(payload["weights_logvar"]),
        feature_names=names,
    )


def _dot(weights: Sequence[float], vector: Sequence[float]) -> float:
    acc = weights[0]
    for weight, value in zip(weights[1:], vector):
        acc += weight * value
    return acc


def _softplus(value: float) -> float:
    if value > 40.0:
        return value
    return log(1.0 + exp(value))


def _inv_softplus(value: float) -> float:
    if value <= 1e-8:
        return -20.0
    if value > 40.0:
        return value
    return log(exp(value) - 1.0)


def _logit(probability: float) -> float:
    clipped = min(0.999, max(0.001, probability))
    return log(clipped / (1.0 - clipped))


def _ridge(windows: Sequence[Sequence[float]], targets: Sequence[float], l2: float) -> list[float]:
    rows = [[1.0, *row] for row in windows]
    width = len(rows[0])
    gram = [[0.0] * width for _ in range(width)]
    rhs = [0.0] * width
    for row, target in zip(rows, targets):
        for i in range(width):
            rhs[i] += row[i] * target
            for j in range(width):
                gram[i][j] += row[i] * row[j]
    for i in range(width):
        gram[i][i] += l2
    return _solve(gram, rhs)


def _solve(matrix: list[list[float]], rhs: list[float]) -> list[float]:
    n = len(rhs)
    aug = [matrix[i][:] + [rhs[i]] for i in range(n)]
    for i in range(n):
        pivot = i
        for row in range(i + 1, n):
            if abs(aug[row][i]) > abs(aug[pivot][i]):
                pivot = row
        aug[i], aug[pivot] = aug[pivot], aug[i]
        diag = aug[i][i]
        if abs(diag) < 1e-12:
            raise ValueError("singular linear system")
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
