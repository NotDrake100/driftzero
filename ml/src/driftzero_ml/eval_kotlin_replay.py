"""Replay DeadReckoningFilter on locked IO-VNBD screening intervals and score."""

from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import time
from collections import Counter
from datetime import datetime, timezone
from math import pi
from pathlib import Path
from statistics import mean, median
from typing import Sequence

from driftzero_ml.datasets.io_vnbd import load_smartphone_csv
from driftzero_ml.eval_iovnbd_blackout import (
    _time_ordered,
    alignment_from_records,
    attach_alignment,
    eval_record,
    screening_csv_paths,
)
from driftzero_ml.eval_navstate import load_navigation_state_jsonl, score_states_against_truth
from driftzero_ml.export_sensorframe import (
    export_sensor_frames,
    hold_last_imu_upsample,
    imu_gap_count,
    write_sensorframe_jsonl,
    write_truth_jsonl,
)
from driftzero_ml.gnss_truth import TruthGateConfig, course_rad, score_epochs
from driftzero_ml.metrics import BlackoutMetrics, circular_mae_rad
from driftzero_ml.screening import locked_blackouts


GATED_CSV = Path("results/io_vnbd_screening_v1/metrics_per_interval.csv")
KOTLIN_DIR = Path("results/io_vnbd_screening_v1/kotlin_replay")
SENSITIVITY_INTERVAL = "S-Vta2:d50"
REPLAY_HEADER_KEYS = ("declared_rate_hz", "clock_domain", "frame", "source_id")
SUITE_GATES = {
    "mid": TruthGateConfig(),
    "d50": TruthGateConfig(min_path_length_m=40.0, min_unique_fixes=3),
    "d1000": TruthGateConfig(min_path_length_m=800.0, min_unique_fixes=8),
}


def _nearest_rank(values: Sequence[float], probability: float) -> float:
    ordered = sorted(values)
    if probability == 0:
        return ordered[0]
    rank = max(1, int(probability * len(ordered) + 0.999999999))
    return ordered[min(rank - 1, len(ordered) - 1)]


def gated_interval_ids(path: Path) -> tuple[str, ...]:
    ids: list[str] = []
    seen: set[str] = set()
    with path.open() as handle:
        for row in csv.DictReader(handle):
            interval_id = row["interval_id"]
            if interval_id not in seen:
                seen.add(interval_id)
                ids.append(interval_id)
    return tuple(ids)


def java_home() -> str:
    probe = subprocess.run(
        ["/usr/libexec/java_home", "-v", "17"],
        check=True,
        capture_output=True,
        text=True,
    )
    return probe.stdout.strip()


def ensure_replay_binary(
    repo: Path,
    env: dict[str, str],
    *,
    force_rebuild: bool = False,
    retries: int = 0,
    retry_wait_s: float = 60.0,
) -> Path:
    script = repo / "packages" / "navigation-core" / "build" / "install" / "navigation-core" / "bin" / "navigation-core"
    if script.is_file() and not force_rebuild:
        return script
    last_error: Exception | None = None
    attempts = max(1, 1 + retries)
    for attempt in range(attempts):
        try:
            subprocess.run(
                [str(repo / "gradlew"), ":navigation-core:installDist", "--offline"],
                cwd=repo,
                check=True,
                env=env,
            )
            if script.is_file():
                return script
            subprocess.run(
                [str(repo / "gradlew"), ":navigation-core:installDist"],
                cwd=repo,
                check=True,
                env=env,
            )
            if script.is_file():
                return script
            last_error = FileNotFoundError(f"replay binary missing at {script}")
        except subprocess.CalledProcessError as error:
            last_error = error
        if attempt + 1 < attempts:
            time.sleep(retry_wait_s)
    if last_error is not None:
        raise last_error
    raise FileNotFoundError(f"replay binary missing at {script}")


