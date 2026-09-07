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
