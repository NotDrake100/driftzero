"""Causal learned-motion experiment for heading change and travelled distance.

Owns student/training configuration after the frozen A split. D owns shared
runner wiring. Hidden GNSS is training-label only. TimesFM is not imported.
The live phone path stays on the deterministic coast until D selects a
candidate. This module is research, not a 10% accuracy claim.
"""

from __future__ import annotations

import json
import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from pathlib import Path
from statistics import mean

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.learned_imu import (
    LearnedImuPrediction,
    default_synthetic_odometry,
    odometry_windows,
    train_from_odometry,
)
from driftzero_ml.metrics import (
    EARTH_MEAN_RADIUS_M,
    BlackoutMetrics,
    evaluate_blackout,
    wrap_heading_error_rad,
)

ROOT = Path(__file__).resolve().parents[3]
SPLIT_MANIFEST = ROOT / "results" / "cursor_diagnostics" / "split_manifest.json"
PREREGISTRATION = ROOT / "results" / "cursor_learned_motion" / "preregistration.json"
SEED = "26168"
UNCERTAINTY_FALLBACK = 2.5
CONFIGS: dict[str, dict] = {
    "speed_hold": {
        "kind": "deterministic_fallback",
        "outputs": ("persist_speed_mps", "persist_heading_rad"),
        "requires_torch": False,
    },
    "linear_joint_v1": {
        "kind": "linear_displacement_student",
        "outputs": ("delta_length_m", "delta_heading_rad", "log_sigma"),
        "requires_torch": False,
        "objective": "joint_distance_and_heading",
    },
    "ronin_tcn_v1": {
        "kind": "causal_tcn",
        "outputs": ("delta_length_m", "delta_heading_rad", "log_sigma"),
        "requires_torch": True,
        "parameter_budget": 250_000,
        "status": "preregistered_not_trained",
    },
    "gru_v1": {
        "kind": "causal_gru",
        "outputs": ("delta_length_m", "delta_heading_rad", "log_sigma"),
        "requires_torch": True,
        "parameter_budget": 250_000,
        "status": "preregistered_not_trained",
    },
}


@dataclass(frozen=True)
class SplitRoles:
    seed: str
    train: frozenset[str]
    development: frozenset[str]
    fresh_holdout: frozenset[str]
    locked: frozenset[str]
    excluded: frozenset[str]


@dataclass(frozen=True)
class JointStep:
    """One causal increment. Distance metres. Heading change radians."""

    distance_m: float
    heading_change_rad: float
    log_var_distance: float
    log_var_heading: float
    used_fallback: bool
    reason: str


def load_split_roles(path: Path | None = None) -> SplitRoles:
    payload = json.loads((path or SPLIT_MANIFEST).read_text())
    return SplitRoles(
        seed=str(payload["seed"]),
        train=frozenset(payload["train_session_groups"]),
        development=frozenset(payload["development_session_groups"]),
        fresh_holdout=frozenset(payload["fresh_holdout_session_groups"]),
        locked=frozenset(payload["locked_session_groups"]),
        excluded=frozenset(payload["excluded_session_groups"]),
    )


def group_role(trip_or_group: str, roles: SplitRoles | None = None) -> str:
    roles = roles or load_split_roles()
    group = session_group_id(trip_or_group)
    if group in roles.excluded:
        return "excluded"
    if group in roles.locked:
        return "locked_confirmation"
    if group in roles.development:
        return "development"
    if group in roles.fresh_holdout:
        return "fresh_holdout"
    if group in roles.train:
        return "train"
    raise ValueError(f"session group is not in the frozen split: {group}")


def assert_trainable(trip_or_group: str, roles: SplitRoles | None = None) -> str:
    """Allow training labels only from the frozen 24 train groups."""

    roles = roles or load_split_roles()
    group = session_group_id(trip_or_group)
    role = group_role(group, roles)
    if role != "train":
        raise ValueError(
            f"{group} has role {role}; training may use only the frozen train groups"
        )
    return group


def holdout_is_closed(used_groups: Sequence[str], roles: SplitRoles | None = None) -> None:
    roles = roles or load_split_roles()
    leaked = sorted({session_group_id(item) for item in used_groups} & roles.fresh_holdout)
    if leaked:
        raise ValueError(f"fresh holdout groups are closed: {', '.join(leaked)}")


