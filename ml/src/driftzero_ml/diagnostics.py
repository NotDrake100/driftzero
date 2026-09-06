"""Score-only error-budget diagnostics. Never a candidate inference API.

Every report this module emits is labeled DIAGNOSTIC_ONLY. Hidden GNSS is used
only to reconstruct counterfactual paths and attribute scored error. Those
paths are not phone-filter outputs and must not be submitted to accuracy_gate
as a candidate. Sparse unique-fix derivatives are approximate.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import itertools
import json
import math
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from statistics import median

from driftzero_ml.accuracy_gate import DIAGNOSTIC_LABEL, assess
from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.eval_iovnbd_blackout import (
    _time_ordered,
    eval_record,
    screening_csv_paths,
)
from driftzero_ml.gnss_truth import (
    TruthGateConfig,
    assess_truth,
    course_rad,
    persist_coast_seed,
    score_epochs,
    unique_fix_median_spacing_s,
    unique_fix_records,
)
from driftzero_ml.io_vnbd.splits import driver_group_id, session_group_id
from driftzero_ml.metrics import (
    EARTH_MEAN_RADIUS_M,
    along_cross_m,
    evaluate_blackout,
    haversine_m,
    path_heading_rad,
    path_length_m,
    wrap_heading_error_rad,
)
from driftzero_ml.screening import GATED_INTERVAL_IDS, locked_blackouts

SEED = "26168"
HOLDOUT_FRACTION = 0.30
STOP_SPEED_MPS = 0.4
IMU_GAP_S = 0.40
HEADING_TURN_RAD = math.radians(30.0)
SPARSE_FIX_S = 2.0
NS_PER_S = 1_000_000_000
# Identical to eval_kotlin_replay.SUITE_GATES. Copied so this module stays score-only.
SUITE_GATES = {
    "mid": TruthGateConfig(),
    "d50": TruthGateConfig(min_path_length_m=40.0, min_unique_fixes=3),
    "d1000": TruthGateConfig(min_path_length_m=800.0, min_unique_fixes=8),
}

# ADR 013/014 development groups. Already used for selection. Not fresh.
DEVELOPMENT_SESSION_GROUPS = frozenset(
    {
        "S-S4",
        "S-Vta5",
        "S-Vta6",
        "S-Vta8",
        "S-Vta11",
        "S-Vta13",
        "S-Vta29",
        "S-Vtb2",
        "S-Vtb7",
        "S-Vw3",
        "S-Vw12",
        "S-Vw17",
    }
)
# Speed column matches neither m/s nor km/h. Do not train or score as holdout.
EXCLUDED_SESSION_GROUPS = frozenset({"S-Vtb3"})

# Categorised synchronised S-*.csv stems from data/manifests/io_vnbd_screening_v1.yaml.
SCREENING_TRIPS = (
    "S-M",
    "S-S1",
    "S-S2",
    "S-S3a",
    "S-S3b",
    "S-S3c",
    "S-S4",
    "S-Vfa01",
    "S-Vfa02",
    "S-Vta1a",
    "S-Vta1b",
    "S-Vta2",
    "S-Vta3",
    "S-Vta4",
    "S-Vta5",
    "S-Vta6",
    "S-Vta7",
    "S-Vta8",
    "S-Vta9",
    "S-Vta10",
    "S-Vta11",
    "S-Vta12",
    "S-Vta13",
    "S-Vta14",
    "S-Vta15",
    "S-Vta16",
    "S-Vta17",
    "S-Vta19",
    "S-Vta20",
    "S-Vta21",
    "S-Vta22",
    "S-Vta23",
    "S-Vta24",
    "S-Vta25",
    "S-Vta26",
    "S-Vta27",
    "S-Vta28",
    "S-Vta29",
    "S-Vta30",
    "S-Vtb1",
    "S-Vtb2",
    "S-Vtb3",
    "S-Vtb4",
    "S-Vtb5",
    "S-Vtb6",
    "S-Vtb7",
    "S-Vtb8",
    "S-Vtb9",
    "S-Vtb10",
    "S-Vtb11",
    "S-Vtb12",
    "S-Vw1",
    "S-Vw2",
    "S-Vw3",
    "S-Vw4",
    "S-Vw5",
    "S-Vw6",
    "S-Vw7",
    "S-Vw8",
    "S-Vw9",
    "S-Vw10",
    "S-Vw11",
    "S-Vw12",
    "S-Vw13",
    "S-Vw14a",
    "S-Vw14b",
    "S-Vw14c",
    "S-Vw15",
    "S-Vw16a",
    "S-Vw16b",
    "S-Vw17",
    "S-Y1",
)

# Archived locked confirmation of latch_sparse_reseed. Do not replace silently.
ARCHIVED_LOCKED = {
    "source": (
        "results/accuracy_v3_20260906/development/locked_selected/"
        "metrics_latch_sparse_reseed.json"
    ),
    "csv": (
        "results/accuracy_v3_20260906/development/locked_selected/"
        "metrics_per_interval_latch_sparse_reseed.csv"
    ),
    "interval_count": 35,
    "drift_ratio_p50": 0.48834485395037297,
    "drift_ratio_p95": 2.682407053981851,
    "drift_ratio_worst": 3.1766947772530147,
    "intervals_below_10": 6,
    "endpoint_p50_m": 110.41919116328249,
}

SUBSTITUTION_VARIANTS = (
    "ref_speed",
    "ref_heading",
    "ref_road",
    "ref_speed_heading",
    "ref_speed_road",
    "ref_heading_road",
    "ref_joint",
)

METADATA_LIMITS = (
    "Route and vehicle identities are unavailable in published IO-VNBD stems. "
    "Driver letter is only the prefix mapping in "
    "ml/src/driftzero_ml/io_vnbd/splits.py (S-S=A, S-M=B, S-Y=D, S-V=E)."
)


def locked_session_groups() -> frozenset[str]:
    return frozenset(session_group_id(key.split(":")[0]) for key in GATED_INTERVAL_IDS)


def group_role(group_id: str, *, unused_holdout: set[str], unused_train: set[str]) -> str:
    if group_id in EXCLUDED_SESSION_GROUPS:
        return "excluded"
    if group_id in locked_session_groups():
        return "locked_confirmation"
    if group_id in DEVELOPMENT_SESSION_GROUPS:
        return "development"
    if group_id in unused_holdout:
        return "fresh_holdout"
    if group_id in unused_train:
        return "train"
    return "unassigned"


def _hash_unit(seed: str, tag: str, group_id: str) -> float:
    material = f"{seed}:{tag}:{group_id}".encode()
    return int.from_bytes(hashlib.sha256(material).digest()[:8], "big") / 2**64


def reserve_unused_groups(
    unused: Sequence[str],
    *,
    seed: str = SEED,
    holdout_fraction: float = HOLDOUT_FRACTION,
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Reserve unused groups for holdout before any outcome is read."""

    if not 0.0 < holdout_fraction < 1.0:
        raise ValueError("holdout_fraction must be between 0 and 1")
    ordered = sorted(unused, key=lambda g: (_hash_unit(seed, "fresh-holdout", g), g))
    holdout_n = max(1, round(len(ordered) * holdout_fraction)) if ordered else 0
    holdout = tuple(ordered[:holdout_n])
    train = tuple(sorted(ordered[holdout_n:]))
    return holdout, train


