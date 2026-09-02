"""DriftZero research utilities."""

from .baselines import constant_velocity_baseline, freeze_baseline
from .blackout import BlackoutInterval, mask_gnss_records, validate_blackout_intervals
from .contracts import ContractError, validate_navigation_state, validate_sensor_frame
from .metrics import BlackoutMetrics, evaluate_blackout, haversine_m, path_length_m

__all__ = [
    "BlackoutInterval",
    "BlackoutMetrics",
    "ContractError",
    "constant_velocity_baseline",
    "evaluate_blackout",
    "freeze_baseline",
    "haversine_m",
    "mask_gnss_records",
    "path_length_m",
    "validate_blackout_intervals",
    "validate_navigation_state",
    "validate_sensor_frame",
]