def advance_latlon(
    origin: tuple[float, float],
    heading_rad: float,
    distance_m: float,
) -> tuple[float, float]:
    """Advance origin by distance along north-zero, east-positive heading."""

    lat, lon = origin
    if not math.isfinite(heading_rad) or not math.isfinite(distance_m):
        raise ValueError("heading and distance must be finite")
    if distance_m < 0:
        raise ValueError("distance_m must be non-negative")
    north = distance_m * math.cos(heading_rad)
    east = distance_m * math.sin(heading_rad)
    dlat = math.degrees(north / EARTH_MEAN_RADIUS_M)
    denom = EARTH_MEAN_RADIUS_M * math.cos(math.radians(lat))
    if abs(denom) < 1e-12:
        raise ValueError("cannot advance at the pole")
    dest = (lat + dlat, lon + math.degrees(east / denom))
    if not -90.0 <= dest[0] <= 90.0:
        raise ValueError(f"latitude out of range: {dest[0]}")
    return dest[0], ((dest[1] + 180.0) % 360.0) - 180.0


def persist_speed_heading_step(
    speed_mps: float,
    heading_rad: float,
    dt_s: float,
) -> JointStep:
    """Deterministic fallback: hold last trusted speed and heading."""

    if not math.isfinite(speed_mps) or speed_mps < 0:
        raise ValueError("speed_mps must be a non-negative finite value")
    if not math.isfinite(heading_rad) or not math.isfinite(dt_s) or dt_s <= 0:
        raise ValueError("heading and dt_s must be finite, and dt_s must be positive")
    return JointStep(
        distance_m=speed_mps * dt_s,
        heading_change_rad=0.0,
        log_var_distance=math.log(max(1e-4, (0.2 * speed_mps * dt_s + 0.5) ** 2)),
        log_var_heading=math.log(0.05**2),
        used_fallback=True,
        reason="persist_speed_heading",
    )


def joint_step_from_prediction(
    pred: LearnedImuPrediction,
    *,
    last_speed_mps: float,
    dt_s: float,
    uncertainty_gate: float = UNCERTAINTY_FALLBACK,
) -> JointStep:
    """Map a student window onto joint distance and heading change.

    High log-sigma falls back to persist-speed/heading. Missing finite heads
    are not converted to zero silently.
    """

    if dt_s <= 0 or not math.isfinite(dt_s):
        raise ValueError("dt_s must be a positive finite interval")
    if not math.isfinite(pred.delta_heading_rad):
        return replace(persist_speed_heading_step(last_speed_mps, 0.0, dt_s), reason="nonfinite_heading")
    if not math.isfinite(pred.dx_m) or not math.isfinite(pred.dy_m):
        return replace(
            persist_speed_heading_step(last_speed_mps, 0.0, dt_s),
            reason="nonfinite_displacement",
        )
    sigma = math.exp(0.5 * max(pred.log_sigma_x, pred.log_sigma_y))
    if sigma > uncertainty_gate:
        return replace(
            persist_speed_heading_step(last_speed_mps, 0.0, dt_s),
            reason="uncertainty_fallback",
        )
    return JointStep(
        distance_m=pred.delta_length_m,
        heading_change_rad=pred.delta_heading_rad,
        log_var_distance=pred.log_sigma_x + pred.log_sigma_y,
        log_var_heading=pred.log_speed_variance,
        used_fallback=False,
        reason="student",
    )


def rollout_joint_steps(
    origin: tuple[float, float],
    heading_rad: float,
    steps: Sequence[JointStep],
) -> list[tuple[float, float]]:
    """Integrate causal increments. No future observation is consulted."""

    points = [origin]
    heading = heading_rad
    for step in steps:
        heading += step.heading_change_rad
        points.append(advance_latlon(points[-1], heading, step.distance_m))
    return points


def score_blackout_rollout(
    estimate: Sequence[tuple[float, float]],
    truth: Sequence[tuple[float, float]],
    *,
    estimate_heading_rad: Sequence[float] | None = None,
    truth_heading_rad: Sequence[float] | None = None,
) -> dict[str, float | int | None]:
    """Primary report is blackout position drift, not window speed MAE."""

    metrics: BlackoutMetrics = evaluate_blackout(estimate, truth)
    payload: dict[str, float | int | None] = dict(metrics.to_dict())
    payload["primary_metric"] = "blackout_position_drift_ratio"
    if estimate_heading_rad is not None and truth_heading_rad is not None:
        if len(estimate_heading_rad) != len(truth_heading_rad):
            raise ValueError("heading series must align")
        payload["heading_mae_rad"] = mean(
            abs(wrap_heading_error_rad(a, b))
            for a, b in zip(estimate_heading_rad, truth_heading_rad)
        )
    return payload


def assert_no_feature_leakage(features: Mapping[str, object]) -> None:
    leaked = GNSS_KEYS.intersection(features)
    if leaked:
        names = ", ".join(sorted(leaked))
        raise AssertionError(f"learned-motion features contain GNSS fields: {names}")


