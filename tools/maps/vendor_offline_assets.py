#!/usr/bin/env python3
"""Download OpenFreeMap glyphs and sprites into an area pack.

MapLibre Native does not resolve relative style URLs. The Android style
builder rewrites glyphs and sprite to file:// paths at install time. This
script only places the files the pack is expected to contain.

Does not touch tile.openstreetmap.org.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

FONTS = ("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic")
# Latin through Arabic, plus Devanagari (2304) already in 0-4095, then punctuation.
RANGE_STARTS = list(range(0, 4096, 256)) + [8192, 8448]
SPRITE_BASE = "https://tiles.openfreemap.org/sprites/ofm_f384/ofm"
SPRITE_FILES = ("ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png")
FONT_HOST = "https://tiles.openfreemap.org/fonts"
UA = "DriftZero/0.1 (area pack glyph vendor)"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", required=True, help="area pack directory")
    parser.add_argument(
        "--cache",
        default=None,
        help="download cache (default: $HOME/.cache/driftzero/map-assets)",
    )
    return parser.parse_args()


def cache_dir(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit)
    return Path.home() / ".cache" / "driftzero" / "map-assets"


def fetch(url: str, dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and dest.stat().st_size > 0:
        return True
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return False
        raise
    dest.write_bytes(data)
    return dest.stat().st_size > 0


def vendor_glyphs(cache: Path, pack: Path) -> int:
    copied = 0
    for font in FONTS:
        for start in RANGE_STARTS:
            end = start + 255
            name = f"{start}-{end}.pbf"
            encoded = urllib.parse.quote(font)
            url = f"{FONT_HOST}/{encoded}/{name}"
            cached = cache / "glyphs" / font / name
            if not fetch(url, cached):
                continue
            dest = pack / "glyphs" / font / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(cached.read_bytes())
            copied += 1
    return copied


def vendor_sprites(cache: Path, pack: Path) -> int:
    copied = 0
    dest_dir = pack / "sprites"
    dest_dir.mkdir(parents=True, exist_ok=True)
    prefix = SPRITE_BASE[: SPRITE_BASE.rfind("/") + 1]
    for name in SPRITE_FILES:
        url = prefix + name
        cached = cache / "sprites" / name
        if not fetch(url, cached):
            continue
        dest = dest_dir / name
        dest.write_bytes(cached.read_bytes())
        copied += 1
    return copied


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def refresh_manifest(pack: Path) -> None:
    manifest_path = pack / "manifest.json"
    if not manifest_path.is_file():
        return
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    files: dict[str, dict[str, int | str]] = {}
    total = 0
    names = [
        "tiles.pmtiles",
        "graph.osm.pbf",
        "graph.bin",
        "style.json",
        "style-day.json",
        "style-night.json",
    ]
    for name in names:
        path = pack / name
        if not path.is_file():
            continue
        size = path.stat().st_size
        total += size
        files[name] = {"sha256": sha256(path), "bytes": size}
    for folder in ("glyphs", "sprites"):
        root = pack / folder
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(pack).as_posix()
            size = path.stat().st_size
            total += size
            files[rel] = {"sha256": sha256(path), "bytes": size}
    manifest["files"] = files
    manifest["bytes"] = total
    if "tiles.pmtiles" in files:
        manifest["pmtilesSha256"] = files["tiles.pmtiles"]["sha256"]
    if "graph.bin" in files:
        manifest["graphSha256"] = files["graph.bin"]["sha256"]
    elif "graph.osm.pbf" in files:
        manifest["graphSha256"] = files["graph.osm.pbf"]["sha256"]
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"updated {manifest_path} bytes {total}")


def main() -> int:
    args = parse_args()
    pack = Path(args.pack)
    if not pack.is_dir():
        print(f"pack directory missing: {pack}", file=sys.stderr)
        return 1
    cache = cache_dir(args.cache)
    cache.mkdir(parents=True, exist_ok=True)
    glyphs = vendor_glyphs(cache, pack)
    sprites = vendor_sprites(cache, pack)
    print(f"glyphs {glyphs} files")
    print(f"sprites {sprites} files")
    if glyphs == 0:
        print("no glyph files downloaded", file=sys.stderr)
        return 1
    refresh_manifest(pack)
    return 0


if __name__ == "__main__":
    sys.exit(main())
