# ADR 004: OpenFreeMap liberty as the default visual basemap

- Status: accepted
- Date: 2026-09-02
- Scope: production-path prototype (visual map only)

## Context

The Android first screen is a navigation product, not a firmware panel. Street rendering must be real OSM streets. A local PMTiles city package is not in this repository yet. ADR 003 still owns offline visual packages and the separate road graph.

## Decision

The default MapLibre style URI is `https://tiles.openfreemap.org/styles/liberty`. The first-open camera is Koregaon Park (18.5362, 73.8938). The blue you-are-here marker is MapLibre `LocationComponent`.

This is an online visual default. It does not replace PMTiles, does not use the public OSM raster tile server, and does not feed map-matched truth into the filter.

## Consequences

- The first screen looks like ordinary street navigation when the device has internet.
- Airplane-mode street tiles still require a later area package (ADR 003).
- Attribution stays on the map via MapLibre's attribution widget.
