# Map matching references

Retrieved 2026-09-02. Product choices below are DriftZero interpretations. The papers do not endorse the app.

## Primary algorithm

Paul Newson and John Krumm. Hidden Markov Map Matching Through Noise and Sparseness. 17th ACM SIGSPATIAL International Conference on Advances in Geographic Information Systems (ACM GIS 2009), Seattle, WA, 4-6 November 2009. [DOI 10.1145/1653771.1653818](https://doi.org/10.1145/1653771.1653818). [Microsoft Research page](https://www.microsoft.com/en-us/research/publication/hidden-markov-map-matching-noise-sparseness/).

Hidden states are directed road segments. Observations are timestamped lat/lon. Inference is Viterbi.

Emission (their eq. 1): zero-mean Gaussian of great-circle distance from the observation to the closest point on the candidate segment. They estimated σ_z = 4.07 m with a MAD scale of 1.4826 times the median residual (their eq. 5).

Transition (their eq. 2): exponential of |route distance between successive projections − great-circle between successive observations|. β is estimated from that absolute difference (Gather and Schultze median estimator). A missing network path, a route that overshoots the great-circle by 2000 m, or a required speed above 50 m/s is probability zero.

They zero any candidate more than 200 m from the observation. They drop points within 2 m of the last kept point before building the lattice (§4.1). When every transition dies they heal or split the trip (§4.2). The published algorithm is batch Viterbi. They speculated a sliding window for a live navigator.

They did not use compass heading. Hummel (2006) added heading to the emission. Newson and Krumm omitted it because heading from sparse GPS is poor.

## Reviews and integrity

Mohammed A. Quddus, Washington Y. Ochieng, and Robert B. Noland. Current map-matching algorithms for transport applications: State-of-the-art and future research directions. Transportation Research Part C, 15(5):312-328, 2007. [DOI 10.1016/j.trc.2007.05.002](https://doi.org/10.1016/j.trc.2007.05.002).

Quddus, Ochieng, and Noland group matchers as geometric, topological, probabilistic, and advanced (Kalman, fuzzy, belief). They stress heading, connectivity, and integrity: do not force a link when the evidence is poor. Urban parallel roads and junctions are the failure mode.

Related Quddus work used here as design checks, not as a second live engine:

- Quddus, Ochieng, Zhao, and Noland. A general map matching algorithm for transport telematics applications. GPS Solutions, 7(3):157-167, 2003.
- Quddus, Ochieng, and Noland. Integrity of map-matching algorithms. Transportation Research Part C, 14(4):283-302, 2006.
- Quddus, Noland, and Ochieng. A high accuracy fuzzy logic based map matching algorithm for road transport. Journal of Intelligent Transportation Systems, 10(3):103-115, 2006.

## OSM graph and desktop references

OpenStreetMap ways with `highway=*` are the source topology. The bbox is any WGS84 box. A city name is a label, not an API. PBF follows the [OSM PBF format](https://wiki.openstreetmap.org/wiki/PBF_Format). Do not download tiles from `tile.openstreetmap.org`.

Desktop references for later agreement tests, not live dependencies: [GraphHopper map matching](https://github.com/graphhopper/graphhopper/blob/master/map-matching/README.md) and [Valhalla Meili](https://valhalla.github.io/valhalla/contributing/architecture/meili/).

## What DriftZero implements versus Newson-Krumm

| Piece | Newson and Krumm 2009 | DriftZero |
|---|---|---|
| Emission | Gaussian of distance to polyline, σ_z = 4.07 m | Same, with σ at least half of the filter 95% radius so large covariance flattens the score |
| Heading | Not used | Extra Gaussian on heading vs edge azimuth, weight 0 below 1 m/s (Hummel / Quddus) |
| Transition | exp(−|route − great-circle| / β) | Same, plus Dijkstra on the directed graph. No path is probability zero |
| Candidate cutoff | 200 m hard zero | Search radius from covariance, clamped to 15-250 m |
| 2 m thinning | Yes, §4.1 | Yes. Live `update` reprojects onto the last edge instead of adding a lattice step |
| 2000 m slack and 50 m/s | Yes | Yes |
| Unmatched / breaks | Remove points or split the trip | Explicit unmatched state. If every road transition dies, restart the lattice |
| Inference | Batch Viterbi | `matchSequence` is batch plus backtrack. `update` is the live column they speculated |
| Output | Snapped route | Best edge, posterior confidence, entropy. Display pose is the centerline projection. ESKF lat/lon is never overwritten |
| Lane claim | None | None. Ambiguous stays `AMBIGUOUS`. The puck uses the display pose only when status is `MATCHED` |

Code: `packages/navigation-core` (`HmmRoadMatcher`, `OsmGraphLoader`, `RoadGraph`). Tests: `HmmRoadMatcherTest`, `OsmGraphLoaderTest` on a two-parallel-road fixture. OSM XML/PBF loader takes any bbox.

## What this is not

This is not lane-level matching. It is not a snap that writes the filter. It is not GraphHopper or Valhalla on the phone. Soft filter feedback (cross-track as an ESKF measurement) is still off. Evaluate filter-only, display-only, and feedback variants separately (`docs/05_MAPS_AND_MAP_MATCHING.md`).
