"""IO-VNBD smartphone rows to SensorFrame JSONL for Kotlin replay.

IMU stays at the table timestamps. Official replay is 10 Hz table rate.
Do not invent IMU samples for the scored row. `--hold-last-hz` is a
sensitivity helper only. GNSS is emitted only on unique-fix rows.
phone_align rotates accel into a gravity-aligned frame. Gyro is not
rotated as a device vector: IO-VNBD yaw/pitch/roll are not that frame.
Heading rate is the per-interval column that tracks unique-fix course
strictly before the blackout mask, placed on +Z. A weak pick (fewer than 8
hops or |r| < 0.25) falls back to the gravity-vertical gyro and is flagged.
Missing sensors stay omitted. Zeros are never invented. Gaps larger than
DeadReckoningFilter.maxIntegrateS (0.40 s) are left as timestamp jumps.
Timestamp rewinds drop the suffix and are flagged in the export header.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from statistics import median
from typing import Sequence

from driftzero_ml.contracts import validate_sensor_frame
from driftzero_ml.datasets.io_vnbd import SmartphoneRow, TimestampRewind, trim_nondecreasing_rows
from driftzero_ml.features.phone_align import (
    HeadingGyroDecision,
    TripAlignment,
    estimate_alignment,
    heading_gyro_decision,
    heading_gyro_radps,
    rotate_vector,
    vertical_gyro_radps,
)
from driftzero_ml.gnss_truth import column_speed_to_mps, orientation_rad

SCHEMA_VERSION = "1.0.0"
CLOCK_DOMAIN = "dataset_declared"
# Gravity-aligned axes after phone_align. Not a verified vehicle-forward map.
VECTOR_FRAME = "unspecified"
ACCEL_UNIT = "m/s^2"
GYRO_UNIT = "rad/s"
# packages/navigation-core DeadReckoningFilter / InsConfig.maxIntegrateS
MAX_INTEGRATE_S = 0.40
NS_PER_S = 1_000_000_000
DEFAULT_RATE_HZ = 10.0
MIN_GRAVITY_SAMPLES = 8
FLAG_HEADING_PICK_WEAK = "gyro_heading_pick_weak"
FLAG_HEADING_VERTICAL = "gyro_vertical_only"
FLAG_HEADING_UNAVAILABLE = "gyro_heading_axis_unavailable"
FLAG_REWIND = "timestamp_rewind_dropped"
FLAG_HOLD_LAST = "hold_last_imu"


def alignment_from_rows(rows: Sequence[SmartphoneRow], trip_id: str) -> TripAlignment | None:
    grav = [
        (float(row.gravity_x), float(row.gravity_y), float(row.gravity_z))
        for row in rows
        if row.gravity_x is not None and row.gravity_y is not None and row.gravity_z is not None
    ]
    if len(grav) < MIN_GRAVITY_SAMPLES:
        return None
    return estimate_alignment(grav, trip_id=trip_id)


def file_header(source_id: str, declared_rate_hz: float) -> dict:
    return {
        "declared_rate_hz": declared_rate_hz,
        "clock_domain": CLOCK_DOMAIN,
        "frame": VECTOR_FRAME,
        "source_id": source_id,
    }


def export_sensor_frames(
    rows: Sequence[SmartphoneRow],
    *,
    source_id: str | None = None,
    alignment: TripAlignment | None = None,
    validate: bool = True,
    mask_start_ns: int | None = None,
) -> tuple[dict, list[dict]]:
    """Build a replay header plus SensorFrame objects. No resampling.

    Heading-gyro correlation uses unique-fix hops strictly before mask_start_ns
    when that argument is set. Official blackout export must pass the interval
    start. A missing mask keeps the whole-trip pick for non-blackout helpers
    and is labeled in the header.
    """

    ordered, rewind = trim_nondecreasing_rows(list(rows))
    if not ordered:
        raise ValueError("no smartphone rows to export")
    trip_id = source_id or ordered[0].trip_id
    aligned = alignment if alignment is not None else alignment_from_rows(ordered, trip_id)
    gyros, fixes = _gyro_and_unique_fixes(ordered)
    heading_decision = heading_gyro_decision(gyros, fixes, mask_start_ns=mask_start_ns)
    frames: list[dict] = []
    sequence = 0
    last_fix: tuple[float, float] | None = None
    last_imu_ns: int | None = None
    imu_dts: list[float] = []
    rewind_flags = [FLAG_REWIND] if rewind is not None else []

    for row in ordered:
        stamp = int(row.timestamp_ns)
        gap = False
        if last_imu_ns is not None:
            dt = (stamp - last_imu_ns) / NS_PER_S
            if dt > 0.0:
                imu_dts.append(dt)
            if dt > MAX_INTEGRATE_S:
                gap = True
        accel_flags = _row_flags(row)
        accel_flags.extend(rewind_flags)
        if aligned is None:
            accel_flags.append("phone_align_unavailable")
        if gap:
            accel_flags.append("imu_gap")
        accel = _maybe_rotate((row.ax, row.ay, row.az), aligned)
        if accel is not None:
            frames.append(
                _vector_frame(
                    trip_id,
                    sequence,
                    stamp,
                    "accelerometer",
                    ACCEL_UNIT,
                    accel,
                    available=True,
                    accuracy_code=3,
                    flags=accel_flags,
                )
            )
            sequence += 1
        last_imu_ns = stamp

        gyro_triple = (row.gyro_yaw, row.gyro_pitch, row.gyro_roll)
        if None in gyro_triple:
            # Do not emit a gyro sample. Zeros would be a silent fill.
            pass
        else:
            raw_gyro = (float(row.gyro_yaw), float(row.gyro_pitch), float(row.gyro_roll))
            gyro, gyro_extra = _heading_rate_gyro(raw_gyro, aligned, heading_decision)
            gyro_flags = _row_flags(row)
            gyro_flags.extend(rewind_flags)
            if aligned is None:
                gyro_flags.append("phone_align_unavailable")
            gyro_flags.extend(gyro_extra)
            if gap:
                gyro_flags.append("imu_gap")
            if gyro is None:
                continue
            frames.append(
                _vector_frame(
                    trip_id,
                    sequence,
                    stamp,
                    "gyroscope",
                    GYRO_UNIT,
                    gyro,
                    available=True,
                    accuracy_code=3,
                    flags=gyro_flags,
                )
            )
            sequence += 1

        if row.latitude_deg is None or row.longitude_deg is None:
            continue
        pos = (float(row.latitude_deg), float(row.longitude_deg))
        if last_fix is not None and pos == last_fix:
            continue
        last_fix = pos
        gnss, ok = _gnss_frame(trip_id, sequence, row)
        if ok:
            gnss_flags = list(gnss["quality"]["flags"])
            gnss_flags.extend(rewind_flags)
            gnss["quality"]["flags"] = list(dict.fromkeys(gnss_flags))
            frames.append(gnss)
            sequence += 1

    if not frames:
        raise ValueError(f"{trip_id} produced no SensorFrame rows")
    rate = _declared_rate_hz(imu_dts)
    header = file_header(trip_id, rate)
    header["official_row"] = True
    header["sensitivity"] = False
    header["heading_gyro"] = _heading_gyro_header(heading_decision)
    header["notes"] = _export_notes(heading_decision, rewind)
    if rewind is not None:
        header["timestamp_rewind"] = {
            "dropped_rows": rewind.dropped_rows,
            "kept_rows": rewind.kept_rows,
            "source_rows": rewind.source_rows,
            "first_rewind_ns": rewind.first_rewind_ns,
            "suffix": rewind.suffix,
            "flag": FLAG_REWIND,
        }
    if validate:
        for frame in frames:
            validate_sensor_frame(frame)
    return header, frames


def hold_last_imu_upsample(
    header: dict,
    frames: Sequence[dict],
    *,
    target_hz: float = 100.0,
    validate: bool = True,
) -> tuple[dict, list[dict]]:
    """Sensitivity helper. Repeat the last IMU sample. Do not fill gaps > 0.40 s.

    Not an official IO-VNBD row. Output is labeled sensitivity_hold_last_imu.
    """

    if target_hz <= 0.0 or not math.isfinite(target_hz):
        raise ValueError("target_hz must be positive and finite")
    period_ns = int(round(NS_PER_S / target_hz))
    if period_ns <= 0:
        raise ValueError("target_hz is too high")
    imu = [row for row in frames if row["kind"] in ("accelerometer", "gyroscope")]
    gnss = [row for row in frames if row["kind"] == "gnss_fix"]
    last_by_kind: dict[str, dict] = {}
    last_ns_by_kind: dict[str, int] = {}
    held: list[dict] = []
    for row in imu:
        kind = str(row["kind"])
        stamp = int(row["timestamp_ns"])
        previous = last_by_kind.get(kind)
        prev_ns = last_ns_by_kind.get(kind)
        if previous is not None and prev_ns is not None:
            dt_s = (stamp - prev_ns) / NS_PER_S
            if 0.0 < dt_s <= MAX_INTEGRATE_S:
                fill = prev_ns + period_ns
                while fill < stamp:
                    held.append(_copy_imu_at(previous, fill))
                    fill += period_ns
        held.append(row)
        last_by_kind[kind] = row
        last_ns_by_kind[kind] = stamp
    merged = held + list(gnss)
    merged.sort(key=lambda item: (int(item["timestamp_ns"]), _kind_order(str(item["kind"]))))
    for index, row in enumerate(merged):
        row["sequence"] = index
    out_header = dict(header)
    out_header["declared_rate_hz"] = float(target_hz)
    out_header["official_row"] = False
    out_header["sensitivity"] = True
    out_header["sensitivity_kind"] = "hold_last_imu"
    out_header["hold_last_hz"] = float(target_hz)
    notes = list(out_header.get("notes") or [])
    notes.append(
        f"sensitivity hold_last_imu at {target_hz:g} Hz. Not an official IO-VNBD row."
    )
    out_header["notes"] = notes
    if validate:
        for row in merged:
            validate_sensor_frame(row)
    return out_header, merged


def write_sensorframe_jsonl(path: Path, header: dict, frames: Sequence[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as handle:
        handle.write(json.dumps(header, separators=(",", ":")) + "\n")
        for row in frames:
            handle.write(json.dumps(row, separators=(",", ":")) + "\n")


def write_truth_jsonl(path: Path, rows: Sequence[SmartphoneRow]) -> int:
    """Score-only unique-fix truth. Hidden from the filter by the replay mask."""

    path.parent.mkdir(parents=True, exist_ok=True)
    last: tuple[float, float] | None = None
    count = 0
    with path.open("w") as handle:
        for row in trim_nondecreasing_rows(list(rows))[0]:
            if row.latitude_deg is None or row.longitude_deg is None:
                continue
            pos = (float(row.latitude_deg), float(row.longitude_deg))
            if last is not None and pos == last:
                continue
            last = pos
            item: dict = {
                "timestamp_ns": int(row.timestamp_ns),
                "latitude_deg": float(row.latitude_deg),
                "longitude_deg": float(row.longitude_deg),
            }
            if row.speed_kmh is not None:
                item["gnss_speed_mps"] = column_speed_to_mps(row.speed_kmh, row.speed_unit)
            bearing = orientation_rad(
                {"gps_orientation_deg": row.gps_orientation_deg}
            )
            if bearing is not None:
                item["gnss_bearing_rad"] = bearing
            handle.write(json.dumps(item, separators=(",", ":")) + "\n")
            count += 1
    return count


def imu_gap_count(frames: Sequence[dict], *, max_integrate_s: float = MAX_INTEGRATE_S) -> int:
    """Count forward IMU jumps larger than the filter integration limit."""

    times = [
        int(row["timestamp_ns"])
        for row in frames
        if row["kind"] == "accelerometer"
    ]
    gaps = 0
    for earlier, later in zip(times, times[1:]):
        if (later - earlier) / NS_PER_S > max_integrate_s:
            gaps += 1
    return gaps


def _vector_frame(
    source_id: str,
    sequence: int,
    timestamp_ns: int,
    kind: str,
    unit: str,
    xyz: tuple[float, float, float],
    *,
    available: bool,
    accuracy_code: int,
    flags: list[str],
) -> dict:
    return {
        "schema_version": SCHEMA_VERSION,
        "source_id": source_id,
        "sequence": sequence,
        "timestamp_ns": timestamp_ns,
        "clock_domain": CLOCK_DOMAIN,
        "kind": kind,
        "quality": {
            "available": available,
            "accuracy_code": accuracy_code,
            "flags": list(dict.fromkeys(flags)),
        },
        "payload": {
            "x": xyz[0],
            "y": xyz[1],
            "z": xyz[2],
            "unit": unit,
            "frame": VECTOR_FRAME,
        },
    }


def _gnss_frame(source_id: str, sequence: int, row: SmartphoneRow) -> tuple[dict, bool]:
    flags = _row_flags(row)
    if row.accuracy_m is None:
        flags.append("gnss_accuracy_missing")
        return {}, False
    if not math.isfinite(row.accuracy_m) or row.accuracy_m < 0.0:
        flags.append("gnss_accuracy_invalid")
        return {}, False
    payload: dict = {
        "latitude_deg": float(row.latitude_deg),
        "longitude_deg": float(row.longitude_deg),
        "horizontal_accuracy_m": float(row.accuracy_m),
        "provider_time_ms": int(row.timestamp_ns // 1_000_000),
    }
    if row.altitude_m is not None and math.isfinite(row.altitude_m):
        payload["altitude_m"] = float(row.altitude_m)
    if row.speed_kmh is not None:
        speed = column_speed_to_mps(row.speed_kmh, row.speed_unit)
        if math.isfinite(speed) and speed >= 0.0:
            payload["speed_mps"] = speed
        else:
            flags.append("gnss_speed_invalid")
    else:
        flags.append("gnss_speed_missing")
    bearing = orientation_rad({"gps_orientation_deg": row.gps_orientation_deg})
    if bearing is not None:
        payload["bearing_rad"] = bearing
    frame = {
        "schema_version": SCHEMA_VERSION,
        "source_id": source_id,
        "sequence": sequence,
        "timestamp_ns": int(row.timestamp_ns),
        "clock_domain": CLOCK_DOMAIN,
        "kind": "gnss_fix",
        "quality": {
            "available": True,
            "accuracy_code": 3 if row.accuracy_m <= 15.0 else 2,
            "flags": list(dict.fromkeys(flags)),
        },
        "payload": payload,
    }
    return frame, True


def _row_flags(row: SmartphoneRow) -> list[str]:
    return [str(flag) for flag in row.quality_flags]


def _gyro_and_unique_fixes(
    rows: Sequence[SmartphoneRow],
) -> tuple[list[tuple[int, float, float, float]], list[tuple[int, float, float]]]:
    gyros: list[tuple[int, float, float, float]] = []
    fixes: list[tuple[int, float, float]] = []
    last: tuple[float, float] | None = None
    for row in rows:
        if row.gyro_yaw is not None and row.gyro_pitch is not None and row.gyro_roll is not None:
            if all(math.isfinite(axis) for axis in (row.gyro_yaw, row.gyro_pitch, row.gyro_roll)):
                gyros.append(
                    (int(row.timestamp_ns), float(row.gyro_yaw), float(row.gyro_pitch), float(row.gyro_roll))
                )
        if row.latitude_deg is None or row.longitude_deg is None:
            continue
        pos = (float(row.latitude_deg), float(row.longitude_deg))
        if last is not None and pos == last:
            continue
        last = pos
        fixes.append((int(row.timestamp_ns), pos[0], pos[1]))
    return gyros, fixes


def heading_gyro_from_rows(
    rows: Sequence[SmartphoneRow],
    *,
    mask_start_ns: int | None = None,
) -> HeadingGyroDecision:
    """Public pick used by export and by leak checks. Pre-mask when mask_start_ns is set."""

    ordered, _ = trim_nondecreasing_rows(list(rows))
    gyros, fixes = _gyro_and_unique_fixes(ordered)
    return heading_gyro_decision(gyros, fixes, mask_start_ns=mask_start_ns)


def _heading_rate_gyro(
    raw: tuple[float, float, float],
    alignment: TripAlignment | None,
    decision: HeadingGyroDecision,
) -> tuple[tuple[float, float, float] | None, list[str]]:
    """Place heading rate on +Z. Do not treat yaw/pitch/roll as device XYZ."""

    extra: list[str] = []
    if not all(math.isfinite(axis) for axis in raw):
        return None, extra
    pick = decision.pick
    if pick is not None:
        rate = heading_gyro_radps(raw, pick)
        if not math.isfinite(rate):
            return None, extra
        return (0.0, 0.0, rate), [f"gyro_heading_axis_{pick.axis}"]
    extra.append(FLAG_HEADING_PICK_WEAK)
    extra.append(f"gyro_heading_pick_{decision.reason}")
    if alignment is None:
        extra.append(FLAG_HEADING_UNAVAILABLE)
        return None, extra
    rate = vertical_gyro_radps(raw, alignment)
    if rate is None or not math.isfinite(rate):
        extra.append(FLAG_HEADING_UNAVAILABLE)
        return None, extra
    extra.append(FLAG_HEADING_VERTICAL)
    return (0.0, 0.0, rate), extra


def _heading_gyro_header(decision: HeadingGyroDecision) -> dict:
    pick = decision.pick
    source = "pre_mask" if decision.mask_start_ns is not None else "full_trip_no_mask"
    if pick is None:
        source = "gravity_vertical_fallback"
    return {
        "axis": None if pick is None else pick.axis,
        "sign": None if pick is None else pick.sign,
        "correlation": decision.best_correlation,
        "pair_count": decision.pair_count,
        "reason": decision.reason,
        "source": source,
        "mask_start_ns": decision.mask_start_ns,
        "best_axis": decision.best_axis,
    }


def _export_notes(decision: HeadingGyroDecision, rewind: TimestampRewind | None) -> list[str]:
    r_txt = "none" if decision.best_correlation is None else f"{decision.best_correlation:.4f}"
    axis = "none" if decision.best_axis is None else decision.best_axis
    chosen = decision.pick.axis if decision.pick is not None else "gravity_vertical_fallback"
    mask = "none" if decision.mask_start_ns is None else str(decision.mask_start_ns)
    notes = [
        (
            f"heading_gyro pick={chosen} axis={axis} r={r_txt} "
            f"n={decision.pair_count} reason={decision.reason} mask_start_ns={mask}"
        )
    ]
    if rewind is not None:
        kind = "suffix" if rewind.suffix else "interspersed"
        notes.append(
            f"timestamp_rewind dropped {rewind.dropped_rows} {kind} rows, "
            f"kept {rewind.kept_rows} of {rewind.source_rows}, "
            f"first_rewind_ns={rewind.first_rewind_ns}"
        )
    return notes


def _maybe_rotate(
    vector: tuple[float, float, float],
    alignment: TripAlignment | None,
) -> tuple[float, float, float] | None:
    if not all(math.isfinite(axis) for axis in vector):
        return None
    if alignment is None:
        return vector
    rotated = rotate_vector(vector, alignment.rotation)
    if not all(math.isfinite(axis) for axis in rotated):
        return None
    return rotated


def _copy_imu_at(frame: dict, timestamp_ns: int) -> dict:
    copied = json.loads(json.dumps(frame))
    copied["timestamp_ns"] = int(timestamp_ns)
    flags = list(copied["quality"]["flags"])
    if FLAG_HOLD_LAST not in flags:
        flags.append(FLAG_HOLD_LAST)
    copied["quality"]["flags"] = flags
    return copied


def _kind_order(kind: str) -> int:
    return {"accelerometer": 0, "gyroscope": 1, "gnss_fix": 2}.get(kind, 9)


def _declared_rate_hz(imu_dts: Sequence[float]) -> float:
    if not imu_dts:
        return DEFAULT_RATE_HZ
    mid = median(imu_dts)
    if mid <= 0.0 or not math.isfinite(mid):
        return DEFAULT_RATE_HZ
    return 1.0 / mid


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Export one IO-VNBD trip to SensorFrame JSONL")
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--truth-out", type=Path, default=None)
    parser.add_argument("--hold-last-hz", type=float, default=None)
    parser.add_argument(
        "--mask-start-ns",
        type=int,
        default=None,
        help="Blackout start. Heading-gyro pick uses unique-fix hops strictly before this.",
    )
    args = parser.parse_args(argv)
    from driftzero_ml.datasets.io_vnbd import load_smartphone_csv

    rows = load_smartphone_csv(args.csv)
    header, frames = export_sensor_frames(rows, mask_start_ns=args.mask_start_ns)
    if args.hold_last_hz is not None:
        header, frames = hold_last_imu_upsample(header, frames, target_hz=args.hold_last_hz)
    write_sensorframe_jsonl(args.out, header, frames)
    truth_n = 0
    if args.truth_out is not None:
        truth_n = write_truth_jsonl(args.truth_out, rows)
    payload = {
        "frames": len(frames),
        "imu_gaps_over_max_integrate_s": imu_gap_count(frames),
        "declared_rate_hz": header["declared_rate_hz"],
        "truth_fixes": truth_n,
        "out": str(args.out),
        "official_row": bool(header.get("official_row", True)),
        "heading_gyro": header.get("heading_gyro"),
        "notes": header.get("notes"),
    }
    if header.get("timestamp_rewind"):
        payload["timestamp_rewind"] = header["timestamp_rewind"]
    if header.get("sensitivity"):
        payload["sensitivity"] = header.get("sensitivity_kind", True)
        payload["hold_last_hz"] = header.get("hold_last_hz")
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
