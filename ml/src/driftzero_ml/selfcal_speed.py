"""Per-trip online ridge of causal IMU features to GNSS speed.

Fit uses only samples before the blackout start. The model is then frozen.
Stdlib only. No TimesFM. No torch.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from typing import Callable, Sequence

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    MAX_SAMPLES,
    MAX_SPEED_MPS,
    MIN_SAMPLES,
    causal_gravity_vectors,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)

ROLL_WINDOW_NS = 180_000_000_000
RIDGE_L2 = 1e-2
MIN_FIT_SAMPLES = 40
MIN_AFFINE_SAMPLES = 20
MIN_LINEAR_STD = 0.50
BAND_NAMES = ("vib_band_low", "vib_band_mid", "vib_band_high")
SELF_CAL_FEATURE_NAMES: tuple[str, ...] = FEATURE_NAMES + BAND_NAMES

if set(SELF_CAL_FEATURE_NAMES) & GNSS_KEYS:
    raise RuntimeError("self-cal features must not include GNSS fields")


@dataclass(frozen=True)
class SelfCalModel:
    weights: tuple[float, ...]
    feature_names: tuple[str, ...]
    n_fit: int
    used_span_s: float
    l2: float

    def infer(self, vector: Sequence[float]) -> float:
        if len(vector) != len(self.feature_names):
            raise ValueError("self-cal feature length mismatch")
        acc = self.weights[0]
        for weight, value in zip(self.weights[1:], vector):
            acc += weight * value
        return min(MAX_SPEED_MPS, max(0.0, acc))


@dataclass(frozen=True)
class AffineCorrection:
    """v_hat = scale * student_speed + bias, fit before blackout only."""

    scale: float
    bias: float
    n_fit: int
    used_span_s: float
    linear_std: float

    def apply(self, student_speed: float) -> float:
        return min(MAX_SPEED_MPS, max(0.0, self.scale * student_speed + self.bias))


def vibration_bands(samples: Sequence) -> tuple[float, float, float]:
    """Low / mid / high residual-energy proxies. 10 Hz cannot resolve fine bands."""

    gravity = causal_gravity_vectors(samples)
    residuals: list[float] = []
    for row, grav in zip(samples, gravity):
        dx = row.ax - grav[0]
        dy = row.ay - grav[1]
        dz = row.az - grav[2]
        residuals.append(sqrt(dx * dx + dy * dy + dz * dz))
    if not residuals:
        return (0.0, 0.0, 0.0)
    low_series: list[float] = []
    for index, value in enumerate(residuals):
        if index == 0:
            low_series.append(value)
        elif index == 1:
            low_series.append(0.5 * (residuals[0] + value))
        else:
            low_series.append((residuals[index - 2] + residuals[index - 1] + value) / 3.0)
    low_e = sum(item * item for item in low_series) / len(low_series)
    mid_e = sum((raw - low) ** 2 for raw, low in zip(residuals, low_series)) / len(residuals)
    diffs: list[float] = []
    for index in range(1, len(samples)):
        dt = (samples[index].timestamp_ns - samples[index - 1].timestamp_ns) / 1_000_000_000.0
        if dt <= 1e-4:
            continue
        diffs.append((residuals[index] - residuals[index - 1]) / dt)
    high_e = 0.0 if not diffs else sum(item * item for item in diffs) / len(diffs)
    return (low_e, mid_e, high_e)


def selfcal_vector(samples: Sequence) -> tuple[float, ...]:
    features = extract_causal_imu_features(samples)
    return features.vector + vibration_bands(samples)


def calibration_budget_s(records: Sequence[dict], start_ns: int) -> float:
    """Seconds from the first healthy GNSS sample to the blackout start."""

    first: int | None = None
    for row in records:
        stamp = int(row["timestamp_ns"])
        if stamp >= start_ns:
            break
        if row.get("gnss_speed_mps") is None:
            continue
        if "latitude_deg" not in row or "longitude_deg" not in row:
            continue
        first = stamp
        break
    if first is None:
        return 0.0
    return (start_ns - first) / 1_000_000_000.0


def budget_bucket(budget_s: float) -> str:
    if budget_s < 60.0:
        return "under_60s"
    if budget_s <= 300.0:
        return "60_to_300s"
    return "over_300s"


def fit_selfcal(
    records: Sequence[dict],
    start_ns: int,
    *,
    roll_ns: int = ROLL_WINDOW_NS,
    l2: float = RIDGE_L2,
    min_fit: int = MIN_FIT_SAMPLES,
) -> SelfCalModel | None:
    """Ridge on causal IMU features to GNSS speed. Samples with t >= start_ns are ignored."""

    if roll_ns <= 0:
        raise ValueError("roll_ns must be positive")
    window_start = start_ns - roll_ns
    samples = records_to_imu_samples(records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}
    rows: list[tuple[float, ...]] = []
    targets: list[float] = []
    used_t: list[int] = []
    for row in records:
        stamp = int(row["timestamp_ns"])
        if stamp >= start_ns or stamp < window_start:
            continue
        if row.get("gnss_speed_mps") is None:
            continue
        index = by_t.get(stamp)
        if index is None:
            continue
        window = trim_causal_window(samples[max(0, index + 1 - MAX_SAMPLES) : index + 1], stamp)
        if len(window) < MIN_SAMPLES:
            continue
        rows.append(selfcal_vector(window))
        targets.append(max(0.0, float(row["gnss_speed_mps"])))
        used_t.append(stamp)
    if len(rows) < min_fit:
        return None
    return fit_selfcal_precomputed(rows, targets, used_t, l2=l2, min_fit=min_fit)


def fit_selfcal_precomputed(
    rows: Sequence[Sequence[float]],
    targets: Sequence[float],
    used_t: Sequence[int],
    *,
    l2: float = RIDGE_L2,
    min_fit: int = MIN_FIT_SAMPLES,
) -> SelfCalModel | None:
    if len(rows) < min_fit:
        return None
    weights = _ridge(rows, targets, l2)
    span = 0.0 if len(used_t) < 2 else (used_t[-1] - used_t[0]) / 1_000_000_000.0
    return SelfCalModel(tuple(weights), SELF_CAL_FEATURE_NAMES, len(rows), span, l2)


def fit_affine_correction(
    records: Sequence[dict],
    start_ns: int,
    student_speed_at: Callable[[dict], float | None],
    *,
    roll_ns: int = ROLL_WINDOW_NS,
    l2: float = RIDGE_L2,
    min_fit: int = MIN_AFFINE_SAMPLES,
) -> AffineCorrection:
    """Per-trip scale and bias on the frozen offline student. Identity if under-determined."""

    window_start = start_ns - roll_ns
    xs: list[tuple[float, ...]] = []
    ys: list[float] = []
    used_t: list[int] = []
    for row in records:
        stamp = int(row["timestamp_ns"])
        if stamp >= start_ns or stamp < window_start:
            continue
        if row.get("gnss_speed_mps") is None:
            continue
        pred = student_speed_at(row)
        if pred is None:
            continue
        xs.append((pred,))
        ys.append(max(0.0, float(row["gnss_speed_mps"])))
        used_t.append(stamp)
    span = 0.0 if len(used_t) < 2 else (used_t[-1] - used_t[0]) / 1_000_000_000.0
    if len(xs) < min_fit:
        return AffineCorrection(1.0, 0.0, len(xs), span, 0.0)
    mean_x = sum(row[0] for row in xs) / len(xs)
    var_x = sum((row[0] - mean_x) ** 2 for row in xs) / len(xs)
    std_x = sqrt(var_x)
    if std_x < MIN_LINEAR_STD:
        return AffineCorrection(1.0, 0.0, len(xs), span, std_x)
    weights = _ridge(xs, ys, l2)
    return AffineCorrection(weights[1], weights[0], len(xs), span, std_x)


def persist_selfcal_speed_fn(
    model: SelfCalModel | None,
    imu_records: Sequence[dict],
) -> Callable[[dict, float], float]:
    samples = records_to_imu_samples(imu_records)
    by_t = {sample.timestamp_ns: index for index, sample in enumerate(samples)}

    def speed_at(row: dict, prior: float) -> float:
        if model is None:
            return prior
        index = by_t.get(int(row["timestamp_ns"]))
        if index is None:
            return prior
        window = trim_causal_window(
            samples[max(0, index + 1 - MAX_SAMPLES) : index + 1],
            int(row["timestamp_ns"]),
        )
        if len(window) < MIN_SAMPLES:
            return prior
        return model.infer(selfcal_vector(window))

    return speed_at


def linear_selfcal_speed_fn(
    student_speed_at: Callable[[dict], float | None],
    correction: AffineCorrection,
) -> Callable[[dict, float], float]:
    def speed_at(row: dict, prior: float) -> float:
        pred = student_speed_at(row)
        if pred is None:
            return prior
        return correction.apply(pred)

    return speed_at


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
