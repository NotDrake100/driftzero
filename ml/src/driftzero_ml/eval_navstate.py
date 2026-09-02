"""Score an external 10 Hz NavigationState JSONL against hidden IO-VNBD truth."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Sequence

from driftzero_ml.contracts import ContractError, validate_navigation_state
from driftzero_ml.gnss_truth import TruthGateConfig, assess_truth, score_epochs
from driftzero_ml.metrics import BlackoutMetrics, evaluate_blackout


def load_navigation_state_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_no, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as error:
            raise ContractError(f"{path}:{line_no}: invalid JSON") from error
        validate_navigation_state(obj)
        rows.append(obj)
    if not rows:
        raise ContractError(f"{path} has no NavigationState rows")
    for previous, current in zip(rows, rows[1:]):
        if int(current["timestamp_ns"]) < int(previous["timestamp_ns"]):
            raise ContractError("NavigationState JSONL timestamps must be non-decreasing")
    return rows


def states_to_latlon(states: Sequence[dict]) -> list[tuple[int, tuple[float, float]]]:
    return [
        (
            int(row["timestamp_ns"]),
            (float(row["position"]["latitude_deg"]), float(row["position"]["longitude_deg"])),
        )
        for row in states
    ]


def score_states_against_truth(
    states: Sequence[dict],
    truth_records: Sequence[dict],
    start_ns: int,
    end_ns: int,
    *,
    gate: TruthGateConfig | None = None,
) -> BlackoutMetrics:
    """Align estimate and truth on fresh-fix epochs inside the blackout."""

    by_t = {int(row["timestamp_ns"]): row for row in states}
    epochs = score_epochs(truth_records, start_ns, end_ns)
    if not epochs:
        raise ValueError("no unique GNSS fix epochs inside the blackout")
    estimate: list[tuple[float, float]] = []
    truth: list[tuple[float, float]] = []
    original = {int(row["timestamp_ns"]): row for row in truth_records}
    for stamp in epochs:
        if stamp not in by_t:
            raise ValueError(f"NavigationState JSONL missing timestamp {stamp}")
        src = original[stamp]
        if "latitude_deg" not in src or "longitude_deg" not in src:
            raise ValueError(f"truth missing lat/lon at {stamp}")
        estimate.append(
            (
                float(by_t[stamp]["position"]["latitude_deg"]),
                float(by_t[stamp]["position"]["longitude_deg"]),
            )
        )
        truth.append((float(src["latitude_deg"]), float(src["longitude_deg"])))
    duration = (end_ns - start_ns) / 1_000_000_000.0
    coverage = 1.0
    checked = assess_truth(truth, duration_s=duration, coverage=coverage, config=gate)
    if not checked.accepted:
        raise ValueError("truth gate rejected interval: " + "; ".join(checked.reasons))
    return evaluate_blackout(estimate, truth)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Score NavigationState JSONL on locked IO-VNBD blackouts"
    )
    parser.add_argument("--states", type=Path, required=True)
    parser.add_argument("--start-ns", type=int, required=True)
    parser.add_argument("--end-ns", type=int, required=True)
    parser.add_argument("--truth-jsonl", type=Path, required=True, help="score-only truth rows")
    args = parser.parse_args(argv)
    states = load_navigation_state_jsonl(args.states)
    truth = []
    for line in args.truth_jsonl.read_text().splitlines():
        if line.strip():
            truth.append(json.loads(line))
    metrics = score_states_against_truth(states, truth, args.start_ns, args.end_ns)
    print(json.dumps(metrics.to_dict(), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
