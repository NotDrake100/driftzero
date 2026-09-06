# Accuracy and mountless implementation review

Date: 2026-09-06. This is a correctness and placement-continuity milestone, not
a completed less-than-10% accuracy release.

## What changed

1. Corrected the physical gyro sign used by clockwise compass heading and GNSS
   attitude reseeding. Constant-speed curves now integrate circular arcs.
2. Excluded blackout and future gravity measurements from exported calibration.
   Old exported frames must be regenerated for this convention.
3. Deduplicated map matching and filter feedback by sensor timestamp. Persist
   speed is injected before matching in engine replay.
4. Added gravity-projected yaw for unaligned, resting Android phones. Aligned
   vehicle-frame IMU continues to use its calibrated attitude and bias.
5. Added pickup/tilt detection, settling, explicit reduced-confidence copy and
   conservative motion propagation. Moving the phone no longer wipes position
   or releases a held-GNSS session. A remount still invalidates its alignment.
6. Restored CI lint and link checks without weakening their rules. Narrowed
   research exception handling, made time metadata UTC, and restored the LFS
   pointer fixture. Replay preserves trailing-lambda API compatibility.
7. Made locked evaluation work without a missing historical CSV or a macOS-only
   Java path. Added a strict completeness and per-interval 10% target gate.
8. Fixed a newly observed export bug where unavailable gyro projection skipped
   valid GNSS. No sensor values are filled with invented zeros.

## Placement scope

| Situation | Implemented behavior | Remaining evidence |
|---|---|---|
| Resting passenger seat, no mount | Orientation alignment and gravity-axis yaw | Real drives on different cushions/phones |
| Cup holder or stable surface | Same alignment path; physical mount optional | Vibration and sliding slices |
| Portrait, landscape, tilted resting phone | Yaw projection is independent of the screen-forward axis | Field trajectory accuracy |
| Pickup or tilt during outage | Preserve trajectory, suppress inertial pseudo-updates, grow uncertainty | False-positive and recovery rates |
| Slow yaw-only phone spin | Ambiguous with vehicle turning | No arbitrary handheld guarantee |
| Pocket, bag, freely moving passenger phone | Unvalidated | Separate motion model and recorded evaluation |

A five-second still phase and observable straight-driving alignment remain part
of setup. "Mountless" does not mean arbitrary phone manipulation becomes fully
observable. See [ADR 011](adr/011-coast-and-phone-continuity.md).

## Reproduction

The [accuracy workflow](../.github/workflows/accuracy.yml) builds the Kotlin CLI,
pulls smartphone-only LFS files from IO-VNBD commit
`118939602e3422d47b8ab0807b623751c3ac135b`, exports fresh per-interval pre-mask
calibration and evaluates the locked 35 interval IDs. It records source commits,
LFS object IDs, runtime versions, per-interval errors, failures and gate output.
There is no training, OBD input, on-phone TimesFM, or runtime map input in this
screening configuration. The run is deterministic; no random seed is consumed.

The final configuration is `YAW_SPEED_HOLD` with weak-heading `HOLD_COURSE`,
matching ADR 009's evaluation configuration. It is distinct from live Android's
configuration and does not measure the new live phone-handling guard.

With pinned raw data materialized under `data/raw/io_vnbd` and JDK 17 installed:

```sh
PYTHONPATH=ml/src python -m driftzero_ml.eval_kotlin_replay \
  --out results/accuracy_candidate --system gravity_yaw_v2 \
  --coast-mode=yaw_speed_hold --replay-arg=--weak-heading-policy=hold_course \
  --reexport --rerun --skip-sensitivity
PYTHONPATH=ml/src python -m driftzero_ml.accuracy_gate \
  results/accuracy_candidate/metrics_gravity_yaw_v2.json \
  --out results/accuracy_candidate/target_gate.json
```

The target command intentionally exits nonzero if any interval reaches 10%,
any locked interval is missing, or evidence is invalid. It is separate from the
standard correctness CI. A median below 10% alone does not satisfy this target.

## Measured result

The complete run at code commit `43afe464d41b3cb08ce5104a650957659c703890`
scored **35 of 35 intervals**, with zero replay/evaluation failures. The requested
accuracy target **failed**: 5 intervals are below 10%; 30 are at or above it.

| Metric | Measured value |
|---|---:|
| Drift p50 | 0.585213 = 58.52% |
| Drift p90 | 3.159781 = 315.98% |
| Drift p95 | 10.979629 = 1097.96% |
| Drift maximum | 13.166542 = 1316.65% |
| Endpoint error p50 | 210.98 m |
| Endpoint error maximum | 3868.39 m |
| Speed MAE p50 | 3.481 m/s |
| Heading MAE p50 | 0.715 rad |

This candidate does not beat the historical persist p50 of 0.5168. The earlier
filter v5 p50 of 0.6107 is historical context, not a controlled before/after
measurement under the revised exporter. No accuracy improvement is claimed.
Large ratio tails are reported in full; the denominator has not been trimmed.

Evidence: [per-interval table](../results/accuracy_review_20260906/hold_complete/metrics_per_interval_gravity_yaw_v2.csv),
[full metrics](../results/accuracy_review_20260906/hold_complete/metrics_gravity_yaw_v2.json),
[strict gate](../results/accuracy_review_20260906/hold_complete/target_gate.json),
and [LFS object manifest](../results/accuracy_review_20260906/hold_complete/lfs_objects.txt).
[GitHub run](https://github.com/NotDrake100/driftzero/actions/runs/34023328243).

Two earlier runs are retained: [INTEGRATE, 33/35](../results/accuracy_review_20260906/integrate_incomplete/target_gate.json)
and [HOLD_COURSE, 33/35](../results/accuracy_review_20260906/hold_incomplete/target_gate.json).
Both are incomplete and cannot be used as aggregate accuracy evidence. Their
missing S-S3b intervals exposed the gyro/GNSS export bug. The final run keeps
those intervals and repairs their export; it does not exclude their failures.

## Validation

Local `make validate`: 182 tests run, two optional dependency tests skipped.
`make lint` and `make links` pass. GitHub runs validate the JVM regressions and
Android unit tests, lint and debug APK build because the local environment
cannot fetch the Gradle distribution. The accuracy job remains visibly failed
because the 10% target is unmet; its threshold is not weakened to obtain green CI.

## Remaining release work

Measure speed and heading errors on development trips before selecting another
model or filter. Preserve the locked split; do not tune against its failures.
Collect reference drives with resting and moved phones across seat, cup-holder,
orientation, road and outage slices. Evaluate uncertainty coverage, reacquisition,
latency, battery and thermal behavior on physical devices. The emulator and
analytical tests cannot establish those results.
