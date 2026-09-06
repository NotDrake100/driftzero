"""ZUPT plus vibration speed. Keep gates in sync with ZuptAccelMotionModel.kt."""

from __future__ import annotations

import math
from dataclasses import dataclass
from math import log, sqrt

from driftzero_ml.features.causal_imu import (
    MAX_SPEED_MPS,
    SPEED_VIB_GAIN,
    STOP_ZUPT,
    ImuSample,
    extract_causal_imu_features,
)


@dataclass(frozen=True)
class MotionHeads:
    """Student / heuristic outputs. Speed m/s, log-variance ln(sigma^2)."""

    forward_speed_mps: float
    stop_logit: float
    log_variance: float
    stop_probability: float
    idle: bool
    bump: bool
    vibration_energy: float
    yaw_rate_radps: float


def zupt_accel_infer(samples: list[ImuSample] | tuple[ImuSample, ...]) -> MotionHeads:
    if not samples:
        raise ValueError("IMU window must not be empty")
    features = extract_causal_imu_features(samples)
    idle = features.idle
    if idle:
        speed = 0.0
        sigma = 0.05
    else:
        speed = min(MAX_SPEED_MPS, SPEED_VIB_GAIN * sqrt(features.vibration_energy))
        sigma = min(8.0, max(0.4, 1.5 + 3.0 * sqrt(features.vibration_energy)))
    stop_probability = min(1.0, max(0.0, features.idle_score))
    logit = _logit(stop_probability)
    return MotionHeads(
        forward_speed_mps=speed,
        stop_logit=logit,
        log_variance=log(sigma * sigma),
        stop_probability=stop_probability,
        idle=idle,
        bump=features.bump,
        vibration_energy=features.vibration_energy,
        yaw_rate_radps=features.gyro_z_mean,
    )


def coast_speed_mps(prior_speed_mps: float, heads: MotionHeads) -> float:
    """CV-stub hook. ESKF should use heads as a gated measurement instead."""

    if prior_speed_mps < 0.0 or math.isnan(prior_speed_mps):
        raise ValueError("prior_speed_mps must be a non-negative finite speed")
    if heads.idle or heads.stop_probability >= STOP_ZUPT:
        return 0.0
    return prior_speed_mps


def _logit(probability: float) -> float:
    clipped = min(0.999, max(0.001, probability))
    return log(clipped / (1.0 - clipped))
