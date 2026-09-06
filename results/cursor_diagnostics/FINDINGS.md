# Task A findings. DIAGNOSTIC_ONLY

Date: 2026-09-06. Score-only. Not achieved accuracy.

## Status

| Claim | Result |
|---|---|
| Code merged to main | Failed |
| Experiment completed | This diagnostic run completed |
| Selected baseline reproduced | Yes. Matches the archived locked result |
| Target gate | Failed. Exit code 1. 29/35 intervals at or above 10% |
| Median target passed | Failed. 48.83% |
| All-interval target passed | Failed |
| Field placements tested | Failed. Not in scope of this run |

`accuracy_gate.py` rejects every DIAGNOSTIC_ONLY report. Oracle numbers below
are not candidate evidence.

## 1. Reproduced selected deterministic baseline

Pins: IO-VNBD `118939602e3422d47b8ab0807b623751c3ac135b`, seed 26168,
Temurin 17, coast flags from docs/15.

| Metric | Archived locked | This machine |
|---|---:|---:|
| Median drift | 48.83% | 48.83% |
| p95 drift | 268.24% | 268.24% |
| Worst drift | 317.67% | 317.67% |
| Median endpoint | 110.42 m | 110.42 m |
| Intervals below 10% | 6/35 | 6/35 |

`archive_comparison.json` records `matches_archive: true`. The archive was not
replaced. Compact copies live in `reproduced_baseline/`.

## 2. Frozen splits

See `split_manifest.json`. Seed 26168.

- Locked confirmation (never train or select): 19 session groups from the 35
  interval IDs, including letter-suffixed siblings.
- Development (already used for ADR 013/014, not fresh): S-S4, S-Vta5, S-Vta6,
  S-Vta8, S-Vta11, S-Vta13, S-Vta29, S-Vtb2, S-Vtb7, S-Vw3, S-Vw12, S-Vw17.
- Excluded: S-Vtb3 (speed column matches neither m/s nor km/h).
- Train (24 unused groups): S-M, S-S2, S-Vfa02, S-Vta3, S-Vta7, S-Vta9,
  S-Vta10, S-Vta16, S-Vta21, S-Vta23, S-Vta26, S-Vta28, S-Vtb10, S-Vtb12,
  S-Vw2, S-Vw4, S-Vw6, S-Vw7, S-Vw8, S-Vw9, S-Vw11, S-Vw13, S-Vw14, S-Vw15.
- Fresh holdout (10 reserved unused groups): S-Vta4, S-Vta14, S-Vta19,
  S-Vta27, S-Vta30, S-Vtb4, S-Vtb5, S-Vtb11, S-Vw1, S-Vw10.

Route and vehicle identities are unavailable in published IO-VNBD stems.
Driver letter is only the prefix map (S-S=A, S-M=B, S-Y=D, S-V=E). Most
locked, development, train, and holdout groups share driver E.

## 3. Audit

23 locked trips. Timestamps are integer nanoseconds from TIME SINCE START (ms).
Median IMU dt is 0.10 s (declared 10 Hz). Non-integer timestamps: 0 after load.
The time-ordered loader drops rewinds before audit. Export still recorded 3375
dropped rows on S-Y1.

GPS SPEED (Kmh) is metres per second on verified tables. Gyro yaw/pitch/roll
are published labels, not a verified Android XYZ map. `heading_gyro_radps` is
right-handed up (ADR 012).

Unique-fix median spacing is 9 s on 19/23 locked trips, 1 s on 3 trips. Sparse
unique-fix finite differences are approximate. They cannot certify a 5 m claim.

Seed position error is 0.0 m on every locked interval. The start unique fix is
the seed. Seed offset does not close the 10% gap.

IMU gaps over 0.40 s inside a scored window: S-Vta17:d1000 only.

Stop samples inside a window: S-S3b:d1000, S-Vta17:mid, S-Vta17:d1000,
S-Vta25:mid, S-Vw16b:mid, S-Vw16b:d1000.

Reference hops over 200 m on the trip (not necessarily inside the mask):
S-S3b 1112 m, S-Y1 1558 m, S-Vtb1 3986 m, plus several 200-400 m hops.
S-S3b and S-Y1 also sit in the worst locked rows.

