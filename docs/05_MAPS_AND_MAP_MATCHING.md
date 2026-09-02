# Offline maps and map matching

## 1. Two artifacts, two responsibilities

DriftZero separates visual maps from the navigation graph:

1. **Visual package:** vector tiles in PMTiles format, rendered by MapLibre Native.
2. **Navigation graph:** compact indexed road segments and adjacency used by the HMM matcher.

This avoids reverse-engineering topology from rendered tiles and lets each artifact be optimized independently.

## 2. Source and build pipeline

- Download a pinned OpenStreetMap PBF extract, for example from [Geofabrik](https://download.geofabrik.de/), or create a bounded extract from a documented provider. An India extract is a valid sample, not a product lock.
- Select any corridor bounding box. Address packs by bbox and opaque id. Do not hard-code one city as the world.
- Build vector tiles and style into PMTiles. [Protomaps](https://docs.protomaps.com/guide/getting-started) documents PMTiles extracts and basemap tooling. `tools/maps/pack_bbox.py` writes the generic manifest for that bbox.
- Build the road graph from the same OSM snapshot.
- Package metadata: bounding region, build time, OSM replication timestamp, schema version, style version, hashes, size, and supported app version.
- Copy bundled PMTiles from Android assets to app storage before opening. MapLibre's [Android PMTiles example](https://www.maplibre.org/maplibre-native/android/examples/data/PMTiles/) documents local file use. Until a Ready pack with `style.json` is installed, the app uses hosted OpenFreeMap.

Do not bulk-download `tile.openstreetmap.org`. The [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/) prohibits offline or bulk use of that public service. Self-host or use a provider that explicitly permits the required workflow.

## 3. Road graph schema

Each directed segment stores:

- stable graph ID and source OSM way/node references;
- polyline geometry in local projected coordinates;
- start/end node and adjacency offsets;
- road class, service type, surface where useful;
- one-way and access restrictions;
- bridge, tunnel, layer, ramp/link, and roundabout flags;
- typical or tagged speed prior, never assumed exact;
- name/ref only for display, not core matching;
- bounding box and cumulative length.

Use spatial indexing such as an R-tree, packed Hilbert index, or fixed grid chosen through benchmark. Keep allocations bounded on the navigation worker.

## 4. Online HMM design

The approach follows the robust probabilistic pattern established by [Newson and Krumm](https://www.microsoft.com/en-us/research/publication/hidden-markov-map-matching-noise-sparseness/), adapted for dense phone output and an uncertain inertial trace. Paper formulas, Quddus integrity notes, and the implemented delta live in [docs/refs/MAP_MATCHING.md](refs/MAP_MATCHING.md). Kotlin: `HmmRoadMatcher` plus `OsmGraphLoader` in `packages/navigation-core`.

### Candidate generation

- Search radius derives from navigation covariance, with safe minimum and maximum bounds.
- Project the estimate to directed segments.
- Keep top candidates by a cheap preliminary score, preserving road-layer diversity.
- Add an explicit unmatched candidate.

### Emission score

Use:

- Mahalanobis or covariance-aware cross-track distance;
- heading agreement, weakened at low speed;
- vehicle direction versus one-way/access rule;
- road class and speed plausibility as weak priors;
- bridge/tunnel/layer consistency from recent context;
- sensor/map health and phone confidence.

### Transition score

Compare:

- shortest legal path distance between candidates;
- dead-reckoned displacement and uncertainty;
- turn angle/yaw evidence;
- elapsed time and speed feasibility;
- one-way, turn, ramp, and connectivity rules.

### Inference

Use a rolling Viterbi beam with bounded candidates and history. Retain N-best hypotheses near junctions. Commit delayed states only after sufficient evidence. The live marker can use the best current hypothesis while displaying a wider confidence halo.

## 5. Filter feedback

Map matching is correlated with the navigation estimate and can create feedback loops. Therefore:

- never replace filter position with a snapped coordinate;
- use a soft cross-track or road-heading pseudo-measurement;
- inflate map covariance when candidate entropy is high;
- disable feedback at junctions, parallel roads, or when unmatched;
- log whether an update came from road geometry.

Evaluate filter-only, visual snap-only, and soft-feedback variants separately.

## 6. Essential test fixtures

| Fixture | Expected behavior |
|---|---|
| Two close parallel roads | Preserve both until heading/topology disambiguates |
| Flyover above surface road | Use layer, approach topology, and continuity; do not switch at crossing |
| Main road and service road | Consider legal connection and displacement, not nearest geometry only |
| Tunnel | Prefer connected tunnel segment through GNSS absence |
| U-turn | Permit only through plausible topology and yaw evidence |
| Roundabout | Maintain direction and correct exit hypothesis |
| Stationary near junction | Do not hop between segments |
| Large uncertainty | Return ambiguous/unmatched rather than false precision |
| Stale map or new road | Continue inertial result with low map confidence |

## 7. Build versus reference engines

Use a compact custom matcher on Android for bounded offline operation. Validate it against established references such as [GraphHopper map matching](https://github.com/graphhopper/graphhopper/blob/master/map-matching/README.md) or [Valhalla Meili](https://valhalla.github.io/valhalla/contributing/architecture/meili/) on desktop. Reference agreement is diagnostic, not ground truth.

## 8. Storage strategy

- Ship only a tiny sample corridor in the development build.
- Let the user install one or more city/corridor packages, including an India extract as an example.
- Queue from the visible camera bbox (`AreaPackStore.queue`) or sideload a built directory (`installSideload`).
- Display exact download and installed sizes.
- Use resumable download, checksum, atomic activation, and rollback.
- Store visual and graph packages under versioned area IDs.
- Permit sideload for secure/offline government deployment.
- Never include an entire India PBF or full-country vector archive in the APK.

