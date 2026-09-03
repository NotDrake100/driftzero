"""Score Kotlin ESKF v6 ablations on leak-free frames_premask.

Writes new metrics files only. Does not overwrite v1-v5. Does not edit
eval_kotlin_replay.py, eval_kotlin_eskf_v4.py, or eval_kotlin_eskf_v5.py.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
from pathlib import Path

from driftzero_ml.eval_kotlin_eskf_v5 import (
    csv_to_slice_rows,
    named_from_json,
    run,
    slice_stats,
)
from driftzero_ml.eval_kotlin_replay import GATED_CSV, KOTLIN_DIR
from driftzero_ml.eval_navstate import load_navigation_state_jsonl

GUARD_NAMED = ("S-Vta2:d50", "S-S1:mid")
TABLE_NAMED = ("S-Vta2:d50", "S-S1:mid", "S-Vw16b:mid")
NAMED_SLACK_M = 5.0
VW16B = "S-Vw16b:mid"
FLAG_RESEED = "gnss_reseed_after_gap"
FLAG_WOULD_ADMIT = "gnss_gate_would_admit"

# (system, reseed_s, decay)
ABLATIONS: list[tuple[str, float, bool]] = [
    ("kotlin_eskf_v6a", 3.0, False),
    ("kotlin_eskf_v6b", 6.0, False),
    ("kotlin_eskf_v6c", 3.0, True),
    ("kotlin_eskf_v6d", 6.0, True),
]


def unique_premask_gnss(frames_path: Path, start_ns: int) -> list[dict]:
    """Unique GNSS_FIX rows at or before interval start (the last ingested seed)."""

    out: list[dict] = []
    last_pos: tuple[float, float] | None = None
    if not frames_path.is_file():
        return out
    for line in frames_path.read_text().splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("kind") != "gnss_fix":
            continue
        quality = row.get("quality") or {}
        if not quality.get("available", True):
            continue
        stamp = int(row["timestamp_ns"])
        if stamp > start_ns:
            continue
        payload = row.get("payload") or {}
        if "latitude_deg" not in payload or "longitude_deg" not in payload:
            continue
        pos = (float(payload["latitude_deg"]), float(payload["longitude_deg"]))
        if last_pos is None or pos != last_pos:
            out.append({"timestamp_ns": stamp, **payload})
            last_pos = pos
        else:
            out[-1] = {"timestamp_ns": stamp, **payload}
    return out


def premask_mean_gnss_speed_mps(frames_path: Path, start_ns: int) -> float | None:
    speeds = [
        float(row["speed_mps"])
        for row in unique_premask_gnss(frames_path, start_ns)
        if row.get("speed_mps") is not None
    ]
    if not speeds:
        return None
    return sum(speeds) / len(speeds)


def extra_args_for(
    window: dict,
    *,
    reseed_s: float,
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


def named_from_payload(payload: dict, ids: tuple[str, ...] = TABLE_NAMED) -> dict[str, dict]:
    wanted = set(ids)
    out: dict[str, dict] = {}
    for row in payload.get("per_interval") or []:
        interval_id = row["interval_id"]
        if interval_id not in wanted:
            continue
        metrics = row["metrics"]
        out[interval_id] = {
            "endpoint_error_m": metrics["endpoint_error_m"],
            "drift_ratio": metrics.get("drift_ratio"),
        }
    return out


def patch_named(path: Path) -> dict:
    payload = json.loads(path.read_text())
    payload["named_intervals"] = named_from_payload(payload)
    path.write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def flag_counts(states: list[dict], start_ns: int, end_ns: int) -> dict[str, int]:
    window = [row for row in states if start_ns <= int(row["timestamp_ns"]) < end_ns]
    return {
        "states": len(window),
        "reseed": sum(1 for row in window if FLAG_RESEED in row["health"]["flags"]),
        "would_admit": sum(1 for row in window if FLAG_WOULD_ADMIT in row["health"]["flags"]),
    }


def vw16b_mechanism(out_dir: Path, system: str) -> dict:
    payload = json.loads((out_dir / f"metrics_{system}.json").read_text())
    row = next((item for item in payload["per_interval"] if item["interval_id"] == VW16B), None)
    if row is None:
        return {"interval_id": VW16B, "missing": True}
    states_path = out_dir / f"states_{system}" / "S-Vw16b_mid.jsonl"
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
            "Reseed wins when the gap exceeds T_reseed. Honest-P would-admit means "
            "gate=6*(sigma+sqrt(P_h)) would have accepted the same fix without reseed."
        ),
    }


def persist_rows(repo: Path) -> dict[str, dict]:
    out: dict[str, dict] = {}
    with (repo / GATED_CSV).open(newline="") as handle:
        for row in csv.DictReader(handle):
            if row.get("system") != "persist":
                continue
            out[row["interval_id"]] = row
    return out


def persist_losses(
    repo: Path,
    out_dir: Path,
    winner: str,
    frames_dir: Path,
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
        v6_end = float(row["metrics"]["endpoint_error_m"])
        persist_end = float(base["endpoint_error_m"])
        delta = v6_end - persist_end
        if delta <= 10.0:
            continue
        meta = manifest[interval_id]
        seed_fixes = unique_premask_gnss(frames_dir / meta["file"], int(row["start_ns"]))
        last = seed_fixes[-1] if seed_fixes else {}
        speed = last.get("speed_mps")
        course = last.get("bearing_rad")
        has_speed_course = speed is not None and course is not None
        if speed is None:
            why = "mask-start unique fix has no speed, so persist and v6 cannot share a speed seed"
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
                "v6_endpoint_m": v6_end,
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


def _fmt(value: float | int | None, digits: int) -> str:
    if value is None:
        return "n/a"
    number = float(value)
    if number != number:
        return "n/a"
    return f"{number:.{digits}f}"


def _named_cell(named: dict, interval_id: str) -> str:
    row = named.get(interval_id) or {}
    end = row.get("endpoint_error_m")
    return "n/a" if end is None else _fmt(end, 2)


def promote_winner(out_dir: Path, winner: str, note: str, extra: dict) -> None:
    src_json = out_dir / f"metrics_{winner}.json"
    src_csv = out_dir / f"metrics_per_interval_{winner}.csv"
    payload = json.loads(src_json.read_text())
    payload["system"] = "kotlin_eskf_v6"
    payload["winner_ablation"] = winner
    payload["winner_note"] = note
    payload.update(extra)
    (out_dir / "metrics_kotlin_eskf_v6.json").write_text(json.dumps(payload, indent=2) + "\n")
    shutil.copyfile(src_csv, out_dir / "metrics_per_interval_kotlin_eskf_v6.csv")


def pick_winner(out_dir: Path, v5_named: dict[str, dict]) -> tuple[str, str]:
    candidates: list[tuple[str, float, float, bool]] = []
    for system, _reseed, _decay in ABLATIONS:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        payload = json.loads(path.read_text())
        summary = payload["summary"]
        named = named_from_payload(payload, GUARD_NAMED)
        drift = float(summary["drift_ratio_p50"])
        end = float(summary["endpoint_p50_m"])
        named_ok = True
        for interval_id in GUARD_NAMED:
            v6_end = (named.get(interval_id) or {}).get("endpoint_error_m")
            v5_end = (v5_named.get(interval_id) or {}).get("endpoint_error_m")
            if v6_end is None or v5_end is None:
                named_ok = False
                break
            if float(v6_end) > float(v5_end) + NAMED_SLACK_M:
                named_ok = False
                break
        candidates.append((system, drift, end, named_ok))
    pool = [row for row in candidates if row[3]] or candidates
    if not pool:
        return "kotlin_eskf_v6a", "no scored ablations; defaulted to v6a"
    pool.sort(key=lambda row: (not row[3], row[1], row[2], row[0]))
    winner, drift, end, named_ok = pool[0]
    if named_ok:
        note = (
            f"Best frames_premask row that keeps S-Vta2:d50 and S-S1:mid within "
            f"{NAMED_SLACK_M:.0f} m of official v5. Student forward-speed left off. "
            f"Stop detector left off."
        )
    else:
        note = (
            "Best frames_premask ablation. Named-interval guard versus v5 missed. "
            "Student forward-speed left off. Stop detector left off."
        )
    return winner, note


def write_readme(
    repo: Path,
    out_dir: Path,
    winner: str,
    note: str,
    mechanism: dict,
    losses: list[dict],
) -> None:
    truth_dir = out_dir / "truth"
    persist_rows_csv = csv_to_slice_rows(repo / GATED_CSV, "persist")
    persist_slices = slice_stats(persist_rows_csv, truth_dir) if persist_rows_csv else {}

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
            csv_path = path.with_name(
                path.name.replace("metrics_", "metrics_per_interval_").replace(".json", ".csv")
            )
            system = payload.get("system") or label
            csv_rows = csv_to_slice_rows(csv_path, system)
            if not csv_rows and persist_rows_csv and "persist" in label:
                csv_rows = persist_rows_csv
            slices = slice_stats(csv_rows, truth_dir) if csv_rows else {}
        named = named_from_payload(payload) if payload.get("per_interval") else named_from_json(path)
        if not named.get(VW16B) and payload.get("per_interval"):
            named = named_from_payload(payload)
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
    for system in (
        "kotlin_eskf_v5a",
        "kotlin_eskf_v5b",
        "kotlin_eskf_v5c",
        "kotlin_eskf_v5d",
        "kotlin_eskf_v5e",
    ):
        rows.append(row_from_json(out_dir / f"metrics_{system}.json", system, "frames_premask/"))
    rows.append(row_from_json(out_dir / "metrics_kotlin_eskf_v5.json", "v5 official", "frames_premask/"))
    for system, _reseed, _decay in ABLATIONS:
        rows.append(row_from_json(out_dir / f"metrics_{system}.json", system, "frames_premask/"))
    rows.append(row_from_json(out_dir / "metrics_kotlin_eskf_v6.json", "v6 official", "frames_premask/"))

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
        "Official v6 is scored on `frames_premask` (n=35). Rows labeled `_leakyframes` used the older heading-gyro pick that could see inside the mask. Those files are kept. Do not score new work on them.",
        "",
        f"Official v6 winner: `{winner}`. {note}",
        "",
        "Stop detector v2 is calibrated on the pre-mask prefix only. It stays off by default. The live phone IMU is 100 Hz. IO-VNBD screening is 10 Hz. Pune drives decide whether to arm it. Learned forward-speed is off. Default blackout speed is the held GNSS speed.",
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
    fired = ", ".join(mechanism.get("mechanism") or ["n/a"])
    lines.extend(
        [
            "",
            "Ablations (all on frames_premask, HOLD_COURSE on weak heading picks, honest coast P on, latch off, stop off, student forward-speed off):",
            "",
            "- v5a: HOLD_COURSE on weak heading picks. Speed latch off. Stop off.",
            "- v5b: 2 s GNSS speed latch on. INTEGRATE heading. Stop off.",
            "- v5c: HOLD_COURSE + 2 s GNSS speed latch. Stop off.",
            "- v5d: stop detector v2 only (require stopped prefix, 3 s hold). Heading INTEGRATE. Latch off.",
            "- v5e: HOLD_COURSE + latch + stop v2.",
            "- v6a: T_reseed 3 s. No speed decay.",
            "- v6b: T_reseed 6 s. No speed decay.",
            "- v6c: T_reseed 3 s. Decay toward pre-mask mean GNSS speed, tau 30 s.",
            "- v6d: T_reseed 6 s. Decay toward pre-mask mean GNSS speed, tau 30 s.",
            "",
            "Persist holds last unique-fix GNSS speed and course. It does not integrate gyro.",
            "",
            f"S-Vw16b:mid mechanism on official v6: {fired}. "
            f"Endpoint {_fmt(mechanism.get('endpoint_error_m'), 2)} m "
            f"(persist 12.07 m, v5 332.46 m). Reseed fires at the unique fix at mask start. "
            "A 0 m/s column after a long unique hop uses hop speed and the 10 m course. "
            "Honest coast P grows so gate=6*(sigma+sqrt(P_h)) would also admit that fix if reseed were off.",
            "",
        ]
    )
    if losses:
        lines.append("Intervals where official v6 endpoint exceeds persist by more than 10 m:")
        lines.append("")
        for item in losses:
            lines.append(
                f"- `{item['interval_id']}`: v6 {_fmt(item['v6_endpoint_m'], 1)} m vs persist "
                f"{_fmt(item['persist_endpoint_m'], 1)} m. {item['why']}."
            )
        lines.append("")
    else:
        lines.append("Official v6 matches or beats persist within 10 m endpoint on every scored interval.")
        lines.append("")
    (out_dir / "README.md").write_text("\n".join(lines))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF v6 ablations on frames_premask")
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
    v5_named = named_from_json(out_dir / "metrics_kotlin_eskf_v5.json")
    if not v5_named:
        v5_named = named_from_json(out_dir / "metrics_kotlin_eskf_v5a.json")

    if not args.readme_only:
        for system in wanted:
            _name, reseed_s, decay = by_name[system]
            run(
                repo,
                out_dir,
                system=system,
                extra_for=lambda window, rs=reseed_s, dec=decay: extra_args_for(
                    window, reseed_s=rs, decay=dec, frames_dir=frames_dir
                ),
                states_subdir=f"states_{system}",
                logs_subdir=f"logs_{system}",
                windows_from=args.windows_from,
                rerun=not args.keep_states,
            )
            patch_named(out_dir / f"metrics_{system}.json")

    winner, note = pick_winner(out_dir, v5_named)
    mechanism = vw16b_mechanism(out_dir, winner) if (out_dir / f"metrics_{winner}.json").is_file() else {}
    losses = (
        persist_losses(repo, out_dir, winner, frames_dir)
        if (out_dir / f"metrics_{winner}.json").is_file()
        else []
    )
    extra = {"s_vw16b_mechanism": mechanism, "persist_losses_over_10m": losses}
    if (out_dir / f"metrics_{winner}.json").is_file():
        promote_winner(out_dir, winner, note, extra)
    write_readme(repo, out_dir, winner, note, mechanism, losses)
    print(json.dumps({"winner": winner, "note": note, "s_vw16b": mechanism, "persist_losses": losses}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