Pre-mask heading-gyro pick on the 35 exported intervals: 17 accepted, 18
gravity-vertical fallback (13 few hops, 3 insufficient fixes, 2 weak
correlation). Weak heading calibration is common.

## 4. Reference-substitution diagnostics

Hidden reference was applied only after the candidate path existed, or to
rebuild a counterfactual path. Candidate motion came from reproduced
`latch_sparse_reseed` states. Masks, epochs, and path-length denominators are
the locked set. OSM topology is unsupported here.

| Variant | Median | p95 | Below 10% | Meaning |
|---|---:|---:|---:|---|
| Selected coast (achieved) | 48.83% | 268.24% | 6/35 | Real candidate |
| ref_speed | 34.08% | 140.65% | 12/35 | Oracle speed, candidate heading |
| ref_heading | 16.29% | 84.64% | 12/35 | Oracle course, candidate speed |
| ref_road | 16.29% | 178.57% | 12/35 | Truth polyline, candidate speed |
| ref_heading_road | 16.29% | 178.57% | 12/35 | Same leftover as heading or road |
| ref_speed_heading | ~0 | ~0 | 35/35 | Oracle speed and course |
| ref_speed_road | ~0 | ~0 | 35/35 | Oracle distance on the truth path |
| ref_joint | ~0 | ~0 | 35/35 | All three |

Joint near-zero is a coordinate, time, and scoring audit. It is not a product
result. S-Vta20:mid left one unsupported speed epoch (missing heading sample).

## 5. What could close the gap, and what would not

Could plausibly close the gap:

- Causal heading and turn, together with speed or incremental displacement.
  Heading-only still leaves 16% median. Speed-only still leaves 34% median.
  Both together reach the 10% budget on this diagnostic.
- Map work only as a competing-hypothesis overlay on development groups, and
  only coupled to better distance. 11 locked intervals have a >=30 deg
  reference turn.
- Stop/restart on the six intervals that actually stop.

Would not close the gap:

- Seed-position work. Seed error is already 0 m.
- A speed-only network. Isolated speed stays at 34% median.
- Map-only rollout. Isolated road choice stays at 16% median and a 179% tail.
- Another coast-flag search on the locked 35.
- Changing masks, endpoints, or the path-length denominator.
- 5 m claims on 9 s unique-fix truth.

## 6. Recommendations

Task B (map): Do not enable a live map default. The road-only diagnostic
improves the median versus 48.83% but fails the 10% target and worsens the
idea that a snapped path is enough. Keep competing hypotheses through the 11
turn intervals. Calibrate ambiguity. Treat historical OSM versus this drive as
unknown. Coordinate with D on the adapter. Do not learn a selector from locked
IDs.

Task C (learned motion): Train only on the 24 train groups. Validate on the 12
development groups. Do not open the 10 fresh-holdout groups. Do not train on
locked labels. Learn incremental horizontal displacement and turn change with
uncertainty and a stop hypothesis. Do not start with a speed-MAE-only
network. Compare against the existing speed hold and linear student on the
same development intervals. Inputs stay trailing IMU plus last trusted
velocity/heading age. No TimesFM on the phone path.

## Reproduce

```sh
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
export PYTHONPATH=ml/src
python -m unittest ml.tests.test_diagnostics ml.tests.test_accuracy_gate -v
python -m driftzero_ml.eval_kotlin_replay \
  --out results/cursor_baseline --system latch_sparse_reseed \
  --coast-mode=yaw_speed_hold --reexport --rerun --skip-sensitivity \
  --replay-arg=--weak-heading-policy=hold_course \
  --replay-arg=--gnss-reseed-after-s=3 \
  --replay-arg=--gnss-reseed-min-median-unique-s=2 \
  --replay-arg=--gnss-reseed-while-fused \
  --replay-arg=--coast-latch-gnss-speed
python -m driftzero_ml.accuracy_gate \
  results/cursor_baseline/metrics_latch_sparse_reseed.json \
  --out results/cursor_baseline/target_gate.json
# Expect exit code 1.
python -m driftzero_ml.diagnostics \
  --out results/cursor_diagnostics \
  --states-dir results/cursor_baseline/states \
  --reproduced-metrics results/cursor_baseline/metrics_latch_sparse_reseed.json
```
