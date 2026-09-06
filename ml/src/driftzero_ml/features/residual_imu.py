"""Hold-interval IMU extras for the speed residual student.

GNSS is never an input. Gravity is a causal low-pass on the prefix through t.
Forward axis is gravity-horizontal specific force along the device axis with
the largest |mean|. |omega_z| is |gyro · up|.
"""

from __future__ import annotations

from collections.abc import Sequence
from math import sqrt

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.features.causal_imu import ImuSample, causal_gravity_vectors

HOLD_EXTRA_NAMES: tuple[str, ...] = (
    "forward_accel_mean",
    "omega_z_abs",
)

if set(HOLD_EXTRA_NAMES) & GNSS_KEYS:
    raise RuntimeError("residual IMU extras must not include GNSS fields")


def hold_forward_accel_and_omega_z(
    samples: Sequence[ImuSample],
    *,
    hold_start_ns: int,
) -> tuple[float, float]:
    """Mean signed forward specific force (m/s^2) and mean |omega_up| (rad/s).

    `samples` must be causal through t, including pre-hold rows so gravity is
    not initialized on the hold acceleration.
    """

    if not samples:
        raise ValueError("IMU prefix must not be empty")
    horiz, omega = horizontal_sf_and_omega_series(samples)
    return hold_mean_from_series(samples, horiz, omega, hold_start_ns=hold_start_ns)


def horizontal_sf_and_omega_series(
    samples: Sequence[ImuSample],
) -> tuple[list[tuple[float, float, float] | None], list[float | None]]:
    """Per-sample horizontal specific force and |omega_up|. Causal gravity LPF."""

    if not samples:
        raise ValueError("IMU prefix must not be empty")
    gravity = causal_gravity_vectors(samples)
    horiz: list[tuple[float, float, float] | None] = []
    omega_abs: list[float | None] = []
    for row, grav in zip(samples, gravity):
        up = _unit(grav)
        if up is None:
            horiz.append(None)
            omega_abs.append(None)
            continue
        sf = (row.ax - grav[0], row.ay - grav[1], row.az - grav[2])
        horiz.append(_reject(sf, up))
        if row.gx is None or row.gy is None or row.gz is None:
            omega_abs.append(None)
            continue
        dotted = row.gx * up[0] + row.gy * up[1] + row.gz * up[2]
        omega_abs.append(abs(dotted))
    return horiz, omega_abs


def hold_mean_from_series(
    samples: Sequence[ImuSample],
    horiz: Sequence[tuple[float, float, float] | None],
    omega_abs: Sequence[float | None],
    *,
    hold_start_ns: int,
    end_ns: int | None = None,
) -> tuple[float, float]:
    held_h: list[tuple[float, float, float]] = []
    held_w: list[float] = []
    for row, h, w in zip(samples, horiz, omega_abs):
        stamp = row.timestamp_ns
        if stamp <= hold_start_ns:
            continue
        if end_ns is not None and stamp > end_ns:
            break
        if h is not None:
            held_h.append(h)
        if w is not None:
            held_w.append(w)
    if not held_h:
        raise ValueError("hold IMU window must not be empty")
    omega = sum(held_w) / len(held_w) if held_w else 0.0
    return _signed_dominant_mean(held_h), omega


def _unit(vector: tuple[float, float, float]) -> tuple[float, float, float] | None:
    norm = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
    if norm < 1e-6:
        return None
    return (vector[0] / norm, vector[1] / norm, vector[2] / norm)


def _reject(
    vector: tuple[float, float, float],
    up: tuple[float, float, float],
) -> tuple[float, float, float]:
    dotted = vector[0] * up[0] + vector[1] * up[1] + vector[2] * up[2]
    return (
        vector[0] - dotted * up[0],
        vector[1] - dotted * up[1],
        vector[2] - dotted * up[2],
    )


def _signed_dominant_mean(rows: Sequence[tuple[float, float, float]]) -> float:
    mean = (
        sum(row[0] for row in rows) / len(rows),
        sum(row[1] for row in rows) / len(rows),
        sum(row[2] for row in rows) / len(rows),
    )
    axis = max(range(3), key=lambda index: abs(mean[index]))
    return mean[axis]
