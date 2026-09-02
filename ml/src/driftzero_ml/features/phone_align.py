"""Per-trip gravity alignment. No hard-coded yaw/pitch/roll swap."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from math import sqrt
from typing import Sequence


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
