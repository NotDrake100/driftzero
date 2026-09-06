"""Causal IMU windows and pooled features. GNSS is never an input."""

from __future__ import annotations

import itertools
from collections.abc import Sequence
from dataclasses import dataclass
from math import isfinite, sqrt

from driftzero_ml.blackout import GNSS_KEYS

WINDOW_NS = 1_000_000_000
MAX_SAMPLES = 128
MIN_SAMPLES = 8
GRAVITY_MPS2 = 9.80665
GRAVITY_TAU_S = 0.8
GYRO_IDLE_ENERGY = 0.025
ACCEL_IDLE_STD = 0.35
VIB_IDLE_ENERGY = 0.45
BUMP_MPS2 = 4.0
STOP_ZUPT = 0.65
IDLE_SCORE_GATE = 0.85
SPEED_VIB_GAIN = 3.5
MAX_SPEED_MPS = 50.0

FEATURE_NAMES: tuple[str, ...] = (
    "accel_mag_mean",
    "accel_mag_std",
    "accel_energy",
    "gyro_energy",
    "vibration_energy",
    "specific_force_rms",
    "jerk_rms",
    "idle_flag",
    "bump_flag",
    "gyro_z_mean",
    "dt_mean_s",
    "n_norm",
)

if set(FEATURE_NAMES) & GNSS_KEYS:
    raise RuntimeError("IMU feature names must not include GNSS fields")


@dataclass(frozen=True)
class ImuSample:
    """One causal IMU row. Accel m/s^2, gyro rad/s, timestamp integer ns."""

    timestamp_ns: int
    ax: float
    ay: float
    az: float
    gx: float | None = None
    gy: float | None = None
    gz: float | None = None

    def __post_init__(self) -> None:
        if self.timestamp_ns < 0:
            raise ValueError("timestamp_ns must be non-negative")
        for name, value in (("ax", self.ax), ("ay", self.ay), ("az", self.az)):
            if not isfinite(value):
                raise ValueError(f"{name} must be finite")
        for name, value in (("gx", self.gx), ("gy", self.gy), ("gz", self.gz)):
            if value is not None and not isfinite(value):
                raise ValueError(f"{name} must be finite")

    def accel_mag(self) -> float:
        return sqrt(self.ax * self.ax + self.ay * self.ay + self.az * self.az)


@dataclass(frozen=True)
class CausalImuFeatures:
    vector: tuple[float, ...]
    idle_score: float
    idle: bool
    bump: bool
    vibration_energy: float
    gyro_z_mean: float


def trim_causal_window(
    samples: Sequence[ImuSample],
    end_ns: int,
    window_ns: int = WINDOW_NS,
) -> tuple[ImuSample, ...]:
    """Keep samples in (end_ns - window_ns, end_ns]. Future rows are dropped."""

    if end_ns < 0:
        raise ValueError("end_ns must be non-negative")
    if window_ns <= 0:
        raise ValueError("window_ns must be positive")
    start_ns = max(0, end_ns - window_ns)
    kept = [row for row in samples if start_ns <= row.timestamp_ns <= end_ns]
    for previous, current in itertools.pairwise(kept):
        if current.timestamp_ns < previous.timestamp_ns:
            raise ValueError("IMU window timestamps must be non-decreasing")
    if len(kept) > MAX_SAMPLES:
        kept = kept[-MAX_SAMPLES:]
    return tuple(kept)


def causal_gravity_vectors(samples: Sequence[ImuSample]) -> list[tuple[float, float, float]]:
    """Causal low-pass gravity in the device frame. HACF uses this, never GNSS."""

    return _causal_gravity(samples)


