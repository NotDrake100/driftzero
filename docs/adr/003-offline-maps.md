# ADR 003: PMTiles rendering plus a separate compact road graph

- Status: accepted
- Date: 2026-09-02

## Context

Offline rendering and probabilistic map matching need different data representations. Render tiles do not reliably expose legal topology, while a routing graph is inefficient for visual styling.

## Decision

Use MapLibre Native with local PMTiles for display and build a separate versioned OSM-derived graph for road matching. Package both from the same pinned regional extract. Do not use the public OSM tile server for offline downloads.

## Consequences

- The APK stays small by installing corridor/city packages separately.
- Visual styling can change without changing graph identity.
- Builds require a reproducible map pipeline and package compatibility checks.
- Map feedback must be soft and confidence-aware.

