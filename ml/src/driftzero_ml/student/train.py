"""Train the compact motion student. Synthetic if IO-VNBD LFS is missing."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from statistics import mean

from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.datasets.io_vnbd import (
    load_smartphone_csv,
    screening_smartphone_tables,
    to_imu_records,
)
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    MAX_SAMPLES,
    MIN_SAMPLES,
    WINDOW_NS,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.io_vnbd import IOVNBDMissing, assign_grouped_trip_splits
from driftzero_ml.learned_imu import train_torch_student
from driftzero_ml.student.csv_load import load_imu_csv
from driftzero_ml.student.gru import torch_is_installed
from driftzero_ml.student.heads import zupt_accel_infer
from driftzero_ml.student.linear import (
    LinearMotionStudent,
    fit_linear_motion_student,
    save_linear_student,
    zero_speed_baseline,
)
from driftzero_ml.student.synthetic import synthetic_trip

KINDS = ("idle", "cruise", "stop", "bump")


Window = tuple[tuple[float, ...], float, float, list[list[float]], float]


def labeled_windows(records: list[dict], *, stride: int = 1) -> list[Window]:
    if stride < 1:
        raise ValueError("stride must be at least 1")
    samples = records_to_imu_samples(records)
    labeled: list[Window] = []
    speed_by_t = {
        int(row["timestamp_ns"]): float(row["speed_mps"])
        for row in records
        if row.get("speed_mps") is not None
    }
    stopped_by_t = {
        int(row["timestamp_ns"]): float(row["stopped"])
        for row in records
        if "stopped" in row
    }
    for index in range(len(samples)):
        if index % stride != 0:
            continue
        end_ns = samples[index].timestamp_ns
        recent = samples[max(0, index + 1 - MAX_SAMPLES) : index + 1]
        window = trim_causal_window(recent, end_ns)
        if len(window) < MIN_SAMPLES:
            continue
        if end_ns not in speed_by_t:
            continue
        features = extract_causal_imu_features(window)
        seq = [
            [
                row.ax,
                row.ay,
                row.az,
                # Optional torch path only. Missing gyro stays 0 here. Feature
                # pooling skips None axes and does not treat that as idle.
                0.0 if row.gx is None else row.gx,
                0.0 if row.gy is None else row.gy,
                0.0 if row.gz is None else row.gz,
            ]
            for row in window
        ]
        labeled.append(
            (
                features.vector,
                speed_by_t[end_ns],
                stopped_by_t.get(end_ns, 0.0),
                seq,
                zupt_accel_infer(window).forward_speed_mps,
            )
        )
    return labeled


def mae(predicted: list[float], target: list[float]) -> float:
    if not predicted:
        raise ValueError("predicted must not be empty")
    return mean(abs(a - b) for a, b in zip(predicted, target))


def train_from_trips(
    trips: dict[str, list[dict]],
    *,
    seed: str = "26168",
    stride: int = 1,
) -> dict:
    assignments = {row.trip_id: row.split for row in assign_grouped_trip_splits(sorted(trips), seed=seed)}
    train_x: list[tuple[float, ...]] = []
    train_speed: list[float] = []
    train_stop: list[float] = []
    eval_rows: dict[str, list[tuple[tuple[float, ...], float, float]]] = defaultdict(list)
    for trip_id, records in trips.items():
        split = assignments[trip_id]
        for vector, speed, stopped, _seq, heuristic_speed in labeled_windows(records, stride=stride):
            if split == "train":
                train_x.append(vector)
                train_speed.append(speed)
                train_stop.append(stopped)
            else:
                eval_rows[split].append((vector, speed, heuristic_speed))
    if not train_x:
        raise ValueError("no labeled IMU windows in the training split")
    student = fit_linear_motion_student(train_x, train_speed, train_stop)
    report: dict = {
        "seed": seed,
        "train_windows": len(train_x),
        "splits": {},
        "speed_unit": "m/s",
        "split": "session_grouped",
        "supersedes_invalid_kmh_labels": True,
    }
    for split, rows in eval_rows.items():
        targets = [item[1] for item in rows]
        freeze = zero_speed_baseline(len(targets))
        heuristic = [item[2] for item in rows]
        linear = [student.infer(item[0])[0] for item in rows]
        report["splits"][split] = {
            "count": len(targets),
            "freeze_speed_mae": mae(freeze, targets),
            "heuristic_speed_mae": mae(heuristic, targets),
            "linear_speed_mae": mae(linear, targets),
        }
    report["torch_available"] = torch_is_installed()
    return {"student": student, "report": report}


def default_synthetic_trips(seed: int = 26168) -> dict[str, list[dict]]:
    trips: dict[str, list[dict]] = {}
    index = 0
    for kind in KINDS:
        for copy in range(3):
            trip_id = f"{kind}-{copy}"
            trips[trip_id] = synthetic_trip(
                kind,
                duration_s=3.0,
                hz=50,
                seed=seed + index,
                trip_id=trip_id,
            )
            index += 1
    return trips


def maybe_iovnbd_trips() -> tuple[dict[str, list[dict]] | None, str]:
    try:
        tables = screening_smartphone_tables(Path("data/raw/io_vnbd"))
    except (IOVNBDMissing, DatasetLfsMissing, DatasetMissing):
        return None, "synthetic"
    trips: dict[str, list[dict]] = {}
    for path in tables:
        try:
            trips[path.stem] = to_imu_records(load_smartphone_csv(path))
        except (ValueError, DatasetMissing):
            continue
    if len(trips) < 4:
        return None, f"synthetic; IO-VNBD subset too small ({len(trips)} tables)"
    return trips, f"io_vnbd:{len(trips)} smartphone tables"


def train_gru_if_available(trips: dict[str, list[dict]], out_dir: Path) -> str:
    """Train the RoNIN/TLIO-aligned student when pose labels exist.

    Speed-only synthetic trips from this module have no Δp labels, so the
    paper trainer is a no-op unless `learned_imu.synthetic_vehicle_odometry`
    (or a real dataset with pose) is used.
    """

    if not torch_is_installed():
        return "skipped: torch not installed"
    return train_torch_student(trips, out_dir)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train DriftZero causal motion student")
    parser.add_argument("--seed", default="26168")
    parser.add_argument("--imu-csv", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=Path("models/motion_student_v1"))
    args = parser.parse_args(argv)
    trips: dict[str, list[dict]] = {}
    source = "synthetic"
    if args.imu_csv is not None:
        loaded = load_imu_csv(args.imu_csv)
        grouped: dict[str, list[dict]] = defaultdict(list)
        for row in loaded:
            grouped[str(row.get("trip_id", args.imu_csv.stem))].append(row)
        trips = dict(grouped)
        source = f"csv:{args.imu_csv}"
    else:
        loaded, source = maybe_iovnbd_trips()
        if loaded is None:
            trips = default_synthetic_trips(seed=int(args.seed) if str(args.seed).isdigit() else 26168)
            source = "synthetic"
        else:
            trips = loaded
    stride = 10 if source.startswith("io_vnbd:") else 1
    result = train_from_trips(trips, seed=str(args.seed), stride=stride)
    student: LinearMotionStudent = result["student"]
    report = result["report"]
    report["source"] = source
    report["window_stride"] = stride
    report["feature_names"] = list(FEATURE_NAMES)
    report["window_ns"] = WINDOW_NS
    args.out.mkdir(parents=True, exist_ok=True)
    save_linear_student(student, args.out / "linear.json")
    report["gru"] = train_gru_if_available(trips, args.out)
    report["learned_imu"] = (
        "RoNIN/TLIO heads live in driftzero_ml.learned_imu. "
        "This script still fits the speed-only linear student. "
        "Run python -m driftzero_ml.learned_imu for Δp + log σ."
    )
    (args.out / "train_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
