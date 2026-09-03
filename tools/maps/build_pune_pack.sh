#!/usr/bin/env bash
# Build an offline area pack (PMTiles + highway PBF + OpenFreeMap styles)
# from a Geofabrik PBF and a WGS84 bbox. City names are labels only.
#
# Pinned tools:
#   Planetiler 0.10.2 (jar; needs Java 21+, class file 65)
#   osmium-tool 1.19.1 / libosmium 2.23.1
#
# Example (Pune core) is in data/area-packs/README.md, not in this logic.
set -euo pipefail

PLANETILER_VERSION="0.10.2"
PLANETILER_SHA256="f310bd0413e2e4512b27f4046d418664e8e1d3bf31603c2a70e23de06c167e4d"
OSMIUM_VERSION="1.19.1"

SOUTH=""
WEST=""
NORTH=""
EAST=""
PACK_ID=""
LABEL=""
OSM_URL=""
OSM_SOURCE_PAGE=""
EXPECTED_MD5=""
CACHE="${DRIFTZERO_CACHE:-$HOME/.cache/driftzero}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACKS="$ROOT/data/area-packs"

usage() {
  cat <<'EOF'
Usage:
  tools/maps/build_pune_pack.sh \
    --south LAT --west LON --north LAT --east LON \
    --id SLUG \
    --osm-url URL \
    [--label TEXT] \
    [--osm-source-page URL] \
    [--expected-md5 HEX] \
    [--cache DIR]

Downloads a Geofabrik PBF into CACHE/osm, extracts the bbox with osmium,
filters highway=* ways for OsmGraphLoader, runs Planetiler 0.10.2 into
tiles.pmtiles (OpenMapTiles schema), and writes style JSON that points
the vector source at pmtiles://tiles.pmtiles. Vendors OpenFreeMap glyphs
and sprites into glyphs/ and sprites/ so labels can render offline. The
app rewrites pmtiles:// to an absolute file:// path at install time.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --south) SOUTH="$2"; shift 2 ;;
    --west) WEST="$2"; shift 2 ;;
    --north) NORTH="$2"; shift 2 ;;
    --east) EAST="$2"; shift 2 ;;
    --id) PACK_ID="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --osm-url) OSM_URL="$2"; shift 2 ;;
    --osm-source-page) OSM_SOURCE_PAGE="$2"; shift 2 ;;
    --expected-md5) EXPECTED_MD5="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ -z "$SOUTH" || -z "$WEST" || -z "$NORTH" || -z "$EAST" || -z "$PACK_ID" || -z "$OSM_URL" ]]; then
  echo "south, west, north, east, id, and osm-url are required" >&2
  usage >&2
  exit 1
fi

python3 - "$SOUTH" "$WEST" "$NORTH" "$EAST" "$PACK_ID" <<'PY'
import sys
south, west, north, east = map(float, sys.argv[1:5])
pack_id = sys.argv[5]
if not (-90.0 <= south < north <= 90.0):
    raise SystemExit("south must be less than north, both in [-90, 90]")
if not (-180.0 <= west < east <= 180.0):
    raise SystemExit("west must be less than east, both in [-180, 180]; no antimeridian wrap")
if not pack_id or pack_id[0] not in "abcdefghijklmnopqrstuvwxyz0123456789":
    raise SystemExit("id must start with a lowercase letter or digit")
allowed = set("abcdefghijklmnopqrstuvwxyz0123456789._-")
if any(ch not in allowed for ch in pack_id) or len(pack_id) > 64:
    raise SystemExit("id must match [a-z0-9][a-z0-9._-]{0,63}")
PY

OSMIUM="${OSMIUM:-$CACHE/tools/osmium}"
if [[ ! -x "$OSMIUM" ]]; then
  if command -v osmium >/dev/null 2>&1; then
    OSMIUM="$(command -v osmium)"
  else
    echo "osmium $OSMIUM_VERSION not on PATH. Install osmium-tool or place a binary at $CACHE/tools/osmium" >&2
    exit 1
  fi
