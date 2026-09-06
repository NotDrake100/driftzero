"""Fair IO-VNBD reporting slices. Does not change the official 35-row denominator."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from statistics import median

from driftzero_ml.gnss_truth import unique_fix_median_spacing_s

HZ_SLICE_MAX_SPACING_S = 1.5
SLICE_1HZ = "1hz"
SLICE_SPARSE = "sparse"


def interval_rows_from_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def spacing_from_truth_dir(truth_dir: Path) -> dict[str, float | None]:
    """Trip unique-fix median spacing from score-only truth JSONL."""

    out: dict[str, float | None] = {}
    if not truth_dir.is_dir():
        return out
    for path in sorted(truth_dir.glob("*.jsonl")):
        stamps: list[int] = []
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            stamps.append(int(row["timestamp_ns"]))
        out[path.stem] = unique_fix_median_spacing_s(stamps)
    return out


def rewind_notes_from_inventory(path: Path) -> dict[str, str]:
    """Map trip_id to a rewind reason copied from inventory counts. No new numbers."""

    if not path.is_file():
        return {}
    payload = json.loads(path.read_text())
    notes: dict[str, str] = {}
    for row in payload.get("trips") or []:
        trip_id = str(row.get("trip_id") or "")
        jumps = int(row.get("backward_jumps") or 0)
        source_rows = int(row.get("rows") or 0)
        kept = int(row.get("kept_rows") or 0)
        if jumps <= 0 or source_rows <= kept:
            continue
        notes[trip_id] = (
            f"timestamp rewind dropped rows (inventory jumps {jumps}, kept {kept} of {source_rows})"
        )
    return notes


def classify_spacing(median_s: float | None, *, max_1hz_s: float = HZ_SLICE_MAX_SPACING_S) -> str:
    if median_s is not None and median_s <= max_1hz_s:
        return SLICE_1HZ
    return SLICE_SPARSE


def interval_reason(
    trip_id: str,
    median_s: float | None,
    *,
    slice_name: str,
    rewind_note: str | None,
    max_1hz_s: float = HZ_SLICE_MAX_SPACING_S,
) -> str:
    if median_s is None:
        spacing = "unique-fix median spacing unknown (fewer than 2 unique fixes)"
    elif slice_name == SLICE_1HZ:
        spacing = f"trip unique-fix median spacing {median_s:.3f} s (<= {max_1hz_s} s)"
    else:
        spacing = f"trip unique-fix median spacing {median_s:.3f} s (> {max_1hz_s} s)"
    if rewind_note:
        return f"{spacing}. {rewind_note}"
    return spacing


def slice_interval_ids(
    interval_ids: Sequence[str],
    spacing_by_trip: Mapping[str, float | None],
    *,
    max_1hz_s: float = HZ_SLICE_MAX_SPACING_S,
) -> dict[str, tuple[str, ...]]:
    hz: list[str] = []
    sparse: list[str] = []
    seen: set[str] = set()
    for interval_id in interval_ids:
        if interval_id in seen:
            continue
        seen.add(interval_id)
        trip_id = interval_id.split(":", 1)[0]
        name = classify_spacing(spacing_by_trip.get(trip_id), max_1hz_s=max_1hz_s)
        if name == SLICE_1HZ:
            hz.append(interval_id)
        else:
            sparse.append(interval_id)
    return {SLICE_1HZ: tuple(hz), SLICE_SPARSE: tuple(sparse)}


def summarize_system_rows(rows: Sequence[Mapping[str, str]]) -> dict[str, float | int]:
    drifts: list[float] = []
    endpoints: list[float] = []
    for row in rows:
        endpoints.append(float(row["endpoint_error_m"]))
        raw = row.get("drift_ratio") or ""
        if raw != "":
            drifts.append(float(raw))
    out: dict[str, float | int] = {
        "n": len(rows),
        "endpoint_p50_m": median(endpoints) if endpoints else float("nan"),
    }
    if drifts:
        out["drift_ratio_p50"] = median(drifts)
    return out


def slice_tables(
    rows: Sequence[Mapping[str, str]],
    spacing_by_trip: Mapping[str, float | None],
    *,
    rewind_notes: Mapping[str, str] | None = None,
    max_1hz_s: float = HZ_SLICE_MAX_SPACING_S,
) -> dict:
    """Build 1 Hz and sparse summaries from an existing per-interval metrics table."""

    notes = dict(rewind_notes or {})
    ids: list[str] = []
    seen: set[str] = set()
    for row in rows:
        interval_id = row["interval_id"]
        if interval_id in seen:
            continue
        seen.add(interval_id)
        ids.append(interval_id)
    grouped = slice_interval_ids(ids, spacing_by_trip, max_1hz_s=max_1hz_s)
    systems = list(dict.fromkeys(row["system"] for row in rows))
    by_id_system: dict[tuple[str, str], Mapping[str, str]] = {}
    for row in rows:
        by_id_system[(row["interval_id"], row["system"])] = row

    def _member_rows(interval_ids: Sequence[str]) -> list[dict]:
        members = []
        for interval_id in interval_ids:
            trip_id = interval_id.split(":", 1)[0]
            median_s = spacing_by_trip.get(trip_id)
            slice_name = classify_spacing(median_s, max_1hz_s=max_1hz_s)
            members.append(
                {
                    "interval_id": interval_id,
                    "trip_id": trip_id,
                    "unique_fix_median_s": median_s,
                    "slice": slice_name,
                    "reason": interval_reason(
                        trip_id,
                        median_s,
                        slice_name=slice_name,
                        rewind_note=notes.get(trip_id),
                        max_1hz_s=max_1hz_s,
                    ),
                }
            )
        return members

    tables: dict[str, dict] = {}
    for name, interval_ids in grouped.items():
        system_rows: dict[str, dict[str, float | int]] = {}
        for system in systems:
            selected = [
                by_id_system[(interval_id, system)]
                for interval_id in interval_ids
                if (interval_id, system) in by_id_system
            ]
            if selected:
                system_rows[system] = summarize_system_rows(selected)
        reason = (
            f"trip unique-fix spacing median <= {max_1hz_s} s"
            if name == SLICE_1HZ
            else f"trip unique-fix spacing median > {max_1hz_s} s, or spacing unknown"
        )
        tables[name] = {
            "reason": reason,
            "n_intervals": len(interval_ids),
            "interval_ids": list(interval_ids),
            "systems": system_rows,
            "members": _member_rows(interval_ids),
        }
    return {
        "max_1hz_s": max_1hz_s,
        "n_intervals": len(ids),
        "slices": tables,
    }


def _fmt(value: float | None, digits: int) -> str:
    if value is None:
        return "n/a"
    number = float(value)
    if math.isnan(number):  # NaN
        return "n/a"
    return f"{number:.{digits}f}"


def format_slices_markdown(
    tables: Mapping,
    *,
    source: str,
    metrics_date: str,
    written_date: str,
) -> str:
    lines = [
        "# IO-VNBD screening slices",
        "",
        f"Source: `{source}`",
        f"Metrics file date: {metrics_date}",
        f"Slice tables written: {written_date}",
        "",
        "The official 35-interval table is unchanged. These slices do not drop rows from that denominator.",
        "Drift p50 and endpoint p50 are the median of the slice. n is the number of intervals in the slice that have that system row.",
        "",
    ]
    labels = {
        SLICE_1HZ: "1 Hz slice",
        SLICE_SPARSE: "sparse truth slice",
    }
    for key in (SLICE_1HZ, SLICE_SPARSE):
        block = tables["slices"][key]
        lines.append(f"## {labels[key]}")
        lines.append("")
        lines.append(f"Reason: {block['reason']}.")
        lines.append("")
        lines.append("| System | drift p50 | endpoint p50 m | n | reason |")
        lines.append("|---|---:|---:|---:|---|")
        for system, stats in block["systems"].items():
            lines.append(
                f"| {system} | {_fmt(stats.get('drift_ratio_p50'), 4)} | "
                f"{_fmt(stats.get('endpoint_p50_m'), 2)} | {stats['n']} | {block['reason']} |"
            )
        lines.append("")
        lines.append("| interval_id | unique-fix median s | reason |")
        lines.append("|---|---:|---|")
        for member in block["members"]:
            spacing = member["unique_fix_median_s"]
            spacing_txt = "n/a" if spacing is None else f"{float(spacing):.3f}"
            lines.append(f"| {member['interval_id']} | {spacing_txt} | {member['reason']} |")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def write_slices_markdown(path: Path, markdown: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(markdown)


def file_date_iso(path: Path) -> str:
    return datetime.fromtimestamp(path.stat().st_mtime, tz=UTC).date().isoformat()


def build_slices_from_screening_dir(
    screening_dir: Path,
    *,
    csv_name: str = "metrics_per_interval.csv",
    truth_dir: Path | None = None,
    inventory_path: Path | None = None,
    spacing_by_trip: Mapping[str, float | None] | None = None,
) -> tuple[dict, str]:
    csv_path = screening_dir / csv_name
    if not csv_path.is_file():
        raise FileNotFoundError(csv_path)
    rows = interval_rows_from_csv(csv_path)
    truth = truth_dir if truth_dir is not None else screening_dir / "kotlin_replay" / "truth"
    spacing = spacing_from_truth_dir(truth)
    if spacing_by_trip:
        spacing.update(spacing_by_trip)
    inventory = inventory_path if inventory_path is not None else screening_dir / "data_inventory.json"
    rewind = rewind_notes_from_inventory(inventory)
    tables = slice_tables(rows, spacing, rewind_notes=rewind)
    markdown = format_slices_markdown(
        tables,
        source=str(csv_path),
        metrics_date=file_date_iso(csv_path),
        written_date=datetime.now(UTC).date().isoformat(),
    )
    return tables, markdown


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Write 1 Hz and sparse IO-VNBD slice tables")
    parser.add_argument("--dir", type=Path, default=Path("results/io_vnbd_screening_v1"))
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)
    screening_dir = args.dir
    csv_path = screening_dir / "metrics_per_interval.csv"
    if not csv_path.is_file():
        print(f"missing per-interval CSV: {csv_path}")
        return 1
    tables, markdown = build_slices_from_screening_dir(screening_dir)
    out = args.out if args.out is not None else screening_dir / "slices.md"
    write_slices_markdown(out, markdown)
    print(
        json.dumps(
            {
                "out": str(out),
                "source": str(csv_path),
                "n_intervals": tables["n_intervals"],
                "n_1hz": tables["slices"][SLICE_1HZ]["n_intervals"],
                "n_sparse": tables["slices"][SLICE_SPARSE]["n_intervals"],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