def freeze_split_manifest(
    trips: Sequence[str] | None = None,
    *,
    seed: str = SEED,
) -> dict:
    """Freeze train / development / fresh-holdout roles. No outcome peeking."""

    trip_ids = tuple(trips or SCREENING_TRIPS)
    locked = locked_session_groups()
    groups: dict[str, list[str]] = {}
    for trip_id in trip_ids:
        groups.setdefault(session_group_id(trip_id), []).append(trip_id)
    unused = [
        group
        for group in groups
        if group not in locked
        and group not in DEVELOPMENT_SESSION_GROUPS
        and group not in EXCLUDED_SESSION_GROUPS
    ]
    holdout, train = reserve_unused_groups(unused, seed=seed)
    holdout_set, train_set = set(holdout), set(train)
    assignments = []
    for trip_id in trip_ids:
        group = session_group_id(trip_id)
        role = group_role(group, unused_holdout=holdout_set, unused_train=train_set)
        exposed = role in {"locked_confirmation", "development"}
        assignments.append(
            {
                "trip_id": trip_id,
                "session_group_id": group,
                "driver_letter": driver_group_id(trip_id),
                "role": role,
                "previously_exposed": exposed,
                "fresh": role == "fresh_holdout",
            }
        )
    return {
        "label": DIAGNOSTIC_LABEL,
        "diagnostic_only": True,
        "seed": seed,
        "holdout_fraction": HOLDOUT_FRACTION,
        "metadata_limits": METADATA_LIMITS,
        "locked_interval_ids": sorted(GATED_INTERVAL_IDS),
        "locked_session_groups": sorted(locked),
        "development_session_groups": sorted(DEVELOPMENT_SESSION_GROUPS),
        "excluded_session_groups": sorted(EXCLUDED_SESSION_GROUPS),
        "train_session_groups": list(train),
        "fresh_holdout_session_groups": list(holdout),
        "previously_exposed_session_groups": sorted(locked | DEVELOPMENT_SESSION_GROUPS),
        "notes": [
            "Locked session siblings are excluded from training and selection.",
            "Development groups were used for ADR 013/014. They are not fresh.",
            "Fresh holdout groups were reserved by SHA-256 order before outcomes.",
            "S-Vtb3 is excluded because the speed column matches neither unit.",
            "Unsynchronised S-A*/S-T* trees are outside this screening manifest.",
        ],
        "assignments": assignments,
    }


def destination_latlon(
    origin: tuple[float, float],
    heading_rad: float,
    distance_m: float,
) -> tuple[float, float]:
    """Advance origin by distance_m along north-zero, east-positive heading."""

    lat, lon = origin
    if not math.isfinite(heading_rad) or not math.isfinite(distance_m):
        raise ValueError("heading and distance must be finite")
    north = distance_m * math.cos(heading_rad)
    east = distance_m * math.sin(heading_rad)
    dlat = math.degrees(north / EARTH_MEAN_RADIUS_M)
    denom = EARTH_MEAN_RADIUS_M * math.cos(math.radians(lat))
    if abs(denom) < 1e-12:
        raise ValueError("cannot advance at the pole")
    dlon = math.degrees(east / denom)
    dest = (lat + dlat, lon + dlon)
    if not -90.0 <= dest[0] <= 90.0:
        raise ValueError(f"latitude out of range: {dest[0]}")
    wrapped = ((dest[1] + 180.0) % 360.0) - 180.0
    return dest[0], wrapped


def follow_polyline(
    polyline: Sequence[tuple[float, float]],
    distance_m: float,
) -> tuple[tuple[float, float], bool]:
    """Walk distance_m from the first vertex. overshoot=True if length is short."""

    if not polyline:
        raise ValueError("polyline must contain at least one point")
    if distance_m <= 0.0:
        return polyline[0], False
    remaining = distance_m
    for start, end in itertools.pairwise(polyline):
        seg = haversine_m(start, end)
        if remaining <= seg:
            if seg < 1e-9:
                return end, False
            heading = path_heading_rad([start, end])
            return destination_latlon(start, heading, remaining), False
        remaining -= seg
    heading = path_heading_rad(polyline) if len(polyline) >= 2 else 0.0
    return destination_latlon(polyline[-1], heading, remaining), True


@dataclass(frozen=True)
class EpochMotion:
    timestamp_ns: int
    truth: tuple[float, float]
    dt_s: float
    candidate_speed_mps: float | None
    candidate_heading_rad: float | None
    ref_speed_mps: float | None
    ref_heading_rad: float | None
    ref_speed_sparse: bool
    ref_heading_sparse: bool


