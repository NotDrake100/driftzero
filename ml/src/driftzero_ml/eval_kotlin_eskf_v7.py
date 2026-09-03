"""Score Kotlin ESKF v7 ablations on leak-free frames_premask.

Writes new metrics files only. Does not overwrite v1-v6.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from driftzero_ml.eval_kotlin_eskf_v5 import csv_to_slice_rows, run, slice_stats
from driftzero_ml.eval_kotlin_eskf_v6 import (
    _fmt,
    _named_cell,
    flag_counts,
    named_from_payload,
    patch_named,
    persist_rows,
    premask_mean_gnss_speed_mps,
    unique_premask_gnss,
)
from driftzero_ml.eval_navstate import load_navigation_state_jsonl
from driftzero_ml.eval_kotlin_replay import GATED_CSV, KOTLIN_DIR

TABLE_NAMED = ("S-Vta2:d50", "S-S1:mid", "S-Vw16b:mid")
VW16B = "S-Vw16b:mid"
V5_HZ_DRIFT = 0.257
HZ_NOISE = 0.03
PERSIST_SPARSE_DRIFT = 0.709
VTA2_MAX_M = 15.0
S1_MAX_M = 75.0

# (system, t_sparse_s, reseed_while_fused, decay)
ABLATIONS: list[tuple[str, float, bool, bool]] = [
    ("kotlin_eskf_v7a", 6.0, False, False),
    ("kotlin_eskf_v7b", 8.0, False, False),
    ("kotlin_eskf_v7c", 6.0, True, False),
    ("kotlin_eskf_v7d", 8.0, True, False),
    ("kotlin_eskf_v7e", 6.0, False, True),
]


def extra_args_for(
    window: dict,
    *,
    reseed_s: float,
    while_fused: bool,
    decay: bool,
    frames_dir: Path,
) -> list[str]:
    quality = "weak" if window["fallback"] else "accepted"
    args = [
        "--coast-mode=yaw_speed_hold",
        "--coast-restart=held_speed",
        "--weak-heading-policy=hold_course",
        f"--heading-pick-quality={quality}",
        f"--gnss-reseed-after-s={reseed_s}",
        "--coast-honest-p",
    ]
    if while_fused:
        args.append("--gnss-reseed-while-fused")
    if decay:
        mean_speed = premask_mean_gnss_speed_mps(frames_dir / window["file"], int(window["start_ns"]))
        if mean_speed is not None:
            args.extend(
                [
                    "--coast-speed-decay",
                    "--config",
                    f"coastSpeedDecayTargetMps={mean_speed}",
                ]
            )
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
    a = hz is not None and hz <= V5_HZ_DRIFT + HZ_NOISE
    b = sparse is not None and sparse <= PERSIST_SPARSE_DRIFT
    c = vta2 is not None and vta2 <= VTA2_MAX_M
    d = s1 is not None and s1 <= S1_MAX_M
    return {
        "hz_drift": hz,
        "sparse_drift": sparse,
        "vta2_m": vta2,
        "s1_m": s1,
        "a_1hz": a,
        "b_sparse": b,
        "c_vta2": c,
        "d_s1": d,
        "all": a and b and c and d,
    }


def pick_official(out_dir: Path) -> dict:
    scored: list[dict] = []
    for system, t_s, fused, decay in ABLATIONS:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        payload = json.loads(path.read_text())
        check = criteria(payload)
        scored.append(
            {
                "system": system,
                "t_sparse_s": t_s,
                "reseed_while_fused": fused,
                "decay": decay,
                "drift": float(payload["summary"]["drift_ratio_p50"]),
                "end": float(payload["summary"]["endpoint_p50_m"]),
                **check,
            }
        )
    ok = [row for row in scored if row["all"]]
    if ok:
        ok.sort(key=lambda row: (row["reseed_while_fused"], row["decay"], row["drift"], row["end"], row["system"]))
        winner = ok[0]["system"]
        return {
            "single_winner": winner,
            "official_rows": [winner],
            "note": (
                f"{winner} keeps 1 Hz drift within 0.03 of v5, sparse drift at or below persist, "
                f"S-Vta2:d50 at or below 15 m, and S-S1:mid within 20 m of v5. "
                "Student forward-speed left off. Decay left off unless that row is the winner."
            ),
            "ablation_checks": scored,
        }
    return {
        "single_winner": None,
        "official_rows": ["v5_1hz", "v6_sparse"],
        "note": (
            "No single winner. No v7 row meets all four gates (1 Hz vs v5, sparse vs persist, "
            "S-Vta2:d50 <= 15 m, S-S1:mid within 20 m of v5). Official rows stay split: "
            "v5_1hz and v6_sparse. No blended number."
        ),
        "ablation_checks": scored,
    }


def persist_losses_v7(
    repo: Path,
    out_dir: Path,
    winner: str,
    frames_dir: Path,
    *,
    reseeds: bool,
) -> list[dict]:
    persist = persist_rows(repo)
    payload = json.loads((out_dir / f"metrics_{winner}.json").read_text())
    manifest = {
        item["interval_id"]: item
        for item in json.loads((frames_dir / "manifest.json").read_text())["intervals"]
    }
    losses: list[dict] = []
    for row in payload["per_interval"]:
        interval_id = row["interval_id"]
        base = persist.get(interval_id)
        if base is None:
            continue
        end_m = float(row["metrics"]["endpoint_error_m"])
        persist_end = float(base["endpoint_error_m"])
        delta = end_m - persist_end
        if delta <= 10.0:
            continue
        meta = manifest[interval_id]
        seed_fixes = unique_premask_gnss(frames_dir / meta["file"], int(row["start_ns"]))
        last = seed_fixes[-1] if seed_fixes else {}
        speed = last.get("speed_mps")
        course = last.get("bearing_rad")
        has_speed_course = speed is not None and course is not None
        if speed is None:
            why = "mask-start unique fix has no speed, so persist and the filter cannot share a speed seed"
        elif not reseeds and float(speed) < 1.0:
            why = (
                "v5 does not reseed. The unique at mask start reports 0 m/s, so the filter "
                "never takes persist's earlier unique (persist uses t < start_ns)"
            )
        elif not reseeds:
            why = (
                "mask-start unique fix has speed and course. Residual is IMU yaw "
                "and held-speed coast. v5 does not unique-gap reseed"
            )
        elif course is None and float(speed) >= 1.0:
            why = "mask-start unique fix has no course, so heading is not the persist seed"
        elif float(speed) < 1.0:
            why = (
                "mask-start unique reports 0 m/s. Reseed uses unique-hop speed and 10 m course. "
                "HOLD_COURSE plus IMU still parts from persist, which seeds from t < start_ns"
            )
        else:
            why = (
                "mask-start unique fix has speed and course. Residual is IMU yaw "
                "and held-speed coast after that seed, not a gated skip of the seed"
            )
        losses.append(
            {
                "interval_id": interval_id,
                "endpoint_m": end_m,
                "persist_endpoint_m": persist_end,
                "delta_m": delta,
                "mask_start_speed_mps": speed,
                "mask_start_bearing_rad": course,
                "has_speed_and_course": has_speed_course,
                "why": why,
            }
        )
    losses.sort(key=lambda item: -item["delta_m"])
    return losses


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
            "Reseed fires when the unique-fix gap is at least T_sparse and the filter is coasting, "
            "or while fused if that ablation is on. Honest-P would-admit means "
            "gate=6*(sigma+sqrt(P_h)) would have accepted the same fix without reseed."
        ),
    }


def write_v7_manifest(out_dir: Path, decision: dict, extra: dict) -> None:
    payload = {
        "system": "kotlin_eskf_v7",
        "single_winner": decision["single_winner"],
        "official_rows": decision["official_rows"],
        "winner_note": decision["note"],
        "ablation_checks": decision["ablation_checks"],
    }
    payload.update(extra)
    (out_dir / "metrics_kotlin_eskf_v7.json").write_text(json.dumps(payload, indent=2) + "\n")
    winner = decision["single_winner"]
    if winner:
        src_csv = out_dir / f"metrics_per_interval_{winner}.csv"
        if src_csv.is_file():
            (out_dir / "metrics_per_interval_kotlin_eskf_v7.csv").write_text(src_csv.read_text())


def row_from_json(path: Path, label: str, frames: str, persist_rows_csv: list, truth_dir: Path) -> dict:
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
        csv_path = path.with_name(
            path.name.replace("metrics_", "metrics_per_interval_").replace(".json", ".csv")
        )
        system = payload.get("system") or label
        csv_rows = csv_to_slice_rows(csv_path, system)
        if not csv_rows and persist_rows_csv and "persist" in label:
            csv_rows = persist_rows_csv
        slices = slice_stats(csv_rows, truth_dir) if csv_rows else {}
    named = named_from_payload(payload) if payload.get("per_interval") else {}
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
    ]
    for system, _t, _fused, _decay in ABLATIONS:
        rows.append(row_from_json(out_dir / f"metrics_{system}.json", system, "frames_premask/", persist_rows_csv, truth_dir))
    winner = decision["single_winner"]
    if winner:
        rows.append(
            row_from_json(out_dir / f"metrics_{winner}.json", "v7 official", "frames_premask/", persist_rows_csv, truth_dir)
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
        f"v7 decision: {decision['note']}",
        "",
        "Stop detector v2 stays off. Learned forward-speed stays off. Default blackout speed is the held GNSS speed. Unique-gap reseed is off while fused unless the ablation turns that on.",
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
            "",
            "v7 gates: (a) 1 Hz drift p50 <= 0.287, (b) sparse drift p50 <= 0.709, (c) S-Vta2:d50 <= 15 m, (d) S-S1:mid <= 75 m.",
            "",
        ]
    )
    lines.append("| ablation | a 1 Hz | b sparse | c S-Vta2:d50 | d S-S1:mid |")
    lines.append("|---|---|---|---|---|")
    for check in decision.get("ablation_checks") or []:
        lines.append(
            f"| {check['system']} | "
            f"{'pass' if check['a_1hz'] else 'fail'} {_fmt(check.get('hz_drift'), 3)} | "
            f"{'pass' if check['b_sparse'] else 'fail'} {_fmt(check.get('sparse_drift'), 3)} | "
            f"{'pass' if check['c_vta2'] else 'fail'} {_fmt(check.get('vta2_m'), 2)} | "
            f"{'pass' if check['d_s1'] else 'fail'} {_fmt(check.get('s1_m'), 2)} |"
        )
    lines.extend(
        [
            "",
            "Why there is no single winner: T_sparse 6 s and 8 s both reseed S-S1:mid (last unique hop 9 s), so S-S1 stays at 163 m, more than 20 m worse than v5 55 m. The same threshold reseeds an earlier 9 s unique hop on the 1 Hz trip S-Vta2, so S-Vta2:d50 moves from 12.46 m to 34.25 m even though the hop at mask start is 1 s. Blocking reseed while fused changed no interval. Every reseed that fired was already in coast. v7a matches v6b on every interval. v7b differs from v7a only on S-Vta17:mid (0.46 m). v7e matches v6d. Decay gets 1 Hz drift to 0.283, inside 0.03 of v5, but still fails S-Vta2 and S-S1.",
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
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF v7 ablations on frames_premask")
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
        for system in wanted:
            _name, reseed_s, while_fused, decay = by_name[system]
            run(
                repo,
                out_dir,
                system=system,
                extra_for=lambda window, rs=reseed_s, wf=while_fused, dec=decay: extra_args_for(
                    window, reseed_s=rs, while_fused=wf, decay=dec, frames_dir=frames_dir
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
    for label in decision["official_rows"]:
        system = metrics_file_for_official(out_dir, label)
        if system is None:
            continue
        states_system = states_system_for(out_dir, label, system)
        if (out_dir / f"states_{states_system}").is_dir() or label.startswith("kotlin_eskf_v7"):
            mechanisms[label] = vw16b_for(out_dir, system, states_system)
        losses_by_row[label] = persist_losses_v7(
            repo,
            out_dir,
            system,
            frames_dir,
            reseeds=label != "v5_1hz",
        )
    extra = {"s_vw16b_mechanism": mechanisms, "persist_losses_over_10m": losses_by_row}
    write_v7_manifest(out_dir, decision, extra)
    write_readme(repo, out_dir, decision, mechanisms, losses_by_row)
    print(json.dumps({"decision": decision, "s_vw16b": mechanisms, "persist_losses": losses_by_row}, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
