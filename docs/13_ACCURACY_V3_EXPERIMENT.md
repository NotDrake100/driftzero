# Accuracy v3 experiment

Date: 2026-09-06. Target: endpoint error below 10% of travelled distance on every
locked interval. This target has not been achieved.

## Correction and measured baseline

The v2 export introduced a double negation for selected gyro columns. The helper
already produces right-handed angular velocity. A new left/right circle test
covers this convention across the export boundary. The gravity fallback still
requires negation because that helper returns compass course rate. This change
is a physical sign correction, not a choice fitted to benchmark outcomes.

At commit `b1abf5d27555cafe16ac257f3156f3306eb1c3c0`, all 35 intervals score without
failures. The corrected baseline has the following results:

| Metric | Previous v2 | Corrected v3 |
|---|---:|---:|
| Median drift | 58.52% | 72.45% |
| p95 drift | 1097.96% | 315.98% |
| Maximum drift | 1316.65% | 1377.69% |
| Intervals below 10% | 5/35 | 6/35 |
| Median endpoint error | 210.98 m | 210.98 m |
| Maximum endpoint error | 3868.39 m | 2406.56 m |

The outcome is mixed and fails the accuracy target. The median regression is
reported alongside the tail improvement. Do not cite only the favorable number.
No live Android default is changed by this exporter correction.

Evidence: [full metrics](../results/accuracy_v3_20260906/corrected_baseline/metrics_gravity_yaw_v2.json),
[raw interval table](../results/accuracy_v3_20260906/corrected_baseline/metrics_per_interval_gravity_yaw_v2.csv),
[target gate](../results/accuracy_v3_20260906/corrected_baseline/target_gate.json),
and [workflow run](https://github.com/NotDrake100/driftzero/actions/runs/34038670687).
Artifact filenames retain the runner's legacy system label `gravity_yaw_v2`;
the source commit and `right_handed_up_v3` export convention identify this run.

## Development search

[ADR 013](adr/013-development-coast-search.md) predeclares seven coast ablations,
session-group exclusion, fixed group selection and acceptance criteria. The
search does not use any locked session or sibling for candidate selection. It
saves its decision before confirming an eligible winner on the full locked set.
A relative 10% median gain is only a selection requirement, not achievement of
the absolute below-10% drift target.

The search completed at commit `1cd92fb3d5dc60fea57e944a4862991c714d484d`.
Twelve session groups were considered; truth gating yielded 11 development
intervals. Every candidate scored all 11. No locked session or sibling was used.

| Development candidate | Median drift | p95 drift | Intervals failing 10% |
|---|---:|---:|---:|
| Baseline | 45.31% | 241.38% | 10/11 |
| GNSS speed latch | 50.82% | 280.40% | 10/11 |
| Persist pseudo-speed | 45.31% | 241.38% | 10/11 |
| Sparse GNSS reseed | 39.84% | 54.02% | 10/11 |
| Latch plus sparse reseed | 39.84% | 54.02% | 10/11 |
| Stop/restart | 48.46% | 374.85% | 9/11 |
| Course hold plus latch | 45.31% | 287.85% | 10/11 |

The fixed tie-breaking rule selected **latch_sparse_reseed**. Its development
median improved 12.07%, p95 improved 77.62%, and failure count did not worsen.
The stop/restart variant was rejected despite one fewer failure because its
median and tail errors worsened. Selection was saved before locked confirmation.

## Locked confirmation of selected configuration

All 35 intervals scored with no evaluation failures:

| Metric | Corrected baseline | Development-selected candidate |
|---|---:|---:|
| Median drift | 72.45% | **48.83%** |
| p95 drift | 315.98% | **268.24%** |
| Maximum drift | 1377.69% | **317.67%** |
| Median endpoint error | 210.98 m | **110.42 m** |
| Median speed MAE | 3.481 m/s | **2.930 m/s** |
| Median heading MAE | 0.694 rad | **0.651 rad** |
| Intervals below 10% | 6/35 | **6/35** |

Relative to the earlier v2 median of 58.52%, the selected result is 16.55% lower.
Relative to the corrected v3 baseline, it is 32.60% lower. These relative gains
are not a below-10% drift result. The strict target still fails on 29 intervals.
No intervals were omitted, and the target threshold was not relaxed.

Evidence: [selection and all candidate summaries](../results/accuracy_v3_20260906/development/selection.json),
[development manifest and exclusions](../results/accuracy_v3_20260906/development/manifest.json),
[complete selected interval table](../results/accuracy_v3_20260906/development/locked_selected/metrics_per_interval_latch_sparse_reseed.csv),
[full selected metrics](../results/accuracy_v3_20260906/development/locked_selected/metrics_latch_sparse_reseed.json),
and [unchanged target gate](../results/accuracy_v3_20260906/development/locked_gate.json).
[Experiment run](https://github.com/NotDrake100/driftzero/actions/runs/34038877938).

## Reproduce the selected coast

With the pinned raw data available, JDK 17 and the repository dependencies:

```sh
PYTHONPATH=ml/src python -m driftzero_ml.eval_kotlin_replay \
  --out results/accuracy_v3_selected --system latch_sparse_reseed \
  --coast-mode=yaw_speed_hold --reexport --rerun --skip-sensitivity \
  --replay-arg=--weak-heading-policy=hold_course \
  --replay-arg=--gnss-reseed-after-s=3 \
  --replay-arg=--gnss-reseed-min-median-unique-s=2 \
  --replay-arg=--gnss-reseed-while-fused \
  --replay-arg=--coast-latch-gnss-speed
PYTHONPATH=ml/src python -m driftzero_ml.accuracy_gate \
  results/accuracy_v3_selected/metrics_latch_sparse_reseed.json \
  --out results/accuracy_v3_selected/target_gate.json
```

The gate command currently returns failure, as it should. The development
workflow's success means the experiment completed; it does not mean accuracy
passed. Existing Android defaults and mountless handling remain unchanged.

## Validation and next bottleneck

Local validation: 186 tests run, two optional dependency tests skipped. Ruff and
local document links pass. Standard GitHub CI passes Python, JVM and Android unit
tests, Android lint, and the debug APK build for the tested code.

Sparse-GNSS reseeding corrects accumulated state error while GNSS is still
available. It does not reveal vehicle speed changes during a blackout. The
remaining speed MAE and heading errors require further development-set model
work and physical-device recordings. This reused IO-VNBD set does not certify
passenger-seat, handheld, pocket/bag, thermal, battery, or latency performance.
