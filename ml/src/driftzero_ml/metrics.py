"""Geodesic metrics for score-only blackout evaluation.

The functions deliberately depend only on Python's standard library so the
integrity-critical evaluator can run in a minimal environment.
"""

from __future__ import annotations

import itertools
from collections.abc import Iterable, Sequence
from dataclasses import asdict, dataclass
from math import asin, atan2, cos, exp, isfinite, log, pi, radians, sin, sqrt
from statistics import mean, median

EARTH_MEAN_RADIUS_M = 6_371_008.8
LatLon = tuple[float, float]


def _validate_point(point: LatLon) -> None:
    lat, lon = point
    if not (isfinite(lat) and isfinite(lon)):
        raise ValueError("latitude and longitude must be finite")
    if not -90.0 <= lat <= 90.0:
        raise ValueError(f"latitude out of range: {lat}")
    if not -180.0 <= lon <= 180.0:
        raise ValueError(f"longitude out of range: {lon}")


def haversine_m(a: LatLon, b: LatLon) -> float:
    """Return horizontal great-circle distance in metres."""

    _validate_point(a)
    _validate_point(b)
    lat1, lon1 = map(radians, a)
    lat2, lon2 = map(radians, b)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    h = sin(dlat / 2.0) ** 2 + cos(lat1) * cos(lat2) * sin(dlon / 2.0) ** 2
    h = min(1.0, max(0.0, h))
    return 2.0 * EARTH_MEAN_RADIUS_M * asin(sqrt(h))


def path_length_m(points: Sequence[LatLon]) -> float:
    """Return the sum of horizontal segment lengths."""

    if not points:
        raise ValueError("path must contain at least one point")
    for point in points:
        _validate_point(point)
    return sum(haversine_m(a, b) for a, b in itertools.pairwise(points))


@dataclass(frozen=True)
class BlackoutMetrics:
    """Metrics for one blackout interval."""

    endpoint_error_m: float
    truth_path_length_m: float
    drift_ratio: float | None
    mean_position_error_m: float | None
    max_position_error_m: float | None
    sample_count: int
    along_track_error_m: float | None = None
    cross_track_error_m: float | None = None
    speed_mae_mps: float | None = None
    heading_mae_rad: float | None = None

    def to_dict(self) -> dict[str, float | int | None]:
        return asdict(self)


def enu_m(origin: LatLon, point: LatLon) -> tuple[float, float]:
    """East/north metres from origin. Phone GNSS, not RTK."""

    _validate_point(origin)
    _validate_point(point)
    dlat = radians(point[0] - origin[0])
    dlon = radians(point[1] - origin[1])
    east = EARTH_MEAN_RADIUS_M * dlon * cos(radians(origin[0]))
    north = EARTH_MEAN_RADIUS_M * dlat
    return east, north


def along_cross_m(estimate: LatLon, truth: LatLon, heading_rad: float) -> tuple[float, float]:
    """Endpoint error in the truth heading frame. Along is forward, cross is right."""

    east, north = enu_m(truth, estimate)
    along = north * cos(heading_rad) + east * sin(heading_rad)
    cross = east * cos(heading_rad) - north * sin(heading_rad)
    return along, cross


def path_heading_rad(points: Sequence[LatLon]) -> float:
    """Heading of the last non-trivial truth segment, else first-to-last."""

    if len(points) < 2:
        raise ValueError("need two points for a path heading")
    for a, b in zip(reversed(points[:-1]), reversed(points[1:])):
        east, north = enu_m(a, b)
        if east * east + north * north >= 0.25:
            return atan2(east, north)
    east, north = enu_m(points[0], points[-1])
    if east * east + north * north < 1e-12:
        return 0.0
    return atan2(east, north)


def wrap_heading_error_rad(estimate_rad: float, truth_rad: float) -> float:
    return (estimate_rad - truth_rad + pi) % (2.0 * pi) - pi


def circular_mae_rad(estimate: Sequence[float], truth: Sequence[float]) -> float:
    if len(estimate) != len(truth) or not estimate:
        raise ValueError("heading series must be non-empty and aligned")
    return mean(abs(wrap_heading_error_rad(a, b)) for a, b in zip(estimate, truth))