def reference_derivatives(
    previous: dict | None,
    current: dict,
    *,
    sparse_s: float = SPARSE_FIX_S,
) -> tuple[float | None, float | None, bool, bool]:
    """Finite-difference speed and course from consecutive unique fixes.

    Sparse unique-fix spacing makes these approximate. They are not a perfect
    oracle and must stay inside DIAGNOSTIC_ONLY reports.
    """

    if previous is None:
        column = current.get("gnss_speed_mps")
        heading = None
        if current.get("gps_orientation_deg") is not None:
            heading = math.radians(float(current["gps_orientation_deg"])) % (2.0 * math.pi)
        return (
            float(column) if column is not None else None,
            heading,
            True,
            True,
        )
    dt = (int(current["timestamp_ns"]) - int(previous["timestamp_ns"])) / NS_PER_S
    sparse = dt > sparse_s
    if dt <= 0.0:
        return None, None, True, True
    dist = haversine_m(
        (float(previous["latitude_deg"]), float(previous["longitude_deg"])),
        (float(current["latitude_deg"]), float(current["longitude_deg"])),
    )
    heading = course_rad(previous, current)
    return dist / dt, heading, sparse, sparse or heading is None


def build_epoch_motion(
    truth_records: Sequence[dict],
    start_ns: int,
    end_ns: int,
    *,
    candidate_by_t: dict[int, dict] | None = None,
    seed_speed_mps: float | None = None,
    seed_heading_rad: float | None = None,
) -> list[EpochMotion]:
    """Align score_epochs with reference derivatives and optional candidate motion."""

    original = {int(row["timestamp_ns"]): row for row in truth_records}
    epochs = score_epochs(truth_records, start_ns, end_ns)
    out: list[EpochMotion] = []
    previous = None
    last_t = start_ns
    for stamp in epochs:
        src = original[stamp]
        truth = (float(src["latitude_deg"]), float(src["longitude_deg"]))
        dt_s = (stamp - last_t) / NS_PER_S
        ref_speed, ref_heading, speed_sparse, heading_sparse = reference_derivatives(
            previous, src
        )
        cand = (candidate_by_t or {}).get(stamp, {})
        motion = cand.get("motion") if isinstance(cand.get("motion"), dict) else {}
        cand_speed = motion.get("speed_mps")
        cand_heading = motion.get("heading_rad")
        if cand_speed is None and seed_speed_mps is not None:
            cand_speed = seed_speed_mps
        if cand_heading is None and seed_heading_rad is not None:
            cand_heading = seed_heading_rad
        out.append(
            EpochMotion(
                timestamp_ns=stamp,
                truth=truth,
                dt_s=dt_s,
                candidate_speed_mps=None if cand_speed is None else float(cand_speed),
                candidate_heading_rad=(
                    None if cand_heading is None else float(cand_heading)
                ),
                ref_speed_mps=ref_speed,
                ref_heading_rad=ref_heading,
                ref_speed_sparse=speed_sparse,
                ref_heading_sparse=heading_sparse,
            )
        )
        previous = src
        last_t = stamp
    return out


def reconstruct_path(
    seed: tuple[float, float],
    epochs: Sequence[EpochMotion],
    *,
    use_ref_speed: bool,
    use_ref_heading: bool,
    use_ref_road: bool,
) -> tuple[list[tuple[float, float]], list[str]]:
    """Integrate a counterfactual path on the same scored epochs.

    Road choice here is the hidden unique-fix polyline, not an OSM map. OSM
    topology is unsupported in this module (Task B owns map code).
    """

    notes: list[str] = []
    if use_ref_road:
        notes.append("road_choice=reference_unique_fix_polyline")
        notes.append("osm_topology=unsupported")
    if any(row.ref_speed_sparse for row in epochs if use_ref_speed):
        notes.append("ref_speed=approximate_sparse_unique_fix_fd")
    if any(row.ref_heading_sparse for row in epochs if use_ref_heading):
        notes.append("ref_heading=approximate_sparse_unique_fix_course")

    polyline = [row.truth for row in epochs]
    estimate: list[tuple[float, float]] = []
    pos = seed
    travelled = 0.0
    for index, row in enumerate(epochs):
        if index == 0 and row.dt_s <= 1e-12:
            estimate.append(seed if not use_ref_road else row.truth)
            pos = estimate[-1]
            continue
        speed = row.ref_speed_mps if use_ref_speed else row.candidate_speed_mps
        heading = row.ref_heading_rad if use_ref_heading else row.candidate_heading_rad
        if speed is None:
            notes.append(f"unsupported_speed@{row.timestamp_ns}")
            estimate.append(pos)
            continue
        step = max(0.0, speed * max(0.0, row.dt_s))
        travelled += step
        if use_ref_road:
            if not polyline:
                notes.append("unsupported_empty_polyline")
                estimate.append(pos)
                continue
            pos, overshoot = follow_polyline(polyline, travelled)
            if overshoot:
                notes.append(f"polyline_overshoot@{row.timestamp_ns}")
        else:
            if heading is None:
                notes.append(f"unsupported_heading@{row.timestamp_ns}")
                estimate.append(pos)
                continue
            pos = destination_latlon(pos, heading, step)
        estimate.append(pos)
    return estimate, notes


def variant_flags(name: str) -> tuple[bool, bool, bool]:
    if name not in SUBSTITUTION_VARIANTS:
        raise ValueError(f"unknown substitution variant: {name}")
    return (
        "speed" in name or name == "ref_joint",
        "heading" in name or name == "ref_joint",
        "road" in name or name == "ref_joint",
    )


def stamp_diagnostic(payload: dict) -> dict:
    stamped = dict(payload)
    stamped["label"] = DIAGNOSTIC_LABEL
    stamped["diagnostic_only"] = True
    stamped["evidence_class"] = DIAGNOSTIC_LABEL
    return stamped