def run_replay(
    binary: Path,
    frames: Path,
    states: Path,
    start_ns: int,
    end_ns: int,
    log_path: Path,
    env: dict[str, str],
    extra_args: Sequence[str] = (),
) -> dict:
    states.parent.mkdir(parents=True, exist_ok=True)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    mask_start, mask_end = replay_mask_ns(start_ns, end_ns)
    args = replay_cli_args(
        binary,
        frames,
        states,
        mask_start,
        mask_end,
        extra_args=extra_args,
    )
    result = subprocess.run(args, cwd=binary.parent, capture_output=True, text=True, env=env)
    log_path.write_text(result.stdout + ("\n" + result.stderr if result.stderr else ""))
    if result.returncode != 0:
        raise RuntimeError(f"replay failed ({result.returncode}): {result.stderr or result.stdout}")
    return _parse_replay_log(result.stdout)


def replay_cli_args(
    binary: Path,
    frames: Path,
    states: Path,
    mask_start: int,
    mask_end: int,
    extra_args: Sequence[str] = (),
) -> list[str]:
    args = [
        str(binary),
        "--input",
        str(frames),
        "--output",
        str(states),
        "--mask-start-ns",
        str(mask_start),
        "--mask-end-ns",
        str(mask_end),
    ]
    args.extend(str(item) for item in extra_args)
    return args


def slim_replay_header(header: dict) -> dict:
    """Keep the four-key file header the Kotlin loader reads. Pick metadata is sidecar."""

    return {key: header[key] for key in REPLAY_HEADER_KEYS}


