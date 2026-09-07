# Real-data joint motion experiment

Date: 2026-09-07. Research only. The full-benchmark below-10% target remains open.

The reviewed diagnostics and split freeze are on main. The next experiment now
has executable real-data training, rather than only a preregistration or synthetic
smoke test. [ADR 018](adr/018-real-data-joint-sequence.md) defines the experiment.

## What is new

Three small residual-velocity networks share a causal input boundary: feedforward,
TCN and GRU. Integration of their two-dimensional velocity estimates changes both
travelled distance and heading. A separate physics-only course/gyro candidate
measures the effect of initialization and integration without learned weights.
The comparison baseline is the freshly rerun selected Kotlin coast.

Only the frozen 24 training groups can supply training windows. The ten holdout
groups remain closed. Training begins from four deterministic points per trip,
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

Outputs: results/joint_sequence/preregistration.json, training_manifest.json,
training_samples.pt, model checkpoints, every development checkpoint report,
metrics.csv, baseline evidence and selection.json. The selection rule requires
complete finite predictions, no fallback, at least 10% relative median improvement
and no worse p95 or below-10% failure count. A relative gain is not an absolute
10% pass. Locked confirmation requires a frozen eligible candidate first.

Source checkpoint: `359f7288904943831bb23f62015a5f050c316061`.
[Experiment run](https://github.com/NotDrake100/driftzero/actions/runs/34157730168).
[Implementation PR](https://github.com/NotDrake100/driftzero/pull/22).

## Boundaries tested

Hidden-GNSS independence; feature prefix invariance; neural output prefix invariance
for all three architectures; real backward gradients; rotated coordinate roundtrip;
IMU-gap rejection; disjoint split roles; diagnostic-evidence rejection and explicit
fallback disqualification. PyTorch remains an optional desktop dependency.

These tests establish implementation properties, not accuracy. Physical phone
placements remain untested until owner recordings are provided. Existing Android
coast defaults and the other agents' PR branches are not modified by this experiment.
