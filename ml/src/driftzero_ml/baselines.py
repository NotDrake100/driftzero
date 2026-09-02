"""Causal position baselines used before a learned model exists."""

from __future__ import annotations

from typing import Sequence

from .metrics import LatLon

LatLonTime = tuple[float, float, int]


def freeze_baseline(last_fix: LatLon, count: int) -> list[LatLon]:
    """Hold the last accepted fix. Used as the dumb GNSS-loss floor."""

    if count < 1:
        raise ValueError("count must be at least 1")
    _validate_fix(last_fix)
    return [last_fix] * count


def constant_velocity_baseline(
    history: Sequence[LatLonTime],
    horizon_ns: Sequence[int],
) -> list[LatLon]:
    """Propagate the last two accepted fixes at constant geographic rate.

    `history` must be causal. Horizon timestamps must be >= the last history
    time. This is a screening baseline, not a navigation filter.
    """

    if len(history) < 2:
        raise ValueError("constant-velocity baseline needs at least two fixes")
    if not horizon_ns:
        raise ValueError("horizon_ns must not be empty")
    for lat, lon, timestamp in history:
        _validate_fix((lat, lon))
        if timestamp < 0:
            raise ValueError("timestamps must be non-negative")
    for previous, current in zip(history, history[1:]):
        if current[2] < previous[2]:
            raise ValueError("history timestamps must be non-decreasing")
    last_lat, last_lon, last_t = history[-1]
    prev_lat, prev_lon, prev_t = history[-2]
    dt = last_t - prev_t
    if dt <= 0:
        raise ValueError("the last two history samples need a positive dt")
    dlat = (last_lat - prev_lat) / dt
    dlon = (last_lon - prev_lon) / dt
    points: list[LatLon] = []
    for stamp in horizon_ns:
        if stamp < last_t:
            raise ValueError("horizon timestamps must be at or after the last fix")
        delta = stamp - last_t
        points.append((last_lat + dlat * delta, last_lon + dlon * delta))
    return points


def _validate_fix(point: LatLon) -> None:
    lat, lon = point
    if not -90.0 <= lat <= 90.0:
        raise ValueError(f"latitude out of range: {lat}")
    if not -180.0 <= lon <= 180.0:
        raise ValueError(f"longitude out of range: {lon}")
