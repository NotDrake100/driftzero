"""Deterministic GNSS blackout definitions and masking."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

GNSS_KEYS = frozenset(
    {
        "latitude_deg",
        "longitude_deg",
        "altitude_m",
        "gnss_speed_mps",
        "gnss_bearing_rad",
        "horizontal_accuracy_m",
        "vertical_accuracy_m",
        "satellites_visible",
        "satellites_used",
        "constellations",
        "raw_gnss",
    }
)


@dataclass(frozen=True, order=True)
class BlackoutInterval:
    """Half-open monotonic interval [start_ns, end_ns)."""

    start_ns: int
    end_ns: int
    interval_id: str

    def __post_init__(self) -> None:
        if self.start_ns < 0:
            raise ValueError("start_ns must be non-negative")
        if self.end_ns <= self.start_ns:
            raise ValueError("end_ns must be greater than start_ns")
        if not self.interval_id:
            raise ValueError("interval_id must not be empty")

    def contains(self, timestamp_ns: int) -> bool:
        return self.start_ns <= timestamp_ns < self.end_ns


def validate_blackout_intervals(intervals: Iterable[BlackoutInterval]) -> tuple[BlackoutInterval, ...]:
    """Sort intervals and reject overlap or duplicate IDs."""

    ordered = tuple(sorted(intervals, key=lambda item: (item.start_ns, item.end_ns)))
    ids: set[str] = set()
    previous: BlackoutInterval | None = None
    for interval in ordered:
        if interval.interval_id in ids:
            raise ValueError(f"duplicate blackout interval ID: {interval.interval_id}")
        ids.add(interval.interval_id)
        if previous is not None and interval.start_ns < previous.end_ns:
            raise ValueError(
                f"overlapping blackout intervals: {previous.interval_id} and {interval.interval_id}"
            )
        previous = interval
    return ordered


def mask_gnss_records(
    records: Iterable[Mapping[str, Any]],
    intervals: Iterable[BlackoutInterval],
    *,
    timestamp_key: str = "timestamp_ns",
) -> list[dict[str, Any]]:
    """Return copied records with all known GNSS fields removed in blackouts.

    The function adds `gnss_masked` and `blackout_interval_id`. It never mutates
    the source mappings and never carries the last or next fix into the gap.
    """

    ordered = validate_blackout_intervals(intervals)
    output: list[dict[str, Any]] = []
    prior_timestamp: int | None = None
    interval_index = 0

    for source in records:
        if timestamp_key not in source:
            raise KeyError(f"record is missing {timestamp_key}")
        timestamp = source[timestamp_key]
        if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp < 0:
            raise ValueError(f"{timestamp_key} must be a non-negative integer")
        if prior_timestamp is not None and timestamp < prior_timestamp:
            raise ValueError("records must be ordered by non-decreasing timestamp")
        prior_timestamp = timestamp

        while interval_index < len(ordered) and timestamp >= ordered[interval_index].end_ns:
            interval_index += 1
        active = (
            ordered[interval_index]
            if interval_index < len(ordered) and ordered[interval_index].contains(timestamp)
            else None
        )

        copied = dict(source)
        copied["gnss_masked"] = active is not None
        copied["blackout_interval_id"] = active.interval_id if active else None
        if active:
            for key in GNSS_KEYS:
                copied.pop(key, None)
        output.append(copied)
    return output


def assert_no_gnss_leakage(records: Iterable[Mapping[str, Any]]) -> None:
    """Reject a masked record that still contains a known GNSS input key."""

    for index, record in enumerate(records):
        if record.get("gnss_masked"):
            leaked = GNSS_KEYS.intersection(record.keys())
            if leaked:
                names = ", ".join(sorted(leaked))
                raise AssertionError(f"masked record {index} contains GNSS fields: {names}")

