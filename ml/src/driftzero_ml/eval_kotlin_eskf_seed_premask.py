"""Score leak-free yaw-speed-hold with persist-like coast seed on frames_premask.

Writes `results/io_vnbd_screening_v1/kotlin_replay/seed_premask/` only.
Does not overwrite screening summary.md or v1-v8 metrics. Replay extra args
are v5a (HOLD_COURSE on weak picks). No latch, honest P, student, decay,
stop detector, or unique-gap reseed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from driftzero_ml.eval_kotlin_eskf_v5 import _fmt, named_from_json, run
from driftzero_ml.eval_kotlin_replay import KOTLIN_DIR, ensure_replay_binary, java_home

SYSTEM = "kotlin_eskf_seed_premask"
NAMED = ("S-Vta2:d50", "S-S1:mid")
SIH_DRIFT_GATE = 0.10
PERSIST_DRIFT_P50 = 0.5168


def extra_args_for(window: dict) -> list[str]:
    quality = "weak" if window["fallback"] else "accepted"
    return [
        "--coast-mode=yaw_speed_hold",
        "--coast-restart=held_speed",
        "--weak-heading-policy=hold_course",
        f"--heading-pick-quality={quality}",
    ]


def v5_named(repo: Path, kotlin_dir: Path) -> dict[str, dict]:
    path = kotlin_dir / "metrics_kotlin_eskf_v5.json"
    return named_from_json(path if path.is_file() else repo / path)


def named_end(named: dict[str, dict], interval_id: str) -> float | None:
    row = named.get(interval_id) or {}
    end = row.get("endpoint_error_m")
    return None if end is None else float(end)


def abort_if_named_worse(payload: dict, v5: dict[str, dict]) -> str | None:
    named = payload.get("named_intervals") or {}
    reasons: list[str] = []
    for interval_id in NAMED:
        new_end = named_end(named, interval_id)
        old_end = named_end(v5, interval_id)
        if new_end is None or old_end is None:
            reasons.append(f"{interval_id} missing (new={new_end}, v5={old_end})")
            continue
        if new_end > old_end:
            reasons.append(
                f"{interval_id} {new_end:.2f} m worse than published v5 {old_end:.2f} m"
            )
    if not reasons:
        return None
    return "; ".join(reasons)


def write_readme(
    out_dir: Path,
    payload: dict,
    *,
    aborted: str | None,
    v5: dict[str, dict],
) -> None:
    summary = payload.get("summary") or {}
    named = payload.get("named_intervals") or {}
    n = summary.get("interval_count")
    drift = summary.get("drift_ratio_p50")
    gate_met = drift is not None and float(drift) < SIH_DRIFT_GATE
    lines = [
        "# Persist-like coast seed on leak-free frames_premask",
        "",
        "Labeled eval row. Not an official screening overwrite. Frames are",
        "`kotlin_replay/frames_premask` (heading-gyro pick uses unique-fix hops",
        "strictly before the mask). Coast is `YAW_SPEED_HOLD` plus HOLD_COURSE on",
        "weak heading picks, same extra args as v5a. Default Replay `InsConfig`",
        "stays STRAPDOWN. No GNSS speed latch, honest P, student, decay, stop",
        "detector, or unique-gap reseed.",
        "",
        "Coast seed is the last unique GNSS with speed and a 10 m course.",
        "A 0 m/s unique on the mask start is skipped when an earlier unique",
        "qualifies (S-Vw16b).",
        "",
        f"Official SIH gate is median drift ratio < {SIH_DRIFT_GATE:.2f}. Persist p50 is {PERSIST_DRIFT_P50}.",
        "",
    ]
    if aborted:
        lines.extend(
            [
                f"Aborted. Named-interval guard: {aborted}.",
                "Filter persist-seed change must not ship as this week's eval row.",
                "",
            ]
        )
    lines.extend(
        [
            "| row | n | drift p50 | drift p90 | drift p95 | drift worst | endpoint p50 m | S-Vta2:d50 m | S-S1:mid m | 0.10 met |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|",
            (
                f"| {SYSTEM} | {n if n is not None else 'n/a'} | "
                f"{_fmt(summary.get('drift_ratio_p50'), 4)} | "
                f"{_fmt(summary.get('drift_ratio_p90'), 4)} | "
                f"{_fmt(summary.get('drift_ratio_p95'), 4)} | "
                f"{_fmt(summary.get('drift_ratio_worst'), 4)} | "
                f"{_fmt(summary.get('endpoint_p50_m'), 2)} | "
                f"{_fmt(named_end(named, 'S-Vta2:d50'), 2)} | "
                f"{_fmt(named_end(named, 'S-S1:mid'), 2)} | "
                f"{'yes' if gate_met else 'no'} |"
            ),
            (
                f"| v5 official (guard) | 35 | n/a | n/a | n/a | n/a | n/a | "
                f"{_fmt(named_end(v5, 'S-Vta2:d50'), 2)} | "
                f"{_fmt(named_end(v5, 'S-S1:mid'), 2)} | no |"
            ),
            "",
            "0.10 is unmet unless drift p50 above is < 0.10. Do not cite Integrity UI,",
            "Drift Budget, or Road DNA fixtures as this gate.",
            "",
        ]
    )
    (out_dir / "README.md").write_text("\n".join(lines))


def missing_inputs(repo: Path, kotlin_dir: Path, frames_dir: Path, windows_from: Path) -> str | None:
    if not frames_dir.is_dir():
        return f"missing frames_premask at {frames_dir}"
    if not (frames_dir / "manifest.json").is_file():
        return f"missing {frames_dir / 'manifest.json'}"
    truth = kotlin_dir / "truth"
    if not truth.is_dir():
        return f"missing truth dir at {truth}"
    resolved_windows = windows_from if windows_from.is_absolute() else repo / windows_from
    if not resolved_windows.is_file():
        return f"missing windows json at {resolved_windows}"
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score persist-like YAW_SPEED_HOLD seed on frames_premask")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--kotlin-dir", type=Path, default=KOTLIN_DIR)
    parser.add_argument(
        "--windows-from",
        type=Path,
        default=Path("results/io_vnbd_screening_v1/kotlin_replay/metrics_kotlin_eskf_v3_premask.json"),
    )
    parser.add_argument("--keep-states", action="store_true")
    parser.add_argument("--readme-only", action="store_true")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    kotlin_dir = args.kotlin_dir if args.kotlin_dir.is_absolute() else repo / args.kotlin_dir
    out_dir = kotlin_dir / "seed_premask"
    frames_dir = kotlin_dir / "frames_premask"
    truth_dir = kotlin_dir / "truth"
    out_dir.mkdir(parents=True, exist_ok=True)

    missing = missing_inputs(repo, kotlin_dir, frames_dir, args.windows_from)
    if missing:
        (out_dir / "README.md").write_text(
            "# Persist-like coast seed\n\n"
            f"Eval did not run: {missing}. No metrics invented. Official 0.10 gate is unmet.\n"
        )
        print(json.dumps({"ok": False, "reason": missing}, indent=2))
        return 1

    v5 = v5_named(repo, kotlin_dir)
    metrics_path = out_dir / f"metrics_{SYSTEM}.json"

    if not args.readme_only:
        env = os.environ.copy()
        env["JAVA_HOME"] = java_home()
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
        ensure_replay_binary(repo, env, force_rebuild=True)
        run(
            repo,
            out_dir,
            system=SYSTEM,
            extra_for=extra_args_for,
            states_subdir="states",
            logs_subdir="logs",
            windows_from=args.windows_from,
            frames_dir=frames_dir,
            truth_dir=truth_dir,
            rerun=not args.keep_states,
        )

    if not metrics_path.is_file():
        write_readme(out_dir, {}, aborted="no metrics file", v5=v5)
        print(json.dumps({"ok": False, "reason": "no metrics file"}, indent=2))
        return 1

    payload = json.loads(metrics_path.read_text())
    aborted = abort_if_named_worse(payload, v5)
    payload["v5_named_guard"] = v5
    payload["aborted"] = aborted
    payload["sih_drift_gate"] = SIH_DRIFT_GATE
    payload["sih_gate_met"] = bool(
        payload.get("summary")
        and payload["summary"].get("drift_ratio_p50") is not None
        and float(payload["summary"]["drift_ratio_p50"]) < SIH_DRIFT_GATE
    )
    metrics_path.write_text(json.dumps(payload, indent=2) + "\n")
    write_readme(out_dir, payload, aborted=aborted, v5=v5)
    print(
        json.dumps(
            {
                "ok": aborted is None,
                "aborted": aborted,
                "summary": payload.get("summary"),
                "named_intervals": payload.get("named_intervals"),
                "sih_gate_met": payload.get("sih_gate_met"),
            },
            indent=2,
            default=str,
        )
    )
    if aborted:
        print(f"abort: {aborted}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