def gaussian_nll(residual: Sequence[float], log_variance: Sequence[float]) -> float:
    """Mean Gaussian NLL. log_variance is ln(sigma^2)."""

    if len(residual) != len(log_variance) or not residual:
        raise ValueError("NLL series must be non-empty and aligned")
    acc = 0.0
    for err, log_var in zip(residual, log_variance):
        var = max(1e-8, exp(log_var) if log_var <= 80.0 else 5.540622384e34)
        acc += 0.5 * (log(2.0 * pi) + log_var + err * err / var)
    return acc / len(residual)


def picp(residual: Sequence[float], sigma: Sequence[float], z: float) -> float:
    """Prediction interval coverage. z=1 is ~68%, z=1.959964 is ~95%."""

    if len(residual) != len(sigma) or not residual:
        raise ValueError("PICP series must be non-empty and aligned")
    if z <= 0:
        raise ValueError("z must be positive")
    hits = 0
    for err, s in zip(residual, sigma):
        if s < 0:
            raise ValueError("sigma must be non-negative")
        if abs(err) <= z * s:
            hits += 1
    return hits / len(residual)


def evaluate_blackout(
    estimate: Sequence[LatLon],
    truth: Sequence[LatLon],
    *,
    minimum_ratio_path_m: float = 1.0,
) -> BlackoutMetrics:
    """Score an already-produced estimate against hidden truth.

    This function must be called after navigation inference. It accepts no
    sensor/model object, which keeps the score-only boundary explicit.
    """

    if len(estimate) != len(truth):
        raise ValueError("estimate and truth must contain the same number of samples")
    if not estimate:
        raise ValueError("at least one sample is required")
    if minimum_ratio_path_m <= 0:
        raise ValueError("minimum_ratio_path_m must be positive")

    errors = [haversine_m(est, ref) for est, ref in zip(estimate, truth)]
    truth_distance = path_length_m(truth)
    endpoint_error = errors[-1]
    ratio = endpoint_error / truth_distance if truth_distance >= minimum_ratio_path_m else None
    heading = path_heading_rad(truth) if len(truth) >= 2 else 0.0
    along, cross = along_cross_m(estimate[-1], truth[-1], heading)
    return BlackoutMetrics(
        endpoint_error_m=endpoint_error,
        truth_path_length_m=truth_distance,
        drift_ratio=ratio,
        mean_position_error_m=mean(errors),
        max_position_error_m=max(errors),
        sample_count=len(errors),
        along_track_error_m=along,
        cross_track_error_m=cross,
    )


def _nearest_rank(values: Sequence[float], probability: float) -> float:
    if not 0.0 <= probability <= 1.0:
        raise ValueError("probability must be between zero and one")
    if not values:
        raise ValueError("cannot compute a quantile of no values")
    ordered = sorted(values)
    if probability == 0:
        return ordered[0]
    rank = max(1, int(probability * len(ordered) + 0.999999999))
    return ordered[min(rank - 1, len(ordered) - 1)]


def summarize_blackouts(metrics: Iterable[BlackoutMetrics]) -> dict[str, float | int]:
    """Summarize interval metrics without hiding ratio-ineligible intervals."""

    rows = list(metrics)
    if not rows:
        raise ValueError("at least one blackout metric is required")
    errors = [row.endpoint_error_m for row in rows]
    ratios = [row.drift_ratio for row in rows if row.drift_ratio is not None]
    summary: dict[str, float | int] = {
        "interval_count": len(rows),
        "ratio_eligible_count": len(ratios),
        "endpoint_error_median": median(errors),
        "endpoint_error_p95": _nearest_rank(errors, 0.95),
        "endpoint_error_max": max(errors),
    }
    if ratios:
        summary.update(
            {
                "drift_ratio_median": median(ratios),
                "drift_ratio_p90": _nearest_rank(ratios, 0.90),
                "drift_ratio_p95": _nearest_rank(ratios, 0.95),
                "drift_ratio_max": max(ratios),
            }
        )
    return summary

