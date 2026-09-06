"""Complete-trip split assignment. Never split rows from one trip."""

from __future__ import annotations

import hashlib
from collections.abc import Iterable, Mapping
from dataclasses import dataclass


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
        material = f"{seed}:{trip_id}".encode()
        unit = int.from_bytes(hashlib.sha256(material).digest()[:8], "big") / 2**64
        split = ordered_names[-1]
        for name, ceiling in thresholds:
            if unit < ceiling:
                split = name
                break
        assignments.append(SplitAssignment(trip_id=trip_id, split=split))
    return tuple(assignments)


def session_group_id(trip_id: str) -> str:
    """Group letter-suffixed siblings (S-S3a/b/c, S-Vta1a/b, S-Vw14a/b/c)."""

    if not trip_id:
        raise ValueError("trip_id must not be empty")
    if len(trip_id) >= 2 and trip_id[-1].isalpha() and trip_id[-2].isdigit():
        return trip_id[:-1]
    return trip_id


def driver_group_id(trip_id: str) -> str:
    """IO-VNBD driver letter from the published stem prefix."""

    if trip_id.startswith("S-M"):
        return "B"
    if trip_id.startswith("S-S"):
        return "A"
    if trip_id.startswith("S-Y"):
        return "D"
    if trip_id.startswith("S-V"):
        return "E"
    return "unknown"


def assign_grouped_trip_splits(
    trip_ids: Iterable[str],
    *,
    seed: str,
    group_id_fn=session_group_id,
    fractions: Mapping[str, float] | None = None,
) -> tuple[SplitAssignment, ...]:
    """Assign each session group to one split, then copy that split to every trip.

    Prevents S-S3a in public_test while S-S3b/c sit in train. Driver-held-out
    reporting is a slice on top of this assignment, not a second random split.
    """

    unique: list[str] = []
    seen: set[str] = set()
    for trip_id in trip_ids:
        if not trip_id:
            raise ValueError("trip_id must not be empty")
        if trip_id in seen:
            raise ValueError(f"duplicate trip_id: {trip_id}")
        seen.add(trip_id)
        unique.append(trip_id)
    groups: dict[str, list[str]] = {}
    for trip_id in unique:
        groups.setdefault(group_id_fn(trip_id), []).append(trip_id)
    group_rows = assign_trip_splits(sorted(groups), seed=seed, fractions=fractions)
    by_group = {row.trip_id: row.split for row in group_rows}
    return tuple(
        SplitAssignment(trip_id=trip_id, split=by_group[group_id_fn(trip_id)])
        for trip_id in unique
    )
