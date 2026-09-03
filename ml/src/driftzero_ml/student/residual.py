"""Causal GNSS-speed residual student. Target is delta_v, not absolute speed.

delta_v = v_gnss(t) - v_last_accepted_gnss(t0) at 1, 5, 10, 30 s into a hold.
The only GNSS-derived feature is v_last_accepted_mps at t0. Labels are m/s.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from math import exp, log, pi
from pathlib import Path
from typing import Mapping, Sequence

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.datasets.io_vnbd import (
    SmartphoneRow,
    keep_nondecreasing_rows,
    load_smartphone_csv,
    screening_smartphone_tables,
)
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    MAX_SAMPLES,
    MIN_SAMPLES,
    STOP_ZUPT,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.features.residual_imu import (
    HOLD_EXTRA_NAMES,
    hold_mean_from_series,
    horizontal_sf_and_omega_series,
)
from driftzero_ml.gnss_truth import SPEED_UNIT_MPS, column_speed_to_mps, infer_speed_unit, unique_fix_indices
from driftzero_ml.io_vnbd import assign_grouped_trip_splits
from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M, picp
from driftzero_ml.student.linear import LinearMotionStudent, _dot, _ridge, load_linear_student

HORIZONS_S: tuple[float, ...] = (1.0, 5.0, 10.0, 30.0)
HORIZON_SLACK_S: dict[float, float] = {1.0: 0.51, 5.0: 1.5, 10.0: 2.5, 30.0: 5.0}
MIN_HOLD_SAMPLES = 3
MIN_T0_SPACING_NS = 1_000_000_000
CLIP_DELTA_MPS = 3.0
RIDGE_L2 = 1e-2
SEED_DEFAULT = "26168"
ALLOWED_GNSS_FEATURE = "v_last_accepted_mps"
STOP_FLAG_NAME = "stop_flag"
ELAPSED_NAME = "elapsed_s"
RESIDUAL_FEATURE_NAMES: tuple[str, ...] = FEATURE_NAMES + HOLD_EXTRA_NAMES + (
    STOP_FLAG_NAME,
    ELAPSED_NAME,
    ALLOWED_GNSS_FEATURE,
)
RESIDUAL_RIDGE_SCHEMA = "driftzero.linear_speed_residual.v1"
LOGVAR_MIN = -8.0
LOGVAR_MAX = 6.0
SIGMA_MIN = 0.10
SIGMA_MAX = 8.0
Z68 = 1.0
Z95 = 1.959964
CHI2_1DOF_95 = 3.841458820694124
CLEAR_MARGIN_REL = 0.10
MIN_LOCKED_WINDOWS = 50
MIN_RIDGE_TRAIN = 80
TAU_GRID_S: tuple[float, ...] = (5.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0)

_FORBIDDEN_NAME_PARTS = (
    "gnss",
    "latitude",
    "longitude",
    "satellite",
    "bearing",
    "altitude",
    "accuracy",
)

if set(RESIDUAL_FEATURE_NAMES) & GNSS_KEYS:
    raise RuntimeError("residual feature names must not include GNSS keys")


def assert_residual_feature_names(names: Sequence[str]) -> None:
    """Reject GNSS-derived columns other than v_last_accepted_mps at t0."""

    leaked = set(names) & GNSS_KEYS
    if leaked:
        raise AssertionError(f"GNSS keys in residual features: {sorted(leaked)}")
    if ALLOWED_GNSS_FEATURE not in names:
        raise AssertionError(f"{ALLOWED_GNSS_FEATURE} must be present as the t0 scalar")
    for name in names:
        if name == ALLOWED_GNSS_FEATURE:
            continue
        lower = name.lower()
        for part in _FORBIDDEN_NAME_PARTS:
            if part in lower:
                raise AssertionError(f"GNSS-derived feature name not allowed: {name}")


assert_residual_feature_names(RESIDUAL_FEATURE_NAMES)


@dataclass(frozen=True)
class ResidualWindow:
    trip_id: str
    t0_ns: int
    t_ns: int
    horizon_s: float
    elapsed_s: float
    v_last_mps: float
    v_gnss_mps: float
    delta_v_mps: float
    features: tuple[float, ...]
    imu12: tuple[float, ...]


@dataclass(frozen=True)
class ResidualRidgeStudent:
    feature_names: tuple[str, ...]
    feature_mean: tuple[float, ...]
    feature_std: tuple[float, ...]
    weights_delta: tuple[float, ...]
    weights_logvar: tuple[float, ...]
    clip_mps: float
    seed: str
    horizon_s: float
    l2: float
    n_train: int
    train_resid_q68: float
    train_resid_q95: float
    train_resid_rmse: float

    def infer_delta(self, vector: Sequence[float]) -> tuple[float, float]:
        if len(vector) != len(self.feature_names):
            raise ValueError("residual feature length mismatch")
        scaled = _scale_row(vector, self.feature_mean, self.feature_std)
        delta = _dot(self.weights_delta, scaled)
        log_var = min(LOGVAR_MAX, max(LOGVAR_MIN, _dot(self.weights_logvar, scaled)))
        return delta, log_var

    def sigma(self, log_var: float) -> float:
        var = exp(log_var) if log_var <= 80.0 else SIGMA_MAX * SIGMA_MAX
        return min(SIGMA_MAX, max(SIGMA_MIN, var ** 0.5))


def smartphone_rows_to_residual_records(rows: Sequence[SmartphoneRow]) -> list[dict]:
    """IMU plus score-only lat/lon/speed for unique-fix labels. Features ignore GNSS."""

    records: list[dict] = []
    for row in keep_nondecreasing_rows(list(rows)):
        item: dict = {
            "trip_id": row.trip_id,
            "timestamp_ns": row.timestamp_ns,
            "ax": row.ax,
            "ay": row.ay,
            "az": row.az,
            "speed_unit": row.speed_unit,
        }
        if row.latitude_deg is not None:
            item["latitude_deg"] = row.latitude_deg
        if row.longitude_deg is not None:
            item["longitude_deg"] = row.longitude_deg
        if row.speed_kmh is not None:
            item["speed_mps"] = column_speed_to_mps(row.speed_kmh, row.speed_unit)
        if row.gyro_yaw is not None:
            item["gx"] = row.gyro_yaw
        if row.gyro_pitch is not None:
            item["gy"] = row.gyro_pitch
        if row.gyro_roll is not None:
            item["gz"] = row.gyro_roll
        records.append(item)
    return records


def _short_error(path: Path, error: Exception) -> str:
    text = str(error)
    prefix = f"{path}:"
    if text.startswith(prefix):
        return text[len(prefix) :].strip()
    return text


def verify_speed_labels_mps(records: Sequence[dict]) -> None:
    """Re-check unique-fix finite difference against already-converted m/s labels."""

    probe = []
    for row in records:
        if "latitude_deg" not in row or "longitude_deg" not in row:
            continue
        if row.get("speed_mps") is None:
            continue
        probe.append(
            {
                "timestamp_ns": int(row["timestamp_ns"]),
                "latitude_deg": float(row["latitude_deg"]),
                "longitude_deg": float(row["longitude_deg"]),
                "speed_column": float(row["speed_mps"]),
            }
        )
    infer_speed_unit(probe, declared=SPEED_UNIT_MPS, column_key="speed_column")


def load_residual_trips(root: Path) -> tuple[dict[str, list[dict]], tuple[str, ...]]:
    skipped: list[str] = []
    trips: dict[str, list[dict]] = {}
    for path in screening_smartphone_tables(root):
        try:
            rows = load_smartphone_csv(path)
            records = smartphone_rows_to_residual_records(rows)
            if len(unique_fix_indices(records)) < 4:
                skipped.append(f"{path.stem}:too_few_unique_fixes")
                continue
            verify_speed_labels_mps(records)
        except (ValueError, KeyError) as error:
            skipped.append(f"{path.stem}:{_short_error(path, error)}")
            continue
        trips[path.stem] = records
    return trips, tuple(skipped)


def synthetic_speed_residual_trip(
    *,
    trip_id: str,
    duration_s: float = 40.0,
    hz: int = 10,
    v0: float = 16.0,
    accel_mps2: float = -0.4,
    lat0: float = 18.52,
    lon0: float = 73.86,
    seed: int = 0,
) -> list[dict]:
    """Northbound coast with unique GNSS every 1 s. IMU ax is the signed accel."""

    if hz <= 0 or duration_s <= 0:
        raise ValueError("hz and duration_s must be positive")
    dt = 1.0 / hz
    dt_ns = 1_000_000_000 // hz
    meters_to_lat = 180.0 / (pi * EARTH_MEAN_RADIUS_M)
    records: list[dict] = []
    north_m = 0.0
    speed = v0
    for index in range(int(duration_s * hz)):
        t = index * dt
        speed = max(0.0, v0 + accel_mps2 * t)
        north_m += speed * dt
        stamp = index * dt_ns
        ax = accel_mps2 if speed > 0.0 else 0.0
        row: dict = {
            "trip_id": trip_id,
            "timestamp_ns": stamp,
            "ax": ax,
            "ay": 0.0,
            "az": 9.80665,
            "gx": 0.0,
            "gy": 0.0,
            "gz": 0.0,
            "speed_mps": speed,
            "speed_unit": SPEED_UNIT_MPS,
            "latitude_deg": lat0 + north_m * meters_to_lat,
            "longitude_deg": lon0,
        }
        records.append(row)
    _ = seed
    return records


def residual_windows_for_trip(records: Sequence[dict], trip_id: str) -> list[ResidualWindow]:
    """One window per unique-fix t0 and horizon with a nearby later unique fix."""

    if not records:
        return []
    samples = records_to_imu_samples(records)
    if not samples:
        return []
    horiz_series, omega_series = horizontal_sf_and_omega_series(samples)
    stamps = [row.timestamp_ns for row in samples]
    fixes = _thin_speed_fixes(_unique_speed_fixes(records), MIN_T0_SPACING_NS)
    if len(fixes) < 2:
        return []
    windows: list[ResidualWindow] = []
    for t0_index, t0 in enumerate(fixes[:-1]):
        for horizon in HORIZONS_S:
            later = _fix_at_horizon(fixes[t0_index + 1 :], t0[0], horizon)
            if later is None:
                continue
            t_ns, v_t = later
            elapsed_s = (t_ns - t0[0]) / 1_000_000_000.0
            if elapsed_s <= 0.0:
                continue
            end_index = _rightmost_at_or_before(stamps, t_ns)
            if end_index < 0:
                continue
            recent = samples[max(0, end_index + 1 - MAX_SAMPLES) : end_index + 1]
            window_1s = trim_causal_window(recent, t_ns)
            if len(window_1s) < MIN_SAMPLES:
                continue
            start_index = _rightmost_at_or_before(stamps, t0[0]) + 1
            if start_index < 0 or start_index > end_index:
                continue
            if end_index - start_index + 1 < MIN_HOLD_SAMPLES:
                continue
            imu_features = extract_causal_imu_features(window_1s)
            try:
                forward, omega_abs = hold_mean_from_series(
                    samples[start_index : end_index + 1],
                    horiz_series[start_index : end_index + 1],
                    omega_series[start_index : end_index + 1],
                    hold_start_ns=t0[0],
                    end_ns=t_ns,
                )
            except ValueError:
                continue
            stop_flag = 1.0 if imu_features.idle_score >= STOP_ZUPT else 0.0
            vector = imu_features.vector + (
                forward,
                omega_abs,
                stop_flag,
                elapsed_s,
                t0[1],
            )
            if len(vector) != len(RESIDUAL_FEATURE_NAMES):
                raise RuntimeError("residual feature length drifted")
            windows.append(
                ResidualWindow(
                    trip_id=trip_id,
                    t0_ns=t0[0],
                    t_ns=t_ns,
                    horizon_s=horizon,
                    elapsed_s=elapsed_s,
                    v_last_mps=t0[1],
                    v_gnss_mps=v_t,
                    delta_v_mps=v_t - t0[1],
                    features=vector,
                    imu12=imu_features.vector,
                )
            )
    return windows


def windows_by_split(
    trips: Mapping[str, Sequence[dict]],
    *,
    seed: str = SEED_DEFAULT,
) -> tuple[dict[str, list[ResidualWindow]], dict[str, str], str]:
    assignments = {
        row.trip_id: row.split
        for row in assign_grouped_trip_splits(sorted(trips), seed=seed)
    }
    grouped: dict[str, list[ResidualWindow]] = {
        "train": [],
        "validation": [],
        "public_test": [],
        "locked_test": [],
    }
    for trip_id, records in trips.items():
        split = assignments[trip_id]
        grouped.setdefault(split, [])
        grouped[split].extend(residual_windows_for_trip(records, trip_id))
    digest = split_assignment_hash(assignments, seed=seed, feature_names=RESIDUAL_FEATURE_NAMES)
    return grouped, assignments, digest


def split_assignment_hash(
    assignments: Mapping[str, str],
    *,
    seed: str,
    feature_names: Sequence[str],
) -> str:
    lines = [seed, ",".join(feature_names)]
    for trip_id in sorted(assignments):
        lines.append(f"{trip_id}\t{assignments[trip_id]}")
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def fit_residual_ridge(
    windows: Sequence[ResidualWindow],
    *,
    seed: str,
    horizon_s: float,
    l2: float = RIDGE_L2,
    clip_mps: float = CLIP_DELTA_MPS,
) -> ResidualRidgeStudent:
    if not windows:
        raise ValueError("no residual windows to fit")
    rows = [list(row.features) for row in windows]
    targets = [row.delta_v_mps for row in windows]
    mean_v, std_v = _fit_scaler(rows)
    scaled = [_scale_row(row, mean_v, std_v) for row in rows]
    weights_delta = tuple(_ridge(scaled, targets, l2))
    residuals = [
        target - _dot(weights_delta, row) for target, row in zip(targets, scaled)
    ]
    logvar_targets = [log(max(1e-4, err * err)) for err in residuals]
    weights_logvar = tuple(_ridge(scaled, logvar_targets, l2))
    abs_err = [abs(err) for err in residuals]
    rmse = (sum(err * err for err in residuals) / len(residuals)) ** 0.5
    return ResidualRidgeStudent(
        feature_names=RESIDUAL_FEATURE_NAMES,
        feature_mean=mean_v,
        feature_std=std_v,
        weights_delta=weights_delta,
        weights_logvar=weights_logvar,
        clip_mps=clip_mps,
        seed=seed,
        horizon_s=horizon_s,
        l2=l2,
        n_train=len(windows),
        train_resid_q68=empirical_quantile(abs_err, 0.68),
        train_resid_q95=empirical_quantile(abs_err, 0.95),
        train_resid_rmse=rmse,
    )


def decay_delta(v_last: float, v_mean: float, elapsed_s: float, tau_s: float) -> float:
    if tau_s <= 0.0:
        raise ValueError("tau_s must be positive")
    return (v_mean - v_last) * (1.0 - exp(-elapsed_s / tau_s))


def fit_decay_tau(
    windows: Sequence[ResidualWindow],
    v_mean: float,
    *,
    grid: Sequence[float] = TAU_GRID_S,
) -> float:
    if not windows:
        raise ValueError("no windows to fit decay tau")
    best_tau = grid[0]
    best_mae = float("inf")
    for tau in grid:
        if tau <= 0.0:
            raise ValueError("tau grid must be positive")
        pred = [decay_delta(row.v_last_mps, v_mean, row.elapsed_s, tau) for row in windows]
        err = mae(pred, [row.delta_v_mps for row in windows])
        if err < best_mae:
            best_mae = err
            best_tau = tau
    return best_tau


def mae(predicted: Sequence[float], target: Sequence[float]) -> float:
    if not predicted or len(predicted) != len(target):
        raise ValueError("mae needs non-empty aligned series")
    return sum(abs(a - b) for a, b in zip(predicted, target)) / len(predicted)


def empirical_quantile(values: Sequence[float], probability: float) -> float:
    if not values:
        raise ValueError("cannot compute a quantile of no values")
    if not 0.0 <= probability <= 1.0:
        raise ValueError("probability must be between zero and one")
    ordered = sorted(values)
    if probability == 0.0:
        return ordered[0]
    if probability == 1.0:
        return ordered[-1]
    rank = probability * (len(ordered) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(ordered) - 1)
    weight = rank - lo
    return ordered[lo] * (1.0 - weight) + ordered[hi] * weight


def evaluate_horizon(
    windows: Sequence[ResidualWindow],
    *,
    ridge: ResidualRidgeStudent | None,
    linear: LinearMotionStudent | None,
    v_mean: float,
    tau_s: float,
    persist_sigma_68: float,
    persist_sigma_95: float,
    decay_sigma_68: float,
    decay_sigma_95: float,
) -> dict:
    if not windows:
        return {"n": 0}
    delta_true = [row.delta_v_mps for row in windows]
    persist = [0.0] * len(windows)
    decay = [
        decay_delta(row.v_last_mps, v_mean, row.elapsed_s, tau_s) for row in windows
    ]
    payload: dict = {
        "n": len(windows),
        "persist": _score_constant_sigma(persist, delta_true, persist_sigma_68, persist_sigma_95),
        "decay_to_mean": _score_constant_sigma(decay, delta_true, decay_sigma_68, decay_sigma_95),
        "decay_tau_s": tau_s,
        "v_mean_mps": v_mean,
    }
    if linear is not None:
        linear_delta = []
        linear_sigma = []
        for row in windows:
            speed, _stop, log_var = linear.infer(row.imu12)
            linear_delta.append(speed - row.v_last_mps)
            var = exp(log_var) if log_var <= 80.0 else SIGMA_MAX * SIGMA_MAX
            linear_sigma.append(min(SIGMA_MAX, max(SIGMA_MIN, var ** 0.5)))
        payload["linear_absolute"] = {
            "mae_mps": mae(linear_delta, delta_true),
            "picp68": picp([a - b for a, b in zip(linear_delta, delta_true)], linear_sigma, Z68),
            "picp95": picp([a - b for a, b in zip(linear_delta, delta_true)], linear_sigma, Z95),
        }
    if ridge is not None:
        pred = []
        clipped = []
        sigma = []
        for row in windows:
            delta, log_var = ridge.infer_delta(row.features)
            pred.append(delta)
            clipped.append(min(ridge.clip_mps, max(-ridge.clip_mps, delta)))
            sigma.append(ridge.sigma(log_var))
        resid = [a - b for a, b in zip(pred, delta_true)]
        payload["ridge_residual"] = {
            "mae_mps": mae(pred, delta_true),
            "picp68": picp(resid, sigma, Z68),
            "picp95": picp(resid, sigma, Z95),
            "picp68_quantile": _coverage(resid, ridge.train_resid_q68),
            "picp95_quantile": _coverage(resid, ridge.train_resid_q95),
        }
        payload["ridge_clipped"] = {
            "mae_mps": mae(clipped, delta_true),
            "clip_mps": ridge.clip_mps,
        }
    return payload


def ridge_beats_persist(
    locked_by_horizon: Mapping[float, dict],
    *,
    rel: float = CLEAR_MARGIN_REL,
    min_n: int = MIN_LOCKED_WINDOWS,
    required: Sequence[float] = (5.0, 10.0),
) -> tuple[bool, str]:
    """GRU is allowed only if ridge beats persist by `rel` on eligible locked horizons."""

    eligible = [
        horizon
        for horizon in required
        if (locked_by_horizon.get(horizon) or {}).get("n", 0) >= min_n
        and (locked_by_horizon.get(horizon) or {}).get("ridge_residual")
    ]
    if not eligible:
        eligible = [
            horizon
            for horizon, row in locked_by_horizon.items()
            if row.get("n", 0) >= min_n and row.get("ridge_residual")
        ]
    if not eligible:
        return False, "no locked horizon has enough windows to test the margin"
    reasons: list[str] = []
    for horizon in eligible:
        row = locked_by_horizon[horizon]
        ridge = row.get("ridge_residual")
        persist = row.get("persist")
        if ridge is None or persist is None:
            reasons.append(f"{horizon:g}s missing scores")
            continue
        persist_mae = persist["mae_mps"]
        ridge_mae = ridge["mae_mps"]
        if persist_mae <= 1e-9:
            reasons.append(f"{horizon:g}s persist MAE is ~0")
            continue
        gain = (persist_mae - ridge_mae) / persist_mae
        if gain < rel:
            reasons.append(
                f"{horizon:g}s ridge MAE {ridge_mae:.4f} vs persist {persist_mae:.4f} "
                f"(rel {gain:.3f} < {rel})"
            )
    if reasons:
        return False, "; ".join(reasons)
    names = ", ".join(f"{h:g}s" for h in eligible)
    return True, f"ridge beat persist by at least {rel:.0%} on {names}"



def save_residual_ridge(model: ResidualRidgeStudent, path: Path, *, ship: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": RESIDUAL_RIDGE_SCHEMA,
        "ship": ship,
        "feature_names": list(model.feature_names),
        "feature_mean": list(model.feature_mean),
        "feature_std": list(model.feature_std),
        "weights_delta": list(model.weights_delta),
        "weights_logvar": list(model.weights_logvar),
        "clip_mps": model.clip_mps,
        "seed": model.seed,
        "horizon_s": model.horizon_s,
        "l2": model.l2,
        "n_train": model.n_train,
        "train_resid_q68": model.train_resid_q68,
        "train_resid_q95": model.train_resid_q95,
        "train_resid_rmse": model.train_resid_rmse,
        "speed_unit": "m/s",
        "target": "delta_v_mps",
    }
    path.write_text(json.dumps(payload, indent=2) + "\n")


def load_residual_ridge(path: Path) -> ResidualRidgeStudent:
    payload = json.loads(path.read_text())
    names = tuple(payload["feature_names"])
    if names != RESIDUAL_FEATURE_NAMES:
        raise ValueError("residual feature_names do not match RESIDUAL_FEATURE_NAMES")
    return ResidualRidgeStudent(
        feature_names=names,
        feature_mean=tuple(payload["feature_mean"]),
        feature_std=tuple(payload["feature_std"]),
        weights_delta=tuple(payload["weights_delta"]),
        weights_logvar=tuple(payload["weights_logvar"]),
        clip_mps=float(payload["clip_mps"]),
        seed=str(payload["seed"]),
        horizon_s=float(payload["horizon_s"]),
        l2=float(payload["l2"]),
        n_train=int(payload["n_train"]),
        train_resid_q68=float(payload["train_resid_q68"]),
        train_resid_q95=float(payload["train_resid_q95"]),
        train_resid_rmse=float(payload["train_resid_rmse"]),
    )


def _rightmost_at_or_before(stamps: Sequence[int], target_ns: int) -> int:
    lo = 0
    hi = len(stamps) - 1
    best = -1
    while lo <= hi:
        mid = (lo + hi) // 2
        if stamps[mid] <= target_ns:
            best = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return best


def _thin_speed_fixes(
    fixes: Sequence[tuple[int, float]],
    min_dt_ns: int,
) -> list[tuple[int, float]]:
    if min_dt_ns < 0:
        raise ValueError("min_dt_ns must be non-negative")
    out: list[tuple[int, float]] = []
    for item in fixes:
        if not out or item[0] - out[-1][0] >= min_dt_ns:
            out.append(item)
    return out


def _unique_speed_fixes(records: Sequence[dict]) -> list[tuple[int, float]]:
    out: list[tuple[int, float]] = []
    for index in unique_fix_indices(records):
        row = records[index]
        if row.get("speed_mps") is None:
            continue
        out.append((int(row["timestamp_ns"]), float(row["speed_mps"])))
    return out


def _fix_at_horizon(
    later: Sequence[tuple[int, float]],
    t0_ns: int,
    horizon_s: float,
) -> tuple[int, float] | None:
    target_ns = t0_ns + int(round(horizon_s * 1_000_000_000.0))
    slack_ns = int(round(HORIZON_SLACK_S[horizon_s] * 1_000_000_000.0))
    best: tuple[int, float] | None = None
    best_delta: int | None = None
    for stamp, speed in later:
        if stamp <= t0_ns:
            continue
        delta = abs(stamp - target_ns)
        if best_delta is None or delta < best_delta:
            best = (stamp, speed)
            best_delta = delta
    if best is None or best_delta is None or best_delta > slack_ns:
        return None
    return best


def _fit_scaler(rows: Sequence[Sequence[float]]) -> tuple[tuple[float, ...], tuple[float, ...]]:
    width = len(rows[0])
    n = len(rows)
    mean_v = []
    std_v = []
    for col in range(width):
        values = [row[col] for row in rows]
        mu = sum(values) / n
        var = sum((item - mu) ** 2 for item in values) / n
        mean_v.append(mu)
        std_v.append(max(1e-6, var ** 0.5))
    return tuple(mean_v), tuple(std_v)


def _scale_row(
    row: Sequence[float],
    mean_v: Sequence[float],
    std_v: Sequence[float],
) -> list[float]:
    return [(value - mu) / sd for value, mu, sd in zip(row, mean_v, std_v)]


def _score_constant_sigma(
    predicted: Sequence[float],
    target: Sequence[float],
    sigma_68: float,
    sigma_95: float,
) -> dict:
    row = {"mae_mps": mae(predicted, target)}
    if sigma_68 > 0.0 and sigma_95 > 0.0:
        resid = [a - b for a, b in zip(predicted, target)]
        row["picp68"] = _coverage(resid, sigma_68)
        row["picp95"] = _coverage(resid, sigma_95)
    return row


def _coverage(residual: Sequence[float], radius: float) -> float:
    if radius < 0:
        raise ValueError("coverage radius must be non-negative")
    if not residual:
        raise ValueError("residual must not be empty")
    return sum(1 for err in residual if abs(err) <= radius) / len(residual)


def maybe_load_linear(path: Path) -> LinearMotionStudent | None:
    if not path.is_file():
        return None
    return load_linear_student(path)
