"""Causal curve-speed observation: v = a_lat / omega_z for a ground vehicle.

Gravity-aligned IMU. Forward axis is frozen from pre-blackout GNSS-known
accel events, or from moving-turn geometry. GNSS is not an input after
the freeze time. Stdlib only.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from typing import Callable, Sequence

from driftzero_ml.features.causal_imu import MAX_SPEED_MPS
from driftzero_ml.features.phone_align import TripAlignment, rotate_vector, vertical_gyro_radps

OMEGA_MIN_RADPS = 0.05
HARD_BRAKE_MPS2 = -1.5
JERK_MAX_MPS3 = 10.0
SMOOTH_NS = 300_000_000
ACCEL_EVENT_MPS2 = 0.50
MOVE_SPEED_MPS = 2.0
MIN_FORWARD_EVENTS = 8
# Phone 10 Hz accel / gyro noise used for the curve measurement variance.
ACCEL_LAT_SIGMA_MPS2 = 0.30
GYRO_SIGMA_RADPS = 0.02
VAR_CURVE_FLOOR = 1.0
VAR_CURVE_CEIL = 100.0
VAR_LINEAR_FLOOR = 0.0025


@dataclass(frozen=True)
class ForwardAxis:
    """Unit forward in the gravity-aligned horizontal plane (x, y)."""

    ux: float
    uy: float
    source: str
    sample_count: int
    notes: tuple[str, ...]


@dataclass(frozen=True)
class CurveObservation:
    timestamp_ns: int
    v_mps: float | None
    a_lat_mps2: float
    a_fwd_mps2: float
    omega_z_radps: float
    jerk_mps3: float
    variance_mps2: float | None
    valid: bool
    reasons: tuple[str, ...]


def left_axis(forward: ForwardAxis) -> tuple[float, float]:
    """z-up cross forward: left in the horizontal plane."""

    return (-forward.uy, forward.ux)


def horizontal_specific_force(
    ax: float,
    ay: float,
    az: float,
    alignment: TripAlignment,
) -> tuple[float, float]:
    """Gravity-aligned horizontal specific force. Gravity sits on +Z."""

    rot = rotate_vector((ax, ay, az), alignment.rotation)
    return (rot[0], rot[1])


def omega_z_radps(row: dict, alignment: TripAlignment | None) -> float | None:
    if "gyro_vertical_radps" in row and row["gyro_vertical_radps"] is not None:
        return float(row["gyro_vertical_radps"])
    if alignment is None:
        return None
    return vertical_gyro_radps((row.get("gx"), row.get("gy"), row.get("gz")), alignment)


def curve_variance_mps2(a_lat_mps2: float, omega_z_radps: float) -> float:
    """Error-propagation variance for v = a_lat / omega.

    var = sigma_a^2 / w^2 + a_lat^2 sigma_w^2 / w^4
    """

    if abs(omega_z_radps) < 1e-9:
        return VAR_CURVE_CEIL
    w2 = omega_z_radps * omega_z_radps
    var = (ACCEL_LAT_SIGMA_MPS2 * ACCEL_LAT_SIGMA_MPS2) / w2
    var += (a_lat_mps2 * a_lat_mps2 * GYRO_SIGMA_RADPS * GYRO_SIGMA_RADPS) / (w2 * w2)
    return min(VAR_CURVE_CEIL, max(VAR_CURVE_FLOOR, var))


def blend_speeds(
    v_linear: float,
    var_linear: float,
    v_curve: float,
    var_curve: float,
) -> float:
    """Inverse-variance blend. Variances are (m/s)^2."""

    if var_linear <= 0.0 or var_curve <= 0.0:
        raise ValueError("variances must be positive")
    w_lin = 1.0 / var_linear
    w_cur = 1.0 / var_curve
    blended = (w_lin * v_linear + w_cur * v_curve) / (w_lin + w_cur)
    return min(MAX_SPEED_MPS, max(0.0, blended))


def estimate_forward_axis(
    records: Sequence[dict],
    alignment: TripAlignment | None,
    end_ns: int,
) -> ForwardAxis | None:
    """Phone-frame forward from pre-end_ns GNSS-known motion only.

    Primary: mean horizontal specific force while GNSS speed is rising.
    Fallback: centripetal geometry while GNSS reports motion and |omega|
    is above threshold. Course angle is ENU and is not a phone-frame axis.
    """

    if alignment is None:
        return None
    accel_vecs: list[tuple[float, float]] = []
    turn_vecs: list[tuple[float, float]] = []
    prev_speed: float | None = None
    prev_t: int | None = None
    for row in records:
        stamp = int(row["timestamp_ns"])
        if stamp >= end_ns:
            break
        ah = horizontal_specific_force(float(row["ax"]), float(row["ay"]), float(row["az"]), alignment)
        omega = omega_z_radps(row, alignment)
        speed = row.get("gnss_speed_mps")
        if speed is not None:
            speed = float(speed)
        if (
            prev_speed is not None
            and prev_t is not None
            and speed is not None
        ):
            dt = (stamp - prev_t) / 1_000_000_000.0
            if dt >= 0.05:
                a_gnss = (speed - prev_speed) / dt
                if a_gnss >= ACCEL_EVENT_MPS2 and speed >= MOVE_SPEED_MPS:
                    accel_vecs.append(ah)
        if (
            speed is not None
            and speed >= MOVE_SPEED_MPS
            and omega is not None
            and abs(omega) >= OMEGA_MIN_RADPS
        ):
            sign = 1.0 if omega >= 0.0 else -1.0
            turn_vecs.append((sign * ah[1], -sign * ah[0]))
        if speed is not None:
            prev_speed = speed
            prev_t = stamp
    notes: list[str] = []
    if len(accel_vecs) >= MIN_FORWARD_EVENTS:
        chosen = accel_vecs
        source = "gnss_accel_events"
        notes.append(f"{len(accel_vecs)} GNSS acceleration events before freeze")
    elif len(turn_vecs) >= MIN_FORWARD_EVENTS:
        chosen = turn_vecs
        source = "gnss_moving_turn"
        notes.append(
            f"{len(turn_vecs)} moving-turn samples. GNSS course angle is ENU "
            "and was not mapped into the phone frame"
        )
    else:
        return None
    mx = sum(item[0] for item in chosen) / len(chosen)
    my = sum(item[1] for item in chosen) / len(chosen)
    norm = sqrt(mx * mx + my * my)
    if norm < 1e-6:
        return None
    axis = ForwardAxis(mx / norm, my / norm, source, len(chosen), tuple(notes))
    return _sign_align_forward(records, alignment, axis, end_ns)


def _sign_align_forward(
    records: Sequence[dict],
    alignment: TripAlignment,
    axis: ForwardAxis,
    end_ns: int,
) -> ForwardAxis:
    """Flip forward if most pre-freeze v = a_lat/omega estimates are negative."""

    positive = 0
    negative = 0
    left = left_axis(axis)
    for row in records:
        stamp = int(row["timestamp_ns"])
        if stamp >= end_ns:
            break
        omega = omega_z_radps(row, alignment)
        if omega is None or abs(omega) < OMEGA_MIN_RADPS:
            continue
        ah = horizontal_specific_force(float(row["ax"]), float(row["ay"]), float(row["az"]), alignment)
        a_lat = ah[0] * left[0] + ah[1] * left[1]
        estimate = a_lat / omega
        if estimate > 0.0:
            positive += 1
        elif estimate < 0.0:
            negative += 1
    if negative > positive and (positive + negative) >= MIN_FORWARD_EVENTS:
        flipped = ForwardAxis(
            -axis.ux,
            -axis.uy,
            axis.source,
            axis.sample_count,
            axis.notes + ("flipped so a_lat/omega is mostly positive before freeze",),
        )
        return flipped
    return axis


def observe_curve_speeds(
    rows: Sequence[dict],
    alignment: TripAlignment | None,
    forward: ForwardAxis | None,
) -> list[CurveObservation]:
    """Causal per-sample v_curve. Quality flag is the `valid` field."""

    if alignment is None or forward is None:
        return [
            CurveObservation(
                timestamp_ns=int(row["timestamp_ns"]),
                v_mps=None,
                a_lat_mps2=0.0,
                a_fwd_mps2=0.0,
                omega_z_radps=0.0,
                jerk_mps3=0.0,
                variance_mps2=None,
                valid=False,
                reasons=("no_forward_axis_or_alignment",),
            )
            for row in rows
        ]
    left = left_axis(forward)
    history: list[tuple[int, float, float, float]] = []
    out: list[CurveObservation] = []
    prev_lat: float | None = None
    prev_t: int | None = None
    for row in rows:
        stamp = int(row["timestamp_ns"])
        ah = horizontal_specific_force(float(row["ax"]), float(row["ay"]), float(row["az"]), alignment)
        omega = omega_z_radps(row, alignment)
        if omega is None:
            out.append(
                CurveObservation(
                    timestamp_ns=stamp,
                    v_mps=None,
                    a_lat_mps2=0.0,
                    a_fwd_mps2=0.0,
                    omega_z_radps=0.0,
                    jerk_mps3=0.0,
                    variance_mps2=None,
                    valid=False,
                    reasons=("omega_missing",),
                )
            )
            continue
        a_lat = ah[0] * left[0] + ah[1] * left[1]
        a_fwd = ah[0] * forward.ux + ah[1] * forward.uy
        history.append((stamp, a_lat, a_fwd, omega))
        cutoff = stamp - SMOOTH_NS
        while history and history[0][0] < cutoff:
            history.pop(0)
        n = len(history)
        lat_s = sum(item[1] for item in history) / n
        fwd_s = sum(item[2] for item in history) / n
        omg_s = sum(item[3] for item in history) / n
        jerk = 0.0
        if prev_lat is not None and prev_t is not None:
            dt = (stamp - prev_t) / 1_000_000_000.0
            if dt > 1e-4:
                jerk = abs(lat_s - prev_lat) / dt
        prev_lat = lat_s
        prev_t = stamp
        reasons: list[str] = []
        if abs(omg_s) < OMEGA_MIN_RADPS:
            reasons.append("omega_below_threshold")
        if fwd_s < HARD_BRAKE_MPS2:
            reasons.append("hard_brake")
        if jerk > JERK_MAX_MPS3:
            reasons.append("high_jerk")
        v_raw = lat_s / omg_s if abs(omg_s) >= 1e-9 else None
        if v_raw is None:
            reasons.append("omega_zero")
        elif v_raw <= 0.0:
            reasons.append("sign_inconsistent")
        elif v_raw > MAX_SPEED_MPS:
            reasons.append("speed_implausible")
        valid = not reasons
        variance = curve_variance_mps2(lat_s, omg_s) if valid and v_raw is not None else None
        out.append(
            CurveObservation(
                timestamp_ns=stamp,
                v_mps=None if v_raw is None else min(MAX_SPEED_MPS, max(0.0, v_raw)),
                a_lat_mps2=lat_s,
                a_fwd_mps2=fwd_s,
                omega_z_radps=omg_s,
                jerk_mps3=jerk,
                variance_mps2=variance,
                valid=valid,
                reasons=tuple(reasons),
            )
        )
    return out


def persist_curve_speed_fn(
    observations: Sequence[CurveObservation],
) -> Callable[[dict, float], float]:
    by_t = {row.timestamp_ns: row for row in observations}

    def speed_at(row: dict, prior: float) -> float:
        obs = by_t.get(int(row["timestamp_ns"]))
        if obs is not None and obs.valid and obs.v_mps is not None:
            return obs.v_mps
        return prior

    return speed_at


def linear_curve_speed_fn(
    linear_infer: Callable[[dict], tuple[float, float, bool] | None],
    observations: Sequence[CurveObservation],
) -> Callable[[dict, float], float]:
    """Blend student speed with v_curve when the quality flag is set.

    linear_infer returns (speed, variance_mps2, stopped) or None.
    """

    by_t = {row.timestamp_ns: row for row in observations}

    def speed_at(row: dict, prior: float) -> float:
        inferred = linear_infer(row)
        obs = by_t.get(int(row["timestamp_ns"]))
        if inferred is None:
            if obs is not None and obs.valid and obs.v_mps is not None:
                return obs.v_mps
            return prior
        speed, variance, stopped = inferred
        if stopped:
            return 0.0
        if obs is None or not obs.valid or obs.v_mps is None or obs.variance_mps2 is None:
            return speed
        return blend_speeds(
            speed,
            max(VAR_LINEAR_FLOOR, variance),
            obs.v_mps,
            obs.variance_mps2,
        )

    return speed_at


def validation_pairs(
    records: Sequence[dict],
    observations: Sequence[CurveObservation],
) -> tuple[list[float], list[float], list[float], list[float]]:
    """Aligned GNSS speed, v_curve, a_lat, |omega| on quality-flagged samples."""

    by_t = {int(row["timestamp_ns"]): row for row in records}
    gnss: list[float] = []
    curve: list[float] = []
    lats: list[float] = []
    omegas: list[float] = []
    for obs in observations:
        if not obs.valid or obs.v_mps is None:
            continue
        src = by_t.get(obs.timestamp_ns)
        if src is None or src.get("gnss_speed_mps") is None:
            continue
        gnss.append(float(src["gnss_speed_mps"]))
        curve.append(obs.v_mps)
        lats.append(obs.a_lat_mps2)
        omegas.append(abs(obs.omega_z_radps))
    return gnss, curve, lats, omegas


def pearson_corr(xs: Sequence[float], ys: Sequence[float]) -> float | None:
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


def median_relative_error(predicted: Sequence[float], truth: Sequence[float]) -> float | None:
    if not predicted or len(predicted) != len(truth):
        return None
    ratios = [abs(a - b) / max(abs(b), 0.5) for a, b in zip(predicted, truth)]
    ordered = sorted(ratios)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return 0.5 * (ordered[mid - 1] + ordered[mid])
