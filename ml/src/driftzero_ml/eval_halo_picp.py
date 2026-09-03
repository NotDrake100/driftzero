"""Position halo PICP from NavigationState JSONL plus score-only truth.

Compares `uncertainty.horizontal_95_m` to horizontal geodesic error.
Does not import eval_navstate. Kotlin replay can feed the same files later.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence

from driftzero_ml.contracts import ContractError, validate_navigation_state
from driftzero_ml.metrics import haversine_m

MAX_ALIGN_NS = 100_000_000
Z95_TO_Z68 = 2.0


@dataclass(frozen=True)
class HaloPicpRow:
    timestamp_ns: int
    error_m: float
    horizontal95_m: float
    covered_95: bool
    covered_68_from_95: bool


@dataclass(frozen=True)
class HaloPicpReport:
    n: int
    picp95: float
    picp68_from_95: float
    median_error_m: float
    median_horizontal95_m: float
    align_max_ns: int

    def to_dict(self) -> dict:
        payload = asdict(self)
        payload["n"] = self.n
        return payload


def load_truth_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    for line_no, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}:{line_no}: invalid JSON") from error
        if "timestamp_ns" not in obj or "latitude_deg" not in obj or "longitude_deg" not in obj:
            raise ValueError(f"{path}:{line_no}: truth needs timestamp_ns, latitude_deg, longitude_deg")
        rows.append(obj)
    if not rows:
        raise ValueError(f"{path} has no truth rows")
    return rows


def load_states_jsonl(path: Path, *, validate: bool = True) -> list[dict]:
    rows: list[dict] = []
    for line_no, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as error:
            raise ContractError(f"{path}:{line_no}: invalid JSON") from error
        if validate:
            validate_navigation_state(obj)
        rows.append(obj)
    if not rows:
        raise ContractError(f"{path} has no NavigationState rows")
    return rows


def halo_picp(
    states: Sequence[dict],
    truth: Sequence[dict],
    *,
    align_max_ns: int = MAX_ALIGN_NS,
) -> HaloPicpReport:
    """Coverage of truth by the 95 percent halo, and by halo/2 as a 68 percent proxy."""

    if align_max_ns < 0:
        raise ValueError("align_max_ns must be non-negative")
    if not states:
        raise ValueError("states must not be empty")
    if not truth:
        raise ValueError("truth must not be empty")
    state_times = [int(row["timestamp_ns"]) for row in states]
    for previous, current in zip(state_times, state_times[1:]):
        if current < previous:
            raise ValueError("states timestamps must be non-decreasing")
    rows: list[HaloPicpRow] = []
    for item in truth:
        stamp = int(item["timestamp_ns"])
        matched = _nearest_state(states, state_times, stamp, align_max_ns)
        if matched is None:
            continue
        estimate = matched["position"]
        error = haversine_m(
            (float(estimate["latitude_deg"]), float(estimate["longitude_deg"])),
            (float(item["latitude_deg"]), float(item["longitude_deg"])),
        )
        radius = float(matched["uncertainty"]["horizontal_95_m"])
        if radius < 0:
            raise ValueError("horizontal_95_m must be non-negative")
        rows.append(
            HaloPicpRow(
                timestamp_ns=stamp,
                error_m=error,
                horizontal95_m=radius,
                covered_95=error <= radius,
                covered_68_from_95=error <= radius / Z95_TO_Z68,
            )
        )
    if not rows:
        raise ValueError("no truth epochs aligned to a NavigationState")
    errors = sorted(row.error_m for row in rows)
    radii = sorted(row.horizontal95_m for row in rows)
    n = len(rows)
    return HaloPicpReport(
        n=n,
        picp95=sum(1 for row in rows if row.covered_95) / n,
        picp68_from_95=sum(1 for row in rows if row.covered_68_from_95) / n,
        median_error_m=_median(errors),
        median_horizontal95_m=_median(radii),
        align_max_ns=align_max_ns,
    )


def _nearest_state(
    states: Sequence[dict],
    times: Sequence[int],
    stamp: int,
    align_max_ns: int,
) -> dict | None:
    best_index = 0
    best_delta = abs(times[0] - stamp)
    for index, time in enumerate(times):
        delta = abs(time - stamp)
        if delta < best_delta:
            best_delta = delta
            best_index = index
    if best_delta > align_max_ns:
        return None
    return states[best_index]


def _median(ordered: Sequence[float]) -> float:
    mid = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[mid]
    return 0.5 * (ordered[mid - 1] + ordered[mid])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Halo PICP from states.jsonl and truth JSONL")
    parser.add_argument("--states", type=Path, required=True)
    parser.add_argument("--truth-jsonl", type=Path, required=True)
    parser.add_argument("--align-max-ns", type=int, default=MAX_ALIGN_NS)
    args = parser.parse_args(argv)
    report = halo_picp(
        load_states_jsonl(args.states),
        load_truth_jsonl(args.truth_jsonl),
        align_max_ns=args.align_max_ns,
    )
    print(json.dumps(report.to_dict(), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
