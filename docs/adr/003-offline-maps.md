# ADR 003: PMTiles rendering plus a separate compact road graph

- Status: accepted
- Date: 2026-09-02

## Context

Offline rendering and probabilistic map matching need different data representations. Render tiles do not reliably expose legal topology, while a routing graph is inefficient for visual styling.

## Decision

Use MapLibre Native with local PMTiles for display and build a separate versioned OSM-derived graph for road matching. Package both from the same pinned extract of an arbitrary WGS84 bounding box. A city or country name is a label on that bbox, not the API. An India Geofabrik extract is a sample, not the only pack. Do not use the public OSM tile server for offline downloads.

## Consequences

- The APK stays small by installing corridor/city packages separately. Users queue the visible map bbox or sideload a built directory.
- Visual styling can change without changing graph identity.
- Builds require a reproducible map pipeline and package compatibility checks. `tools/maps/pack_bbox.py` writes the generic manifest; Planetiler and the graph packer fill tiles and topology.
- Map feedback must be soft and confidence-aware.

