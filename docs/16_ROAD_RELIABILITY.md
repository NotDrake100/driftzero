# Road reliability: topology audit, adapter, and development ablations

Date: 2026-09-06. Task B. Research only. Not live Android. Not a locked
confirmation. Not a lane-accuracy claim.

Code branch starts from main `c7a1ab9ccf1c156c48da1cac7b6e10ef384f66d4`.
IO-VNBD pin remains `118939602e3422d47b8ab0807b623751c3ac135b`. Seed 26168.
Development interval IDs are exactly those in
`results/accuracy_v3_20260906/development/manifest.json`. This document does
not change ADR 014's recorded rejection or `selection.json` meaning.

## Status (keep these separate)

| Claim | Status |
|---|---|
| Code merged to main | No |
| Experiment completed | Yes. Development ablations in `results/road_reliability_20260906/` |
| Relative development gate passed | No. All five preregistered configs rejected |
| Absolute median below 0.10 | No |
| All-interval 0.10 target passed | No |
| Field placements tested | No |

## Why development p95 worsened (ADR 014 evidence, not a new locked run)

Selected deterministic development baseline: median 39.84%, p95 54.02%, 1/11
below 0.10. ADR 014 complete-map road overlay: median 26.90%, p95 84.61%, 2/11
below 0.10. Maps on 11/11. Rejected because the tail worsened.

Per-interval comparison from the published CSVs:

| Interval | Baseline drift | Road drift | Road along m | Road cross m |
|---|---:|---:|---:|---:|
| S-Vta11:mid | 46.05% | 72.08% | -151.2 | 2.2 |
| S-Vta29:mid | 54.02% | 23.09% | 41.9 | 47.3 |
| S-Vta29:d1000 | 50.99% | 65.70% | -171.7 | -651.5 |
| S-Vta6:mid | 4.52% | 2.28% | 11.7 | 3.7 |
| S-Vta8:mid | 18.07% | 26.90% | -88.1 | -0.3 |
| S-Vta8:d1000 | 53.16% | 84.61% | -899.5 | -37.2 |
| S-Vtb2:mid | 24.52% | 27.43% | -83.8 | 0.9 |
| S-Vtb2:d1000 | 21.34% | 21.00% | 227.0 | 31.7 |
| S-Vtb7:mid | 22.40% | 20.15% | -63.8 | 9.6 |
| S-Vw3:mid | 39.84% | 6.90% | see JSON | see JSON |
| S-Vw3:d1000 | 42.78% | 51.05% | see JSON | see JSON |

S-Vta8:d1000 is the published p95. Almost all of that 900 m endpoint error is
along-track. The overlay stayed near a corridor and under-traveled. S-Vta29:d1000
is a different failure: 652 m cross-track, a wrong-road hypothesis. S-Vta11:mid
is another along-track shortfall. The median win is real on other rows. The
always-on posterior mean is what made the tail worse.

The particle mean can sit between roads. ADR 014 already said the spread is not
calibrated coverage. The implementation still wrote that mean into lat/lon on
every epoch.

## Topology audit of `osm_coast.Graph` (ADR 014 code)

Directed geometry and one-way tags are present. Motorway and roundabout default
to forward-only. `oneway=-1` reverses. Untagged and `reversible` / `alternating`
stay bidirectional. Access `no` / `motor_vehicle=no` are dropped.

Intersections are OSM node identity. A particle that leaves a segment picks one
outgoing edge uniformly, except the immediate reverse. Heading is applied once
per second on the current segment, not at the junction. That is a uniform
unconstrained branch prior.

Grade separation is not represented. `layer`, `bridge`, `tunnel`, and `level`
are discarded. Shared nodes become legal hops even when tags conflict.

Turn restrictions are not in the way-only Overpass query, so cached snapshots
have no restriction relations. The parser now reads them when present and is a
no-op on the ADR 014 caches. Map bounds and the way query are unchanged.

Dead ends pin the particle and multiply weight by 0.1. There is no U-turn. A
short spur can trap mass and shorten along-track travel.

U-turns are forbidden except the implicit reverse edge when `deadend_uturn` is
on. That flag is off in the ADR 014 control.

Map provenance is unchanged: 16 km radius around the last available GNSS point
rounded to 0.1 degree. No hidden route, endpoint, or blackout length chooses
the tile. Query text, SHA256, endpoint, fetch time, and OSM timestamp stay in
the meta file.

## Prospective development gate (recorded before new runs)

This relative gate is not the absolute 10% target. It is not a reinterpretation
of ADR 014.

A development candidate is eligible only if all of the following hold on the
same 11 intervals, with missing/fallback rows kept in the denominator:

1. Complete finite predictions. No evaluation failures. One row per development
   interval.
2. Median drift at most 90% of a fresh deterministic `latch_sparse_reseed`
   baseline (at least 10% relative median improvement).
3. p95 no worse than that baseline p95.
4. Count of intervals with drift >= 0.10 no worse than the baseline count.

Map/model activation and fallback rates must be reported. A valid causal
fallback counts as coverage and must not be dropped. Locked confirmation is
out of scope for Task B. Android defaults stay unchanged.

Published ADR 014 numbers remain a rejected historical run. If a new map
snapshot hash differs, the run is a new snapshot, not a bit-identical replay.

## Preregistered ablations

