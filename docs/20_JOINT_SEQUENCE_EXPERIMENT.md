# Real-data joint motion experiment

Experiments: 2026-09-07. Evidence review: 2026-09-10. Research only. The full-benchmark below-10% target remains open.

The reviewed diagnostics and split freeze are on main. The next experiment now
has executable real-data training, rather than only a preregistration or synthetic
smoke test. [ADR 018](adr/018-real-data-joint-sequence.md) defines the experiment.

## What is new

Three small motion networks share a causal input boundary: feedforward,
TCN and GRU. Integration of their two-dimensional velocity estimates changes both
travelled distance and heading. A separate physics-only course/gyro candidate
measures the effect of initialization and integration without learned weights.
The comparison baseline is the freshly rerun selected Kotlin coast.

Only the frozen 24 training groups can supply training windows. The ten holdout
groups remain closed. The first round used four deterministic starts per trip; rounds two and three use twelve,
with frozen pre-mask calibration and actual sensor timestamps. Model inputs do
not contain masked GNSS. Reference positions supervise training at available fix
epochs; there is no future label interpolation. Exclusions are reported per source.

Normalization is fitted only on training features. Fixed seeds, CPU threads,
architecture sizes, learning rate, epochs and checkpoint schedule are recorded.
Each architecture trains for 16 epochs and is evaluated at epochs 4, 8, 12 and 16
on the existing 11 development intervals. No fresh holdout is used for tuning.

The uncertainty head is experimental and is not calibrated field coverage. This
is a position-only research estimator, not an Android deployment or a complete
NavigationState. Whole-interval fallback remains visible in the report and makes
a candidate ineligible for release, avoiding a retrospective fallback claim.

## Reproduce

Use JDK 17, Python 3.11 and the pinned raw IO-VNBD checkout described in
.github/workflows/joint-motion.yml. The workflow installs CPU PyTorch 2.6.0 and
runs the actual neural gradient and prefix-invariance tests before training.

```sh
python -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
PYTHONPATH=ml/src python -m unittest discover -s ml/tests -p test_joint_sequence.py -v
./gradlew :navigation-core:installDist --no-daemon
PYTHONPATH=ml/src python -m driftzero_ml.train_joint_sequence
```

Current outputs: results/joint_sequence_polar/preregistration.json, training_manifest.json,
training_samples.pt, model checkpoints, every development checkpoint report,
metrics.csv, baseline evidence and selection.json. The selection rule requires
complete finite predictions, no fallback, at least 10% relative median improvement
and no worse p95 or below-10% failure count. A relative gain is not an absolute
10% pass. Locked confirmation requires a frozen eligible candidate first.

[Implementation PR](https://github.com/NotDrake100/driftzero/pull/22).

## Boundaries tested

Hidden-GNSS independence; feature prefix invariance; neural output prefix invariance
for all three architectures; real backward gradients; rotated coordinate roundtrip;
IMU-gap rejection; disjoint split roles; diagnostic-evidence rejection and explicit
fallback disqualification. PyTorch remains an optional desktop dependency.

These tests establish implementation properties, not accuracy. Physical phone
placements remain untested until owner recordings are provided. Existing Android
coast defaults and the other agents' PR branches are not modified by this experiment.

## Measured rounds

All comparisons below use the same 11 exposed development intervals. The fresh
selected Kotlin baseline is 39.84% median, 54.02% p95, and 1/11 below 10%.
These are development scores, not the 35-interval locked benchmark. Each round
scores one physics-only candidate and 12 trained checkpoints; all checkpoint
scores and per-interval metrics are retained, including failures to improve.

| Round | Actual usable training | Lowest-median checkpoint | Median | p95 | Below 10% | Decision |
|---|---|---|---|---|---|---|
| Initial Cartesian | 76 windows, 26 trips, 21 groups | TCN epoch 12 | 33.93% | 88.77% | 0/11 | Reject |
| Raw gyro, more starts | 219 windows, 23 trips, 21 groups | TCN epoch 8 | 30.06% | 120.81% | 1/11 | Reject |
| Polar, balanced loss | 219 windows, 23 trips, 21 groups | GRU epoch 16 | 22.33% | 95.92% | 2/11 | Reject |

The table shows each round's minimum median for diagnosis; it does not select a
release candidate. All three completed rounds have zero evaluation omissions and zero
fallbacks. Every candidate failed the relative acceptance gate. In round two,
S-Vta8:d1000 has 120.81% drift: the better central result conceals a severe tail.
The approved training pool has 24 groups, but exclusions leave 21 with usable
windows. No route- or vehicle-held-out claim is possible from these filenames.

| Round | Source commit | Actions evidence |
|---|---|---|
| Initial | `359f7288904943831bb23f62015a5f050c316061` | [Run 34157730168](https://github.com/NotDrake100/driftzero/actions/runs/34157730168) |
| Raw gyro | `bba1a9c69b7025acc625168db461927bee3d881a` | [Run 34158350064](https://github.com/NotDrake100/driftzero/actions/runs/34158350064) |
| Polar, balanced loss | `5699a7a17505ccc746f30f37236b6a8064ab2eb4` | [Run 34159015449](https://github.com/NotDrake100/driftzero/actions/runs/34159015449) |

Compact evidence is committed under
[initial](../results/joint_motion_20260907/initial/selection.json) and
[raw gyro](../results/joint_motion_20260907/raw_gyro/selection.json), plus
[polar balanced](../results/joint_motion_20260907/polar_balanced/selection.json).
Actions artifacts additionally include tensors, checkpoints, baseline replay
frames and truth, with 30-day retention. Raw public data is pinned to
`118939602e3422d47b8ab0807b623751c3ac135b`; source-file hashes and every accepted
or excluded window are in each training_manifest.json.

Artifact ZIP SHA256:

- Initial: `852b20de827aa04c72f11e4cde7bc2dff901e17af940e73b3bf1acc0782e800b`.
- Raw gyro: `12091b585322a115fa6bbcad1dffa19dccadf4ccc3356d483332ff70ee4342be`.
- Polar balanced: `06d472788b8b107515ee1a9d3a1455315b9162573dfa53ac896daa9a118e9281`.

## What must improve next

Adding a larger network alone is not justified by these results. Heading and
travelled distance must improve together, including stops and long outages.
The polar round removes a representational limit and balances session groups.
Its median improved by 43.95% relative to the development baseline, but its tail
failed the gate. No checkpoint qualifies for locked confirmation. Across the
three rounds, all 36 trained checkpoint evaluations and three physics evaluations
are complete. The 35-interval locked result remains 48.83% median, 268.24% p95,
6/35 below 10%; neither target has passed.
The ten fresh holdout groups stay closed during this development work.

For mountless validation, use the existing [capture procedure](17_CAPTURE_AUDIT.md).
Record separate passenger-seat, console/cup-holder, pickup/repositioning and
handheld sessions. Preserve the raw IMU, hidden GNSS, actual timestamps and drop
counts. Include a sidecar with device model, Android/app version, placement and
pickup times, plus owner-assigned route and vehicle IDs. Reserve whole new drives
before anyone inspects their errors. These data are needed to measure whether
phone movement is being mistaken for vehicle rotation; dataset scores cannot
certify those placements.
