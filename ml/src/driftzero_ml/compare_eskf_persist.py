"""Compare a Kotlin ESKF per-interval JSON against screening persist.

Uses along/cross already stored by evaluate_blackout / eval_navstate.
Does not edit those modules. Score-only: reads result files, never GNSS inside a mask.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def _load_persist(path: Path) -> dict[str, dict[str, float]]:
    out: dict[str, dict[str, float]] = {}
    with path.open() as handle:
        for row in csv.DictReader(handle):
            if row.get("system") != "persist":
                continue
            out[row["interval_id"]] = {
                "endpoint_error_m": float(row["endpoint_error_m"]),
                "drift_ratio": float(row["drift_ratio"]),
                "along_track_error_m": float(row["along_track_error_m"]),
                "cross_track_error_m": float(row["cross_track_error_m"]),
            }
    return out


def _load_kotlin(path: Path) -> dict[str, dict]:
    payload = json.loads(path.read_text())
    by: dict[str, dict] = {}
    for row in payload["per_interval"]:
        metrics = row["metrics"]
        by[row["interval_id"]] = {
            "endpoint_error_m": float(metrics["endpoint_error_m"]),
            "drift_ratio": float(metrics["drift_ratio"]) if metrics["drift_ratio"] is not None else None,
            "along_track_error_m": float(metrics["along_track_error_m"]),
            "cross_track_error_m": float(metrics["cross_track_error_m"]),
        }
    return by


def compare(persist: dict[str, dict[str, float]], kotlin: dict[str, dict]) -> dict:
    losses: list[dict] = []
    along_dom = 0
    cross_dom = 0
    for interval_id, p in persist.items():
        k = kotlin.get(interval_id)
        if k is None:
            continue
        ka = abs(k["along_track_error_m"])
        kc = abs(k["cross_track_error_m"])
        dominant = "along" if ka >= kc else "cross"
        if dominant == "along":
            along_dom += 1
        else:
            cross_dom += 1
        delta_end = k["endpoint_error_m"] - p["endpoint_error_m"]
        kd = k["drift_ratio"]
        pd = p["drift_ratio"]
        if kd is None:
            continue
        if k["endpoint_error_m"] > p["endpoint_error_m"] + 0.01:
            losses.append(
                {
                    "interval_id": interval_id,
                    "delta_endpoint_m": delta_end,
                    "kotlin_endpoint_m": k["endpoint_error_m"],
                    "persist_endpoint_m": p["endpoint_error_m"],
                    "kotlin_drift": kd,
                    "persist_drift": pd,
                    "kotlin_abs_along_m": ka,
                    "kotlin_abs_cross_m": kc,
                    "persist_abs_along_m": abs(p["along_track_error_m"]),
                    "persist_abs_cross_m": abs(p["cross_track_error_m"]),
                    "dominant": dominant,
                }
            )
    losses.sort(key=lambda row: row["delta_endpoint_m"], reverse=True)
    loss_along = sum(1 for row in losses if row["dominant"] == "along")
    loss_cross = sum(1 for row in losses if row["dominant"] == "cross")
    return {
        "n_persist": len(persist),
        "n_kotlin": len(kotlin),
        "n_endpoint_losses": len(losses),
        "all_intervals_dominant_along": along_dom,
        "all_intervals_dominant_cross": cross_dom,
        "loss_dominant_along": loss_along,
        "loss_dominant_cross": loss_cross,
        "losses": losses,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="List ESKF intervals that lose to persist")
    parser.add_argument(
        "--persist-csv",
        type=Path,
        default=Path("results/io_vnbd_screening_v1/metrics_per_interval.csv"),
    )
    parser.add_argument(
        "--kotlin-json",
        type=Path,
        default=Path("results/io_vnbd_screening_v1/kotlin_replay/metrics_kotlin_eskf_v3.json"),
    )
    args = parser.parse_args(argv)
    report = compare(_load_persist(args.persist_csv), _load_kotlin(args.kotlin_json))
    print(json.dumps(report, indent=2))
    print(
        f"dominant term on losses: "
        f"along {report['loss_dominant_along']} / cross {report['loss_dominant_cross']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
