"""Per-trip gravity alignment. No hard-coded yaw/pitch/roll swap."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from math import copysign, pi, sqrt
from typing import Sequence

from driftzero_ml.gnss_truth import course_rad
from driftzero_ml.metrics import haversine_m


@dataclass(frozen=True)
class TripAlignment:
    """Device-to-vertical record. z_aligned is along mean gravity."""

    trip_id: str
    gravity_mean: tuple[float, float, float]
    vertical_unit: tuple[float, float, float]
    dominant_device_axis: str
    sample_count: int
    rotation: tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]
    heading_rate_sign: float
    notes: tuple[str, ...]

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass(frozen=True)
class HeadingGyroPick:
    """Which published gyro column tracks GNSS course rate.

    IO-VNBD yaw/pitch/roll are not a verified device-frame vector. On S-Vta2
    gravity is already +Z while course rate tracks Pitch, not the vertical
    component. The pick is a per-trip correlation, not a fixed column swap.
    """

    axis: str
    sign: float
    correlation: float
    pair_count: int


HEADING_GYRO_AXES = ("yaw", "pitch", "roll")
MIN_HEADING_GYRO_PAIRS = 8
MIN_HEADING_GYRO_ABS_CORR = 0.25
MIN_HEADING_GYRO_HOP_M = 1.0
MIN_HEADING_GYRO_DT_S = 0.20


def estimate_alignment(
    gravity_rows: Sequence[tuple[float, float, float]],
    *,
    trip_id: str,
    heading_rate_sign: float = -1.0,
) -> TripAlignment:
    """Mean gravity defines up. Rotation sends that axis to +Z. No column swap."""

    if not gravity_rows:
        raise ValueError("need gravity samples to estimate alignment")
    mean = (
        sum(row[0] for row in gravity_rows) / len(gravity_rows),
        sum(row[1] for row in gravity_rows) / len(gravity_rows),
        sum(row[2] for row in gravity_rows) / len(gravity_rows),
    )
    norm = sqrt(mean[0] * mean[0] + mean[1] * mean[1] + mean[2] * mean[2])
    notes: list[str] = []
    if norm < 5.0:
        notes.append(f"gravity mean norm {norm:.3f} is far from 9.8 m/s^2")
    if norm < 1e-6:
        raise ValueError("gravity mean is degenerate")
    vertical = (mean[0] / norm, mean[1] / norm, mean[2] / norm)
    abs_axes = (abs(vertical[0]), abs(vertical[1]), abs(vertical[2]))
    dominant = ("x", "y", "z")[max(range(3), key=lambda i: abs_axes[i])]
    rotation = _rotation_mapping_to_z(vertical)
    if heading_rate_sign not in (-1.0, 1.0):
        raise ValueError("heading_rate_sign must be +1 or -1")
    notes.append(
        "heading_rate_sign is -1 when Android right-hand gyro about up is "
        "opposite GPS course (clockwise from north)"
    )
    return TripAlignment(
        trip_id=trip_id,
        gravity_mean=mean,
        vertical_unit=vertical,
        dominant_device_axis=dominant,
        sample_count=len(gravity_rows),
        rotation=rotation,
        heading_rate_sign=heading_rate_sign,
        notes=tuple(notes),
    )


def rotate_vector(
    vector: tuple[float, float, float],
    rotation: tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]],
) -> tuple[float, float, float]:
    return (
        rotation[0][0] * vector[0] + rotation[0][1] * vector[1] + rotation[0][2] * vector[2],
        rotation[1][0] * vector[0] + rotation[1][1] * vector[1] + rotation[1][2] * vector[2],
        rotation[2][0] * vector[0] + rotation[2][1] * vector[1] + rotation[2][2] * vector[2],
    )


def select_heading_gyro(
    gyros: Sequence[tuple[int, float, float, float]],
    unique_fixes: Sequence[tuple[int, float, float]],
    *,
    min_pairs: int = MIN_HEADING_GYRO_PAIRS,
    min_abs_corr: float = MIN_HEADING_GYRO_ABS_CORR,
) -> HeadingGyroPick | None:
    """Pick the raw gyro column whose mean in each unique-fix hop tracks course rate.

    Sign is chosen so exported ω_z is opposite GNSS course rate (right-hand
    about up, ENU heading clockwise from north).
    """

    if min_pairs < 2:
        raise ValueError("min_pairs must be at least 2")
    if len(unique_fixes) < 3 or len(gyros) < 2:
        return None
    hops: list[tuple[int, int, float, tuple[float, float, float]]] = []
    gyro_times = [row[0] for row in gyros]
    start = 0
    for earlier, later in zip(unique_fixes, unique_fixes[1:]):
        dt = (later[0] - earlier[0]) / 1_000_000_000.0
        if dt < MIN_HEADING_GYRO_DT_S:
            continue
        hop = haversine_m((earlier[1], earlier[2]), (later[1], later[2]))
        if hop < MIN_HEADING_GYRO_HOP_M:
            continue
        heading = course_rad(
            {"latitude_deg": earlier[1], "longitude_deg": earlier[2]},
            {"latitude_deg": later[1], "longitude_deg": later[2]},
        )
        if heading is None:
            continue
        window: list[tuple[float, float, float]] = []
        while start < len(gyros) and gyro_times[start] < earlier[0]:
            start += 1
        index = start
        while index < len(gyros) and gyro_times[index] <= later[0]:
            window.append((gyros[index][1], gyros[index][2], gyros[index][3]))
            index += 1
        if len(window) < 2:
            continue
        hops.append(
            (
                earlier[0],
                later[0],
                heading,
                (
                    sum(item[0] for item in window) / len(window),
                    sum(item[1] for item in window) / len(window),
                    sum(item[2] for item in window) / len(window),
                ),
            )
        )
    dpsi: list[float] = []
    aligned_means: list[tuple[float, float, float]] = []
    for previous, current in zip(hops, hops[1:]):
        dt = (current[1] - previous[1]) / 1_000_000_000.0
        if dt < MIN_HEADING_GYRO_DT_S:
            continue
        delta = (current[2] - previous[2] + pi) % (2.0 * pi) - pi
        dpsi.append(delta / dt)
        aligned_means.append(current[3])
    if len(dpsi) < min_pairs:
        return None
    baseline = sum(rate * rate for rate in dpsi) / len(dpsi)
    if baseline < 1e-8:
        return None
    best: HeadingGyroPick | None = None
    best_score = min_abs_corr
    for axis_index, axis in enumerate(HEADING_GYRO_AXES):
        series = [row[axis_index] for row in aligned_means]
        value = _corr(dpsi, series)
        if value is not None:
            score = abs(value)
            sign = -copysign(1.0, value) if value != 0.0 else 1.0
        else:
            sign, mse = _heading_axis_fit(dpsi, series)
            score = 1.0 - (mse / baseline)
            value = score if sign < 0.0 else -score
        if score < min_abs_corr or (best is not None and score <= best_score):
            continue
        best_score = score
        best = HeadingGyroPick(axis=axis, sign=sign, correlation=value, pair_count=len(dpsi))
    return best


def heading_gyro_radps(
    gyro: tuple[float, float, float],
    pick: HeadingGyroPick,
) -> float:
    component = {"yaw": gyro[0], "pitch": gyro[1], "roll": gyro[2]}[pick.axis]
    return pick.sign * component


def vertical_gyro_radps(
    gyro: tuple[float | None, float | None, float | None],
    alignment: TripAlignment,
) -> float | None:
    if gyro[0] is None or gyro[1] is None or gyro[2] is None:
        return None
    dotted = (
        gyro[0] * alignment.vertical_unit[0]
        + gyro[1] * alignment.vertical_unit[1]
        + gyro[2] * alignment.vertical_unit[2]
    )
    return alignment.heading_rate_sign * dotted


def _rotation_mapping_to_z(
    vertical: tuple[float, float, float],
) -> tuple[tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]:
    """Orthonormal R with R @ vertical = (0, 0, 1)."""

    vz = vertical
    if abs(vz[2]) < 0.9:
        helper = (0.0, 0.0, 1.0)
    else:
        helper = (1.0, 0.0, 0.0)
    vx = _cross(helper, vz)
    vx = _unit(vx)
    vy = _cross(vz, vx)
    # Rows are the new-frame axes in device coordinates.
    return (
        vx,
        vy,
        vz,
    )


def _cross(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def _unit(vector: tuple[float, float, float]) -> tuple[float, float, float]:
    norm = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
    if norm < 1e-9:
        raise ValueError("cannot normalise a near-zero vector")
    return (vector[0] / norm, vector[1] / norm, vector[2] / norm)


def _heading_axis_fit(dpsi: Sequence[float], series: Sequence[float]) -> tuple[float, float]:
    """Sign such that sign * gyro ≈ −course rate. Returns (sign, mse)."""

    best_sign = 1.0
    best_mse = float("inf")
    for sign in (1.0, -1.0):
        mse = sum((sign * gyro + rate) ** 2 for gyro, rate in zip(series, dpsi)) / len(dpsi)
        if mse < best_mse:
            best_mse = mse
            best_sign = sign
    return best_sign, best_mse


def _corr(xs: Sequence[float], ys: Sequence[float]) -> float | None:
    if len(xs) != len(ys) or len(xs) < 2:
        return None
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    num = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    den_x = sqrt(sum((x - mean_x) * (x - mean_x) for x in xs))
    den_y = sqrt(sum((y - mean_y) * (y - mean_y) for y in ys))
    if den_x < 1e-12 or den_y < 1e-12:
        return None
    return num / (den_x * den_y)
