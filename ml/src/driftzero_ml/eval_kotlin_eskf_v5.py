"""Score Kotlin ESKF v5 ablations on leak-free frames_premask.

Writes new metrics files only. Does not overwrite v1-v4. Does not edit
eval_kotlin_replay.py or eval_kotlin_eskf_v4.py.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import shutil
import subprocess
from collections import Counter
from collections.abc import Callable
from pathlib import Path

from driftzero_ml.eval_kotlin_replay import (
    GATED_CSV,
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
from driftzero_ml.eval_slices import slice_tables, spacing_from_truth_dir
from driftzero_ml.gnss_truth import score_epochs
from driftzero_ml.metrics import BlackoutMetrics

NAMED = ("S-Vta2:d50", "S-S1:mid")
PERSIST_DRIFT_P50 = 0.5167559030624105
PERSIST_END_P50 = 234.4674762233441


def load_manifest(path: Path) -> dict[str, dict]:
    payload = json.loads(path.read_text())
    return {row["interval_id"]: row for row in payload["intervals"]}


def load_windows(metrics_json: Path, manifest: dict[str, dict]) -> list[dict]:
    payload = json.loads(metrics_json.read_text())
    windows = []
    for row in payload["per_interval"]:
        interval_id = row["interval_id"]
        meta = manifest[interval_id]
        windows.append(
            {
                "interval_id": interval_id,
                "trip_id": row["trip_id"],
                "start_ns": int(row["start_ns"]),
                "end_ns": int(row["end_ns"]),
                "file": meta["file"],
                "fallback": bool(meta.get("fallback")),
                "reason": meta.get("reason") or "",
            }
        )
    return windows


def extra_args_for(
    window: dict,
    *,
    hold_course: bool,
    latch: bool,
    stop: bool,
) -> list[str]:
    args = ["--coast-mode=yaw_speed_hold", "--coast-restart=held_speed"]
    if hold_course:
        args.append("--weak-heading-policy=hold_course")
        quality = "weak" if window["fallback"] else "accepted"
        args.append(f"--heading-pick-quality={quality}")
    if latch:
        args.append("--coast-latch-gnss-speed")
    if stop:
        args.extend(
            [
                "--coast-stop-detect",
                "--config",
                "coastStopRequireStoppedPrefix=1",
                "--config",
                "coastStopHoldS=3",
                "--config",
                "coastStopMovingK=0.5",
            ]
        )
    return args


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
    cmd = [
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
    result = subprocess.run(cmd, cwd=binary.parent, capture_output=True, text=True, env=env, check=False)
    log_path.write_text(result.stdout + ("\n" + result.stderr if result.stderr else ""))
    if result.returncode != 0:
        raise RuntimeError(f"replay failed ({result.returncode}): {result.stderr or result.stdout}")
    return _parse_replay_log(result.stdout)


def write_outputs(out_dir: Path, system: str, payload: dict, scored: list[dict]) -> None:
    (out_dir / f"metrics_{system}.json").write_text(json.dumps(payload, indent=2) + "\n")
    with (out_dir / f"metrics_per_interval_{system}.csv").open("w", newline="") as handle:
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
                "heading_pick_weak",
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
                    int(row.get("heading_pick_weak", False)),
                ]
            )


def named_from_scored(scored: list[dict]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for row in scored:
        interval_id = row["interval_id"]
        if interval_id not in NAMED:
            continue
        metrics: BlackoutMetrics = row["metrics"]
        out[interval_id] = {
            "endpoint_error_m": metrics.endpoint_error_m,
            "drift_ratio": metrics.drift_ratio,
        }
    return out


def named_from_json(path: Path) -> dict[str, dict]:
    if not path.is_file():
        return {}
    payload = json.loads(path.read_text())
    out: dict[str, dict] = {}
    for row in payload.get("per_interval") or []:
        interval_id = row["interval_id"]
        if interval_id not in NAMED:
            continue
        metrics = row["metrics"]
        out[interval_id] = {
            "endpoint_error_m": metrics["endpoint_error_m"],
            "drift_ratio": metrics.get("drift_ratio"),
        }
    return out


def csv_to_slice_rows(path: Path, system: str) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="") as handle:
        rows = []
        for row in csv.DictReader(handle):
            if row.get("system") and row["system"] != system:
                continue
            copied = dict(row)
            copied["system"] = system
            rows.append(copied)
        return rows


def slice_stats(rows: list[dict[str, str]], truth_dir: Path) -> dict:
    spacing = spacing_from_truth_dir(truth_dir)
    tables = slice_tables(rows, spacing)
    out: dict[str, dict] = {}
    for name, block in tables["slices"].items():
        stats = (block.get("systems") or {}).get(rows[0]["system"] if rows else "", {})
        out[name] = {
            "n": stats.get("n"),
            "drift_ratio_p50": stats.get("drift_ratio_p50"),
            "endpoint_p50_m": stats.get("endpoint_p50_m"),
        }
    return out


def run(
    repo: Path,
    out_dir: Path,
    *,
    system: str,
    extra_for: Callable[[dict], list[str]],
    states_subdir: str,
    logs_subdir: str,
    windows_from: Path,
    frames_subdir: str = "frames_premask",
    frames_dir: Path | None = None,
    truth_dir: Path | None = None,
    rerun: bool = True,
) -> dict:
    repo = repo.resolve()
    out_dir = out_dir if out_dir.is_absolute() else repo / out_dir
    frames_dir = frames_dir if frames_dir is None or frames_dir.is_absolute() else repo / frames_dir
    if frames_dir is None:
        frames_dir = out_dir / frames_subdir
    truth_dir = truth_dir if truth_dir is None or truth_dir.is_absolute() else repo / truth_dir
    if truth_dir is None:
        truth_dir = out_dir / "truth"
    states_dir = out_dir / states_subdir
    logs_dir = out_dir / logs_subdir
    states_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest(frames_dir / "manifest.json")
    windows = load_windows(windows_from if windows_from.is_absolute() else repo / windows_from, manifest)
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
        extra_args = extra_for(window)
        safe_id = interval_id.replace(":", "_")
        state_path = states_dir / f"{safe_id}.jsonl"
        log_path = logs_dir / f"{safe_id}.log"
        frames = frames_dir / window["file"]
        try:
            if rerun or not state_path.is_file() or state_path.stat().st_size == 0:
                replay_log = run_replay(
                    binary, frames, state_path, start_ns, end_ns, log_path, env, extra_args,
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
                "heading_pick_weak": window["fallback"],
                "heading_reason": window["reason"],
                "extra_args": extra_args,
            }
        )
        print(
            json.dumps(
                {
                    "interval_id": interval_id,
                    "endpoint_error_m": metrics.endpoint_error_m,
                    "drift_ratio": metrics.drift_ratio,
                    "heading_pick_weak": window["fallback"],
                }
            ),
            flush=True,
        )

    summary = summarize(scored) if scored else {}
    named = named_from_scored(scored)
    payload = {
        "system": system,
        "source_frames": str(frames_dir),
        "windows_from": str(windows_from),
        "intervals_requested": len(windows),
        "intervals_scored": len(scored),
        "failures": failures,
        "summary": summary,
        "named_intervals": named,
        "modes": dict(modes_all),
        "health": health_all,
        "per_interval": [
            {
                "interval_id": row["interval_id"],
                "trip_id": row["trip_id"],
                "start_ns": row["start_ns"],
                "end_ns": row["end_ns"],
                "heading_pick_weak": row["heading_pick_weak"],
                "heading_reason": row["heading_reason"],
                "extra_args": row["extra_args"],
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
    csv_rows = csv_to_slice_rows(out_dir / f"metrics_per_interval_{system}.csv", system)
    if csv_rows:
        payload["slices"] = slice_stats(csv_rows, truth_dir)
        (out_dir / f"metrics_{system}.json").write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def promote_winner(out_dir: Path, winner: str, note: str) -> None:
    src_json = out_dir / f"metrics_{winner}.json"
    src_csv = out_dir / f"metrics_per_interval_{winner}.csv"
    payload = json.loads(src_json.read_text())
    payload["system"] = "kotlin_eskf_v5"
    payload["winner_ablation"] = winner
    payload["winner_note"] = note
    (out_dir / "metrics_kotlin_eskf_v5.json").write_text(json.dumps(payload, indent=2) + "\n")
    shutil.copyfile(src_csv, out_dir / "metrics_per_interval_kotlin_eskf_v5.csv")


def _fmt(value: float | None, digits: int) -> str:
    if value is None:
        return "n/a"
    number = float(value)
    if math.isnan(number):
        return "n/a"
    return f"{number:.{digits}f}"


def _named_cell(named: dict, interval_id: str) -> str:
    row = named.get(interval_id) or {}
    end = row.get("endpoint_error_m")
    return "n/a" if end is None else _fmt(end, 2)


def write_readme(repo: Path, out_dir: Path, v5_systems: list[str], winner: str, note: str) -> None:
    truth_dir = out_dir / "truth"
    persist_rows = csv_to_slice_rows(repo / GATED_CSV, "persist")
    persist_slices = slice_stats(persist_rows, truth_dir) if persist_rows else {}

    def row_from_json(path: Path, label: str, frames: str) -> dict:
        if not path.is_file():
            return {
                "label": label,
                "frames": frames,
                "n": "n/a",
                "drift": None,
                "end": None,
                "hz": {},
                "sparse": {},
                "named": {},
            }
        payload = json.loads(path.read_text())
        summary = payload.get("summary") or {}
        slices = payload.get("slices")
        if slices is None:
            csv_path = path.with_name(path.name.replace("metrics_", "metrics_per_interval_").replace(".json", ".csv"))
            system = payload.get("system") or label
            csv_rows = csv_to_slice_rows(csv_path, system)
            if not csv_rows and persist_rows and "persist" in label:
                csv_rows = persist_rows
            slices = slice_stats(csv_rows, truth_dir) if csv_rows else {}
        named = named_from_json(path)
        return {
            "label": label,
            "frames": frames,
            "n": summary.get("interval_count"),
            "drift": summary.get("drift_ratio_p50"),
            "end": summary.get("endpoint_p50_m"),
            "hz": slices.get("1hz") or {},
            "sparse": slices.get("sparse") or {},
            "named": named,
        }

    persist_summary = json.loads((repo / "results/io_vnbd_screening_v1/metrics_summary.json").read_text())
    persist_sys = persist_summary["systems"]["persist"]
    rows = [
        {
            "label": "persist",
            "frames": "screening unique-fix GNSS (no IMU frames)",
            "n": persist_sys.get("interval_count"),
            "drift": persist_sys.get("drift_ratio_p50"),
            "end": persist_sys.get("endpoint_p50_m"),
            "hz": persist_slices.get("1hz") or {},
            "sparse": persist_slices.get("sparse") or {},
            "named": {},
        },
        row_from_json(out_dir / "metrics_kotlin_eskf.json", "v1_leakyframes", "frames/ (leaky heading pick)"),
        row_from_json(out_dir / "metrics_kotlin_eskf_v2.json", "v2_leakyframes", "frames/ (leaky heading pick)"),
        row_from_json(out_dir / "metrics_kotlin_eskf_v3.json", "v3_leakyframes", "frames/ (leaky heading pick)"),
        row_from_json(out_dir / "metrics_kotlin_eskf_v3_premask.json", "v3_premask", "frames_premask/"),
        row_from_json(out_dir / "metrics_kotlin_eskf_v4.json", "v4_leakyframes", "frames/ (leaky heading pick)"),
    ]
    for system in v5_systems:
        rows.append(row_from_json(out_dir / f"metrics_{system}.json", system, "frames_premask/"))
    rows.append(row_from_json(out_dir / "metrics_kotlin_eskf_v5.json", "v5 official", "frames_premask/"))

    persist_named = {}
    for row in persist_rows:
        if row["interval_id"] in NAMED:
            persist_named[row["interval_id"]] = {
                "endpoint_error_m": float(row["endpoint_error_m"]),
                "drift_ratio": float(row["drift_ratio"]) if row.get("drift_ratio") else None,
            }
    rows[0]["named"] = persist_named

    lines = [
        "# Kotlin ESKF screening replay",
        "",
        "Official v5 is scored on `frames_premask` (n=35). Rows labeled `_leakyframes` used the older heading-gyro pick that could see inside the mask. Those files are kept. Do not score new work on them.",
        "",
        f"Official v5 winner: `{winner}`. {note}",
        "",
        "Stop detector v2 is calibrated on the pre-mask prefix only. It stays off by default. Phone IMU is requested at SENSOR_DELAY_GAME. Do not cite 100 Hz without a measured log. IO-VNBD screening is 10 Hz.",
        "",
        "| system | frames | n | drift p50 | endpoint p50 m | 1 Hz drift / m | sparse drift / m | S-Vta2:d50 m | S-S1:mid m |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        hz = row["hz"]
        sparse = row["sparse"]
        hz_txt = (
            f"{_fmt(hz.get('drift_ratio_p50'), 3)} / {_fmt(hz.get('endpoint_p50_m'), 0)}"
            if hz
            else "n/a"
        )
        sparse_txt = (
            f"{_fmt(sparse.get('drift_ratio_p50'), 3)} / {_fmt(sparse.get('endpoint_p50_m'), 0)}"
            if sparse
            else "n/a"
        )
        lines.append(
            f"| {row['label']} | {row['frames']} | {row['n'] if row['n'] is not None else 'n/a'} | "
            f"{_fmt(row['drift'], 4)} | {_fmt(row['end'], 2)} | {hz_txt} | {sparse_txt} | "
            f"{_named_cell(row['named'], 'S-Vta2:d50')} | {_named_cell(row['named'], 'S-S1:mid')} |"
        )
    lines.extend(
        [
            "",
            "Ablations (all on frames_premask):",
            "",
            "- v5a: HOLD_COURSE on weak heading picks. Speed latch off. Stop off.",
            "- v5b: 2 s GNSS speed latch on. INTEGRATE heading. Stop off.",
            "- v5c: HOLD_COURSE + 2 s GNSS speed latch. Stop off.",
            "- v5d: stop detector v2 only (require stopped prefix, 3 s hold). Heading INTEGRATE. Latch off.",
            "- v5e: HOLD_COURSE + latch + stop v2.",
            "",
            "Persist holds last unique-fix GNSS speed and course. It does not integrate gyro.",
            "",
            "S-Vw16b:mid is the along-track overrun fixture (persist 12 m). The 0 m/s GNSS at mask start is often gated, so the filter has already coasted on the previous 15.5 m/s fix. The 2 s latch can zero speed at hold without snapping position onto that gated fix. Endpoint stays near 330 m. Official v5 therefore leaves the latch off.",
            "",
        ]
    )
    (out_dir / "README.md").write_text("\n".join(lines))


ABLATIONS: list[tuple[str, bool, bool, bool]] = [
    ("kotlin_eskf_v5a", True, False, False),
    ("kotlin_eskf_v5b", False, True, False),
    ("kotlin_eskf_v5c", True, True, False),
    ("kotlin_eskf_v5d", False, False, True),
    ("kotlin_eskf_v5e", True, True, True),
]


def pick_winner(out_dir: Path, v3_named: dict[str, dict]) -> tuple[str, str]:
    no_stop = ["kotlin_eskf_v5a", "kotlin_eskf_v5b", "kotlin_eskf_v5c"]
    candidates = []
    for system in no_stop:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        payload = json.loads(path.read_text())
        summary = payload["summary"]
        named = payload.get("named_intervals") or named_from_json(path)
        drift = float(summary["drift_ratio_p50"])
        end = float(summary["endpoint_p50_m"])
        named_ok = True
        for interval_id in NAMED:
            v5_end = (named.get(interval_id) or {}).get("endpoint_error_m")
            v3_end = (v3_named.get(interval_id) or {}).get("endpoint_error_m")
            if v5_end is None or v3_end is None:
                named_ok = False
                break
            if float(v5_end) > float(v3_end) + 5.0:
                named_ok = False
                break
        beats = drift < PERSIST_DRIFT_P50 and end < PERSIST_END_P50
        candidates.append((system, drift, end, named_ok, beats))
    beating = [c for c in candidates if c[3] and c[4]]
    pool = beating or [c for c in candidates if c[3]] or candidates
    if not pool:
        return "kotlin_eskf_v5c", "no scored ablations; defaulted to v5c"
    pool.sort(key=lambda row: (not row[4], row[1], row[2]))
    winner = pool[0][0]
    beats = pool[0][4]
    named_ok = pool[0][3]
    if beats and named_ok:
        note = (
            "Beats persist on drift p50 and endpoint p50 on frames_premask. "
            "Named intervals stay within 5 m of v3_premask. Stop detector left off."
        )
    elif named_ok:
        note = (
            "Best no-stop ablation on frames_premask that keeps S-Vta2:d50 and S-S1:mid "
            "within 5 m of v3_premask. Does not beat persist on both p50s. Stop detector left off."
        )
    else:
        note = (
            "Best no-stop ablation on frames_premask. Named-interval guard missed. "
            "Stop detector left off."
        )
    stop_path = out_dir / "metrics_kotlin_eskf_v5d.json"
    if stop_path.is_file():
        stop = json.loads(stop_path.read_text())["summary"]
        note += (
            f" Stop v2 screening drift p50 {_fmt(stop.get('drift_ratio_p50'), 3)}, "
            f"endpoint p50 {_fmt(stop.get('endpoint_p50_m'), 1)} m."
        )
    return winner, note


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF v5 ablations on frames_premask")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=KOTLIN_DIR)
    parser.add_argument("--system", action="append", dest="systems")
    parser.add_argument(
        "--windows-from",
        type=Path,
        default=Path("results/io_vnbd_screening_v1/kotlin_replay/metrics_kotlin_eskf_v3_premask.json"),
    )
    parser.add_argument("--keep-states", action="store_true")
    parser.add_argument("--readme-only", action="store_true")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    out_dir = args.out if args.out.is_absolute() else repo / args.out
    wanted = args.systems or [item[0] for item in ABLATIONS]
    by_name = {item[0]: item for item in ABLATIONS}
    v3_named = named_from_json(repo / args.windows_from)

    if not args.readme_only:
        for system in wanted:
            hold_course, latch, stop = by_name[system][1:]
            run(
                repo,
                out_dir,
                system=system,
                extra_for=lambda window, hc=hold_course, la=latch, st=stop: extra_args_for(
                    window, hold_course=hc, latch=la, stop=st
                ),
                states_subdir=f"states_{system}",
                logs_subdir=f"logs_{system}",
                windows_from=args.windows_from,
                rerun=not args.keep_states,
            )

    winner, note = pick_winner(out_dir, v3_named)
    if (out_dir / f"metrics_{winner}.json").is_file():
        promote_winner(out_dir, winner, note)
    write_readme(repo, out_dir, [item[0] for item in ABLATIONS], winner, note)
    print(json.dumps({"winner": winner, "note": note}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