def train_windows_from_trips(
    trips: Mapping[str, Sequence[dict]],
    roles: SplitRoles | None = None,
    *,
    allow_synthetic: bool = False,
) -> list:
    """Fit labels only from approved train groups. Synthetic smoke is explicit."""

    roles = roles or load_split_roles()
    holdout_is_closed(list(trips), roles)
    windows = []
    for trip_id, records in trips.items():
        if allow_synthetic and str(trip_id).startswith("synthetic:"):
            pass
        else:
            assert_trainable(trip_id, roles)
        windows.extend(odometry_windows(list(records)))
    if not windows:
        raise ValueError("no labeled train windows")
    return windows


def preregistration_payload(roles: SplitRoles | None = None) -> dict:
    roles = roles or load_split_roles()
    return {
        "experiment": "learned_motion_joint_v1",
        "status": "preregistered",
        "training_started": False,
        "selected_candidate": None,
        "seed": roles.seed,
        "objective": {
            "outputs": ["travelled_distance_m", "heading_change_rad"],
            "uncertainty": True,
            "deterministic_fallback": "persist_speed_heading",
            "primary_score": "blackout endpoint error / truth path length",
            "insufficient_alone": ["training_loss", "speed_mae_mps"],
        },
        "causal": True,
        "timesfm": False,
        "phone_path_changed": False,
        "train_session_groups": sorted(roles.train),
        "development_session_groups": sorted(roles.development),
        "fresh_holdout_session_groups": sorted(roles.fresh_holdout),
        "excluded_session_groups": sorted(roles.excluded),
        "locked_session_groups": sorted(roles.locked),
        "holdout_open": False,
        "locked_tuning": False,
        "configs": CONFIGS,
        "early_stopping": {
            "monitor": "development_blackout_drift_p50",
            "patience_epochs": 8,
            "min_epochs": 2,
        },
        "compute": {
            "assume_gpu": False,
            "cpu_smoke_required": True,
            "full_io_vnbd_training": "not_started",
        },
        "notes": [
            "Train only the 24 frozen train groups.",
            "Validate on development. Do not open the 10 fresh holdout groups.",
            "S-Vtb3 stays excluded.",
            "Heading plus distance is the A-diagnosed gap. Speed MAE alone is not enough.",
        ],
    }


def write_preregistration(path: Path | None = None) -> dict:
    payload = preregistration_payload()
    destination = path or PREREGISTRATION
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def cpu_smoke(seed: int = 26168) -> dict:
    """Fit the stdlib joint student on synthetic odometry and score a rollout.

    Synthetic trips are not IO-VNBD accuracy evidence. The report still uses
    blackout position drift so C cannot ship a speed-MAE-only checkpoint.
    """

    trips = default_synthetic_odometry(seed)
    trained = train_from_odometry(trips, seed=str(seed))
    student = trained["student"]
    cruise = trips["cruise-0"]
    windows = odometry_windows(cruise, stride=8)
    origin = (18.5204, 73.8567)
    heading = 0.0
    steps: list[JointStep] = []
    truth = [origin]
    est_heading = [heading]
    truth_heading = [float(cruise[0]["heading_rad"])]
    last_speed = float(cruise[0]["speed_mps"])
    for window in windows:
        pred = student.infer(window.features)
        step = joint_step_from_prediction(pred, last_speed_mps=last_speed, dt_s=window.duration_s)
        steps.append(step)
        last_speed = pred.forward_speed_mps
        heading += step.heading_change_rad
        est_heading.append(heading)
        next_truth_heading = truth_heading[-1] + window.delta_heading_rad
        truth_heading.append(next_truth_heading)
        truth.append(
            advance_latlon(truth[-1], next_truth_heading, math.hypot(window.dx_m, window.dy_m))
        )
    estimate = rollout_joint_steps(origin, 0.0, steps)
    n = min(len(estimate), len(truth))
    scored = score_blackout_rollout(
        estimate[:n],
        truth[:n],
        estimate_heading_rad=est_heading[:n],
        truth_heading_rad=truth_heading[:n],
    )
    return {
        "status": "cpu_smoke",
        "label": "SYNTHETIC_SMOKE_ONLY",
        "seed": seed,
        "config": "linear_joint_v1",
        "window_count": len(windows),
        "fallback_steps": sum(1 for step in steps if step.used_fallback),
        "blackout": scored,
        "window_losses": trained["report"]["splits"],
        "not_a_candidate": True,
        "not_io_vnbd": True,
    }
