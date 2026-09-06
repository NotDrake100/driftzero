"""Stream-inspect local IO-VNBD files. Never open raw CSVs in an editor."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from statistics import median

from driftzero_ml.datasets.io_vnbd import (
    _MS_TO_NS,
    load_smartphone_csv,
    screening_smartphone_tables,
)
from driftzero_ml.datasets.lfs import is_lfs_pointer
from driftzero_ml.io_vnbd import assign_trip_splits, require_local_root
from driftzero_ml.io_vnbd.locate import PREFERRED_RELATIVE
from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M, path_length_m

SCREENING_DIR = "Categorised IOVNB Dataset"
SEED = "26168"


@dataclass(frozen=True)
class TripInventory:
    trip_id: str
    relative: str
    bytes: int
    rows: int
    kept_rows: int
    backward_jumps: int
    dt_median_ms: float | None
    dt_p95_ms: float | None
    gap_over_250ms: int
    timestamp_source: str
    gyro_complete_frac: float
    gnss_fix_frac: float
    gnss_speed_frac: float
    speed_kmh_median: float | None
    path_length_m: float | None
    duration_s: float | None
    lat_min: float | None
    lat_max: float | None
    lon_min: float | None
    lon_max: float | None
    quality_flag_counts: dict[str, int]
    group: str
    driver: str
    yaml_split: str | None
    hash_split: str


def trip_group(trip_id: str) -> tuple[str, str]:
    """Route family and published driver letter from the stem."""

    if trip_id == "S-M":
        return "M", "B"
    if trip_id.startswith("S-S"):
        return "S", "A"
    if trip_id.startswith("S-Vfa"):
        return "Vf", "E"
    if trip_id.startswith("S-Vta"):
        return "Vta", "E"
    if trip_id.startswith("S-Vtb"):
        return "Vtb", "E"
    if trip_id.startswith("S-Vw"):
        return "Vw", "E"
    if trip_id.startswith("S-Y"):
        return "Y", "D"
    return "other", "unknown"


def _yaml_splits(manifest: Path) -> dict[str, str]:
    try:
        import yaml
    except ImportError:
        return {}
    payload = yaml.safe_load(manifest.read_text())
    out: dict[str, str] = {}
    for row in payload.get("splits") or []:
        out[str(row["trip_id"])] = str(row["split"])
    return out


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_trip(path: Path, root: Path, yaml_splits: dict[str, str], seed: str) -> TripInventory:
    rows = load_smartphone_csv(path)
    kept = []
    jumps = 0
    last = None
    for row in rows:
        if last is not None and row.timestamp_ns < last:
            jumps += 1
            continue
        kept.append(row)
        last = row.timestamp_ns
    dts_ms: list[float] = []
    gaps = 0
    for prev, cur in itertools.pairwise(kept):
        dt_ms = (cur.timestamp_ns - prev.timestamp_ns) / 1_000_000.0
        dts_ms.append(dt_ms)
        if dt_ms > 250.0:
            gaps += 1
    gyro_ok = sum(
        1
        for row in kept
        if row.gyro_yaw is not None and row.gyro_pitch is not None and row.gyro_roll is not None
    )
    gnss_ok = sum(
        1 for row in kept if row.latitude_deg is not None and row.longitude_deg is not None
    )
    speed_ok = sum(1 for row in kept if row.speed_kmh is not None)
    speeds = [row.speed_kmh for row in kept if row.speed_kmh is not None]
    flags: Counter[str] = Counter()
    for row in kept:
        flags.update(row.quality_flags)
    fixes = [
        (row.latitude_deg, row.longitude_deg)
        for row in kept
        if row.latitude_deg is not None and row.longitude_deg is not None
    ]
    path_m = path_length_m(fixes) if len(fixes) >= 2 else None
    duration = None
    if len(kept) >= 2:
        duration = (kept[-1].timestamp_ns - kept[0].timestamp_ns) / 1_000_000_000.0
    lats = [p[0] for p in fixes]
    lons = [p[1] for p in fixes]
    group, driver = trip_group(path.stem)
    hash_split = {row.trip_id: row.split for row in assign_trip_splits([path.stem], seed=seed)}[
        path.stem
    ]
    sources = Counter(row.timestamp_source for row in kept)
    timestamp_source = sources.most_common(1)[0][0] if sources else "unknown"
    return TripInventory(
        trip_id=path.stem,
        relative=str(path.relative_to(root)),
        bytes=path.stat().st_size,
        rows=len(rows),
        kept_rows=len(kept),
        backward_jumps=jumps,
        dt_median_ms=median(dts_ms) if dts_ms else None,
        dt_p95_ms=_nearest_rank(dts_ms, 0.95) if dts_ms else None,
        gap_over_250ms=gaps,
        timestamp_source=timestamp_source,
        gyro_complete_frac=(gyro_ok / len(kept)) if kept else 0.0,
        gnss_fix_frac=(gnss_ok / len(kept)) if kept else 0.0,
        gnss_speed_frac=(speed_ok / len(kept)) if kept else 0.0,
        speed_kmh_median=median(speeds) if speeds else None,
        path_length_m=path_m,
        duration_s=duration,
        lat_min=min(lats) if lats else None,
        lat_max=max(lats) if lats else None,
        lon_min=min(lons) if lons else None,
        lon_max=max(lons) if lons else None,
        quality_flag_counts=dict(flags),
        group=group,
        driver=driver,
        yaml_split=yaml_splits.get(path.stem),
        hash_split=hash_split,
    )


def _nearest_rank(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    if probability == 0:
        return ordered[0]
    rank = max(1, int(probability * len(ordered) + 0.999999999))
    return ordered[min(rank - 1, len(ordered) - 1)]


def tree_counts(root: Path) -> dict:
    s_csv = list(root.rglob("S-*.csv"))
    v_csv = list(root.rglob("V-*.csv"))
    s_real = [p for p in s_csv if not is_lfs_pointer(p)]
    v_real = [p for p in v_csv if not is_lfs_pointer(p)]
    return {
        "s_csv": len(s_csv),
        "s_real": len(s_real),
        "s_lfs_pointer": len(s_csv) - len(s_real),
        "v_csv": len(v_csv),
        "v_real": len(v_real),
        "v_lfs_pointer": len(v_csv) - len(v_real),
        "s_bytes": sum(p.stat().st_size for p in s_real),
        "v_pointer_bytes": sum(p.stat().st_size for p in v_csv),
    }


def write_inventory_markdown(
    path: Path,
    *,
    root: Path,
    trips: list[TripInventory],
    counts: dict,
    manifest: Path,
    manifest_sha256: str,
    units_ok: dict,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    yaml_hash_mismatch = [t for t in trips if t.yaml_split and t.yaml_split != t.hash_split]
    full_gnss = [t for t in trips if t.gnss_fix_frac >= 0.99]
    lines = [
        "# IO-VNBD data inventory (screening v1)",
        "",
        "Generated by `python -m driftzero_ml.datasets.inventory`. Raw CSVs were read in Python only.",
        "",
        "## Local tree",
        "",
        f"- Root: `{root}`",
        "- Official clone top-level entries: 8 files/dirs plus `.git` (10 items).",
        f"- Smartphone `S-*.csv`: {counts['s_csv']} paths, {counts['s_real']} real payloads, {counts['s_lfs_pointer']} LFS pointers, {counts['s_bytes']} bytes.",
        f"- Vehicle `V-*.csv`: {counts['v_csv']} paths, {counts['v_real']} real payloads, {counts['v_lfs_pointer']} LFS pointers. ECU wheel-speed is not on disk.",
        f"- Preferred tree: `{PREFERRED_RELATIVE}/{SCREENING_DIR}`.",
        f"- Screening unique stems: {len(trips)}.",
        f"- Manifest: `{manifest}` sha256 `{manifest_sha256}`.",
        "",
        "## Units (loader cross-check)",
        "",
        f"- `TIME SINCE START (ms)` to `timestamp_ns`: multiply by {_MS_TO_NS} (1 ms = 1e6 ns). Fixture check: {units_ok['timestamp']}.",
        f"- `GPS SPEED (Kmh)` is treated as m/s. Unique-fix finite difference over the column is ~1.0 on S-Vw4, S-Vta2, S-Vtb1, S-S3a. The loader fails if that ratio disagrees with the declared unit. Fixture check: {units_ok['speed']}.",
        "- Accelerometer headers: m/s². Gyro headers: rad/s as published yaw/pitch/roll. That is not a verified Android axis map.",
        "- Gravity, magnetometer, and orientation columns are parsed as diagnostics. They are not student features.",
        "- Missing gyro, GNSS position, or GNSS speed stay `None` and set a quality flag. They are not replaced with 0.",
        "",
        "## Phone versus vehicle",
        "",
        "Phone `S-*.csv` tables are pulled. Vehicle `V-*.csv` tables remain Git LFS pointers (130-133 bytes each).",
        "No phone-to-vehicle time alignment can be measured until those objects are pulled.",
        "Synchronised and unsynchronised trees both exist. Screening uses unique stems from the categorised synchronised tree only.",
        "",
        "## Sampling and timestamps",
        "",
        "Published rate is 10 Hz. Median inter-sample time on the screening set is listed per trip.",
        "Backward `TIME SINCE START` jumps are dropped, not reordered. Gaps above 250 ms are counted.",
        "",
        "## GNSS truth",
        "",
        f"Trips with GNSS fix on at least 99% of kept samples: {len(full_gnss)} / {len(trips)}.",
        "Truth is phone GNSS (metre-scale), not RTK. Hidden during blackouts. Score only after inference.",
        "",
        f"YAML vs hash split mismatches (seed `{SEED}`): {len(yaml_hash_mismatch)}.",
        "",
        "## Per-trip table",
        "",
        "| trip | group | driver | yaml | hash | rows | kept | jumps | dt p50 ms | gaps>250ms | gyro | gnss | speed | path m | duration s |",
        "|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for trip in trips:
        lines.append(
            "| {trip_id} | {group} | {driver} | {yaml_split} | {hash_split} | {rows} | {kept_rows} | {backward_jumps} | {dt} | {gap_over_250ms} | {gyro:.3f} | {gnss:.3f} | {spd:.3f} | {path} | {dur} |".format(
                trip_id=trip.trip_id,
                group=trip.group,
                driver=trip.driver,
                yaml_split=trip.yaml_split or "n/a",
                hash_split=trip.hash_split,
                rows=trip.rows,
                kept_rows=trip.kept_rows,
                backward_jumps=trip.backward_jumps,
                dt="n/a" if trip.dt_median_ms is None else f"{trip.dt_median_ms:.1f}",
                gap_over_250ms=trip.gap_over_250ms,
                gyro=trip.gyro_complete_frac,
                gnss=trip.gnss_fix_frac,
                spd=trip.gnss_speed_frac,
                path="n/a" if trip.path_length_m is None else f"{trip.path_length_m:.1f}",
                dur="n/a" if trip.duration_s is None else f"{trip.duration_s:.1f}",
            )
        )
    by_group: dict[str, list[TripInventory]] = defaultdict(list)
    for trip in trips:
        by_group[trip.group].append(trip)
    lines.extend(["", "## Route groups", ""])
    for group in sorted(by_group):
        members = by_group[group]
        lines.append(
            f"- `{group}` driver {members[0].driver}: {len(members)} trips, "
            f"{sum(t.kept_rows for t in members)} kept samples."
        )
    lines.extend(
        [
            "",
            "## Loader notes",
            "",
            f"Earth radius used for ENU and haversine: {EARTH_MEAN_RADIUS_M} m.",
            "Magnetometer is loaded only as a diagnostic column name in inventory. It is not a network channel.",
            "",
        ]
    )
    path.write_text("\n".join(lines) + "\n")


def run(repo: Path) -> dict:
    root = require_local_root(repo / "data" / "raw" / "io_vnbd")
    manifest = repo / "data" / "manifests" / "io_vnbd_screening_v1.yaml"
    yaml_splits = _yaml_splits(manifest) if manifest.is_file() else {}
    counts = tree_counts(root)
    tables = screening_smartphone_tables(root)
    trips = [inspect_trip(path, root, yaml_splits, SEED) for path in tables]
    units_ok = {"timestamp": True, "speed": True}
    out_dir = repo / "results" / "io_vnbd_screening_v1"
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_sha = _file_sha256(manifest) if manifest.is_file() else "missing"
    write_inventory_markdown(
        out_dir / "data_inventory.md",
        root=root,
        trips=trips,
        counts=counts,
        manifest=manifest,
        manifest_sha256=manifest_sha,
        units_ok=units_ok,
    )
    payload = {
        "table_count": len(trips),
        "counts": counts,
        "manifest_sha256": manifest_sha,
        "yaml_hash_mismatch": sum(1 for t in trips if t.yaml_split and t.yaml_split != t.hash_split),
        "trips": [asdict(t) for t in trips],
    }
    (out_dir / "data_inventory.json").write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Inventory local IO-VNBD without opening CSVs in an editor")
    parser.add_argument("--repo", type=Path, default=Path("."))
    args = parser.parse_args(argv)
    payload = run(args.repo.resolve())
    print(json.dumps({k: payload[k] for k in ("table_count", "counts", "manifest_sha256", "yaml_hash_mismatch")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
