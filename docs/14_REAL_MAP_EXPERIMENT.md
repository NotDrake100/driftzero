# Real-map and acceleration experiments

Date: 2026-09-06. The below-10% accuracy target remains unmet.
The latest full locked result remains 48.83% median drift, 6/35 intervals below
10%. The following development measurements do not replace that locked score.

## Initial road experiment

[ADR 014](adr/014-real-map-research.md) defines the fixed estimator, development
intervals, map acquisition and acceptance rule. Code commit:
`05309a390cd271ff624876f4d72ffdbe9c5a62fd`.
Data: IO-VNBD `118939602e3422d47b8ab0807b623751c3ac135b`.

| Development result | Selected deterministic baseline | Road particles |
|---|---:|---:|
| Scored intervals | 11 | 11 |
| Median drift | 39.84% | 26.90% |
| p95 / maximum drift | 54.02% | 84.61% |
| Intervals below 10% | 1/11 | 2/11 |
| Intervals using road estimates | 0 | 10/11 |

The median improved, but the tail worsened. S-Vta6:mid retained the baseline
because both map endpoints failed (HTTP 429 and timeout). No interval was
removed. The candidate failed its preregistered acceptance rule and was not
confirmed on the locked set or enabled on Android.

[Raw per-interval CSV](../results/road_research_20260906/road_initial/metrics.csv),
[full metrics](../results/road_research_20260906/road_initial/metrics.json),
[selection decision](../results/road_research_20260906/road_initial/selection.json),
and [workflow](https://github.com/NotDrake100/driftzero/actions/runs/34061171471).

## Prefix acceleration ablation

A small ridge model learns acceleration projection and bias only from pre-mask
GNSS speed changes and horizontal IMU integration. It must improve prediction on
a later pre-mask validation block and satisfy coefficient bounds. Frozen model
inference receives no GNSS. Missing or rejected models leave the road speed prior
unchanged. This is a measured research ablation, not a trained mobile model release.

A local replay of the exact downloaded frames, baseline states and cached maps
accepted zero models. Median and tail scores therefore remained 26.90% and 84.61%.
The missing map remained a baseline fallback. This result does not support an
accuracy claim for the added model.

[Acceleration raw CSV](../results/road_research_20260906/acceleration_initial/metrics.csv),
[full metrics and acceptance flags](../results/road_research_20260906/acceleration_initial/metrics.json),
and [selection](../results/road_research_20260906/acceleration_initial/selection.json).

## Complete-map paired confirmation

The follow-up [workflow](https://github.com/NotDrake100/driftzero/actions/runs/34061769087)
at `0b169c5d045dcb7bdceb03255336b5ac639ef0ec` successfully acquired the missing map.
Both variants then used maps on **all 11 development intervals**, with no evaluation
or IMU-gap failures. S-Vta6:mid improved from 4.52% fallback drift to 2.28% road
drift. Both variants still had **26.90% median, 84.61% p95, and 2/11 below 10%**.
The acceleration model was accepted on zero intervals. Both selection decisions
remain false because the tail regresses. No locked confirmation was run.

Complete evidence: [road CSV](../results/road_research_20260906/road_complete/metrics.csv),
[road metrics](../results/road_research_20260906/road_complete/metrics.json),
[road decision](../results/road_research_20260906/road_complete/selection.json),
[acceleration CSV](../results/road_research_20260906/acceleration_complete/metrics.csv),
[acceleration metrics](../results/road_research_20260906/acceleration_complete/metrics.json),
[acceleration decision](../results/road_research_20260906/acceleration_complete/selection.json),
and [complete snapshot provenance](../results/road_research_20260906/map_provenance_complete.json).

## Provenance and limits

[Map provenance](../results/road_research_20260906/map_provenance.json) records exact
queries, response hashes, source endpoints, fetch times and OSM timestamps.
The workflow artifact includes map response bytes, baseline frames, hidden truth
for scoring, and predicted states. Artifacts expire after 30 days; the durable
CSV/JSON reports and map hashes remain in Git. Reacquiring current OSM may return
different data and must be reported as a new snapshot.

Map data: © OpenStreetMap contributors, ODbL 1.0.
[Licence](https://www.openstreetmap.org/copyright).

The particle mean can lie between roads, and its reported spread is not calibrated
coverage. Turn restrictions are not implemented. Current OSM may differ from the
historical drive. These limitations, sparse/weak heading calibration and unknown
speed changes prevent a claim of lane accuracy or dependable mountless operation.
Prior passenger-seat/resting-phone handling remains available, but this dataset
does not certify arbitrary handheld, pocket, bag or moving-phone performance.

Local validation: 194 tests, two optional dependency skips; Ruff and document
links pass. Initial source CI passes Python, JVM, Android unit tests, Android lint
and APK build. The model checkpoint also passes all four standard CI jobs, including Android
unit tests, lint and APK build. Subsequent commits only archive evidence and docs.

These are position-only research overlays. In the saved traces and initial raw
reports, velocity, heading, mode, health and auxiliary MAE fields belong to the
unchanged deterministic baseline. Only `metrics` position errors and the `road`
metadata describe the road experiment. Do not use the retained auxiliary fields
as road-model speed, heading, confidence or mode measurements.