fi

JAVA_BIN=""
if /usr/libexec/java_home -v 21+ >/dev/null 2>&1; then
  JAVA_BIN="$(/usr/libexec/java_home -v 21+)/bin/java"
else
  echo "Planetiler $PLANETILER_VERSION needs Java 21+ (class file 65). Java 17 cannot run this jar." >&2
  exit 1
fi

if [[ -d "$CACHE/tools/lib" ]]; then
  export DYLD_LIBRARY_PATH="$CACHE/tools/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
fi

mkdir -p "$CACHE/osm" "$CACHE/tools" "$CACHE/styles" "$CACHE/planetiler/sources" "$CACHE/planetiler/tmp"
PACK="$PACKS/$PACK_ID"
mkdir -p "$PACK"

UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
PBF_NAME="$(basename "${OSM_URL%%\?*}")"
SOURCE_PBF="$CACHE/osm/$PBF_NAME"
JAR="$CACHE/tools/planetiler-${PLANETILER_VERSION}.jar"

if [[ ! -f "$JAR" ]]; then
  curl -fL --retry 3 --retry-delay 2 -o "$JAR" \
    "https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"
fi
python3 - "$JAR" "$PLANETILER_SHA256" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
want = sys.argv[2]
got = hashlib.sha256(p.read_bytes()).hexdigest()
if got != want:
    raise SystemExit(f"planetiler sha256 mismatch: {got} != {want}")
PY

if [[ ! -f "$SOURCE_PBF" ]]; then
  curl -fL --retry 5 --retry-delay 3 -C - -A "$UA" -o "$SOURCE_PBF" "$OSM_URL"
fi
if [[ -n "$EXPECTED_MD5" ]]; then
  python3 - "$SOURCE_PBF" "$EXPECTED_MD5" <<'PY'
