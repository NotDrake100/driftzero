#!/usr/bin/env python3
"""Queue an offline area pack for an arbitrary WGS84 bbox.

This does not download tiles from tile.openstreetmap.org. It writes a generic
manifest the Android AreaPackStore can sideload, then prints the Planetiler /
osmium commands that turn the bbox into tiles.pmtiles plus graph.bin.

Example (not the only region):

    python3 tools/maps/pack_bbox.py \\
        --south 6.5546 --west 68.1114 --north 35.6745 --east 97.3956 \\
        --id example-india
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKS = ROOT / "data" / "area-packs"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--south", type=float, required=True, help="south latitude, degrees")
    parser.add_argument("--west", type=float, required=True, help="west longitude, degrees")
    parser.add_argument("--north", type=float, required=True, help="north latitude, degrees")
    parser.add_argument("--east", type=float, required=True, help="east longitude, degrees")
    parser.add_argument("--id", required=True, help="opaque pack id, lowercase slug")
    parser.add_argument("--label", default=None, help="optional display label")
    parser.add_argument(
        "--osm-source",
        default=None,
        help="optional OSM extract URL, for example a Geofabrik region",
    )
    return parser.parse_args()


def validate_bbox(south: float, west: float, north: float, east: float) -> None:
    if not -90.0 <= south < north <= 90.0:
        raise SystemExit("south must be less than north, both in [-90, 90]")
    if not -180.0 <= west < east <= 180.0:
        raise SystemExit("west must be less than east, both in [-180, 180]; no antimeridian wrap")


def validate_id(pack_id: str) -> None:
    if not pack_id or pack_id[0] not in "abcdefghijklmnopqrstuvwxyz0123456789":
        raise SystemExit("id must start with a lowercase letter or digit")
    allowed = set("abcdefghijklmnopqrstuvwxyz0123456789._-")
    if any(ch not in allowed for ch in pack_id) or len(pack_id) > 64:
        raise SystemExit("id must match [a-z0-9][a-z0-9._-]{0,63}")


def main() -> int:
    args = parse_args()
    validate_bbox(args.south, args.west, args.north, args.east)
    validate_id(args.id)
    dest = PACKS / args.id
    dest.mkdir(parents=True, exist_ok=True)
    manifest = {
        "id": args.id,
        "south": args.south,
        "west": args.west,
        "north": args.north,
        "east": args.east,
        "schemaVersion": 1,
        "state": "queued",
    }
    if args.label:
        manifest["label"] = args.label
    if args.osm_source:
        manifest["osmSource"] = args.osm_source
    (dest / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {dest / 'manifest.json'}")
    print("next, from a pinned OSM PBF (not the public OSM tile server):")
    print(
        "  osmium extract --bbox "
        f"{args.west},{args.south},{args.east},{args.north} "
        "planet.osm.pbf -o extract.osm.pbf"
    )
    print(
        "  planetiler --osm-path=extract.osm.pbf "
        f"--bounds={args.west},{args.south},{args.east},{args.north} "
        f"--output={dest / 'tiles.pmtiles'}"
    )
    print(
        "  python3 tools/maps/clip_osm.py --input extract.osm.pbf "
        f"--south {args.south} --west {args.west} --north {args.north} --east {args.east} "
        f"--out {dest / 'graph.osm.xml'}"
    )
    print("  # or keep extract.osm.pbf as graph.osm.pbf; OsmGraphLoader reads XML or PBF")
    print(f"  # copy a MapLibre style.json into {dest / 'style.json'} if you want local rendering")
    print("then sideload that directory with AreaPackStore.installSideload")
    return 0


if __name__ == "__main__":
    sys.exit(main())
