# Area packs

An area pack is a WGS84 bounding box plus two artifacts:

- `tiles.pmtiles` for MapLibre rendering
- `graph.osm.xml` or `graph.osm.pbf` (or `graph.bin`) for road topology. `OsmGraphLoader` reads XML or PBF.

The id is an opaque slug. City names are labels only. Queue any bbox. Do not
name the API after one city.

## Queue a region

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
OpenFreeMap style stays until `tiles.pmtiles`, `graph.bin`, and `style.json`
are sideloaded and `AreaPackStore.installSideload` marks the pack Ready.

Do not bulk-download `tile.openstreetmap.org`. Binaries stay gitignored.
