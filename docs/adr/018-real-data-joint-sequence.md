# ADR 018: Execute a joint-motion experiment on real training groups

Date: 2026-09-07. Research only. No Android estimator change.

PR 17 is merged and freezes 24 training groups, 12 exposed development groups,
19 locked groups and 10 closed holdout groups. PR 20 preregisters models but has
not trained a real-data candidate. This independent implementation executes a
bounded experiment without modifying the other agents' branches or their results.

Predict residual velocity in the horizontal frame of the last trusted course,
then integrate to position. This jointly changes distance and heading. Use a
24-wide feedforward model, causal TCN and unidirectional GRU. Output residuals are
bounded to ±10 m/s per axis and total speed to 55 m/s. Inputs are calibrated
accelerometer, selected physical-up gyro, availability/quality channels, actual dt,
elapsed outage time, last trusted speed and integrated relative heading. Only
prefix GNSS determines initialization/calibration. No masked fix enters features.

Use four deterministic training starts per trip (20/40/60/80% of unique fixes),
up to 90 seconds each. Training labels are fresh GNSS positions at their own
sensor timestamps, with no future interpolation. Reject training windows with
IMU gaps, fewer than three labels or reference speed above 55 m/s; report all
exclusions and resulting group coverage. These training exclusions never remove
evaluation intervals. No held-out raw file is parsed by the training loader.

Normalize on training features only. Train all three architectures for 16 epochs,
Adam 0.001, batch 8, seed 26168, two CPU threads. Score checkpoints at epochs
4/8/12/16 against the existing 11 development intervals. Also score the course-seed
and gyro physics path without a model, since initialization differs from Kotlin.
Loss combines relative robust position error and a small heteroscedastic NLL.
The accumulated sigma is a research uncertainty estimate, not certified coverage.

This model is a position-only research estimator, not a NavigationState with
verified velocity/heading/health fields. Prediction gaps retain the archived
baseline for the entire interval and are explicitly marked; because that fallback
is retrospective, ANY such interval makes a candidate ineligible for release.
A future live adapter must switch causally at the failure time.

Select only complete finite development evidence with zero fallbacks, at least
10% relative median gain over the fresh Kotlin baseline, and no worse p95 or
count failing 0.10. Save decision/checkpoint hashes before locked confirmation.
This workflow does not open the fresh holdout or automatically release a model.
A relative improvement is not an absolute below-10% result.

New modules: joint_sequence.py, joint_network.py, train_joint_sequence.py. The
first is stdlib-only; the PyTorch dependency stays in desktop research. Python
prefix-invariance, hidden-fix, coordinate, gap and split tests guard the boundary.
Results, raw per-interval CSV, training manifest, tensors, checkpoints and source
provenance are archived by .github/workflows/joint-motion.yml. Artifacts expire;
compact reports must also be committed. No measured score is asserted in this ADR.

## Second declared round: retain raw gyro observations

The initial 13 development candidates (physics baseline and twelve trained
checkpoints) all failed the tail requirement. Best median was 33.93%; no candidate
was selected. This result is retained separately, not overwritten.

The first model input discarded weak-calibration gyro information along with the
conservative physics path. In round two, retain the observed projected gyro even
when weak, plus all three original IO-VNBD gyro columns and their availability.
The physics path still holds heading when calibration is weak. Raw columns are
not claimed to be Android XYZ. Timestamp rewinds are trimmed identically to the
exporter so a later suffix cannot overwrite earlier samples. Extracted raw inputs
contain only timestamps and gyro values, no GNSS fields.

Increase training coverage to twelve deterministic starts per trip, fractions
1/14 through 12/14, while retaining the 90-second horizon, 16 epochs, architecture
width, losses and development gate. This is a joint input/coverage experiment;
any gain cannot be attributed to one of these changes alone. The frozen train,
development and closed holdout roles remain unchanged. Record it as raw_gyro_v2
in results/joint_sequence_raw. No locked confirmation or Android change is implied.

## Third declared round: represent stops and sharp turns

The ±10 m/s Cartesian residual cannot stop a 25 m/s straight-motion prior or
rotate its velocity through 90 degrees. This is a model-class restriction, not
an empirical accuracy claim. Add an explicit polar head: heading correction
π*tanh(output), speed clamp(norm(base)+55*tanh(output), 0, 55). At zero output the
physics prior is preserved. Store head_mode with every checkpoint so older
Cartesian weights cannot be silently interpreted as polar outputs. Tests force a
25 m/s prior through a 90-degree turn, full stop and speed saturation.

Use the raw-gyro inputs and twelve-start schedule from round two. Average label
loss within each window, and weight windows by inverse training session-group
frequency. Dense GNSS streams and multiple sibling trips must not dominate the
objective merely by having more labels/windows. Keep seed, epoch budget and all
evaluation gates unchanged. This jointly changes head capacity and weighting;
report it as polar_balanced_v3, without attributing a gain to only one change.
All rounds' measured outputs remain separate. No holdout labels are opened.
