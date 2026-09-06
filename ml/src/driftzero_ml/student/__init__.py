"""Causal motion student: heuristic, linear, optional RoNIN/TLIO-aligned GRU."""

from .heads import MotionHeads, coast_speed_mps, zupt_accel_infer
from .linear import LinearMotionStudent, fit_linear_motion_student, zero_speed_baseline

__all__ = [
    "LinearMotionStudent",
    "MotionHeads",
    "coast_speed_mps",
    "fit_linear_motion_student",
    "zero_speed_baseline",
    "zupt_accel_infer",
]