def score_substitution(
    seed: tuple[float, float],
    epochs: Sequence[EpochMotion],
    variant: str,
) -> dict:
    use_speed, use_heading, use_road = variant_flags(variant)
    estimate, notes = reconstruct_path(
        seed,
        epochs,
        use_ref_speed=use_speed,
        use_ref_heading=use_heading,
        use_ref_road=use_road,
    )
    truth = [row.truth for row in epochs]
    unsupported = [note for note in notes if note.startswith("unsupported")]
    if len(estimate) != len(truth) or not truth:
        return stamp_diagnostic(
            {
                "variant": variant,
                "unsupported": True,
                "notes": notes,
                "metrics": None,
            }
        )
    metrics = evaluate_blackout(estimate, truth)
    return stamp_diagnostic(
        {
            "variant": variant,
            "reference_substitution": True,
            "unsupported": bool(unsupported),
            "notes": notes,
            "metrics": metrics.to_dict(),
        }
    )


def interval_seed(records: Sequence[dict], start_ns: int) -> dict:
    history = [row for row in records if int(row["timestamp_ns"]) < start_ns]
    picked = persist_coast_seed(history, before_ns=start_ns)
    start_row = next(
        (
            row
            for row in unique_fix_records(records)
            if int(row["timestamp_ns"]) == start_ns
        ),
        None,
    )
    if start_row is not None:
        pos = (float(start_row["latitude_deg"]), float(start_row["longitude_deg"]))
        speed = start_row.get("gnss_speed_mps")
        heading = None
        if picked is not None:
            heading = picked.heading_rad
            if speed is None:
                speed = picked.speed_mps
        return {
            "latitude_deg": pos[0],
            "longitude_deg": pos[1],
            "timestamp_ns": start_ns,
            "speed_mps": None if speed is None else float(speed),
            "heading_rad": heading,
            "source": "interval_start_unique_fix",
        }
    if picked is None:
        return {
            "latitude_deg": None,
            "longitude_deg": None,
            "timestamp_ns": None,
            "speed_mps": None,
            "heading_rad": None,
            "source": "unavailable",
        }
    return {
        "latitude_deg": picked.latitude_deg,
        "longitude_deg": picked.longitude_deg,
        "timestamp_ns": picked.timestamp_ns,
        "speed_mps": picked.speed_mps,
        "heading_rad": picked.heading_rad,
        "source": "persist_coast_seed_before_start",
    }


def audit_records(records: Sequence[dict], *, trip_id: str) -> dict:
    stamps = [int(row["timestamp_ns"]) for row in records]
    rewinds = sum(1 for a, b in itertools.pairwise(stamps) if b < a)
    dts = [(b - a) / NS_PER_S for a, b in itertools.pairwise(stamps) if b > a]
    imu_gaps = sum(1 for dt in dts if dt > IMU_GAP_S)
    non_int = sum(1 for row in records if type(row["timestamp_ns"]) is not int)
    fixes = unique_fix_records(records)
    hops = []
    for a, b in itertools.pairwise(fixes):
        hops.append(
            haversine_m(
                (float(a["latitude_deg"]), float(a["longitude_deg"])),
                (float(b["latitude_deg"]), float(b["longitude_deg"])),
            )
        )
    speeds = [
        float(row["gnss_speed_mps"])
        for row in records
        if row.get("gnss_speed_mps") is not None
    ]
    gyro = sum(1 for row in records if "gx" in row and "gy" in row and "gz" in row)
    return stamp_diagnostic(
        {
            "trip_id": trip_id,
            "session_group_id": session_group_id(trip_id),
            "driver_letter": driver_group_id(trip_id),
            "n_rows": len(records),
            "timestamp_rewinds": rewinds,
            "non_integer_timestamps": non_int,
            "dt_median_s": median(dts) if dts else None,
            "dt_p95_s": sorted(dts)[max(0, int(0.95 * len(dts)) - 1)] if dts else None,
            "imu_gaps_over_0_40s": imu_gaps,
            "unique_fixes": len(fixes),
            "unique_fix_median_spacing_s": unique_fix_median_spacing_s(records),
            "max_unique_hop_m": max(hops) if hops else 0.0,
            "gnss_speed_median_mps": median(speeds) if speeds else None,
            "gyro_complete_frac": gyro / len(records) if records else 0.0,
            "axis_semantics": (
                "Gyro yaw/pitch/roll are IO-VNBD published labels, not a verified "
                "Android device XYZ map. heading_gyro_radps is right-handed up. "
                "GPS SPEED (Kmh) is metres per second on verified tables."
            ),
            "coordinate_frame": "WGS84 geodetic degrees. ENU metres from local origin.",
        }
    )


