"""Score Kotlin ESKF v4 ablations on locked screening intervals.

Reads the same 35 windows as v2/v3. Writes new metrics files only.
Does not overwrite v1-v3. Does not edit eval_kotlin_replay.py.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
from collections import Counter
from pathlib import Path

from driftzero_ml.eval_kotlin_replay import (
    KOTLIN_DIR,
    SUITE_GATES,
    _parse_replay_log,
    align_states_to_epochs,
    ensure_replay_binary,
    extras_from_states,
    health_notes,
    java_home,
    load_truth_jsonl,
    mode_histogram,
    replay_mask_ns,
    summarize,
)
from driftzero_ml.eval_navstate import load_navigation_state_jsonl, score_states_against_truth
from driftzero_ml.gnss_truth import score_epochs
from driftzero_ml.metrics import BlackoutMetrics


def load_windows(metrics_json: Path) -> list[dict]:
    payload = json.loads(metrics_json.read_text())
    return [
        {
            "interval_id": row["interval_id"],
            "trip_id": row["trip_id"],
            "start_ns": int(row["start_ns"]),
            "end_ns": int(row["end_ns"]),
        }
        for row in payload["per_interval"]
    ]


def frame_path(frames_dir: Path, trip_id: str, interval_id: str) -> Path:
    safe = frames_dir / f"{interval_id.replace(':', '_')}.jsonl"
    if safe.is_file():
        return safe
    trip = frames_dir / f"{trip_id}.jsonl"
    if trip.is_file():
        return trip
    raise FileNotFoundError(f"missing frames for {interval_id}")


def run_replay(
    binary: Path,
    frames: Path,
    states: Path,
    start_ns: int,
    end_ns: int,
    log_path: Path,
    env: dict[str, str],
    extra_args: list[str],
) -> dict:
    states.parent.mkdir(parents=True, exist_ok=True)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    mask_start, mask_end = replay_mask_ns(start_ns, end_ns)
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
        *extra_args,
    ]
    result = subprocess.run(args, cwd=binary.parent, capture_output=True, text=True, env=env, check=False)
    log_path.write_text(result.stdout + ("\n" + result.stderr if result.stderr else ""))
    if result.returncode != 0:
        raise RuntimeError(f"replay failed ({result.returncode}): {result.stderr or result.stdout}")
    return _parse_replay_log(result.stdout)


def write_outputs(out_dir: Path, system: str, payload: dict, scored: list[dict]) -> None:
    metrics_name = f"metrics_{system}.json"
    csv_name = f"metrics_per_interval_{system}.csv"
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
                "along_track_error_m",
                "cross_track_error_m",
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
                    "" if metrics.along_track_error_m is None else f"{metrics.along_track_error_m:.6f}",
                    "" if metrics.cross_track_error_m is None else f"{metrics.cross_track_error_m:.6f}",
                    f"{row['extras']['speed_mae_mps']:.6f}",
                    f"{row['extras']['heading_mae_rad']:.6f}",
                    row["health"]["low_confidence"],
                    row["health"]["filter_ok_false"],
                ]
            )


def run(
    repo: Path,
    out_dir: Path,
    *,
    system: str,
    extra_args: list[str],
    states_subdir: str,
    logs_subdir: str,
    windows_from: Path,
    rerun: bool = True,
) -> dict:
    repo = repo.resolve()
    out_dir = out_dir if out_dir.is_absolute() else repo / out_dir
    frames_dir = out_dir / "frames"
    truth_dir = out_dir / "truth"
    states_dir = out_dir / states_subdir
    logs_dir = out_dir / logs_subdir
    states_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)

    windows = load_windows(windows_from if windows_from.is_absolute() else repo / windows_from)
    env = os.environ.copy()
    env["JAVA_HOME"] = java_home()
    env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    binary = ensure_replay_binary(repo, env)

    scored: list[dict] = []
    failures: list[dict] = []
    modes_all: Counter = Counter()
    health_all = {"filter_ok_false": 0, "low_confidence": 0, "imu_gap_flag": 0, "states_in_window": 0}

    for window in windows:
        interval_id = window["interval_id"]
        trip_id = window["trip_id"]
        start_ns = window["start_ns"]
        end_ns = window["end_ns"]
        safe_id = interval_id.replace(":", "_")
        state_path = states_dir / f"{safe_id}.jsonl"
        log_path = logs_dir / f"{safe_id}.log"
        try:
            if rerun or not state_path.is_file() or state_path.stat().st_size == 0:
                replay_log = run_replay(
                    binary,
                    frame_path(frames_dir, trip_id, interval_id),
                    state_path,
                    start_ns,
                    end_ns,
                    log_path,
                    env,
                    extra_args,
                )
            else:
                replay_log = _parse_replay_log(log_path.read_text()) if log_path.is_file() else {}
            states = load_navigation_state_jsonl(state_path)
            truth = load_truth_jsonl(truth_dir / f"{trip_id}.jsonl")
            suite = interval_id.rsplit(":", 1)[-1]
            epochs = score_epochs(truth, start_ns, end_ns)
            aligned = align_states_to_epochs(states, epochs)
            metrics = score_states_against_truth(
                aligned,
                truth,
                start_ns,
                end_ns,
                gate=SUITE_GATES.get(suite),
            )
            extras = extras_from_states(aligned, truth, start_ns, end_ns)
        except (OSError, ValueError, RuntimeError, KeyError, TypeError, ArithmeticError) as error:
            failures.append({"interval_id": interval_id, "reason": str(error)})
            continue
        modes = mode_histogram(states, start_ns, end_ns)
        notes = health_notes(states, start_ns, end_ns)
        modes_all.update(modes)
        for key in ("filter_ok_false", "low_confidence", "imu_gap_flag", "states_in_window"):
            health_all[key] += notes[key]
        scored.append(
            {
                "interval_id": interval_id,
                "trip_id": trip_id,
                "start_ns": start_ns,
                "end_ns": end_ns,
                "metrics": metrics,
                "extras": extras,
                "modes": dict(modes),
                "health": notes,
                "replay": replay_log,
            }
        )
        print(
            json.dumps(
                {
                    "interval_id": interval_id,
                    "endpoint_error_m": metrics.endpoint_error_m,
                    "drift_ratio": metrics.drift_ratio,
                }
            ),
            flush=True,
        )

    summary = summarize(scored) if scored else {}
    payload = {
        "system": system,
        "extra_args": extra_args,
        "source_frames": str(frames_dir),
        "windows_from": str(windows_from),
        "intervals_requested": len(windows),
        "intervals_scored": len(scored),
        "failures": failures,
        "summary": summary,
        "modes": dict(modes_all),
        "health": health_all,
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
    write_outputs(out_dir, system, payload, scored)
    return payload


def promote_winner(out_dir: Path, winner: str) -> None:
    src_json = out_dir / f"metrics_{winner}.json"
    src_csv = out_dir / f"metrics_per_interval_{winner}.csv"
    payload = json.loads(src_json.read_text())
    payload["system"] = "kotlin_eskf_v4"
    payload["winner_ablation"] = winner
    (out_dir / "metrics_kotlin_eskf_v4.json").write_text(json.dumps(payload, indent=2) + "\n")
    shutil.copyfile(src_csv, out_dir / "metrics_per_interval_kotlin_eskf_v4.csv")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF v4 ablations")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=KOTLIN_DIR)
    parser.add_argument("--system", required=True)
    parser.add_argument("--states-subdir", required=True)
    parser.add_argument("--logs-subdir", required=True)
    parser.add_argument(
        "--windows-from",
        type=Path,
        default=Path("results/io_vnbd_screening_v1/kotlin_replay/metrics_kotlin_eskf_v2.json"),
    )
    parser.add_argument("--extra-arg", action="append", default=[], dest="extra_args")
    parser.add_argument("--keep-states", action="store_true")
    args = parser.parse_args(argv)
    extra = []
    for token in args.extra_args:
        extra.extend(token.split())
    payload = run(
        args.repo.resolve(),
        args.out,
        system=args.system,
        extra_args=extra,
        states_subdir=args.states_subdir,
        logs_subdir=args.logs_subdir,
        windows_from=args.windows_from,
        rerun=not args.keep_states,
    )
    print(json.dumps({k: payload[k] for k in payload if k != "per_interval"}, indent=2))
    return 0 if payload["intervals_scored"] == payload["intervals_requested"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