Fixed before any new development overlay is scored. Seed 26168. Particle count
512. Heading likelihood 0.4 rad plus 0.005 rad/s, once per second. Speed prior
3 m/s. Speed random walk 0.35 m/s/sqrt(s). IMU gap limit 0.4 s. Weak gyro holds
course. No acceleration model. No learned motion.

Confidence constants come from ADR 014 and the plan's 1 km engineering budget
(10 m seed + 30 m speed + 30 m heading + 20 m remainder, plus a 60 m model gap
= 150 m). They are not fit to interval outcomes.

| id | Topology | Overlay |
|---|---|---|
| `adr014_reproduce` | ADR 014 graph and uniform junctions | Always write the posterior mean |
| `topo_v1` | Heading-weighted successors, dead-end U-turn with penalty, grade-tag veto | Always write the posterior mean |
| `confidence_v1` | ADR 014 topology | Overlay only if a spatial cluster has mass >= 0.50, raw spread <= 30 + 3 t metres, and haversine to the deterministic coast <= 150 m |
| `combined_v1` | `topo_v1` | `confidence_v1` gate |
| `lateral_heal_v1` | `topo_v1` | Same gate, then project the deterministic coast onto the dominant-heading line through the road mean. Research label only. Not a lane fix |

`parse_turn_restrictions` is on for every new topology config and remains a
no-op without relation elements. The Overpass way query is not changed, so
ADR 014 caches stay addressable.

Failed or gated intervals stay in the table.

## Causal adapter contract for Task D

Module: `driftzero_ml.road_adapter`. D owns `eval_osm_coast.py` / shared replay
wiring. This adapter is the only B interface D should import.

```
RoadAdapterConfig          frozen configuration, including CONFIGS[name]
apply_causal_overlay(...)  frames, baseline states, [start_ns, end_ns), Graph
development_gate(...)      relative eligibility vs a fresh deterministic baseline
```

Rules D can rely on:

- Inputs are prefix-available GNSS for seed/map bounds, IMU inside the mask,
  and the deterministic baseline states. Hidden GNSS after `start_ns` is not
  read.
- Output copies baseline states. Only `position.latitude_deg` /
  `longitude_deg` and research metadata may change. Velocity, heading, mode,
  and health stay baseline fields.
- Overlay is epoch-wise and causal. Fallback keeps the baseline position.
- Posterior mean between roads is not a snap. Ambiguity or contradiction
  returns `use_road=false` when the confidence gate is on.
- Computation is finite: dt and gyro must be finite, dt in (0, 0.4], at most
  64 hops per particle step, collapse raises and becomes fallback.
- Reproduction: `PYTHONPATH=ml/src python -m driftzero_ml.eval_road_reliability`
  on development IDs only. Never pass locked IDs to this module.

Exact frozen command for an eligible config is written to
`results/road_reliability_20260906/FOR_TASK_D.json` after the ablation. If no
config is eligible, that file is a rejection report.

## Measured development ablations (2026-09-06)

Maps and baseline frames were restored from Actions run `34061769087`. Map
response SHA256 values match
`results/road_research_20260906/map_provenance_complete.json`. Kotlin replay
was not rerun. `adr014_reproduce` matched the published ADR 014 drifts
(median 26.90%, p95 84.61%). Restriction relations are absent in every cached
extract. Grade-tagged segments exist (hundreds to low thousands per tile).

| Config | Median | p95 | below 0.10 | Eligible |
|---|---:|---:|---:|---|
| Deterministic baseline | 39.84% | 54.02% | 1/11 | comparison only |
| `adr014_reproduce` | 26.90% | 84.61% | 2/11 | No, p95 |
| `topo_v1` | 24.25% | 68.17% | 2/11 | No, p95 |
| `confidence_v1` | 39.84% | 54.02% | 1/11 | No, median |
| `combined_v1` | 39.84% | 54.02% | 1/11 | No, median |
| `lateral_heal_v1` | 39.84% | 54.02% | 1/11 | No, median |

`topo_v1` lowered the ADR 014 tail (S-Vta8:d1000 84.61% to 58.53%, S-Vtb7:mid
20.15% to 2.87%) but S-Vta11:mid stayed at 68.17% and S-Vta29:d1000 stayed
near 65.8% with a 1.97 m collapsed spread on the wrong corridor. That is the
uncalibrated snap the gate exists to stop.

The 25 m cell mass test treated a single-road along-track cloud as many
clusters. `confidence_v1` then fell back on 3653 of 4983 epochs
(`ambiguous_split`) and recovered the deterministic median. That is a failed
confidence design, not a median pass.

Raw tables: `suite_summary.csv`, `interval_drifts.csv`, per-config
`metrics.csv` and `activation.csv`. Task D file:
`results/road_reliability_20260906/FOR_TASK_D.json` (rejection). Do not run
locked confirmation from this result.

A later versioned matrix could replace 25 m cells with a connected along-track
cluster and keep deterministic distance when particles under-travel. That
matrix is not declared here and was not run.

## Honesty

No locked benchmark was altered. No hidden GNSS is used in inference. No lane
accuracy is claimed. A development median gain is not an all-interval pass.
Map hashes and metrics are measured, not invented. ADR 014 remains rejected
for tail regression.

Map data: © OpenStreetMap contributors, ODbL 1.0.
[Licence](https://www.openstreetmap.org/copyright).
