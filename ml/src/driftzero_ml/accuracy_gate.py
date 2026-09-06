"""Strict user-target gate for complete locked interval reports, not a median claim."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from statistics import median

from driftzero_ml.screening import GATED_INTERVAL_IDS


def assess(payload: dict) -> dict:
    rows = payload.get("per_interval", [])
    ids = [row["interval_id"] for row in rows]
    reasons = []
    if len(ids) != len(set(ids)):
        reasons.append("duplicate intervals")
    if set(ids) != GATED_INTERVAL_IDS:
        reasons.append("incomplete or changed locked interval set")
    if payload.get("failures"):
        reasons.append("evaluation failures")
    ratios = []
    failed = []
    for row in rows:
        metrics = row["metrics"]
        endpoint = metrics.get("endpoint_error_m")
        distance = metrics.get("truth_path_length_m")
        if (type(endpoint) not in (int, float) or type(distance) not in (int, float)
                or not math.isfinite(endpoint) or not math.isfinite(distance)
                or endpoint < 0 or distance <= 0):
            reasons.append(f"invalid error/distance: {row['interval_id']}")
            continue
        # Recompute from measured error and distance; never trust a cached ratio.
        ratio = endpoint / distance
        if not math.isfinite(ratio):
            reasons.append(f"nonfinite drift: {row['interval_id']}")
            continue
        ratios.append(ratio)
        if ratio >= 0.10:
            failed.append(row["interval_id"])
    return {
        "target": "endpoint error / travelled distance < 0.10 on every locked interval",
        "passed": not reasons and not failed and len(ratios) == len(GATED_INTERVAL_IDS),
        "interval_count": len(ratios),
        "drift_p50": median(ratios) if ratios else None,
        "drift_worst": max(ratios) if ratios else None,
        "failed_intervals": failed,
        "invalid_evidence": reasons,
        "scope": "IO-VNBD screening only; no handheld or Pune field certification",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metrics", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = assess(json.loads(args.metrics.read_text()))
    args.out.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