def attribute_interval(
    interval_id: str,
    records: Sequence[dict],
    start_ns: int,
    end_ns: int,
    *,
    candidate_row: dict | None = None,
    candidate_states: Sequence[dict] | None = None,
) -> dict:
    epochs_t = score_epochs(records, start_ns, end_ns)
    original = {int(row["timestamp_ns"]): row for row in records}
    truth = [
        (float(original[t]["latitude_deg"]), float(original[t]["longitude_deg"]))
        for t in epochs_t
        if t in original
    ]
    seed = interval_seed(records, start_ns)
    seed_pos = (
        (seed["latitude_deg"], seed["longitude_deg"])
        if seed["latitude_deg"] is not None
        else None
    )
    first = truth[0] if truth else None
    seed_error_m = (
        haversine_m(seed_pos, first) if seed_pos is not None and first is not None else None
    )
    duration_s = (end_ns - start_ns) / NS_PER_S
    spacing = unique_fix_median_spacing_s(
        [original[t] for t in epochs_t if t in original]
    )
    hops = [haversine_m(a, b) for a, b in itertools.pairwise(truth)]
    turns = 0
    last_heading = None
    for a, b in itertools.pairwise(truth):
        heading = path_heading_rad([a, b])
        if last_heading is not None and abs(
            wrap_heading_error_rad(heading, last_heading)
        ) >= HEADING_TURN_RAD:
            turns += 1
        last_heading = heading
    window = [row for row in records if start_ns <= int(row["timestamp_ns"]) < end_ns]
    dts = [
        (int(b["timestamp_ns"]) - int(a["timestamp_ns"])) / NS_PER_S
        for a, b in itertools.pairwise(window)
    ]
    imu_gaps = sum(1 for dt in dts if dt > IMU_GAP_S)
    stop_epochs = 0
    for stamp in epochs_t:
        src = original.get(stamp)
        if src is None:
            continue
        speed = src.get("gnss_speed_mps")
        if speed is not None and float(speed) < STOP_SPEED_MPS:
            stop_epochs += 1
    # Repeated lat/lon at a stop is not a unique-fix epoch. Count those samples.
    stop_samples = sum(
        1
        for row in window
        if row.get("gnss_speed_mps") is not None
        and float(row["gnss_speed_mps"]) < STOP_SPEED_MPS
    )
    path = path_length_m(truth) if truth else 0.0
    cand_metrics = (candidate_row or {}).get("metrics") or {}
    extras = (candidate_row or {}).get("extras") or {}
    endpoint = cand_metrics.get("endpoint_error_m")
    cand_path = cand_metrics.get("truth_path_length_m")
    drift = None
    if (
        isinstance(endpoint, (int, float))
        and isinstance(cand_path, (int, float))
        and cand_path > 0
    ):
        drift = endpoint / cand_path
    along = cand_metrics.get("along_track_error_m")
    cross = cand_metrics.get("cross_track_error_m")
    if (
        along is None
        and candidate_states
        and truth
        and seed_pos is not None
    ):
        last_state = candidate_states[-1]
        est = (
            float(last_state["position"]["latitude_deg"]),
            float(last_state["position"]["longitude_deg"]),
        )
        heading = path_heading_rad(truth) if len(truth) >= 2 else 0.0
        along, cross = along_cross_m(est, truth[-1], heading)
    speed_mae = extras.get("speed_mae_mps")
    heading_mae = extras.get("heading_mae_rad")
    distance_error_m = None
    if candidate_states and len(candidate_states) >= 2:
        est_path = path_length_m(
            [
                (
                    float(row["position"]["latitude_deg"]),
                    float(row["position"]["longitude_deg"]),
                )
                for row in candidate_states
            ]
        )
        distance_error_m = est_path - path
    plausible, not_plausible = _plausible_improvements(
        drift=drift,
        path_m=path,
        seed_error_m=seed_error_m,
        speed_mae=speed_mae,
        heading_mae=heading_mae,
        along=along,
        cross=cross,
        turns=turns,
        stop_epochs=stop_epochs,
        stop_samples=stop_samples,
        imu_gaps=imu_gaps,
        spacing_s=spacing,
    )
    return stamp_diagnostic(
        {
            "interval_id": interval_id,
            "start_ns": start_ns,
            "end_ns": end_ns,
            "duration_s": duration_s,
            "score_epochs": len(epochs_t),
            "truth_path_length_m": path,
            "seed": seed,
            "seed_error_m": seed_error_m,
            "unique_fix_median_spacing_s": spacing,
            "max_hop_m": max(hops) if hops else 0.0,
            "heading_turns_ge_30deg": turns,
            "stop_epochs_speed_lt_0_4": stop_epochs,
            "stop_samples_speed_lt_0_4": stop_samples,
            "imu_gaps_over_0_40s": imu_gaps,
            "candidate_drift_ratio": drift,
            "candidate_endpoint_error_m": endpoint,
            "candidate_along_track_error_m": along,
            "candidate_cross_track_error_m": cross,
            "candidate_speed_mae_mps": speed_mae,
            "candidate_heading_mae_rad": heading_mae,
            "candidate_distance_error_m": distance_error_m,
            "road_ambiguity": {
                "osm_topology": "unsupported",
                "reference_polyline_turns_ge_30deg": turns,
                "sparse_fixes": spacing is not None and spacing > SPARSE_FIX_S,
                "note": (
                    "OSM fork ambiguity is Task B. This row only reports "
                    "reference-polyline heading changes and fix sparsity."
                ),
            },
            "plausibly_closes_gap": plausible,
            "would_not_close_gap": not_plausible,
        }
    )


def _plausible_improvements(
    *,
    drift: float | None,
    path_m: float,
    seed_error_m: float | None,
    speed_mae: float | None,
    heading_mae: float | None,
    along: float | None,
    cross: float | None,
    turns: int,
    stop_epochs: int,
    stop_samples: int,
    imu_gaps: int,
    spacing_s: float | None,
) -> tuple[list[str], list[str]]:
    plausible: list[str] = []
    not_plausible: list[str] = []
    budget = 0.10 * path_m if path_m > 0 else None
    if drift is not None and drift < 0.10:
        not_plausible.append("already_below_10_percent")
        return plausible, not_plausible
    if seed_error_m is not None and budget is not None and seed_error_m > 0.5 * budget:
        plausible.append("seed_or_reference_uncertainty")
    if speed_mae is not None and speed_mae >= 2.0:
        plausible.append("causal_speed_or_distance")
    elif speed_mae is not None and speed_mae < 1.0:
        not_plausible.append("speed_mae_already_small")
    if heading_mae is not None and heading_mae >= 0.35:
        plausible.append("heading_or_turn")
    elif heading_mae is not None and heading_mae < 0.15:
        not_plausible.append("heading_mae_already_small")
    if cross is not None and budget is not None and abs(cross) > 0.4 * budget:
        plausible.append("cross_track_heading_or_road")
    if along is not None and budget is not None and abs(along) > 0.4 * budget:
        plausible.append("along_track_distance")
    if turns >= 1:
        plausible.append("road_choice_at_turns")
    else:
        not_plausible.append("no_reference_turn_ge_30deg")
    if stop_epochs >= 1 or stop_samples >= 1:
        plausible.append("stop_restart")
    if imu_gaps >= 1:
        plausible.append("imu_gap_handling")
    if spacing_s is not None and spacing_s > SPARSE_FIX_S:
        plausible.append("reference_uncertainty_sparse_fixes")
        not_plausible.append("cannot_certify_5m_on_sparse_reference")
    if not plausible:
        plausible.append("audit_coordinate_time_scoring_before_more_model_complexity")
    return plausible, not_plausible


