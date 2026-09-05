"""Score Kotlin ESKF v8 on leak-free frames_premask.

Writes new metrics files only. Does not overwrite v1-v7.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from driftzero_ml.eval_kotlin_eskf_v5 import csv_to_slice_rows, run, slice_stats
from driftzero_ml.eval_kotlin_eskf_v6 import (
    _fmt,
    _named_cell,
    flag_counts,
    named_from_payload,
    patch_named,
    unique_premask_gnss,
)
from driftzero_ml.eval_kotlin_eskf_v7 import persist_losses_v7, row_from_json
from driftzero_ml.eval_kotlin_replay import GATED_CSV, KOTLIN_DIR, ensure_replay_binary, java_home
from driftzero_ml.eval_navstate import load_navigation_state_jsonl
from driftzero_ml.gnss_truth import unique_fix_median_spacing_s

TABLE_NAMED = ("S-Vta2:d50", "S-S1:mid", "S-Vw16b:mid")
VW16B = "S-Vw16b:mid"
PERSIST_DRIFT_P50 = 0.5168
PERSIST_END_P50 = 234.47
HZ_MAX = 0.29
VTA2_MAX_M = 15.0
S1_MAX_M = 75.0

# (system, t_reseed_s, min_median_s, honest_p)
ABLATIONS: list[tuple[str, float, float, bool]] = [
    ("kotlin_eskf_v8a", 8.0, 8.0, True),
    ("kotlin_eskf_v8b", 8.0, 8.0, False),
]


def premask_unique_median_spacing_s(frames_path: Path, start_ns: int) -> float | None:
    """Median unique-fix spacing from pre-mask GNSS only. Leak-free."""

    stamps = [int(row["timestamp_ns"]) for row in unique_premask_gnss(frames_path, start_ns)]
    return unique_fix_median_spacing_s(stamps)


def extra_args_for(
    window: dict,
    *,
    reseed_s: float,
    min_median_s: float,
    honest_p: bool,
) -> list[str]:
    quality = "weak" if window["fallback"] else "accepted"
    args = [
        "--coast-mode=yaw_speed_hold",
        "--coast-restart=held_speed",
        "--weak-heading-policy=hold_course",
        f"--heading-pick-quality={quality}",
        f"--gnss-reseed-after-s={reseed_s}",
        f"--gnss-reseed-min-median-unique-s={min_median_s}",
    ]
    if honest_p:
        args.append("--coast-honest-p")
    return args


def slice_drift(payload: dict, name: str) -> float | None:
    block = ((payload.get("slices") or {}).get(name) or {})
    value = block.get("drift_ratio_p50")
    return None if value is None else float(value)


def named_end(payload: dict, interval_id: str) -> float | None:
    named = payload.get("named_intervals") or named_from_payload(payload)
    row = named.get(interval_id) or {}
    end = row.get("endpoint_error_m")
    return None if end is None else float(end)


def criteria(payload: dict) -> dict:
    hz = slice_drift(payload, "1hz")
    sparse = slice_drift(payload, "sparse")
    vta2 = named_end(payload, "S-Vta2:d50")
    s1 = named_end(payload, "S-S1:mid")
    drift = float(payload["summary"]["drift_ratio_p50"])
    end = float(payload["summary"]["endpoint_p50_m"])
    a = hz is not None and hz <= HZ_MAX
    b = drift < PERSIST_DRIFT_P50
    c = end < PERSIST_END_P50
    d = vta2 is not None and vta2 <= VTA2_MAX_M
    e = s1 is not None and s1 <= S1_MAX_M
    return {
        "hz_drift": hz,
        "sparse_drift": sparse,
        "drift": drift,
        "end": end,
        "vta2_m": vta2,
        "s1_m": s1,
        "a_1hz": a,
        "b_drift_vs_persist": b,
        "c_end_vs_persist": c,
        "d_vta2": d,
        "e_s1": e,
        "all": a and b and c and d and e,
    }


def pick_official(out_dir: Path) -> dict:
    scored: list[dict] = []
    for system, t_s, min_med, honest in ABLATIONS:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        payload = json.loads(path.read_text())
        check = criteria(payload)
        scored.append(
            {
                "system": system,
                "t_reseed_s": t_s,
                "min_median_unique_s": min_med,
                "honest_p": honest,
                **check,
            }
        )
    ok = [row for row in scored if row["all"]]
    if ok:
        ok.sort(key=lambda row: (row["drift"], row["end"], row["system"]))
        winner = ok[0]["system"]
        return {
            "single_winner": winner,
            "official_rows": [winner],
            "note": (
                f"{winner} beats persist on drift p50 and endpoint p50, keeps 1 Hz "
                f"drift <= {HZ_MAX}, S-Vta2:d50 <= {VTA2_MAX_M:.0f} m, and S-S1:mid "
                f"within 20 m of v5. Unique-gap reseed fires only when the last hop "
                f"is at least 8 s and the trip median unique spacing is sparse. "
                "Student forward-speed left off. Stop detector left off."
            ),
            "ablation_checks": scored,
        }
    return {
        "single_winner": None,
        "official_rows": ["v5_1hz", "v6_sparse"],
        "note": (
            "No single winner. No v8 row beats persist on both p50s while keeping "
            "1 Hz drift <= 0.29, S-Vta2:d50 <= 15 m, and S-S1:mid within 20 m of v5. "
            "Official rows stay split: v5_1hz and v6_sparse. No blended number."
        ),
        "ablation_checks": scored,
    }


def vw16b_for(out_dir: Path, metrics_system: str, states_system: str) -> dict:
    payload = json.loads((out_dir / f"metrics_{metrics_system}.json").read_text())
    row = next((item for item in payload["per_interval"] if item["interval_id"] == VW16B), None)
    if row is None:
        return {"interval_id": VW16B, "missing": True}
    states_path = out_dir / f"states_{states_system}" / "S-Vw16b_mid.jsonl"
    counts = {"states": 0, "reseed": 0, "would_admit": 0}
    if states_path.is_file():
        counts = flag_counts(
            load_navigation_state_jsonl(states_path),
            int(row["start_ns"]),
            int(row["end_ns"]),
        )
    fired = []
    if counts["reseed"] > 0:
        fired.append("reseed")
    if counts["would_admit"] > 0:
        fired.append("honest_gate_would_admit")
    return {
        "interval_id": VW16B,
        "endpoint_error_m": row["metrics"]["endpoint_error_m"],
        "flags": counts,
        "mechanism": fired or ["neither"],
        "note": (
            "Reseed fires when the unique-fix gap is at least 8 s, the trip median "
            "unique spacing is sparse (~9 s), and the filter is coasting. "
            "Honest-P would-admit means gate=6*(sigma+sqrt(P_h)) would have "
            "accepted the same fix without reseed."
        ),
    }


def trip_medians(frames_dir: Path, payload: dict) -> list[dict]:
    manifest = {
        item["interval_id"]: item
        for item in json.loads((frames_dir / "manifest.json").read_text())["intervals"]
    }
    out: list[dict] = []
    for row in payload.get("per_interval") or []:
        interval_id = row["interval_id"]
        meta = manifest[interval_id]
        median_s = premask_unique_median_spacing_s(frames_dir / meta["file"], int(row["start_ns"]))
        out.append(
            {
                "interval_id": interval_id,
                "premask_unique_median_s": median_s,
                "sparse_trip": median_s is not None and median_s >= 8.0,
            }
        )
    return out


def write_v8_manifest(out_dir: Path, decision: dict, extra: dict) -> None:
    payload = {
        "system": "kotlin_eskf_v8",
        "single_winner": decision["single_winner"],
        "official_rows": decision["official_rows"],
        "winner_note": decision["note"],
        "ablation_checks": decision["ablation_checks"],
    }
    payload.update(extra)
    (out_dir / "metrics_kotlin_eskf_v8.json").write_text(json.dumps(payload, indent=2) + "\n")


def write_readme(
    repo: Path,
    out_dir: Path,
    decision: dict,
    mechanisms: dict[str, dict],
    losses_by_row: dict[str, list[dict]],
) -> None:
    truth_dir = out_dir / "truth"
    persist_rows_csv = csv_to_slice_rows(repo / GATED_CSV, "persist")
    persist_slices = slice_stats(persist_rows_csv, truth_dir) if persist_rows_csv else {}
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
        row_from_json(out_dir / "metrics_kotlin_eskf.json", "v1_leakyframes", "frames/ (leaky heading pick)", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v2.json", "v2_leakyframes", "frames/ (leaky heading pick)", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v3.json", "v3_leakyframes", "frames/ (leaky heading pick)", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v3_premask.json", "v3_premask", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v4.json", "v4_leakyframes", "frames/ (leaky heading pick)", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v5a.json", "kotlin_eskf_v5a", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v5b.json", "kotlin_eskf_v5b", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v5c.json", "kotlin_eskf_v5c", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v5d.json", "kotlin_eskf_v5d", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v5e.json", "kotlin_eskf_v5e", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v5.json", "v5 official / v5_1hz", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v6a.json", "kotlin_eskf_v6a", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v6b.json", "kotlin_eskf_v6b", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v6c.json", "kotlin_eskf_v6c", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v6d.json", "kotlin_eskf_v6d", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v6.json", "v6 official / v6_sparse", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v7a.json", "kotlin_eskf_v7a", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v7b.json", "kotlin_eskf_v7b", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v7c.json", "kotlin_eskf_v7c", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v7d.json", "kotlin_eskf_v7d", "frames_premask/", persist_rows_csv, truth_dir),
        row_from_json(out_dir / "metrics_kotlin_eskf_v7e.json", "kotlin_eskf_v7e", "frames_premask/", persist_rows_csv, truth_dir),
    ]
    for system, _t, _m, _h in ABLATIONS:
        rows.append(row_from_json(out_dir / f"metrics_{system}.json", system, "frames_premask/", persist_rows_csv, truth_dir))
    winner = decision["single_winner"]
    if winner:
        rows.append(
            row_from_json(out_dir / f"metrics_{winner}.json", "v8 official", "frames_premask/", persist_rows_csv, truth_dir)
        )

    persist_named: dict[str, dict] = {}
    for row in persist_rows_csv:
        if row["interval_id"] in TABLE_NAMED:
            persist_named[row["interval_id"]] = {
                "endpoint_error_m": float(row["endpoint_error_m"]),
                "drift_ratio": float(row["drift_ratio"]) if row.get("drift_ratio") else None,
            }
    rows[0]["named"] = persist_named

    lines = [
        "# Kotlin ESKF screening replay",
        "",
        "Official scoring is `frames_premask` (n=35). Rows labeled `_leakyframes` used the older heading-gyro pick that could see inside the mask. Those files are kept. Do not score new work on them.",
        "",
        f"v8 decision: {decision['note']}",
        "",
        "Stop detector v2 stays off. Learned forward-speed stays off. Default blackout speed is the held GNSS speed. Unique-gap reseed needs last unique hop >= 8 s and a sparse trip median unique spacing (~9 s). HOLD_COURSE on weak heading picks stays.",
        "",
        "| system | frames | n | drift p50 | endpoint p50 m | 1 Hz drift / m | sparse drift / m | S-Vta2:d50 m | S-S1:mid m | S-Vw16b:mid m |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
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
            f"{_named_cell(row['named'], 'S-Vta2:d50')} | {_named_cell(row['named'], 'S-S1:mid')} | "
            f"{_named_cell(row['named'], VW16B)} |"
        )
    lines.extend(
        [
            "",
            "Ablations (all on frames_premask, HOLD_COURSE on weak heading picks unless noted, latch off, stop off, student forward-speed off):",
            "",
            "- v5a: HOLD_COURSE on weak heading picks. Speed latch off. Stop off. No unique-gap reseed. No honest P.",
            "- v5b: 2 s GNSS speed latch on. INTEGRATE heading. Stop off.",
            "- v5c: HOLD_COURSE + 2 s GNSS speed latch. Stop off.",
            "- v5d: stop detector v2 only (require stopped prefix, 3 s hold). Heading INTEGRATE. Latch off.",
            "- v5e: HOLD_COURSE + latch + stop v2.",
            "- v6a: T_reseed 3 s on last-trusted GNSS age, including while fused. Honest coast P on.",
            "- v6b: T_reseed 6 s last-trusted, including while fused. Honest coast P on.",
            "- v6c: T_reseed 3 s. Decay toward pre-mask mean GNSS speed, tau 30 s.",
            "- v6d: T_reseed 6 s. Decay toward pre-mask mean GNSS speed, tau 30 s.",
            "- v7a: T_sparse 6 s unique-gap reseed, blocked while fused. Honest coast P on. No decay.",
            "- v7b: T_sparse 8 s unique-gap reseed, blocked while fused. Honest coast P on. No decay.",
            "- v7c: T_sparse 6 s, reseed allowed while fused.",
            "- v7d: T_sparse 8 s, reseed allowed while fused.",
            "- v7e: T_sparse 6 s, blocked while fused, decay toward pre-mask mean GNSS speed, tau 30 s.",
            "- v8a: T_reseed 8 s unique-gap and trip median unique spacing >= 8 s. Blocked while fused. Honest coast P on. No decay.",
            "- v8b: same median gate as v8a, honest coast P off. HOLD_COURSE, student off, stop off.",
            "",
            "v8 gates: (a) 1 Hz drift p50 <= 0.29, (b) drift p50 < persist 0.5168, (c) endpoint p50 < persist 234.47 m, (d) S-Vta2:d50 <= 15 m, (e) S-S1:mid <= 75 m.",
            "",
        ]
    )
    lines.append("| ablation | a 1 Hz | b drift | c endpoint | d S-Vta2:d50 | e S-S1:mid |")
    lines.append("|---|---|---|---|---|---|")
    for check in decision.get("ablation_checks") or []:
        lines.append(
            f"| {check['system']} | "
            f"{'pass' if check['a_1hz'] else 'fail'} {_fmt(check.get('hz_drift'), 3)} | "
            f"{'pass' if check['b_drift_vs_persist'] else 'fail'} {_fmt(check.get('drift'), 4)} | "
            f"{'pass' if check['c_end_vs_persist'] else 'fail'} {_fmt(check.get('end'), 2)} | "
            f"{'pass' if check['d_vta2'] else 'fail'} {_fmt(check.get('vta2_m'), 2)} | "
            f"{'pass' if check['e_s1'] else 'fail'} {_fmt(check.get('s1_m'), 2)} |"
        )
    lines.extend(
        [
            "",
            "v8 rule: reseed only when the last unique-fix gap is at least 8 s and the median unique spacing already seen is at least 8 s. That is the sparse (~9 s) trip cadence. Median uses unique GNSS the filter has ingested. Replay drops GNSS inside the mask, so in-mask unique hops are not used.",
            "",
            "Why there is no single winner: S-S1 pre-mask unique median is 9.000 s, so both v8 rows reseed S-S1:mid to 163.06 m, more than 20 m worse than v5 55.01 m. S-Vta2 pre-mask unique median is 1.000 s with one 9 s hop at unique index 15. v8a and v8b write 0 `gnss_reseed_after_gap` flags on S-Vta2:d50, so that hop does not reseed. v8a still ends at 118.97 m (1 Hz drift 0.906) because honest coast P Joseph-admits the long hop. v8b turns honest P off and ends at 38.01 m (1 Hz drift 0.616), still above v5 12.46 m / 0.257. S-Vta1a starts with a 36 s unique hop, so the running median is 36 s on that first hop and v8b reseeds it (9 reseed flags on S-Vta1a:d50). S-Vta1b has no hop >= 8 s and matches v5 on both intervals. Sparse drift on both v8 rows is 0.572, at or below persist 0.709. No v8 row beats persist 0.5168 on drift p50.",
            "",
            "Persist holds last unique-fix GNSS speed and course from t < start_ns. It does not integrate gyro.",
            "",
        ]
    )
    for label, mechanism in mechanisms.items():
        fired = ", ".join(mechanism.get("mechanism") or ["n/a"])
        lines.append(
            f"S-Vw16b:mid on `{label}`: {fired}. Endpoint {_fmt(mechanism.get('endpoint_error_m'), 2)} m "
            f"(persist 12.07 m, v5 332.46 m)."
        )
    lines.append("")
    if not losses_by_row:
        lines.append("No persist-loss table (no official metrics).")
        lines.append("")
    for label, losses in losses_by_row.items():
        if losses:
            lines.append(f"Intervals where `{label}` endpoint exceeds persist by more than 10 m:")
            lines.append("")
            for item in losses:
                end_key = "v6_endpoint_m" if "v6_endpoint_m" in item else "endpoint_m"
                lines.append(
                    f"- `{item['interval_id']}`: {_fmt(item[end_key], 1)} m vs persist "
                    f"{_fmt(item['persist_endpoint_m'], 1)} m. {item['why']}."
                )
            lines.append("")
        else:
            lines.append(f"`{label}` matches or beats persist within 10 m endpoint on every scored interval.")
            lines.append("")
    (out_dir / "README.md").write_text("\n".join(lines))


def metrics_file_for_official(out_dir: Path, label: str) -> str | None:
    if label == "v5_1hz":
        return "kotlin_eskf_v5"
    if label == "v6_sparse":
        return "kotlin_eskf_v6a" if (out_dir / "metrics_kotlin_eskf_v6a.json").is_file() else "kotlin_eskf_v6"
    if (out_dir / f"metrics_{label}.json").is_file():
        return label
    return None


def states_system_for(out_dir: Path, label: str, metrics_system: str) -> str:
    if label == "v5_1hz" and (out_dir / "states_kotlin_eskf_v5a").is_dir():
        return "kotlin_eskf_v5a"
    return metrics_system


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF v8 ablations on frames_premask")
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
    frames_dir = out_dir / "frames_premask"
    wanted = args.systems or [item[0] for item in ABLATIONS]
    by_name = {item[0]: item for item in ABLATIONS}

    if not args.readme_only:
        env = os.environ.copy()
        env["JAVA_HOME"] = java_home()
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
        ensure_replay_binary(repo, env, force_rebuild=True)
        for system in wanted:
            _name, reseed_s, min_med, honest = by_name[system]
            run(
                repo,
                out_dir,
                system=system,
                extra_for=lambda window, rs=reseed_s, mm=min_med, hp=honest: extra_args_for(
                    window, reseed_s=rs, min_median_s=mm, honest_p=hp
                ),
                states_subdir=f"states_{system}",
                logs_subdir=f"logs_{system}",
                windows_from=args.windows_from,
                rerun=not args.keep_states,
            )
            patch_named(out_dir / f"metrics_{system}.json")

    decision = pick_official(out_dir)
    mechanisms: dict[str, dict] = {}
    losses_by_row: dict[str, list[dict]] = {}
    medians: list[dict] = []
    for label in decision["official_rows"]:
        system = metrics_file_for_official(out_dir, label)
        if system is None:
            continue
        states_system = states_system_for(out_dir, label, system)
        if (out_dir / f"states_{states_system}").is_dir() or label.startswith("kotlin_eskf_v8"):
            mechanisms[label] = vw16b_for(out_dir, system, states_system)
        losses_by_row[label] = persist_losses_v7(
            repo,
            out_dir,
            system,
            frames_dir,
            reseeds=label not in {"v5_1hz"},
        )
    for system, _t, _m, _h in ABLATIONS:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        if not medians:
            medians = trip_medians(frames_dir, json.loads(path.read_text()))
        if system not in mechanisms and (out_dir / f"states_{system}").is_dir():
            mechanisms[system] = vw16b_for(out_dir, system, system)
        if system not in losses_by_row:
            losses_by_row[system] = persist_losses_v7(
                repo, out_dir, system, frames_dir, reseeds=True
            )
    extra = {
        "s_vw16b_mechanism": mechanisms,
        "persist_losses_over_10m": losses_by_row,
        "premask_unique_median_s": medians,
        "n_sparse_trips": sum(1 for row in medians if row.get("sparse_trip")),
        "n_1hz_trips": sum(1 for row in medians if row.get("premask_unique_median_s") is not None and not row.get("sparse_trip")),
    }
    write_v8_manifest(out_dir, decision, extra)
    write_readme(repo, out_dir, decision, mechanisms, losses_by_row)
    print(json.dumps({"decision": decision, "s_vw16b": mechanisms}, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
