"""Complete-trip split assignment. Never split rows from one trip."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SplitAssignment:
    trip_id: str
    split: str


FROZEN_SPLIT_SEED = "26168"
FROZEN_TRAIN_SESSION_GROUPS = frozenset(
    {
        "S-M",
        "S-S2",
        "S-Vfa02",
        "S-Vta10",
        "S-Vta16",
        "S-Vta21",
        "S-Vta23",
        "S-Vta26",
        "S-Vta28",
        "S-Vta3",
        "S-Vta7",
        "S-Vta9",
        "S-Vtb10",
        "S-Vtb12",
        "S-Vw11",
        "S-Vw13",
        "S-Vw14",
        "S-Vw15",
        "S-Vw2",
        "S-Vw4",
        "S-Vw6",
        "S-Vw7",
        "S-Vw8",
        "S-Vw9",
    }
)
FROZEN_DEVELOPMENT_SESSION_GROUPS = frozenset(
    {
        "S-S4",
        "S-Vta11",
        "S-Vta13",
        "S-Vta29",
        "S-Vta5",
        "S-Vta6",
        "S-Vta8",
        "S-Vtb2",
        "S-Vtb7",
        "S-Vw12",
        "S-Vw17",
        "S-Vw3",
    }
)
FROZEN_FRESH_HOLDOUT_SESSION_GROUPS = frozenset(
    {
        "S-Vw10",
        "S-Vta19",
        "S-Vtb5",
        "S-Vta30",
        "S-Vw1",
        "S-Vtb4",
        "S-Vtb11",
        "S-Vta4",
        "S-Vta14",
        "S-Vta27",
    }
)
FROZEN_LOCKED_SESSION_GROUPS = frozenset(
    {
        "S-S1",
        "S-S3",
        "S-Vfa01",
        "S-Vta1",
        "S-Vta12",
        "S-Vta15",
        "S-Vta17",
        "S-Vta2",
        "S-Vta20",
        "S-Vta22",
        "S-Vta24",
        "S-Vta25",
        "S-Vtb1",
        "S-Vtb6",
        "S-Vtb8",
        "S-Vtb9",
        "S-Vw16",
        "S-Vw5",
        "S-Y1",
    }
)
FROZEN_EXCLUDED_SESSION_GROUPS = frozenset(
    {
        "S-Vtb3",
    }
)


@dataclass(frozen=True)
class FrozenSplit:
    """Pinned split roles used by every training-data entry point."""

    seed: str
    train: frozenset[str]
    development: frozenset[str]
    fresh_holdout: frozenset[str]
    locked: frozenset[str]
    excluded: frozenset[str]


FROZEN_SPLIT = FrozenSplit(
    seed=FROZEN_SPLIT_SEED,
    train=FROZEN_TRAIN_SESSION_GROUPS,
    development=FROZEN_DEVELOPMENT_SESSION_GROUPS,
    fresh_holdout=FROZEN_FRESH_HOLDOUT_SESSION_GROUPS,
    locked=FROZEN_LOCKED_SESSION_GROUPS,
    excluded=FROZEN_EXCLUDED_SESSION_GROUPS,
)
FROZEN_SPLIT_MANIFEST = (
    Path(__file__).resolve().parents[4] / "results" / "cursor_diagnostics" / "split_manifest.json"
)


def load_frozen_split_manifest(path: Path | None = None) -> FrozenSplit:
    """Verify the checked-in manifest still matches the pinned 24/10/1 split."""

    payload = json.loads((path or FROZEN_SPLIT_MANIFEST).read_text())
    if str(payload.get("seed")) != FROZEN_SPLIT_SEED:
        raise ValueError(f"frozen split seed changed: {payload.get('seed')!r}")
    expected = {
        "train_session_groups": FROZEN_TRAIN_SESSION_GROUPS,
        "development_session_groups": FROZEN_DEVELOPMENT_SESSION_GROUPS,
        "fresh_holdout_session_groups": FROZEN_FRESH_HOLDOUT_SESSION_GROUPS,
        "locked_session_groups": FROZEN_LOCKED_SESSION_GROUPS,
        "excluded_session_groups": FROZEN_EXCLUDED_SESSION_GROUPS,
    }
    for key, expected_groups in expected.items():
        actual_groups = frozenset(payload.get(key, ()))
        if actual_groups != expected_groups:
            raise ValueError(f"frozen split manifest mismatch for {key}")
    if len(FROZEN_TRAIN_SESSION_GROUPS) != 24:
        raise RuntimeError("frozen train split must contain 24 groups")
    if len(FROZEN_FRESH_HOLDOUT_SESSION_GROUPS) != 10:
        raise RuntimeError("frozen fresh holdout must contain 10 groups")
    if len(FROZEN_EXCLUDED_SESSION_GROUPS) != 1:
        raise RuntimeError("frozen excluded split must contain 1 group")
    return FROZEN_SPLIT


def frozen_group_role(
    trip_or_group: str,
    split: FrozenSplit | None = None,
) -> str:
    """Return the pinned role for a trip or its session group."""

    selected = split or FROZEN_SPLIT
    group = session_group_id(trip_or_group)
    for role, groups in (
        ("excluded", selected.excluded),
        ("locked", selected.locked),
        ("development", selected.development),
        ("fresh_holdout", selected.fresh_holdout),
        ("train", selected.train),
    ):
        if group in groups:
            return role
    return "unregistered"


def require_frozen_train_group(
    trip_or_group: str,
    split: FrozenSplit | None = None,
) -> str:
    """Reject every group except the exact frozen 24-group training set."""

    group = session_group_id(trip_or_group)
    role = frozen_group_role(group, split)
    if role != "train":
        raise ValueError(
            f"{group} has frozen role {role}; only the frozen train groups may be loaded"
        )
    return group



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