def compare_to_archived(payload: dict) -> dict:
    summary = payload.get("summary") or {}
    rows = payload.get("per_interval") or []
    ratios = []
    for row in rows:
        metrics = row.get("metrics") or {}
        endpoint = metrics.get("endpoint_error_m")
        distance = metrics.get("truth_path_length_m")
        if (
            isinstance(endpoint, (int, float))
            and isinstance(distance, (int, float))
            and distance > 0
            and math.isfinite(endpoint)
            and math.isfinite(distance)
        ):
            ratios.append(endpoint / distance)
    below = sum(1 for ratio in ratios if ratio < 0.10)
    measured = {
        "interval_count": len(ratios),
        "drift_ratio_p50": summary.get("drift_ratio_p50"),
        "drift_ratio_p95": summary.get("drift_ratio_p95"),
        "drift_ratio_worst": summary.get("drift_ratio_worst"),
        "intervals_below_10": below,
    }
    mismatches = []
    if measured["interval_count"] != ARCHIVED_LOCKED["interval_count"]:
        mismatches.append("interval_count")
    for key in ("drift_ratio_p50", "drift_ratio_p95"):
        archived = ARCHIVED_LOCKED[key]
        value = measured[key]
        if value is None or abs(float(value) - archived) > 5e-4:
            mismatches.append(key)
    if measured["intervals_below_10"] != ARCHIVED_LOCKED["intervals_below_10"]:
        mismatches.append("intervals_below_10")
    return stamp_diagnostic(
        {
            "archived": ARCHIVED_LOCKED,
            "measured": measured,
            "matches_archive": not mismatches,
            "mismatches": mismatches,
            "note": (
                "Measured values match the archived locked result."
                if not mismatches
                else "A mismatch is reported. The archived locked result is not replaced."
            ),
        }
    )


def load_archived_interval_rows(repo: Path) -> dict[str, dict]:
    path = repo / ARCHIVED_LOCKED["csv"]
    if not path.is_file():
        return {}
    out: dict[str, dict] = {}
    with path.open() as handle:
        for row in csv.DictReader(handle):
            out[row["interval_id"]] = {
                "interval_id": row["interval_id"],
                "trip_id": row["trip_id"],
                "metrics": {
                    "endpoint_error_m": float(row["endpoint_error_m"]),
                    "truth_path_length_m": float(row["truth_path_length_m"]),
                    "drift_ratio": float(row["drift_ratio"]) if row["drift_ratio"] else None,
                },
                "extras": {
                    "speed_mae_mps": float(row["speed_mae_mps"]),
                    "heading_mae_rad": float(row["heading_mae_rad"]),
                },
            }
    return out


def load_trip_records(repo: Path, trip_id: str) -> list[dict]:
    from driftzero_ml.datasets.io_vnbd import load_smartphone_csv

    tables = {path.stem: path for path in screening_csv_paths(repo / "data" / "raw" / "io_vnbd")}
    if trip_id not in tables:
        raise FileNotFoundError(f"screening CSV missing for {trip_id}")
    return _time_ordered([eval_record(row) for row in load_smartphone_csv(tables[trip_id])])