def extract_causal_imu_features(samples: Sequence[ImuSample]) -> CausalImuFeatures:
    """Pooled stats from a causal window. IMU axes only."""

    if not samples:
        raise ValueError("IMU window must not be empty")
    mags = [row.accel_mag() for row in samples]
    accel_mean = _mean(mags)
    accel_std = _std(mags)
    accel_energy = _mean([mag * mag for mag in mags])
    gravity = _causal_gravity(samples)
    residuals = []
    for row, g in zip(samples, gravity):
        dx = row.ax - g[0]
        dy = row.ay - g[1]
        dz = row.az - g[2]
        residuals.append(dx * dx + dy * dy + dz * dz)
    vibration_energy = _mean(residuals)
    specific_rms = sqrt(vibration_energy)
    gyro_energy = _gyro_energy(samples)
    gyro_present = any(
        row.gx is not None and row.gy is not None and row.gz is not None for row in samples
    )
    gyro_z = [row.gz for row in samples if row.gz is not None]
    gyro_z_mean = _mean(gyro_z)
    bump = any(sqrt(item) >= BUMP_MPS2 for item in residuals)
    jerk_rms = _jerk_rms(samples, mags)
    dt_mean = _mean_dt_s(samples)
    n_norm = len(samples) / MAX_SAMPLES
    idle_score = 0.0
    if gyro_present and gyro_energy < GYRO_IDLE_ENERGY:
        idle_score += 0.4
    if accel_std < ACCEL_IDLE_STD:
        idle_score += 0.3
    if vibration_energy < VIB_IDLE_ENERGY:
        idle_score += 0.3
    idle = idle_score >= IDLE_SCORE_GATE
    vector = (
        accel_mean,
        accel_std,
        accel_energy,
        gyro_energy,
        vibration_energy,
        specific_rms,
        jerk_rms,
        1.0 if idle else 0.0,
        1.0 if bump else 0.0,
        gyro_z_mean,
        dt_mean,
        n_norm,
    )
    if len(vector) != len(FEATURE_NAMES):
        raise RuntimeError("feature vector length drifted from FEATURE_NAMES")
    return CausalImuFeatures(
        vector=vector,
        idle_score=idle_score,
        idle=idle,
        bump=bump,
        vibration_energy=vibration_energy,
        gyro_z_mean=gyro_z_mean,
    )


def records_to_imu_samples(records: Sequence[dict]) -> tuple[ImuSample, ...]:
    """Build IMU samples from dict rows. GNSS keys are ignored on purpose."""

    rows: list[ImuSample] = []
    for index, record in enumerate(records):
        if "ax" not in record or "ay" not in record or "az" not in record:
            raise KeyError(f"record {index} is missing ax/ay/az")
        stamp = record.get("timestamp_ns", index * 20_000_000)
        if not isinstance(stamp, int) or isinstance(stamp, bool) or stamp < 0:
            raise ValueError("timestamp_ns must be a non-negative integer")
        rows.append(
            ImuSample(
                timestamp_ns=stamp,
                ax=float(record["ax"]),
                ay=float(record["ay"]),
                az=float(record["az"]),
                gx=_optional_float(record.get("gx")),
                gy=_optional_float(record.get("gy")),
                gz=_optional_float(record.get("gz")),
            )
        )
    return tuple(rows)


def _optional_float(value: object) -> float | None:
    if value is None:
        return None
    return float(value)


def _mean(values: Sequence[float]) -> float:
    if not values:
        return 0.0
    return sum(values) / len(values)


def _std(values: Sequence[float]) -> float:
    if len(values) < 2:
        return 0.0
    mu = _mean(values)
    acc = sum((item - mu) ** 2 for item in values)
    return sqrt(acc / len(values))


def _causal_gravity(samples: Sequence[ImuSample]) -> list[tuple[float, float, float]]:
    gx, gy, gz = samples[0].ax, samples[0].ay, samples[0].az
    out = [(gx, gy, gz)]
    for previous, current in itertools.pairwise(samples):
        dt = (current.timestamp_ns - previous.timestamp_ns) / 1_000_000_000.0
        alpha = 0.0 if dt <= 0.0 else dt / (GRAVITY_TAU_S + dt)
        gx = (1.0 - alpha) * gx + alpha * current.ax
        gy = (1.0 - alpha) * gy + alpha * current.ay
        gz = (1.0 - alpha) * gz + alpha * current.az
        out.append((gx, gy, gz))
    return out


def _gyro_energy(samples: Sequence[ImuSample]) -> float:
    energies = []
    for row in samples:
        if row.gx is None or row.gy is None or row.gz is None:
            continue
        energies.append(row.gx**2 + row.gy**2 + row.gz**2)
    return _mean(energies)


def _jerk_rms(samples: Sequence[ImuSample], mags: Sequence[float]) -> float:
    jerks: list[float] = []
    for index in range(1, len(samples)):
        dt = (samples[index].timestamp_ns - samples[index - 1].timestamp_ns) / 1_000_000_000.0
        if dt <= 1e-4:
            continue
        jerk = (mags[index] - mags[index - 1]) / dt
        jerks.append(jerk * jerk)
    return 0.0 if not jerks else sqrt(_mean(jerks))


def _mean_dt_s(samples: Sequence[ImuSample]) -> float:
    if len(samples) < 2:
        return 0.0
    dts = [
        (samples[i].timestamp_ns - samples[i - 1].timestamp_ns) / 1_000_000_000.0
        for i in range(1, len(samples))
    ]
    return _mean(dts)
