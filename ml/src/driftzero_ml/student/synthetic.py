"""Synthetic vehicle IMU for training when IO-VNBD LFS is absent."""

from __future__ import annotations

import math
import random
from typing import Literal

from driftzero_ml.features.causal_imu import GRAVITY_MPS2, ImuSample

Kind = Literal["idle", "cruise", "stop", "bump"]


def synthetic_trip(
    kind: Kind,
    *,
    duration_s: float = 4.0,
    hz: int = 50,
    seed: int = 0,
    trip_id: str = "synthetic-0",
) -> list[dict]:
    """Return IMU rows with train-only labels. No GNSS fields."""

    if hz <= 0 or duration_s <= 0:
        raise ValueError("hz and duration_s must be positive")
    rng = random.Random(seed)
    n = int(duration_s * hz)
    dt_ns = 1_000_000_000 // hz
    rows: list[dict] = []
    speed = 0.0
    for index in range(n):
        t = index / hz
        if kind == "idle":
            speed = 0.0
            ax, ay, az = _still(rng)
            gx = gy = gz = rng.gauss(0.0, 0.002)
        elif kind == "cruise":
            speed = 12.0
            ax, ay, az = _vibrate(rng, amp=1.8)
            gx, gy, gz = rng.gauss(0.0, 0.05), rng.gauss(0.0, 0.04), rng.gauss(0.02, 0.03)
        elif kind == "stop":
            speed = max(0.0, 10.0 * (1.0 - t / max(duration_s, 1e-6)))
            if speed < 0.4:
                ax, ay, az = _still(rng)
                gx = gy = gz = rng.gauss(0.0, 0.003)
                speed = 0.0
            else:
                ax, ay, az = _vibrate(rng, amp=1.2)
                gx = gy = gz = rng.gauss(0.0, 0.04)
        else:
            speed = 8.0
            spike = 8.0 if 0.45 * duration_s <= t <= 0.48 * duration_s else 0.0
            ax, ay, az = _vibrate(rng, amp=1.0)
            az += spike
            gx, gy, gz = rng.gauss(0.0, 0.06), rng.gauss(0.0, 0.05), rng.gauss(0.0, 0.04)
        rows.append(
            {
                "trip_id": trip_id,
                "timestamp_ns": index * dt_ns,
                "ax": ax,
                "ay": ay,
                "az": az,
                "gx": gx,
                "gy": gy,
                "gz": gz,
                "speed_mps": speed,
                "stopped": 1.0 if speed < 0.3 else 0.0,
            }
        )
    return rows


def window_matrix(samples: list[ImuSample]) -> list[list[float]]:
    """Per-timestep GRU input. Gyro gaps are zeroed with no GNSS fill."""

    rows: list[list[float]] = []
    for sample in samples:
        rows.append(
            [
                sample.ax,
                sample.ay,
                sample.az,
                0.0 if sample.gx is None else sample.gx,
                0.0 if sample.gy is None else sample.gy,
                0.0 if sample.gz is None else sample.gz,
            ]
        )
    return rows


def _still(rng: random.Random) -> tuple[float, float, float]:
    return rng.gauss(0.0, 0.02), rng.gauss(0.0, 0.02), GRAVITY_MPS2 + rng.gauss(0.0, 0.02)


def _vibrate(rng: random.Random, amp: float) -> tuple[float, float, float]:
    phase = rng.random() * 2.0 * math.pi
    return (
        amp * math.sin(phase) + rng.gauss(0.0, 0.1),
        amp * 0.3 * math.cos(phase) + rng.gauss(0.0, 0.1),
        GRAVITY_MPS2 + amp * 0.2 * math.sin(phase * 2.0) + rng.gauss(0.0, 0.1),
    )
