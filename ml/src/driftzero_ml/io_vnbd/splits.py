"""Complete-trip split assignment. Never split rows from one trip."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Iterable, Mapping


@dataclass(frozen=True)
class SplitAssignment:
    trip_id: str
    split: str


def assign_trip_splits(
    trip_ids: Iterable[str],
    *,
    seed: str,
    fractions: Mapping[str, float] | None = None,
) -> tuple[SplitAssignment, ...]:
    """Assign each unique trip to exactly one split using a stable hash.

    `fractions` keys are split names in priority order. Values must sum to 1.
    Default is train 0.6, validation 0.2, public_test 0.1, locked_test 0.1.
    """

    parts = dict(fractions or {
        "train": 0.6,
        "validation": 0.2,
        "public_test": 0.1,
        "locked_test": 0.1,
    })
    if min(parts.values()) <= 0:
        raise ValueError("each split fraction must be positive")
    total = sum(parts.values())
    if abs(total - 1.0) > 1e-9:
        raise ValueError("split fractions must sum to 1")
    unique: list[str] = []
    seen: set[str] = set()
    for trip_id in trip_ids:
        if not trip_id:
            raise ValueError("trip_id must not be empty")
        if trip_id in seen:
            raise ValueError(f"duplicate trip_id: {trip_id}")
        seen.add(trip_id)
        unique.append(trip_id)

    ordered_names = tuple(parts)
    thresholds: list[tuple[str, float]] = []
    running = 0.0
    for name in ordered_names:
        running += parts[name]
        thresholds.append((name, running))

    assignments: list[SplitAssignment] = []
    for trip_id in unique:
        material = f"{seed}:{trip_id}".encode("utf-8")
        unit = int.from_bytes(hashlib.sha256(material).digest()[:8], "big") / 2**64
        split = ordered_names[-1]
        for name, ceiling in thresholds:
            if unit < ceiling:
                split = name
                break
        assignments.append(SplitAssignment(trip_id=trip_id, split=split))
    return tuple(assignments)
