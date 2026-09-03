"""Score Kotlin ESKF v8 ablations on leak-free frames_premask.

Writes new metrics files only. Does not overwrite v1-v7.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from statistics import mean, median

from driftzero_ml.baselines import persist_course_baseline
from driftzero_ml.blackout import GNSS_KEYS, assert_no_gnss_leakage, mask_gnss_records
from driftzero_ml.datasets.io_vnbd import load_smartphone_csv
from driftzero_ml.eval_iovnbd_blackout import (
    _gnss_history,
    _positive_dt_pair,
    _time_ordered,
    alignment_from_records,
    attach_alignment,
    eval_record,
    last_speed_mps,
    screening_csv_paths,
)
from driftzero_ml.eval_kotlin_eskf_v5 import csv_to_slice_rows, run, slice_stats
from driftzero_ml.eval_kotlin_eskf_v6 import (
    _fmt,
    _named_cell,
    flag_counts,
    named_from_payload,
    patch_named,
    persist_rows,
    unique_premask_gnss,
)
from driftzero_ml.eval_kotlin_replay import (
    GATED_CSV,
    KOTLIN_DIR,
    SUITE_GATES,
    export_premask_frames,
    locked_gated_interval_ids,
    write_truth_jsonl,
)
from driftzero_ml.eval_navstate import load_navigation_state_jsonl
from driftzero_ml.gnss_truth import assess_truth, score_epochs, seed_heading_rad
from driftzero_ml.metrics import circular_mae_rad, evaluate_blackout
from driftzero_ml.screening import GATED_INTERVAL_IDS, _truth_motion, locked_blackouts

TABLE_NAMED = ("S-Vta2:d50", "S-S1:mid", "S-Vw16b:mid")
VW16B = "S-Vw16b:mid"
PERSIST_DRIFT_P50 = 0.5168
PERSIST_END_P50 = 234.47
HZ_WIN_DRIFT = 0.29
VTA2_MAX_M = 15.0
S1_NEAR_M = 55.0
S1_SLACK_M = 10.0

# (system, reseed_s, require_sparse, latch)
ABLATIONS: list[tuple[str, float, bool, bool]] = [
    ("kotlin_eskf_v8a", 0.0, False, False),
    ("kotlin_eskf_v8b", 8.0, True, False),
    ("kotlin_eskf_v8c", 8.0, True, True),
]


def extra_args_for(
    window: dict,
    *,
    reseed_s: float,
    require_sparse: bool,
    latch: bool,
) -> list[str]:
    quality = "weak" if window["fallback"] else "accepted"
    args = [
        "--coast-mode=yaw_speed_hold",
        "--coast-restart=held_speed",
        "--weak-heading-policy=hold_course",
        f"--heading-pick-quality={quality}",
        "--coast-honest-p",
    ]
    if reseed_s > 0.0:
        args.append(f"--gnss-reseed-after-s={reseed_s}")
        args.extend(["--config", "gnssReseedMinSparseHops=3"])
    if require_sparse:
        args.append("--gnss-reseed-require-sparse")
    if latch:
        args.append("--coast-latch-gnss-speed")
    return args


def windows_json_from_manifest(manifest_path: Path, dest: Path) -> Path:
    manifest = json.loads(manifest_path.read_text())
    payload = {
        "per_interval": [
            {
                "interval_id": row["interval_id"],
                "trip_id": row["trip_id"],
                "start_ns": row["start_ns"],
                "end_ns": row["end_ns"],
            }
            for row in manifest["intervals"]
            if row["interval_id"] in GATED_INTERVAL_IDS
        ]
    }
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(payload, indent=2) + "\n")
    return dest


def ensure_premask(repo: Path, out_dir: Path) -> Path:
    frames_dir = out_dir / "frames_premask"
    manifest_path = frames_dir / "manifest.json"
    if not manifest_path.is_file():
        export_premask_frames(repo, frames_dir, manifest_path=manifest_path)
    truth_dir = out_dir / "truth"
    truth_dir.mkdir(parents=True, exist_ok=True)
    root = repo / "data" / "raw" / "io_vnbd"
    tables = {path.stem: path for path in screening_csv_paths(root)}
    gated = locked_gated_interval_ids(repo / GATED_CSV)
    for trip_id in sorted({interval_id.split(":", 1)[0] for interval_id in gated}):
        truth_path = truth_dir / f"{trip_id}.jsonl"
        if truth_path.is_file():
            continue
        write_truth_jsonl(truth_path, load_smartphone_csv(tables[trip_id]))
    return frames_dir


def score_persist_interval(records: list[dict], interval) -> dict | None:
    records = _time_ordered(records)
    history = _gnss_history(records, interval.start_ns)
    pair = _positive_dt_pair(history)
    if pair is None:
        return None
    masked = mask_gnss_records(records, [interval])
    assert_no_gnss_leakage(masked)
    blackout_rows = [row for row in masked if row.get("gnss_masked")]
    if len(blackout_rows) < 8:
        return None
    original_by_t = {int(row["timestamp_ns"]): row for row in records}
    truth: list[tuple[float, float]] = []
    for row in blackout_rows:
        src = original_by_t[int(row["timestamp_ns"])]
        if "latitude_deg" not in src or "longitude_deg" not in src:
            return None
        leaked = GNSS_KEYS.intersection(row.keys())
        if leaked:
            raise AssertionError(f"blackout IMU row still has GNSS keys: {sorted(leaked)}")
        truth.append((float(src["latitude_deg"]), float(src["longitude_deg"])))
    heading0 = seed_heading_rad(history)
    if heading0 is None:
        return None
    last_fix = (float(history[-1]["latitude_deg"]), float(history[-1]["longitude_deg"]))
    speed0 = last_speed_mps(pair[0], pair[1])
    horizon = [int(row["timestamp_ns"]) for row in blackout_rows]
    dts: list[float] = []
    last_t = int(pair[1]["timestamp_ns"])
    for stamp in horizon:
        dts.append((stamp - last_t) / 1_000_000_000.0)
        last_t = stamp
    trace = persist_course_baseline(last_fix, heading0, speed0, dts)
    truth_speed, truth_heading = _truth_motion(original_by_t, blackout_rows)
    epochs = set(score_epochs(records, interval.start_ns, interval.end_ns))
    keep = [int(row["timestamp_ns"]) in epochs for row in blackout_rows]
    if sum(1 for flag in keep if flag) < 2:
        return None
    kept_truth = [point for point, flag in zip(truth, keep) if flag]
    suite = interval.interval_id.rsplit(":", 1)[-1]
    duration = (interval.end_ns - interval.start_ns) / 1_000_000_000.0
    checked = assess_truth(kept_truth, duration_s=duration, coverage=1.0, config=SUITE_GATES.get(suite))
    if not checked.accepted:
        return None
    kept_trace = [point for point, flag in zip(trace, keep) if flag]
    kept_speed = [speed0 for flag in keep if flag]
    kept_head = [heading0 % (2.0 * 3.141592653589793) for flag in keep if flag]
    kept_truth_speed = [value for value, flag in zip(truth_speed, keep) if flag]
    kept_truth_head = [value for value, flag in zip(truth_heading, keep) if flag]
    metrics = evaluate_blackout(kept_trace, kept_truth)
    return {
        "interval_id": interval.interval_id,
        "trip_id": interval.interval_id.split(":", 1)[0],
        "start_ns": interval.start_ns,
        "end_ns": interval.end_ns,
        "endpoint_error_m": metrics.endpoint_error_m,
        "truth_path_length_m": metrics.truth_path_length_m,
        "drift_ratio": metrics.drift_ratio,
        "speed_mae_mps": mean(abs(a - b) for a, b in zip(kept_speed, kept_truth_speed)),
        "heading_mae_rad": circular_mae_rad(kept_head, kept_truth_head),
    }


def write_persist_locked(repo: Path, out_dir: Path) -> Path:
    dest = out_dir / "metrics_per_interval_persist_locked.csv"
    if dest.is_file() and dest.stat().st_size > 0:
        return dest
    root = repo / "data" / "raw" / "io_vnbd"
    tables = {path.stem: path for path in screening_csv_paths(root)}
    gated = locked_gated_interval_ids(repo / GATED_CSV)
    rows: list[dict] = []
    for trip_id in sorted({interval_id.split(":", 1)[0] for interval_id in gated}):
        records = _time_ordered([eval_record(row) for row in load_smartphone_csv(tables[trip_id])])
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
            scored = score_persist_interval(records, window)
            if scored is None:
                raise RuntimeError(f"persist failed to score {interval_id}")
            scored["system"] = "persist"
            rows.append(scored)
    dest.parent.mkdir(parents=True, exist_ok=True)
    with dest.open("w", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "interval_id",
                "trip_id",
                "system",
                "endpoint_error_m",
                "truth_path_length_m",
                "drift_ratio",
                "speed_mae_mps",
                "heading_mae_rad",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "interval_id": row["interval_id"],
                    "trip_id": row["trip_id"],
                    "system": "persist",
                    "endpoint_error_m": f"{row['endpoint_error_m']:.6f}",
                    "truth_path_length_m": f"{row['truth_path_length_m']:.6f}",
                    "drift_ratio": "" if row["drift_ratio"] is None else f"{row['drift_ratio']:.6f}",
                    "speed_mae_mps": f"{row['speed_mae_mps']:.6f}",
                    "heading_mae_rad": f"{row['heading_mae_rad']:.6f}",
                }
            )
    return dest


def persist_summary(persist_csv: Path, truth_dir: Path) -> dict:
    rows = csv_to_slice_rows(persist_csv, "persist")
    drifts = [float(row["drift_ratio"]) for row in rows if row.get("drift_ratio")]
    ends = [float(row["endpoint_error_m"]) for row in rows]
    slices = slice_stats(rows, truth_dir) if rows else {}
    named: dict[str, dict] = {}
    for row in rows:
        if row["interval_id"] in TABLE_NAMED:
            named[row["interval_id"]] = {
                "endpoint_error_m": float(row["endpoint_error_m"]),
                "drift_ratio": float(row["drift_ratio"]) if row.get("drift_ratio") else None,
            }
    return {
        "n": len(rows),
        "drift": median(drifts) if drifts else None,
        "end": median(ends) if ends else None,
        "slices": slices,
        "named": named,
        "rows": {row["interval_id"]: row for row in rows},
    }


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
    summary = payload.get("summary") or {}
    drift = summary.get("drift_ratio_p50")
    end = summary.get("endpoint_p50_m")
    hz = slice_drift(payload, "1hz")
    sparse = slice_drift(payload, "sparse")
    vta2 = named_end(payload, "S-Vta2:d50")
    s1 = named_end(payload, "S-S1:mid")
    beat_persist = (
        drift is not None
        and end is not None
        and float(drift) < PERSIST_DRIFT_P50
        and float(end) < PERSIST_END_P50
    )
    a = hz is not None and hz <= HZ_WIN_DRIFT
    c = vta2 is not None and vta2 <= VTA2_MAX_M
    d = s1 is not None and s1 <= S1_NEAR_M + S1_SLACK_M
    return {
        "drift": None if drift is None else float(drift),
        "end": None if end is None else float(end),
        "hz_drift": hz,
        "sparse_drift": sparse,
        "vta2_m": vta2,
        "s1_m": s1,
        "beat_persist": beat_persist,
        "a_1hz": a,
        "c_vta2": c,
        "d_s1": d,
        "all": beat_persist and a and c and d,
    }


def pick_official(out_dir: Path) -> dict:
    scored: list[dict] = []
    for system, t_s, sparse, latch in ABLATIONS:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        payload = json.loads(path.read_text())
        check = criteria(payload)
        scored.append(
            {
                "system": system,
                "t_sparse_s": t_s,
                "require_sparse": sparse,
                "latch": latch,
                **check,
            }
        )
    ok = [row for row in scored if row["all"]]
    if ok:
        ok.sort(key=lambda row: (row["latch"], row["drift"], row["end"], row["system"]))
        winner = ok[0]["system"]
        return {
            "single_winner": winner,
            "official_rows": [winner],
            "note": (
                f"{winner} is leak-free frames_premask, beats persist on drift p50 and "
                f"endpoint p50, keeps 1 Hz drift <= {HZ_WIN_DRIFT}, S-Vta2:d50 <= {VTA2_MAX_M} m, "
                f"and S-S1:mid within {S1_SLACK_M:.0f} m of {S1_NEAR_M:.0f} m. "
                "Student forward-speed left off. Stop detector left off."
            ),
            "ablation_checks": scored,
        }
    return {
        "single_winner": None,
        "official_rows": ["v5_1hz", "v6_sparse"],
        "note": (
            "No single winner. No v8 row beats persist on both p50s while keeping "
            f"1 Hz drift <= {HZ_WIN_DRIFT}, S-Vta2:d50 <= {VTA2_MAX_M} m, and "
            f"S-S1:mid near {S1_NEAR_M:.0f} m. Official rows stay split: v5_1hz and v6_sparse. "
            "No blended number."
        ),
        "ablation_checks": scored,
    }


def persist_losses_v8(
    persist: dict[str, dict],
    payload: dict,
    frames_dir: Path,
    *,
    reseeds: bool,
) -> list[dict]:
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
        if reseeds:
            why = (
                "sparse-gated unique reseed or HOLD_COURSE yaw after the last visible unique. "
                "Hidden GNSS inside the mask is not used"
            )
        else:
            why = (
                "v8a is HOLD_COURSE without unique-gap reseed. Residual is IMU yaw "
                "and held last-accepted GNSS speed"
            )
        losses.append(
            {
                "interval_id": interval_id,
                "endpoint_m": end_m,
                "persist_endpoint_m": persist_end,
                "delta_m": delta,
                "mask_start_speed_mps": speed,
                "why": why,
            }
        )
    losses.sort(key=lambda item: -item["delta_m"])
    return losses


def vw16b_for(out_dir: Path, system: str) -> dict:
    payload = json.loads((out_dir / f"metrics_{system}.json").read_text())
    row = next((item for item in payload["per_interval"] if item["interval_id"] == VW16B), None)
    if row is None:
        return {"interval_id": VW16B, "missing": True}
    states_path = out_dir / f"states_{system}" / "S-Vw16b_mid.jsonl"
    counts = {"states": 0, "reseed": 0, "would_admit": 0, "sparse_skip": 0}
    if states_path.is_file():
        window = load_navigation_state_jsonl(states_path)
        counts = flag_counts(window, int(row["start_ns"]), int(row["end_ns"]))
        counts["sparse_skip"] = sum(
            1
            for item in window
            if int(row["start_ns"]) <= int(item["timestamp_ns"]) < int(row["end_ns"])
            and "gnss_reseed_sparse_skip" in item["health"]["flags"]
        )
    fired = []
    if counts["reseed"] > 0:
        fired.append("reseed")
    if counts.get("sparse_skip", 0) > 0:
        fired.append("sparse_skip")
    if counts["would_admit"] > 0:
        fired.append("honest_gate_would_admit")
    return {
        "interval_id": VW16B,
        "endpoint_error_m": row["metrics"]["endpoint_error_m"],
        "flags": counts,
        "mechanism": fired or ["neither"],
    }


def write_v8_manifest(out_dir: Path, decision: dict, extra: dict) -> None:
    payload = {
        "system": "kotlin_eskf_v8",
        "single_winner": decision["single_winner"],
        "official_rows": decision["official_rows"],
        "winner_note": decision["note"],
        "ablation_checks": decision["ablation_checks"],
        "score_frames": "frames_premask",
        "seed": 26168,
    }
    payload.update(extra)
    (out_dir / "metrics_kotlin_eskf_v8.json").write_text(json.dumps(payload, indent=2) + "\n")
    winner = decision["single_winner"]
    if winner:
        src_csv = out_dir / f"metrics_per_interval_{winner}.csv"
        if src_csv.is_file():
            (out_dir / "metrics_per_interval_kotlin_eskf_v8.csv").write_text(src_csv.read_text())


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
    persist_info: dict,
    mechanisms: dict[str, dict],
    losses_by_row: dict[str, list[dict]],
    error_budget: list[str],
) -> None:
    truth_dir = out_dir / "truth"
    persist_rows_csv = list(persist_info.get("rows", {}).values())
    rows = [
        {
            "label": "persist",
            "frames": "screening unique-fix GNSS (no IMU frames)",
            "n": persist_info.get("n"),
            "drift": persist_info.get("drift"),
            "end": persist_info.get("end"),
            "hz": (persist_info.get("slices") or {}).get("1hz") or {},
            "sparse": (persist_info.get("slices") or {}).get("sparse") or {},
            "named": persist_info.get("named") or {},
        }
    ]
    for system, _t, _sparse, _latch in ABLATIONS:
        rows.append(row_from_json(out_dir / f"metrics_{system}.json", system, "frames_premask/", persist_rows_csv, truth_dir))
    winner = decision["single_winner"]
    if winner:
        rows.append(
            row_from_json(out_dir / f"metrics_{winner}.json", "v8 official", "frames_premask/", persist_rows_csv, truth_dir)
        )
    lines = [
        "# Kotlin ESKF screening replay",
        "",
        "Official scoring is `frames_premask` (n=35). This file is the v8 note. v1-v7 metrics files are not overwritten.",
        "",
        f"v8 decision: {decision['note']}",
        "",
        "Stop detector stays off. Learned forward-speed stays off. Default blackout speed is last accepted GNSS speed. Unique-gap reseed is off on the live phone. v8b/v8c reseed only when the last unique gap is at least 8 s and the recent unique-fix median spacing is sparse (3 hops). A 1 Hz trip with one historical 9 s hop does not snap.",
        "",
        "Road heading is not scored here. IO-VNBD has no team OSM pack for those roads in the APK.",
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
            "Published prior rows (do not invent others; leaky-frame 0.541 is not a headline):",
            "",
            "- persist 0.5168 / 234 m",
            "- kotlin v1 6.69, v2 7.56",
            "- v3 premask 0.703 / 201 m",
            "- v5 HOLD_COURSE leak-free 0.611 / 187.5 m, 1 Hz 0.257/52 (beats persist 0.319/72), sparse 0.841",
            "- v6a reseed 0.538 / 163 m, sparse 0.572 (beats persist sparse), 1 Hz lost",
            "- v7 no single winner. Official rows stay v5_1hz and v6_sparse unless a later row beats both.",
            "",
            "Ablations (all on frames_premask, HOLD_COURSE on weak heading picks, honest coast P on, stop off, student off):",
            "",
            "- v8a: HOLD_COURSE only. No unique-gap reseed. Latch off.",
            "- v8b: T=8 s unique-gap reseed only when recent unique spacing is sparse (3 hops). Latch off.",
            "- v8c: v8b plus 2 s GNSS speed latch at coast start.",
            "",
            f"v8 gates: persist drift < {PERSIST_DRIFT_P50} and endpoint < {PERSIST_END_P50} m, "
            f"1 Hz drift <= {HZ_WIN_DRIFT}, S-Vta2:d50 <= {VTA2_MAX_M} m, "
            f"S-S1:mid <= {S1_NEAR_M + S1_SLACK_M:.0f} m.",
            "",
            "| ablation | persist both p50 | a 1 Hz | c S-Vta2:d50 | d S-S1:mid |",
            "|---|---|---|---|---|",
        ]
    )
    for check in decision.get("ablation_checks") or []:
        lines.append(
            f"| {check['system']} | "
            f"{'pass' if check['beat_persist'] else 'fail'} {_fmt(check.get('drift'), 3)} / {_fmt(check.get('end'), 1)} | "
            f"{'pass' if check['a_1hz'] else 'fail'} {_fmt(check.get('hz_drift'), 3)} | "
            f"{'pass' if check['c_vta2'] else 'fail'} {_fmt(check.get('vta2_m'), 2)} | "
            f"{'pass' if check['d_s1'] else 'fail'} {_fmt(check.get('s1_m'), 2)} |"
        )
    lines.extend(["", "Error budget:", ""])
    lines.extend(error_budget)
    lines.append("")
    for label, mechanism in mechanisms.items():
        fired = ", ".join(mechanism.get("mechanism") or ["n/a"])
        lines.append(
            f"S-Vw16b:mid on `{label}`: {fired}. Endpoint {_fmt(mechanism.get('endpoint_error_m'), 2)} m."
        )
    lines.append("")
    for label, losses in losses_by_row.items():
        if losses:
            lines.append(f"Intervals where `{label}` endpoint exceeds persist by more than 10 m:")
            lines.append("")
            for item in losses:
                lines.append(
                    f"- `{item['interval_id']}`: {_fmt(item['endpoint_m'], 1)} m vs persist "
                    f"{_fmt(item['persist_endpoint_m'], 1)} m. {item['why']}."
                )
            lines.append("")
        else:
            lines.append(f"`{label}` matches or beats persist within 10 m endpoint on every scored interval.")
            lines.append("")
    (out_dir / "README.md").write_text("\n".join(lines) + "\n")


def error_budget_lines(decision: dict, persist_info: dict) -> list[str]:
    checks = decision.get("ablation_checks") or []
    lines = [
        "Official SIH gate is median drift < 0.10. Persist on this locked 35 is 0.5168. "
        "No v8 row is claimed to meet 0.10.",
        "",
        "v7 already showed that T=6 s or 8 s unique-gap reseed snaps S-S1:mid (last unique hop 9 s) "
        "to about 163 m and snaps an earlier 9 s hop on 1 Hz S-Vta2 (12.46 m to 34.25 m). "
        "v8 requires current unique spacing to be sparse, so that historical hop is not enough.",
    ]
    if persist_info.get("drift") is not None:
        lines.append(
            f"This run's persist-locked recompute is drift p50 {_fmt(persist_info.get('drift'), 4)} / "
            f"endpoint p50 {_fmt(persist_info.get('end'), 2)} m (n={persist_info.get('n')})."
        )
    if not checks:
        lines.append("No v8 ablation has been scored yet.")
        return lines
    winner = decision.get("single_winner")
    if winner:
        row = next(item for item in checks if item["system"] == winner)
        lines.append(
            f"Single official leak-free row `{winner}`: drift {_fmt(row.get('drift'), 4)}, "
            f"endpoint {_fmt(row.get('end'), 2)} m, 1 Hz {_fmt(row.get('hz_drift'), 3)}, "
            f"S-Vta2:d50 {_fmt(row.get('vta2_m'), 2)} m, S-S1:mid {_fmt(row.get('s1_m'), 2)} m."
        )
        return lines
    lines.append(
        "No single row beat persist on both p50s while keeping the 1 Hz and named-interval gates. "
        "Official rows stay v5_1hz and v6_sparse. Do not blend those two into one number."
    )
    v8a = next((item for item in checks if item["system"] == "kotlin_eskf_v8a"), None)
    v8b = next((item for item in checks if item["system"] == "kotlin_eskf_v8b"), None)
    if v8a and v8b:
        lines.append(
            f"v8a (HOLD_COURSE, no reseed) drift {_fmt(v8a.get('drift'), 4)} / "
            f"{_fmt(v8a.get('end'), 1)} m, 1 Hz {_fmt(v8a.get('hz_drift'), 3)}, "
            f"S-Vta2 {_fmt(v8a.get('vta2_m'), 2)} m, S-S1 {_fmt(v8a.get('s1_m'), 2)} m."
        )
        lines.append(
            f"v8b (sparse-only 8 s reseed) drift {_fmt(v8b.get('drift'), 4)} / "
            f"{_fmt(v8b.get('end'), 1)} m, 1 Hz {_fmt(v8b.get('hz_drift'), 3)}, "
            f"S-Vta2 {_fmt(v8b.get('vta2_m'), 2)} m, S-S1 {_fmt(v8b.get('s1_m'), 2)} m."
        )
        if v8a.get("c_vta2") and v8a.get("d_s1") and v8b.get("c_vta2") and v8b.get("d_s1"):
            lines.append(
                "Sparse-only reseed kept the named intervals. If sparse p50 improved versus v8a "
                "without losing 1 Hz, that aid ships even without a single official winner."
            )
        elif v8b.get("c_vta2") is False or v8b.get("d_s1") is False:
            lines.append(
                "Sparse-only reseed still moved a named interval. The aid is not the official row."
            )
    return lines


def write_error_budget_doc(repo: Path, decision: dict, persist_info: dict) -> None:
    lines = [
        "# IO-VNBD v8 error budget",
        "",
        "Written with the v8 leak-free `frames_premask` run. Seed 26168. Locked 35 intervals.",
        "No invented metres. Hidden GNSS inside a mask is score-only.",
        "",
    ]
    lines.extend(error_budget_lines(decision, persist_info))
    lines.extend(
        [
            "",
            "Along-track term is held-speed error times duration. Cross-track is heading error times speed times duration.",
            "Persist heading MAE p50 0.673 rad is scored against 1 to 9 s GNSS course and is not the gyro heading error.",
            "IO-VNBD 10 Hz AndroSensor plus 1 to 9 s unique-fix truth cannot fairly measure the 5 m / 50 m example on most intervals.",
            "The 0.10 median gate is not met on this suite. Persist 0.5168 remains the held-out coast.",
            "",
        ]
    )
    dest = repo / "docs" / "12_IO_VNBD_V8_ERROR_BUDGET.md"
    dest.write_text("\n".join(lines))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score Kotlin ESKF v8 ablations on frames_premask")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=KOTLIN_DIR)
    parser.add_argument("--system", action="append", dest="systems")
    parser.add_argument("--windows-from", type=Path, default=None)
    parser.add_argument("--keep-states", action="store_true")
    parser.add_argument("--readme-only", action="store_true")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    out_dir = args.out if args.out.is_absolute() else repo / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    frames_dir = out_dir / "frames_premask"
    wanted = args.systems or [item[0] for item in ABLATIONS]
    by_name = {item[0]: item for item in ABLATIONS}

    persist_csv = out_dir / "metrics_per_interval_persist_locked.csv"
    persist_info = {"n": None, "drift": None, "end": None, "slices": {}, "named": {}, "rows": {}}
    if persist_csv.is_file() or not args.readme_only:
        if not args.readme_only:
            ensure_premask(repo, out_dir)
            persist_csv = write_persist_locked(repo, out_dir)
        if persist_csv.is_file():
            persist_info = persist_summary(persist_csv, out_dir / "truth")

    windows_from = args.windows_from
    if windows_from is None:
        v3 = repo / "results/io_vnbd_screening_v1/kotlin_replay/metrics_kotlin_eskf_v3_premask.json"
        if v3.is_file():
            windows_from = v3
        else:
            windows_from = windows_json_from_manifest(
                frames_dir / "manifest.json",
                out_dir / "windows_v8_from_premask.json",
            )

    if not args.readme_only:
        for system in wanted:
            _name, reseed_s, require_sparse, latch = by_name[system]
            run(
                repo,
                out_dir,
                system=system,
                extra_for=lambda window, rs=reseed_s, sp=require_sparse, la=latch: extra_args_for(
                    window, reseed_s=rs, require_sparse=sp, latch=la
                ),
                states_subdir=f"states_{system}",
                logs_subdir=f"logs_{system}",
                windows_from=windows_from,
                rerun=not args.keep_states,
            )
            patch_named(out_dir / f"metrics_{system}.json")

    decision = pick_official(out_dir)
    mechanisms: dict[str, dict] = {}
    losses_by_row: dict[str, list[dict]] = {}
    persist_by_id = persist_info.get("rows") or persist_rows(repo)
    for system, _t, _sparse, _latch in ABLATIONS:
        path = out_dir / f"metrics_{system}.json"
        if not path.is_file():
            continue
        payload = json.loads(path.read_text())
        if (out_dir / f"states_{system}").is_dir():
            mechanisms[system] = vw16b_for(out_dir, system)
        losses_by_row[system] = persist_losses_v8(
            persist_by_id,
            payload,
            frames_dir,
            reseeds=_t > 0.0,
        )
    extra = {
        "s_vw16b_mechanism": mechanisms,
        "persist_losses_over_10m": losses_by_row,
        "persist_locked": {
            "n": persist_info.get("n"),
            "drift_ratio_p50": persist_info.get("drift"),
            "endpoint_p50_m": persist_info.get("end"),
        },
    }
    write_v8_manifest(out_dir, decision, extra)
    budget = error_budget_lines(decision, persist_info)
    write_readme(repo, out_dir, decision, persist_info, mechanisms, losses_by_row, budget)
    write_error_budget_doc(repo, decision, persist_info)
    print(json.dumps({"decision": decision, "persist_locked": extra["persist_locked"]}, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