def exporter_git_meta(repo: Path) -> dict:
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )
    describe = subprocess.run(
        ["git", "describe", "--always", "--dirty"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )
    porcelain = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )
    return {
        "git_commit": head.stdout.strip(),
        "git_describe_dirty": describe.stdout.strip(),
        "git_dirty": bool(porcelain.stdout.strip()),
        "exported_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def export_premask_frames(
    repo: Path,
    frames_dir: Path,
    *,
    manifest_path: Path | None = None,
) -> dict:
    """Write per-interval SensorFrame JSONL with the pre-mask gyro pick.

    Does not write into kotlin_replay/frames. Schema of each JSONL header is the
    four keys Replay reads. Pick, r, and fallback live in the sidecar manifest.
    """

    repo = repo.resolve()
    frames_dir = frames_dir if frames_dir.is_absolute() else repo / frames_dir
    frames_dir.mkdir(parents=True, exist_ok=True)
    gated = gated_interval_ids(repo / GATED_CSV)
    root = repo / "data" / "raw" / "io_vnbd"
    tables = {path.stem: path for path in screening_csv_paths(root)}
    needed_trips = sorted({interval_id.split(":", 1)[0] for interval_id in gated})
    git_meta = exporter_git_meta(repo)
    intervals: list[dict] = []
    for trip_id in needed_trips:
        rows = load_smartphone_csv(tables[trip_id])
        records = _time_ordered([eval_record(row) for row in rows])
        aligned = alignment_from_records(records, trip_id)
        if aligned is not None:
            records = attach_alignment(records, aligned)
        windows = {item.interval_id: item for item in locked_blackouts(records, trip_id)}
        for interval_id in gated:
            if interval_id.split(":", 1)[0] != trip_id:
                continue
            window = windows.get(interval_id)
            if window is None:
                raise RuntimeError(f"locked window missing for {interval_id}")
            header, frames = export_sensor_frames(rows, mask_start_ns=window.start_ns)
            safe_id = interval_id.replace(":", "_")
            path = frames_dir / f"{safe_id}.jsonl"
            write_sensorframe_jsonl(path, slim_replay_header(header), frames)
            heading = header.get("heading_gyro") or {}
            rewind = header.get("timestamp_rewind") or {}
            intervals.append(
                {
                    "interval_id": interval_id,
                    "trip_id": trip_id,
                    "file": path.name,
                    "start_ns": window.start_ns,
                    "end_ns": window.end_ns,
                    "axis": heading.get("axis"),
                    "sign": heading.get("sign"),
                    "r": heading.get("correlation"),
                    "pair_count": heading.get("pair_count"),
                    "reason": heading.get("reason"),
                    "source": heading.get("source"),
                    "fallback": heading.get("source") == "gravity_vertical_fallback",
                    "best_axis": heading.get("best_axis"),
                    "mask_start_ns": heading.get("mask_start_ns"),
                    "frame_count": len(frames),
                    "declared_rate_hz": header.get("declared_rate_hz"),
                    "timestamp_rewind_dropped": rewind.get("dropped_rows", 0),
                }
            )
            print(json.dumps({"exported": interval_id, "file": path.name, "heading_gyro": heading}), flush=True)
    manifest = {
        "schema": "sensorframe_jsonl_v1",
        "header_keys": list(REPLAY_HEADER_KEYS),
        "pick": "pre_mask",
        "n_intervals": len(intervals),
        "frames_dir": str(frames_dir.relative_to(repo)) if frames_dir.is_relative_to(repo) else str(frames_dir),
        **git_meta,
        "intervals": intervals,
    }
    dest = manifest_path or (frames_dir / "manifest.json")
    dest.write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def _parse_replay_log(text: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for line in text.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            parsed[key] = value
    return parsed


ALIGN_SLACK_NS = 150_000_000


def replay_mask_ns(start_ns: int, end_ns: int) -> tuple[int, int]:
    """Inclusive Replay mask that keeps the last visible fix at interval start.

    Screening windows are half-open [start, end). The start-ns GNSS is the
    seed, not hidden truth. Replay's mask is inclusive, so start is advanced
    by 1 ns and end is stepped back by 1 ns.
    """

    if end_ns <= start_ns:
        return start_ns, end_ns
    return start_ns + 1, end_ns - 1


def align_states_to_epochs(states: Sequence[dict], epochs: Sequence[int]) -> list[dict]:
    """Hold the last 10 Hz pose onto a unique-fix epoch if Replay skipped a 99 ms slot.

    Exact timestamps win. A later state is never used. Gaps larger than 150 ms fail.
    """

    ordered = sorted(states, key=lambda row: int(row["timestamp_ns"]))
    aligned: list[dict] = []
    index = 0
    last = None
    for stamp in epochs:
        while index < len(ordered) and int(ordered[index]["timestamp_ns"]) <= stamp:
            last = ordered[index]
            index += 1
        if last is None:
            raise ValueError(f"NavigationState JSONL missing timestamp {stamp}")
        last_t = int(last["timestamp_ns"])
        if last_t == stamp:
            aligned.append(last)
            continue
        if stamp - last_t > ALIGN_SLACK_NS:
            raise ValueError(
                f"NavigationState JSONL missing timestamp {stamp} "
                f"(last pose {last_t}, slack {ALIGN_SLACK_NS} ns)"
            )
        copied = dict(last)
        copied["timestamp_ns"] = stamp
        aligned.append(copied)
    return aligned


def extras_from_states(
    states: Sequence[dict],
    truth: Sequence[dict],
    start_ns: int,
    end_ns: int,
) -> dict[str, float]:
    by_t = {int(row["timestamp_ns"]): row for row in states}
    original = {int(row["timestamp_ns"]): row for row in truth}
    epochs = score_epochs(truth, start_ns, end_ns)
    speeds: list[float] = []
    headings: list[float] = []
    truth_speeds: list[float] = []
    truth_headings: list[float] = []
    last = None
    for stamp in epochs:
        src = original[stamp]
        est = by_t[stamp]
        speeds.append(float(est["motion"]["speed_mps"]))
        headings.append(float(est["motion"]["heading_rad"]))
        if "gnss_speed_mps" in src and src["gnss_speed_mps"] is not None:
            truth_speeds.append(float(src["gnss_speed_mps"]))
        else:
            truth_speeds.append(0.0)
        if last is not None:
            heading = course_rad(last, src)
            truth_headings.append(heading % (2.0 * pi) if heading is not None else 0.0)
        else:
            truth_headings.append(0.0)
        last = src
    return {
        "speed_mae_mps": mean(abs(a - b) for a, b in zip(speeds, truth_speeds)),
        "heading_mae_rad": circular_mae_rad(headings, truth_headings),
    }


def summarize(rows: Sequence[dict]) -> dict[str, float | int]:
    errors = [float(row["metrics"].endpoint_error_m) for row in rows]
    ratios = [float(row["metrics"].drift_ratio) for row in rows if row["metrics"].drift_ratio is not None]
    out: dict[str, float | int] = {
        "interval_count": len(rows),
        "endpoint_p50_m": median(errors),
        "endpoint_p90_m": _nearest_rank(errors, 0.90),
        "endpoint_p95_m": _nearest_rank(errors, 0.95),
        "endpoint_worst_m": max(errors),
    }
    if ratios:
        out["drift_ratio_p50"] = median(ratios)
        out["drift_ratio_p90"] = _nearest_rank(ratios, 0.90)
        out["drift_ratio_p95"] = _nearest_rank(ratios, 0.95)
        out["drift_ratio_worst"] = max(ratios)
    if rows and "extras" in rows[0]:
        out["speed_mae_p50"] = median([float(row["extras"]["speed_mae_mps"]) for row in rows])
        out["heading_mae_rad_p50"] = median([float(row["extras"]["heading_mae_rad"]) for row in rows])
    return out


def mode_histogram(states: Sequence[dict], start_ns: int, end_ns: int) -> Counter:
    counts: Counter = Counter()
    for row in states:
        stamp = int(row["timestamp_ns"])
        if start_ns <= stamp < end_ns:
            counts[str(row["mode"])] += 1
    return counts


def health_notes(states: Sequence[dict], start_ns: int, end_ns: int) -> dict:
    window = [row for row in states if start_ns <= int(row["timestamp_ns"]) < end_ns]
    filter_false = sum(1 for row in window if not row["health"]["filter_ok"])
    low = sum(1 for row in window if row["mode"] == "LOW_CONFIDENCE")
    imu_gap = sum(1 for row in window if "imu_gap" in row["health"]["flags"])
    return {
        "states_in_window": len(window),
        "filter_ok_false": filter_false,
        "low_confidence": low,
        "imu_gap_flag": imu_gap,
    }


def load_truth_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text().splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def run(
    repo: Path,
    out_dir: Path,
    *,
    system: str = "kotlin_eskf",
    reexport: bool = False,
    rerun: bool = False,
    frames_dir: Path | None = None,
    states_dir: Path | None = None,
    logs_dir: Path | None = None,
    extra_replay_args: Sequence[str] = (),
    skip_sensitivity: bool = False,
    skip_export: bool = False,
    coast_mode: str | None = None,
    force_rebuild: bool = False,
    rebuild_retries: int = 0,
) -> dict:
    repo = repo.resolve()
    out_dir = out_dir if out_dir.is_absolute() else repo / out_dir
    frames_dir = (frames_dir if frames_dir.is_absolute() else repo / frames_dir) if frames_dir is not None else out_dir / "frames"
    states_dir = (states_dir if states_dir.is_absolute() else repo / states_dir) if states_dir is not None else out_dir / "states"
    logs_dir = (logs_dir if logs_dir.is_absolute() else repo / logs_dir) if logs_dir is not None else out_dir / "logs"
    truth_dir = out_dir / "truth"
    for folder in (frames_dir, states_dir, logs_dir, truth_dir):
        folder.mkdir(parents=True, exist_ok=True)
    replay_extra = list(extra_replay_args)
    if coast_mode:
        flag = f"--coast-mode={coast_mode}"
        if flag not in replay_extra and "--coast-mode" not in replay_extra:
            replay_extra.append(flag)

    gated = gated_interval_ids(repo / GATED_CSV)
    root = repo / "data" / "raw" / "io_vnbd"
    tables = {path.stem: path for path in screening_csv_paths(root)}
    needed_trips = sorted({interval_id.split(":", 1)[0] for interval_id in gated})

    env = os.environ.copy()
    env["JAVA_HOME"] = java_home()
    env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    binary = ensure_replay_binary(
        repo,
        env,
        force_rebuild=force_rebuild,
        retries=rebuild_retries,
    )

    trip_meta: dict[str, dict] = {}
    for trip_id in needed_trips:
        path = tables[trip_id]
        rows = load_smartphone_csv(path)
        trip_frame_path = frames_dir / f"{trip_id}.jsonl"
        truth_path = truth_dir / f"{trip_id}.jsonl"
        if not truth_path.is_file() or reexport:
            write_truth_jsonl(truth_path, rows)
        records = _time_ordered([eval_record(row) for row in rows])
        aligned = alignment_from_records(records, trip_id)
        if aligned is not None:
            records = attach_alignment(records, aligned)
        windows = {item.interval_id: item for item in locked_blackouts(records, trip_id)}
        interval_frames: dict[str, Path] = {}
        header = None
        frame_count = 0
        gap_count = 0
        for interval_id, window in windows.items():
            if interval_id not in gated:
                continue
            safe_id = interval_id.replace(":", "_")
            interval_path = frames_dir / f"{safe_id}.jsonl"
            if interval_path.is_file() and not reexport:
                chosen = interval_path
            elif skip_export:
                raise FileNotFoundError(f"premask frames missing: {interval_path}")
            elif trip_frame_path.is_file() and not reexport:
                chosen = trip_frame_path
            else:
                header, frames = export_sensor_frames(rows, mask_start_ns=window.start_ns)
                write_sensorframe_jsonl(interval_path, header, frames)
                frame_count = len(frames)
                gap_count = imu_gap_count(frames)
                print(
                    json.dumps(
                        {
                            "interval_id": interval_id,
                            "heading_gyro": header.get("heading_gyro"),
                            "notes": header.get("notes"),
                            "timestamp_rewind": header.get("timestamp_rewind"),
                            "official_row": header.get("official_row"),
                        }
                    ),
                    flush=True,
                )
                chosen = interval_path
            interval_frames[interval_id] = chosen
            if header is None:
                header = json.loads(chosen.read_text().splitlines()[0])
                frame_count = max(0, sum(1 for line in chosen.read_text().splitlines()[1:] if line.strip()))
        if header is None and trip_frame_path.is_file():
            header = json.loads(trip_frame_path.read_text().splitlines()[0])
        if header is None:
            header = {"declared_rate_hz": 10.0}
        trip_meta[trip_id] = {
            "frame_path": trip_frame_path if trip_frame_path.is_file() else next(iter(interval_frames.values()), trip_frame_path),
            "interval_frames": interval_frames,
            "truth_path": truth_path,
            "windows": windows,
            "declared_rate_hz": header["declared_rate_hz"],
            "frame_count": frame_count,
            "imu_gaps": gap_count,
        }

    progress = out_dir / ("progress.jsonl" if system == "kotlin_eskf" else f"progress_{system}.jsonl")
    if progress.is_file():
        progress.unlink()
    scored: list[dict] = []
    failures: list[dict] = []
    modes_all: Counter = Counter()
    health_all = {"filter_ok_false": 0, "low_confidence": 0, "imu_gap_flag": 0, "states_in_window": 0}

    for interval_id in gated:
        trip_id = interval_id.split(":", 1)[0]
        meta = trip_meta[trip_id]
        window = meta["windows"].get(interval_id)
        if window is None:
            failures.append({"interval_id": interval_id, "reason": "locked window missing"})
            continue
        frame_path = meta["interval_frames"].get(interval_id, meta["frame_path"])
        safe_id = interval_id.replace(":", "_")
        state_path = states_dir / f"{safe_id}.jsonl"
        log_path = logs_dir / f"{safe_id}.log"
        try:
            if (
                state_path.is_file()
                and state_path.stat().st_size > 0
                and log_path.is_file()
                and not rerun
            ):
                replay_log = _parse_replay_log(log_path.read_text())
            else:
                replay_log = run_replay(
                    binary,
                    frame_path,
                    state_path,
                    window.start_ns,
                    window.end_ns,
                    log_path,
                    env,
                    extra_args=replay_extra,
                )
            states = load_navigation_state_jsonl(state_path)
            truth = load_truth_jsonl(meta["truth_path"])
            suite = interval_id.rsplit(":", 1)[-1]
            epochs = score_epochs(truth, window.start_ns, window.end_ns)
            aligned = align_states_to_epochs(states, epochs)
            metrics = score_states_against_truth(
                aligned,
                truth,
                window.start_ns,
                window.end_ns,
                gate=SUITE_GATES.get(suite),
            )
            extras = extras_from_states(aligned, truth, window.start_ns, window.end_ns)
        except Exception as error:
            failures.append({"interval_id": interval_id, "reason": str(error)})
            continue
        modes = mode_histogram(states, window.start_ns, window.end_ns)
        notes = health_notes(states, window.start_ns, window.end_ns)
        modes_all.update(modes)
        for key in ("filter_ok_false", "low_confidence", "imu_gap_flag", "states_in_window"):
            health_all[key] += notes[key]
        scored.append(
            {
                "interval_id": interval_id,
                "trip_id": trip_id,
                "start_ns": window.start_ns,
                "end_ns": window.end_ns,
                "metrics": metrics,
                "extras": extras,
                "modes": dict(modes),
                "health": notes,
                "replay": replay_log,
            }
        )
        with progress.open("a") as handle:
            handle.write(
                json.dumps(
                    {
                        "interval_id": interval_id,
                        "endpoint_error_m": metrics.endpoint_error_m,
                        "drift_ratio": metrics.drift_ratio,
                    }
                )
                + "\n"
            )

    sensitivity = None
    if not skip_sensitivity and SENSITIVITY_INTERVAL in gated:
        trip_id = SENSITIVITY_INTERVAL.split(":", 1)[0]
        meta = trip_meta[trip_id]
        window = meta["windows"][SENSITIVITY_INTERVAL]
        raw_path = meta["interval_frames"].get(SENSITIVITY_INTERVAL, meta["frame_path"])
        raw_header = json.loads(raw_path.read_text().splitlines()[0])
        cropped = []
        for line in raw_path.read_text().splitlines()[1:]:
            if not line.strip():
                continue
            obj = json.loads(line)
            if int(obj["timestamp_ns"]) <= window.end_ns:
                cropped.append(obj)
        held_header, held_frames = hold_last_imu_upsample(raw_header, cropped, target_hz=100.0)
        held_path = frames_dir / f"{trip_id}_hold100.jsonl"
        write_sensorframe_jsonl(held_path, held_header, held_frames)
        safe_id = SENSITIVITY_INTERVAL.replace(":", "_") + "_hold100"
        state_path = states_dir / f"{safe_id}.jsonl"
        log_path = logs_dir / f"{safe_id}.log"
        try:
            replay_log = run_replay(
                binary,
                held_path,
                state_path,
                window.start_ns,
                window.end_ns,
                log_path,
                env,
                extra_args=replay_extra,
            )
            states = load_navigation_state_jsonl(state_path)
            truth = load_truth_jsonl(meta["truth_path"])
            epochs = score_epochs(truth, window.start_ns, window.end_ns)
            aligned = align_states_to_epochs(states, epochs)
            metrics = score_states_against_truth(
                aligned,
                truth,
                window.start_ns,
                window.end_ns,
                gate=SUITE_GATES["d50"],
            )
            sensitivity = {
                "interval_id": SENSITIVITY_INTERVAL,
                "hold_last_hz": 100.0,
                "official_row": False,
                "sensitivity_kind": "hold_last_imu",
                "metrics": metrics.to_dict(),
                "replay": replay_log,
            }
        except Exception as error:
            sensitivity = {"interval_id": SENSITIVITY_INTERVAL, "error": str(error)}
        if held_path.is_file():
            held_path.unlink()

    summary = summarize(scored) if scored else {}
    payload = {
        "system": system,
        "trips": len(needed_trips),
        "intervals_requested": len(gated),
        "intervals_scored": len(scored),
        "coast_mode": coast_mode,
        "student": "off",
        "persist_speed_pseudo": False,
        "source_frames": str(frames_dir.relative_to(repo)) if frames_dir.is_relative_to(repo) else str(frames_dir),
        "extra_replay_args": replay_extra,
        "failures": failures,
        "summary": summary,
        "modes": dict(modes_all),
        "health": health_all,
        "trip_meta": {
            trip_id: {
                "declared_rate_hz": meta["declared_rate_hz"],
                "frame_count": meta["frame_count"],
                "imu_gaps": meta["imu_gaps"],
            }
            for trip_id, meta in trip_meta.items()
        },
        "sensitivity_hold100": sensitivity,
        "per_interval": [
            {
                "interval_id": row["interval_id"],
                "trip_id": row["trip_id"],
                "start_ns": row["start_ns"],
                "end_ns": row["end_ns"],
                "metrics": row["metrics"].to_dict(),
                "extras": row["extras"],
                "modes": row["modes"],
                "health": row["health"],
                "replay": row["replay"],
            }
            for row in scored
        ],
    }
    metrics_name = "metrics.json" if system == "kotlin_eskf" else f"metrics_{system}.json"
    csv_name = (
        "metrics_per_interval.csv"
        if system == "kotlin_eskf"
        else f"metrics_per_interval_{system}.csv"
    )
    (out_dir / metrics_name).write_text(json.dumps(payload, indent=2) + "\n")
    with (out_dir / csv_name).open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "interval_id",
                "trip_id",
                "system",
                "endpoint_error_m",
                "truth_path_length_m",
                "drift_ratio",
                "speed_mae_mps",
                "heading_mae_rad",
                "low_confidence",
                "filter_ok_false",
            ]
        )
        for row in scored:
            metrics: BlackoutMetrics = row["metrics"]
            writer.writerow(
                [
                    row["interval_id"],
                    row["trip_id"],
                    system,
                    f"{metrics.endpoint_error_m:.6f}",
                    f"{metrics.truth_path_length_m:.6f}",
                    "" if metrics.drift_ratio is None else f"{metrics.drift_ratio:.6f}",
                    f"{row['extras']['speed_mae_mps']:.6f}",
                    f"{row['extras']['heading_mae_rad']:.6f}",
                    row["health"]["low_confidence"],
                    row["health"]["filter_ok_false"],
                ]
            )
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF replay on locked screening intervals")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=KOTLIN_DIR)
    parser.add_argument("--system", default="kotlin_eskf")
    parser.add_argument("--reexport", action="store_true")
    parser.add_argument("--rerun", action="store_true")
    parser.add_argument("--frames-dir", type=Path, default=None)
    parser.add_argument("--states-dir", type=Path, default=None)
    parser.add_argument("--logs-dir", type=Path, default=None)
    parser.add_argument("--coast-mode", default=None)
    parser.add_argument("--skip-sensitivity", action="store_true")
    parser.add_argument("--skip-export", action="store_true")
    parser.add_argument("--force-rebuild", action="store_true")
    parser.add_argument("--rebuild-retries", type=int, default=0)
    parser.add_argument("--export-premask-only", action="store_true")
    parser.add_argument(
        "--premask-frames-dir",
        type=Path,
        default=KOTLIN_DIR / "frames_premask",
    )
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    if args.export_premask_only:
        manifest = export_premask_frames(repo, args.premask_frames_dir)
        print(json.dumps({k: manifest[k] for k in manifest if k != "intervals"}, indent=2))
        return 0
    payload = run(
        repo,
        args.out,
        system=args.system,
        reexport=args.reexport,
        rerun=args.rerun,
        frames_dir=args.frames_dir,
        states_dir=args.states_dir,
        logs_dir=args.logs_dir,
        skip_sensitivity=args.skip_sensitivity,
        skip_export=args.skip_export,
        coast_mode=args.coast_mode,
        force_rebuild=args.force_rebuild,
        rebuild_retries=args.rebuild_retries,
    )
    print(json.dumps({k: payload[k] for k in payload if k != "per_interval"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