def run_diagnostics(
    repo: Path,
    out_dir: Path,
    *,
    states_dir: Path | None = None,
    reproduced_metrics: Path | None = None,
) -> dict:
    repo = repo.resolve()
    out_dir = out_dir if out_dir.is_absolute() else repo / out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = freeze_split_manifest()
    (out_dir / "split_manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n"
    )
    archived_rows = load_archived_interval_rows(repo)
    audits: list[dict] = []
    attributions: list[dict] = []
    substitutions: list[dict] = []
    failures: list[dict] = []
    needed = sorted({key.split(":")[0] for key in GATED_INTERVAL_IDS})
    try:
        tables_ok = bool(screening_csv_paths(repo / "data" / "raw" / "io_vnbd"))
    except (DatasetMissing, DatasetLfsMissing, FileNotFoundError, OSError) as error:
        tables_ok = False
        failures.append({"reason": f"raw dataset unavailable: {error}"})
    if tables_ok:
        for trip_id in needed:
            try:
                records = load_trip_records(repo, trip_id)
            except (OSError, ValueError, KeyError) as error:
                failures.append({"trip_id": trip_id, "reason": str(error)})
                continue
            audits.append(audit_records(records, trip_id=trip_id))
            windows = {item.interval_id: item for item in locked_blackouts(records, trip_id)}
            for interval_id in sorted(GATED_INTERVAL_IDS):
                if interval_id.split(":")[0] != trip_id:
                    continue
                window = windows.get(interval_id)
                if window is None:
                    failures.append({"interval_id": interval_id, "reason": "locked window missing"})
                    continue
                states = None
                if states_dir is not None:
                    path = states_dir / f"{interval_id.replace(':', '_')}.jsonl"
                    if path.is_file():
                        states = [
                            json.loads(line)
                            for line in path.read_text().splitlines()
                            if line.strip()
                        ]
                attributions.append(
                    attribute_interval(
                        interval_id,
                        records,
                        window.start_ns,
                        window.end_ns,
                        candidate_row=archived_rows.get(interval_id),
                        candidate_states=states,
                    )
                )
                seed = interval_seed(records, window.start_ns)
                if seed["latitude_deg"] is None:
                    substitutions.append(
                        stamp_diagnostic(
                            {
                                "interval_id": interval_id,
                                "unsupported": True,
                                "notes": ["unsupported_seed"],
                            }
                        )
                    )
                    continue
                seed_pos = (seed["latitude_deg"], seed["longitude_deg"])
                cand_by_t = {}
                if states:
                    cand_by_t = {int(row["timestamp_ns"]): row for row in states}
                epochs = build_epoch_motion(
                    records,
                    window.start_ns,
                    window.end_ns,
                    candidate_by_t=cand_by_t,
                    seed_speed_mps=seed["speed_mps"],
                    seed_heading_rad=seed["heading_rad"],
                )
                suite = interval_id.rsplit(":", 1)[-1]
                gate = SUITE_GATES.get(suite)
                truth = [row.truth for row in epochs]
                duration = (window.end_ns - window.start_ns) / NS_PER_S
                if gate is not None:
                    checked = assess_truth(truth, duration_s=duration, coverage=1.0, config=gate)
                    if not checked.accepted:
                        failures.append(
                            {
                                "interval_id": interval_id,
                                "reason": "truth gate: " + "; ".join(checked.reasons),
                            }
                        )
                        continue
                variant_rows = {}
                for name in SUBSTITUTION_VARIANTS:
                    variant_rows[name] = score_substitution(seed_pos, epochs, name)
                substitutions.append(
                    stamp_diagnostic(
                        {
                            "interval_id": interval_id,
                            "trip_id": trip_id,
                            "start_ns": window.start_ns,
                            "end_ns": window.end_ns,
                            "score_epochs": len(epochs),
                            "truth_path_length_m": path_length_m(truth) if truth else 0.0,
                            "variants": variant_rows,
                            "reference_substitution": True,
                        }
                    )
                )
    comparison = None
    if reproduced_metrics is not None and reproduced_metrics.is_file():
        comparison = compare_to_archived(json.loads(reproduced_metrics.read_text()))
        (out_dir / "archive_comparison.json").write_text(
            json.dumps(comparison, indent=2) + "\n"
        )
    substitution_summary = summarize_substitutions(substitutions)
    attribution_path = out_dir / "attribution_per_interval.json"
    substitution_path = out_dir / "reference_substitutions.json"
    audit_path = out_dir / "trip_audit.json"
    attribution_path.write_text(json.dumps(attributions, indent=2) + "\n")
    substitution_path.write_text(json.dumps(substitutions, indent=2) + "\n")
    audit_path.write_text(json.dumps(audits, indent=2) + "\n")
    write_substitution_csv(out_dir / "reference_substitutions.csv", substitutions)
    write_attribution_csv(out_dir / "attribution_per_interval.csv", attributions)
    report = stamp_diagnostic(
        {
            "seed": SEED,
            "locked_interval_ids": sorted(GATED_INTERVAL_IDS),
            "split_manifest": "split_manifest.json",
            "archived_locked": ARCHIVED_LOCKED,
            "archive_comparison": comparison,
            "audit_trips": len(audits),
            "attributed_intervals": len(attributions),
            "substituted_intervals": len(substitutions),
            "failures": failures,
            "candidate_source": (
                "replay_states" if states_dir is not None else "persist_seed_hold"
            ),
            "candidate_source_note": (
                "Substitutions use last-seed speed and heading when replay states "
                "are absent. That is not the selected latch_sparse_reseed coast."
            ),
            "substitution_summary": substitution_summary,
            "recommendation": recommendation(
                substitution_summary,
                attributions,
                against_archived=states_dir is not None,
            ),
            "statuses": {
                "code_merged": False,
                "experiment_completed": bool(substitutions) and not failures,
                "baseline_reproduced": comparison is not None,
                "median_target_passed": False,
                "all_interval_target_passed": False,
                "field_placements_tested": False,
            },
        }
    )
    # Confirm the gate would reject this report even if ratios were invented later.
    gate_probe = stamp_diagnostic(
        {
            "per_interval": [
                {
                    "interval_id": key,
                    "metrics": {"endpoint_error_m": 1.0, "truth_path_length_m": 100.0},
                    "diagnostic_only": True,
                }
                for key in sorted(GATED_INTERVAL_IDS)
            ]
        }
    )
    report["accuracy_gate_rejects_this_class"] = assess(gate_probe)["passed"] is False
    (out_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def summarize_substitutions(rows: Sequence[dict]) -> dict:
    summary: dict[str, dict] = {}
    for name in SUBSTITUTION_VARIANTS:
        ratios = []
        unsupported = 0
        for row in rows:
            variant = (row.get("variants") or {}).get(name)
            if not variant:
                continue
            if variant.get("unsupported"):
                unsupported += 1
            metrics = variant.get("metrics") or {}
            endpoint = metrics.get("endpoint_error_m")
            distance = metrics.get("truth_path_length_m")
            if (
                isinstance(endpoint, (int, float))
                and isinstance(distance, (int, float))
                and distance > 0
            ):
                ratios.append(endpoint / distance)
        if not ratios:
            summary[name] = {"count": 0, "unsupported": unsupported}
            continue
        ordered = sorted(ratios)
        p95_rank = max(1, int(0.95 * len(ordered) + 0.999999999))
        summary[name] = {
            "count": len(ratios),
            "unsupported": unsupported,
            "drift_p50": median(ratios),
            "drift_p95": ordered[min(p95_rank - 1, len(ordered) - 1)],
            "below_10": sum(1 for ratio in ratios if ratio < 0.10),
            "label": DIAGNOSTIC_LABEL,
        }
    return summary


def write_substitution_csv(path: Path, rows: Sequence[dict]) -> None:
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "interval_id",
                "variant",
                "endpoint_error_m",
                "truth_path_length_m",
                "drift_ratio",
                "unsupported",
                "label",
            ]
        )
        for row in rows:
            for name, variant in (row.get("variants") or {}).items():
                metrics = variant.get("metrics") or {}
                writer.writerow(
                    [
                        row.get("interval_id"),
                        name,
                        metrics.get("endpoint_error_m", ""),
                        metrics.get("truth_path_length_m", ""),
                        (
                            ""
                            if not metrics.get("truth_path_length_m")
                            else (
                                float(metrics["endpoint_error_m"])
                                / float(metrics["truth_path_length_m"])
                            )
                        ),
                        variant.get("unsupported", ""),
                        DIAGNOSTIC_LABEL,
                    ]
                )


