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

Development evidence is still running at this checkpoint. No configuration is
selected for deployment yet. The standard CI validates the code and synthetic
regressions; it does not certify real-phone or mountless field accuracy.
