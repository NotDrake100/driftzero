# Area packs

An area pack is a WGS84 bounding box plus two artifacts:

- `tiles.pmtiles` for MapLibre rendering (OpenMapTiles schema)
- `graph.bin` compact OSM PBF of the assembled directed road graph for the live matcher and on-device router. `OsmGraphLoader` reads it because the file is PBF even though the name is `.bin`. `graph.osm.pbf` stays as a desktop extract. The phone does not parse that extract.

The id is an opaque slug. City names are labels only. Queue any bbox. Do not
name the API after one city.

Ready, as `AreaPackStore` checks it: `manifest.json` with `id`, `south`, `west`,
`north`, `east`; `tiles.pmtiles`; and `graph.bin` (or a legacy `graph.osm.pbf`
on disk for tooling). Optional `style.json`, `style-day.json`,
`style-night.json`, `glyphs/`, and `sprites/` are copied on sideload.
`schemaVersion`, `label`, `osmSource`, `osmSnapshot`, `pmtilesSha256`,
`graphSha256`, and `bytes` are read when present. A hash mismatch is Corrupt,
not Ready. There is no expiry field.

Convert a highway extract with:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:writeGraphBin --args="--input data/area-packs/pune-core/graph.osm.pbf --output data/area-packs/pune-core/graph.bin --id pune-core"
```

The app rewrites pack styles so the vector source is
`pmtiles://file://<absolute tiles.pmtiles>` and glyphs are
`file://<absolute glyphs dir>/{fontstack}/{range}.pbf`. MapLibre Native does
not resolve relative `pmtiles://tiles.pmtiles`.

Store root on the phone is `context.filesDir/area-packs`, which is
`/data/data/in.driftzero.app/files/area-packs`. Installed packs live under
`installed/<id>/`.

Do not bulk-download `tile.openstreetmap.org`. Binaries stay gitignored
(`*.pmtiles`, `*.pbf`, and `data/area-packs/**` except this README).

## Queue a region (manifest only)

```bash
python3 tools/maps/pack_bbox.py \
  --south 40.70 --west -74.05 --north 40.85 --east -73.90 \
  --id manhattan-sample
```

India Geofabrik extract as a sample, not the only region:

```bash
python3 tools/maps/pack_bbox.py \
  --south 6.5546 --west 68.1114 --north 35.6745 --east 97.3956 \
  --id example-india \
  --osm-source https://download.geofabrik.de/asia/india.html
```

On the phone, long-press Locate to queue the visible camera bbox. The hosted
OpenFreeMap style stays until `tiles.pmtiles`, a graph file, and a matching
checksum are present under `installed/<id>/`.

## Build a Ready pack from a Geofabrik PBF

`tools/maps/build_pune_pack.sh` is generic. Bbox, id, and OSM URL are arguments.
No city name is hardcoded in the script logic. After Planetiler it runs
`tools/maps/vendor_offline_assets.py` for OpenFreeMap glyphs and sprites.

Pinned tools:

- Planetiler 0.10.2 jar (`f310bd0413e2e4512b27f4046d418664e8e1d3bf31603c2a70e23de06c167e4d`). Needs Java 21+. Java 17 cannot run this jar (class file 65). Build used Java 24.0.2. Git `0e5588c4`, timestamp `2026-03-28T14:35:36.815Z`.
- osmium-tool 1.19.1 / libosmium 2.23.1 (portable binary at `~/.cache/driftzero/tools/osmium` if Homebrew is too large).
- `pmtiles` and `tippecanoe` are not required.

Planetiler OpenMapTiles extras stay in `~/.cache/driftzero/planetiler/sources/`. The script downloads lake centerlines and Natural Earth only when missing. It does not download `water-polygons-split-3857.zip` from osmdata.openstreetmap.de (about 1 GB of ocean coastlines). For an inland bbox it writes an empty EPSG:3857 shapefile stub instead, then runs Planetiler with `--download=false`.

Geofabrik no longer publishes `maharashtra-latest.osm.pbf`. The smallest extract that contains Pune is Western Zone. Geofabrik publishes MD5, not SHA256.

### Example: Pune core (Camp to SB Road)

Bbox 18.46 to 18.62 N, 73.76 to 73.96 E (Camp, SB Road, Kasba Peth, Shivajinagar, Koregaon Park, Deccan, Mumbai-Pune expressway approach).

