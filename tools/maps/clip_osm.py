#!/usr/bin/env python3
"""Clip an OSM XML extract to any WGS84 bbox.

PBF input is converted with osmium if that tool is on PATH. The Kotlin
OsmGraphLoader also reads PBF directly. City names are labels only.

Example:

    python3 tools/maps/clip_osm.py \\
        --input extract.osm --south 40.70 --west -74.05 \\
        --north 40.85 --east -73.90 --out data/area-packs/manhattan/graph.osm.xml
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="OSM XML or PBF")
    parser.add_argument("--south", type=float, required=True)
    parser.add_argument("--west", type=float, required=True)
    parser.add_argument("--north", type=float, required=True)
    parser.add_argument("--east", type=float, required=True)
    parser.add_argument("--out", required=True, type=Path)
    return parser.parse_args()


def validate_bbox(south: float, west: float, north: float, east: float) -> None:
    if not -90.0 <= south < north <= 90.0:
        raise SystemExit("south must be less than north, both in [-90, 90]")
    if not -180.0 <= west < east <= 180.0:
        raise SystemExit("west must be less than east, both in [-180, 180]; no antimeridian wrap")


def as_xml(path: Path) -> Path:
    name = path.name.lower()
    if name.endswith(".pbf"):
        osmium = shutil.which("osmium")
        if osmium is None:
            raise SystemExit(
                "PBF clip needs osmium on PATH, or pass OSM XML. "
                "Kotlin OsmGraphLoader.load also reads PBF without this script."
            )
        dest = path.with_suffix(".clip.xml")
        subprocess.run(
            [osmium, "cat", str(path), "-f", "osm", "-o", str(dest), "--overwrite"],
            check=True,
        )
        return dest
    return path


def in_box(lat: float, lon: float, south: float, west: float, north: float, east: float) -> bool:
    return south <= lat <= north and west <= lon <= east


def clip_xml(src: Path, dest: Path, south: float, west: float, north: float, east: float) -> int:
    tree = ET.parse(src)
    root = tree.getroot()
    nodes = {}
    for node in list(root.findall("node")):
        lat = float(node.attrib["lat"])
        lon = float(node.attrib["lon"])
        node_id = node.attrib["id"]
        if in_box(lat, lon, south, west, north, east):
            nodes[node_id] = node
        else:
            root.remove(node)
    kept_ways = 0
    for way in list(root.findall("way")):
        refs = [nd.attrib["ref"] for nd in way.findall("nd")]
        if any(ref in nodes for ref in refs):
            kept_ways += 1
        else:
            root.remove(way)
    for rel in list(root.findall("relation")):
        root.remove(rel)
    dest.parent.mkdir(parents=True, exist_ok=True)
    tree.write(dest, encoding="UTF-8", xml_declaration=True)
    return kept_ways


def main() -> int:
    args = parse_args()
    validate_bbox(args.south, args.west, args.north, args.east)
    xml_path = as_xml(args.input)
    kept = clip_xml(xml_path, args.out, args.south, args.west, args.north, args.east)
    print(f"wrote {args.out} ({kept} ways touching the bbox)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
