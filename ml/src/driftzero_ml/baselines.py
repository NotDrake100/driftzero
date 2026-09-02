"""Causal position baselines used before a learned model exists."""

from __future__ import annotations

from math import cos, pi, radians, sin
from typing import Sequence

from .metrics import EARTH_MEAN_RADIUS_M, LatLon

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


def persist_course_baseline(
    last_fix: LatLon,
    heading_rad: float,
    speed_mps: float,
    horizon_dt_s: Sequence[float],
) -> list[LatLon]:
    """Hold last speed and heading. No gyro update. Persistence, not CV."""

    if speed_mps < 0.0:
        raise ValueError("speed_mps must be non-negative")
    _validate_fix(last_fix)
    lat, lon = last_fix
    out: list[LatLon] = []
    for dt in horizon_dt_s:
        if dt < 0.0:
            raise ValueError("horizon dt must be non-negative")
        east = speed_mps * sin(heading_rad) * dt
        north = speed_mps * cos(heading_rad) * dt
        lat, lon = _offset_m(lat, lon, north, east)
        out.append((lat, lon))
    if not out:
        raise ValueError("horizon_dt_s must not be empty")
    return out


def _offset_m(lat: float, lon: float, north: float, east: float) -> LatLon:
    dlat = (north / EARTH_MEAN_RADIUS_M) * (180.0 / pi)
    coslat = cos(radians(lat))
    denom = EARTH_MEAN_RADIUS_M * (1e-12 if abs(coslat) < 1e-12 else coslat)
    dlon = (east / denom) * (180.0 / pi)
    nlat = min(90.0, max(-90.0, lat + dlat))
    nlon = ((lon + dlon + 180.0) % 360.0) - 180.0
    return nlat, nlon


def _validate_fix(point: LatLon) -> None:
    lat, lon = point
    if not -90.0 <= lat <= 90.0:
        raise ValueError(f"latitude out of range: {lat}")
    if not -180.0 <= lon <= 180.0:
        raise ValueError(f"longitude out of range: {lon}")