def write_attribution_csv(path: Path, rows: Sequence[dict]) -> None:
    with path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "interval_id",
                "seed_error_m",
                "truth_path_length_m",
                "candidate_drift_ratio",
                "candidate_speed_mae_mps",
                "candidate_heading_mae_rad",
                "heading_turns_ge_30deg",
                "stop_epochs_speed_lt_0_4",
                "imu_gaps_over_0_40s",
                "unique_fix_median_spacing_s",
                "plausibly_closes_gap",
                "would_not_close_gap",
                "label",
            ]
        )
        for row in rows:
            writer.writerow(
                [
                    row.get("interval_id"),
                    row.get("seed_error_m"),
                    row.get("truth_path_length_m"),
                    row.get("candidate_drift_ratio"),
                    row.get("candidate_speed_mae_mps"),
                    row.get("candidate_heading_mae_rad"),
                    row.get("heading_turns_ge_30deg"),
                    row.get("stop_epochs_speed_lt_0_4"),
                    row.get("imu_gaps_over_0_40s"),
                    row.get("unique_fix_median_spacing_s"),
                    ";".join(row.get("plausibly_closes_gap") or []),
                    ";".join(row.get("would_not_close_gap") or []),
                    DIAGNOSTIC_LABEL,
                ]
            )


def recommendation(
    summary: dict,
    attributions: Sequence[dict],
    *,
    against_archived: bool,
) -> dict:
    def p50(name: str) -> float | None:
        row = summary.get(name) or {}
        return row.get("drift_p50")

    speed = p50("ref_speed")
    heading = p50("ref_heading")
    road = p50("ref_road")
    joint = p50("ref_joint")
    archived = ARCHIVED_LOCKED["drift_ratio_p50"]
    baseline = archived if against_archived else None
    for_b = []
    for_c = []
    if not against_archived:
        for_b.append(
            "These substitutions used persist seed hold, not the selected coast. "
            "Re-run with replay states before treating a map delta as decisive."
        )
        for_c.append(
            "These substitutions used persist seed hold, not latch_sparse_reseed. "
            "Re-run with replay states before choosing a motion-model target."
        )
    if road is not None and baseline is not None and road < 0.85 * baseline:
        for_b.append(
            "Reference polyline following reduced median drift versus the "
            "selected coast. Investigate causal map hypotheses on development."
        )
    if road is not None and baseline is not None and road >= 0.85 * baseline:
        for_b.append(
            "Reference-informed road choice did not move the median enough. "
            "Audit distance, seed, and reference quality before map rollout."
        )
    if speed is not None and heading is not None and heading < speed:
        for_c.append(
            "Reference heading substitution beat reference speed on this "
            "diagnostic. Do not default to a speed-only network."
        )
    elif speed is not None and heading is not None and speed < heading:
        for_c.append(
            "Reference speed substitution beat heading on this diagnostic. "
            "A causal speed/distance model on train groups is the first C run."
        )
    if speed is not None and baseline is not None and speed >= 0.85 * baseline:
        for_c.append(
            "Fixing speed alone changed little versus the selected coast. "
            "Check heading, seed, and scoring first."
        )
    if joint is not None and joint > 0.20:
        for_b.append(
            "Joint reference substitutions still leave large residual. Audit "
            "coordinate, time, seed, and scoring before more model complexity."
        )
    if joint is not None and joint <= 0.10:
        for_c.append(
            "Joint substitutions can reach the 10% budget on this diagnostic. "
            "That is not achieved accuracy. It only shows the error sources."
        )
    turn_intervals = sum(
        1 for row in attributions if (row.get("heading_turns_ge_30deg") or 0) >= 1
    )
    if turn_intervals >= 10:
        for_b.append(
            f"{turn_intervals} locked intervals have a >=30 deg reference turn. "
            "Map work should keep competing hypotheses through those turns."
        )
    if not for_b:
        for_b.append(
            "No substitution run yet, or road diagnostic unsupported. Reproduce "
            "baseline and substitutions before changing osm_coast defaults."
        )
    if not for_c:
        for_c.append(
            "Train only on split_manifest train groups. Never on locked labels. "
            "Validate on development. Hold fresh_holdout closed until D."
        )
    return stamp_diagnostic(
        {
            "for_map_task_b": for_b,
            "for_learned_motion_task_c": for_c,
            "statuses_remain_failed": [
                "median_target_passed",
                "all_interval_target_passed",
                "field_placements_tested",
            ],
        }
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("results/cursor_diagnostics"))
    parser.add_argument("--states-dir", type=Path, default=None)
    parser.add_argument("--reproduced-metrics", type=Path, default=None)
    parser.add_argument("--manifest-only", action="store_true")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    out = args.out if args.out.is_absolute() else repo / args.out
    out.mkdir(parents=True, exist_ok=True)
    if args.manifest_only:
        manifest = freeze_split_manifest()
        (out / "split_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps({k: manifest[k] for k in manifest if k != "assignments"}, indent=2))
        return 0
    report = run_diagnostics(
        repo,
        out,
        states_dir=args.states_dir,
        reproduced_metrics=args.reproduced_metrics,
    )
    print(json.dumps({k: report[k] for k in report if k != "failures"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
