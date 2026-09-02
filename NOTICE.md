# Prototype notice

DriftZero is a research prototype for resilient vehicle navigation. It is not certified for autonomous control, safety-of-life navigation, or guaranteed lane-level positioning.

This repository does not grant rights to third-party datasets, map data, software packages, or model checkpoints. Before distribution or deployment, review each dependency and artifact independently, pin versions, retain notices and attribution, and replace any research-only component that is not appropriate for the intended use.

No third-party raw dataset dump, proprietary map, or TimesFM checkpoint is included in this repository. `ml/tests/fixtures/io_vnbd_s_vta9_head.csv` is a 3-row CC BY 4.0 excerpt of IO-VNBD `S-Vta9.csv` for loader tests. Cite Onyekpe et al., doi:10.1016/j.dib.2021.106885.

Contest-facing split of original work versus hosted map tools: [docs/SIH_THIRD_PARTY.md](docs/SIH_THIRD_PARTY.md).

## Fonts

IBM Plex Sans and IBM Plex Mono are included under the SIL Open Font License 1.1. The license text is at `apps/android/third_party/ibm-plex/LICENSE.txt`.

## Maps, geocoding, and routing

MapLibre Native Android is used under the BSD 2-Clause license. Street tiles and labels come from OpenFreeMap, which is based on OpenStreetMap data. This hosted style is not a local PMTiles area package.

OpenStreetMap data is © OpenStreetMap contributors, available under the Open Database License (ODbL). If you ship a PMTiles or road-graph extract, keep the ODbL notice with that pack.

Photon (Komoot) is the primary geocoder over HTTPS. Nominatim is the fallback search API. OSRM is the public driving-route API. DriftZero does not vendor those servers. Their software licenses (Photon Apache-2.0, Nominatim GPL-2.0, OSRM BSD 2-Clause) apply to those projects, not to this repository. Search and route results inherit OSM attribution.

Do not bulk-download `tile.openstreetmap.org`. The OSM tile usage policy forbids offline or bulk use of that public service.

## Research extras

TimesFM 3, PyTorch, and optional dataset loaders are desktop-only. They are not APK dependencies. Review each checkpoint and dataset license before training or redistribution.