import hashlib, pathlib, sys
got = hashlib.md5(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
if got != sys.argv[2]:
    raise SystemExit(f"Geofabrik MD5 mismatch: {got} != {sys.argv[2]}")
print(f"source md5 {got}")
PY
fi

SNAPSHOT="$("$OSMIUM" fileinfo -g header.option.osmosis_replication_timestamp "$SOURCE_PBF" || true)"
if [[ -z "$SNAPSHOT" ]]; then
  SNAPSHOT="$("$OSMIUM" fileinfo -g header.option.timestamp "$SOURCE_PBF" || true)"
fi

EXTRACT="$CACHE/osm/extract-${SOUTH}-${WEST}-${NORTH}-${EAST}.osm.pbf"
"$OSMIUM" extract \
  --bbox "${WEST},${SOUTH},${EAST},${NORTH}" \
  --strategy complete_ways \
  --set-bounds \
  --overwrite \
  -o "$EXTRACT" \
  "$SOURCE_PBF"

EXTRACT_BYTES="$(python3 -c "import pathlib; print(pathlib.Path(r'''$EXTRACT''').stat().st_size)")"
if [[ "$EXTRACT_BYTES" -gt $((50 * 1024 * 1024)) ]]; then
  echo "extract is ${EXTRACT_BYTES} bytes; keep the bbox small enough to stay under 50 MB" >&2
  exit 1
fi

"$OSMIUM" tags-filter "$EXTRACT" w/highway --overwrite -o "$PACK/graph.osm.pbf"

JAVA17_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
if [[ -z "$JAVA17_HOME" ]]; then
  echo "graph.bin conversion needs Java 17 for :navigation-core:writeGraphBin" >&2
  exit 1
fi
(
  cd "$ROOT"
  JAVA_HOME="$JAVA17_HOME" ./gradlew :navigation-core:writeGraphBin --quiet --args="--input $PACK/graph.osm.pbf --output $PACK/graph.bin --id $PACK_ID --south $SOUTH --west $WEST --north $NORTH --east $EAST"
)

# OpenMapTiles extras. Do not pull water-polygons-split-3857.zip (about 1 GB of
# ocean coastlines). Inland bboxes use an empty EPSG:3857 shapefile stub.
# Lake centerlines (~77 MB) and Natural Earth (~414 MB) are downloaded only
# when missing. Planetiler then runs with --download=false.
SOURCES="$CACHE/planetiler/sources"
LAKE_URL="https://github.com/acalcutt/osm-lakelines/releases/download/v12/lake_centerline.shp.zip"
NE_URL="https://naciscdn.org/naturalearth/packages/natural_earth_vector.sqlite.zip"
if [[ ! -f "$SOURCES/lake_centerline.shp.zip" ]]; then
  curl -fL --retry 3 --retry-delay 2 -A "$UA" -o "$SOURCES/lake_centerline.shp.zip" "$LAKE_URL"
fi
if [[ ! -f "$SOURCES/natural_earth_vector.sqlite.zip" ]]; then
  curl -fL --retry 3 --retry-delay 2 -A "$UA" -o "$SOURCES/natural_earth_vector.sqlite.zip" "$NE_URL"
fi
python3 - "$SOURCES/water-polygons-split-3857.zip" <<'PY'
"""Write an empty EPSG:3857 water-polygons shapefile zip.

Planetiler's OpenMapTiles profile requires this archive. The real
osmdata.openstreetmap.de file is global ocean coastlines. An inland
bbox does not use those polygons, so a 0-record shapefile is enough.
"""
import pathlib, struct, sys, zipfile

dest = pathlib.Path(sys.argv[1])
if dest.is_file() and dest.stat().st_size <= 2048:
    raise SystemExit(0)

def shp_header() -> bytes:
    # Empty shapefile: 100-byte header, shape type 5 (polygon), 0 records.
    parts = [
        struct.pack(">i", 9994),
        b"\x00" * 20,
        struct.pack(">i", 50),
        struct.pack("<i", 1000),
        struct.pack("<i", 5),
        struct.pack("<4d", 0.0, 0.0, 0.0, 0.0),
        struct.pack("<4d", 0.0, 0.0, 0.0, 0.0),
    ]
    return b"".join(parts)

prj = (
    'PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["WGS 84",DATUM["WGS_1984",'
    'SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],'
    'UNIT["degree",0.0174532925199433]],PROJECTION["Mercator_1SP"],'
    'PARAMETER["central_meridian",0],PARAMETER["scale_factor",1],'
    'PARAMETER["false_easting",0],PARAMETER["false_northing",0],'
    'UNIT["metre",1],AXIS["X",EAST],AXIS["Y",NORTH]]\n'
)
# dBase III header, 0 records, one unused Numeric fid field (width 1).
dbf = (
    b"\x03\x00\x00\x00\x00\x00\x00\x00A\x00\x02"
    + b"\x00" * 21
    + b"fid" + b"\x00" * 8
    + b"N"
    + b"\x00" * 4
    + b"\x01"
    + b"\x00" * 15
    + b"\r"
)

dest.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    prefix = "water-polygons-split-3857/water_polygons"
    zf.writestr(prefix + ".shp", shp_header())
    zf.writestr(prefix + ".shx", shp_header())
    zf.writestr(prefix + ".dbf", dbf)
    zf.writestr(prefix + ".prj", prj)
    zf.writestr(prefix + ".cpg", "UTF-8\n")
print(f"wrote inland water stub {dest} ({dest.stat().st_size} bytes)")
PY

"$JAVA_BIN" -Xmx1g --enable-native-access=ALL-UNNAMED \
  -jar "$JAR" \
  --osm-path="$EXTRACT" \
  --bounds="${WEST},${SOUTH},${EAST},${NORTH}" \
  --output="$PACK/tiles.pmtiles" \
  --download=false \
  --use_wikidata=false \
  --force=true \
  --download_dir="$SOURCES" \
  --tmpdir="$CACHE/planetiler/tmp" \
  --maxzoom=14 \
  --minzoom=0 \
  --lake_centerlines_path="$SOURCES/lake_centerline.shp.zip" \
  --water_polygons_path="$SOURCES/water-polygons-split-3857.zip" \
  --natural_earth_path="$SOURCES/natural_earth_vector.sqlite.zip" \
  --free_water_polygons_after_read=false \
  --free_natural_earth_after_read=false \
  --free_lake_centerlines_after_read=false

LIBERTY="$CACHE/styles/liberty.json"
DARK="$CACHE/styles/dark.json"
if [[ ! -f "$LIBERTY" ]]; then
  curl -fL --retry 3 -o "$LIBERTY" 'https://tiles.openfreemap.org/styles/liberty'
fi
if [[ ! -f "$DARK" ]]; then
  curl -fL --retry 3 -o "$DARK" 'https://tiles.openfreemap.org/styles/dark'
fi

python3 - "$LIBERTY" "$DARK" "$PACK" "$WEST" "$SOUTH" "$EAST" "$NORTH" <<'PY'
import json, pathlib, sys
liberty, dark, pack, west, south, east, north = sys.argv[1:8]
pack = pathlib.Path(pack)
bbox = [float(west), float(south), float(east), float(north)]

def rewrite(src_path: pathlib.Path, dest: pathlib.Path) -> None:
    data = json.loads(src_path.read_text(encoding="utf-8"))
    sources = data.setdefault("sources", {})
    openmaptiles = sources.get("openmaptiles") or {}
    openmaptiles["type"] = "vector"
    openmaptiles["url"] = "pmtiles://tiles.pmtiles"
    openmaptiles["attribution"] = "© OpenStreetMap contributors"
    if "bounds" not in openmaptiles:
        openmaptiles["bounds"] = bbox
    sources["openmaptiles"] = openmaptiles
    dest.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

rewrite(pathlib.Path(liberty), pack / "style-day.json")
rewrite(pathlib.Path(dark), pack / "style-night.json")
(pack / "style.json").write_text((pack / "style-day.json").read_text(encoding="utf-8"), encoding="utf-8")
PY

python3 "$ROOT/tools/maps/vendor_offline_assets.py" --pack "$PACK" --cache "$CACHE/map-assets"

python3 - "$PACK" "$PACK_ID" "$SOUTH" "$WEST" "$NORTH" "$EAST" "$LABEL" "$OSM_SOURCE_PAGE" "$OSM_URL" "$SNAPSHOT" <<'PY'
import hashlib, json, pathlib, sys
from datetime import datetime, timezone

pack = pathlib.Path(sys.argv[1])
pack_id, south, west, north, east = sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]), float(sys.argv[6])
label, source_page, osm_url, snapshot = sys.argv[7], sys.argv[8], sys.argv[9], sys.argv[10]

def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

files = {}
total = 0
named = ("tiles.pmtiles", "graph.bin", "graph.osm.pbf", "style.json", "style-day.json", "style-night.json")
for name in named:
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

manifest = {
    "id": pack_id,
    "south": south,
    "west": west,
    "north": north,
    "east": east,
    "schemaVersion": 1,
    "state": "queued",
    "created": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "osmSnapshot": snapshot or None,
    "bytes": total,
    "files": files,
}
if label:
    manifest["label"] = label
if source_page:
    manifest["osmSource"] = source_page
elif osm_url:
    manifest["osmSource"] = osm_url
if "tiles.pmtiles" in files:
    manifest["pmtilesSha256"] = files["tiles.pmtiles"]["sha256"]
if "graph.bin" in files:
    manifest["graphSha256"] = files["graph.bin"]["sha256"]
elif "graph.osm.pbf" in files:
    manifest["graphSha256"] = files["graph.osm.pbf"]["sha256"]
(pack / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(f"wrote {pack / 'manifest.json'}")
for name, meta in files.items():
    print(f"  {name} {meta['bytes']} {meta['sha256']}")
print(f"osmSnapshot {snapshot}")
print(f"bytes {total}")
PY