```bash
tools/maps/build_pune_pack.sh \
  --south 18.46 --west 73.76 --north 18.62 --east 73.96 \
  --id pune-core \
  --label "Pune core (Camp to SB Road)" \
  --osm-url https://download.geofabrik.de/asia/india/western-zone-latest.osm.pbf \
  --osm-source-page https://download.geofabrik.de/asia/india/western-zone.html \
  --expected-md5 cddd0c0f68f7f0d114ab899b9d3b8a3c
```

To vendor glyphs and sprites into an already built pack without rerunning Planetiler:

```bash
python3 tools/maps/vendor_offline_assets.py --pack data/area-packs/pune-core
```

Built 2026-09-03 (local) from cache `~/.cache/driftzero/osm/`. OSM snapshot `osmosis_replication_timestamp` is `2026-09-01T20:20:50Z`. Glyphs and sprites vendored 2026-09-03.

| File | Bytes | Notes |
| --- | ---: | --- |
| Geofabrik `western-zone-latest.osm.pbf` (scratch, not in repo) | 220574281 | MD5 `cddd0c0f68f7f0d114ab899b9d3b8a3c`. SHA256 `c9f1d97a519afab0f34b602906843a1ba9f7d43b6917cddf953224c587d8e019` |
| bbox extract | 10323690 | under 50 MB |
| `tiles.pmtiles` | 5581639 | Planetiler OpenMapTiles, z0–14, 144 tiles |
| `graph.osm.pbf` | 2703165 | `osmium tags-filter w/highway` (referenced nodes kept). Desktop extract only. |
| `graph.bin` | 4734784 | Compact OSM PBF of the assembled directed graph. 312113 edges, 193090 nodes, 6303 named ways. Tunnel, bridge, layer, oneway, and name tags. Live matcher and local router read this file. |
| `style.json` / `style-day.json` | 105451 | OpenFreeMap liberty, vector source `pmtiles://tiles.pmtiles` until the app rewrites it |
| `style-night.json` | 48921 | OpenFreeMap dark, same rewrite |
| `glyphs/` | 4009488 | Noto Sans Regular, Bold, Italic. Latin through Arabic plus punctuation ranges |
| `sprites/` | 223885 | OpenFreeMap `ofm` sprite json and png, 1x and 2x |
| pack files total (`bytes`) | 17512784 | sum of tiles, graph.bin, graph.osm.pbf, styles, glyphs, sprites |

`tiles.pmtiles` SHA256 `33aa090afa65b6a4441364e758e6fe48194be9769874a27cfefcceb72e0b10a3`.
`graph.bin` SHA256 `09efd33147a92d3140db61ced93d5cad0cda0b2ccc4a41884d64597cb0dd7a36`.
`graph.osm.pbf` SHA256 `4a5074ccc2dcee75483a0dbf6669ffb4d51d6e0e601de2b02f6b2f3a576043b4`.

`style.json` is a copy of `style-day.json`. Night uses `style-night.json` when present. Liberty hillshade still points at OpenFreeMap raster. Streets, labels, and POI icons use the local pack after rewrite.

OpenFreeMap styles are public style documents, not tiles. License pointer: OpenFreeMap repo is MIT. Liberty design is a fork of maputnik/osm-liberty (code BSD 3-Clause, design CC BY 4.0). Noto Sans glyphs follow the SIL OFL. OSM data is ODbL. See `NOTICE.md`. Do not edit NOTICE when regenerating this pack.

## Sideload onto the phone

`applicationId` is `in.driftzero.app`. Push into the installed tree the store already reads. Offline areas also has Import zip and Import folder (`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`).

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb push data/area-packs/pune-core /data/local/tmp/pune-core
adb shell "run-as in.driftzero.app sh -c 'mkdir -p files/area-packs/installed && rm -rf files/area-packs/installed/pune-core && cp -R /data/local/tmp/pune-core files/area-packs/installed/pune-core'"
```

Target is internal `filesDir`, not external storage. After copy, `installed()` marks the pack Ready when `tiles.pmtiles` and `graph.bin` are present and checksums match. The live matcher and local router read `graph.bin`. Offline search has no POI index. Tap the map to set a destination. Public OSRM is the network fallback.
