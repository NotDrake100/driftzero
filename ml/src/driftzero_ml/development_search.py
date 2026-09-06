"""Predeclared coast ablations on session groups excluded from the locked suite."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

from driftzero_ml.datasets.io_vnbd import load_smartphone_csv
from driftzero_ml.eval_iovnbd_blackout import _time_ordered, eval_record, screening_csv_paths
from driftzero_ml.eval_kotlin_replay import SUITE_GATES, run
from driftzero_ml.gnss_truth import assess_truth, score_epochs
from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.screening import GATED_INTERVAL_IDS, locked_blackouts

SEED = "26168"
BASE = ("--weak-heading-policy=hold_course",)
RESEED = ("--gnss-reseed-after-s=3", "--gnss-reseed-min-median-unique-s=2", "--gnss-reseed-while-fused")
CANDIDATES = {
    "baseline": BASE,
    "latch": BASE + ("--coast-latch-gnss-speed",),
    "persist_pseudo": BASE + ("--persist-speed-pseudo",),
    "sparse_reseed": BASE + RESEED,
    "latch_sparse_reseed": BASE + RESEED + ("--coast-latch-gnss-speed",),
    "stop_restart": BASE + ("--coast-latch-gnss-speed", "--coast-stop-detect", "--coast-restart=accel_burst"),
    "course_hold": BASE + ("--heading-pick-quality=weak", "--coast-latch-gnss-speed"),
}


def development_trips(trips: list[str], limit: int = 12) -> list[str]:
    reserved = {session_group_id(key.split(":")[0]) for key in GATED_INTERVAL_IDS}
    allowed = [trip for trip in trips if session_group_id(trip) not in reserved]
    # Select groups before reading any trajectory outcomes. Include all siblings.
    groups = sorted({session_group_id(t) for t in allowed},
                    key=lambda g: hashlib.sha256(f"{SEED}:development:{g}".encode()).hexdigest())[:limit]
    return sorted(t for t in allowed if session_group_id(t) in groups)


def choose_candidate(summaries: dict) -> str:
    baseline = summaries["baseline"]
    eligible = []
    for name, result in summaries.items():
        if (result["complete"] and baseline["complete"]
                and result["failed_count"] <= baseline["failed_count"]
                and result["p95"] <= baseline["p95"]
                and result["p50"] <= 0.90 * baseline["p50"]):
            eligible.append(name)
    return min(eligible, key=lambda n: (summaries[n]["failed_count"], summaries[n]["p95"],
                                       summaries[n]["p50"], n)) if eligible else "baseline"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("results/development_v3"))
    args = parser.parse_args()
    repo = args.repo.resolve()
    out = repo / args.out
    out.mkdir(parents=True, exist_ok=True)
    tables = {p.stem: p for p in screening_csv_paths(repo / "data/raw/io_vnbd")}
    trips = development_trips(list(tables))
    ids, excluded = [], []
    for trip in trips:
        records = _time_ordered([eval_record(r) for r in load_smartphone_csv(tables[trip])])
        for window in locked_blackouts(records, trip):
            epochs = set(score_epochs(records, window.start_ns, window.end_ns))
            truth = [(r["latitude_deg"], r["longitude_deg"]) for r in records if r["timestamp_ns"] in epochs]
            checked = assess_truth(truth, duration_s=(window.end_ns-window.start_ns)/1e9,
                                   coverage=1.0, config=SUITE_GATES[window.interval_id.split(":")[-1]])
            if checked.accepted:
                ids.append(window.interval_id)
            else:
                excluded.append({"interval_id": window.interval_id, "reasons": checked.reasons})
    if len(ids) < 6:
        raise ValueError(f"insufficient development evidence: {len(ids)} intervals")
    manifest = {"seed": SEED, "trips": trips, "interval_ids": ids, "excluded_truth_only": excluded,
                "candidates": CANDIDATES, "selection": "10% lower median, no worse p95 or failure count",
                "scope": "development only; no locked group used for selection"}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    summaries = {}
    for index, (name, flags) in enumerate(CANDIDATES.items()):
        payload = run(repo, out / name, system=name, frames_dir=out / "frames",
                      reexport=index == 0, rerun=True, skip_sensitivity=True,
                      coast_mode="yaw_speed_hold", extra_replay_args=flags,
                      development_interval_ids=ids)
        ratios = [r["metrics"]["drift_ratio"] for r in payload["per_interval"]]
        complete = (len(ratios) == len(ids) and not payload["failures"]
                    and all(r is not None and math.isfinite(r) for r in ratios))
        summary = payload["summary"]
        summaries[name] = {"complete": complete, "count": len(ratios),
                           "failed_count": sum(r is None or r >= 0.10 for r in ratios) + len(ids)-len(ratios),
                           "p50": summary.get("drift_ratio_p50", float("inf")),
                           "p95": summary.get("drift_ratio_p95", float("inf")),
                           "worst": summary.get("drift_ratio_worst", float("inf"))}
        print(json.dumps({"candidate": name, **summaries[name]}), flush=True)
    selected = choose_candidate(summaries)
    decision = {"selected": selected, "summaries": summaries, "flags": CANDIDATES[selected],
                "requires_locked_confirmation": selected != "baseline"}
    (out / "selection.json").write_text(json.dumps(decision, indent=2) + "\n")
    if selected != "baseline":
        # Selection is already frozen and saved before accessing locked outcomes.
        payload = run(repo, out / "locked_selected", system=selected, reexport=True, rerun=True,
                      skip_sensitivity=True, coast_mode="yaw_speed_hold", extra_replay_args=CANDIDATES[selected])
        from driftzero_ml.accuracy_gate import assess
        (out / "locked_gate.json").write_text(json.dumps(assess(payload), indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
