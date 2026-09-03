# ADR 005: Motion pseudo-measurement is a filter update, not a plot

- Status: accepted
- Date: 2026-09-02

## Context

ADR 001 already chose a hybrid: a compact causal student produces motion pseudo-measurements; an ESKF owns state. The phone had GNSS coast and then a sibling ESKF, but no IMU student in the 10 Hz loop. A desktop GRU plot would not change the puck.

## Decision

1. Features are causal IMU windows only. No future samples. No GNSS fields inside a blackout feature vector.
2. The live phone path uses `ZuptAccelMotionModel`. When `models/motion_student_v1/linear.json` is present at assemble, PoseStore loads those speed weights on device. `linear_dp.json` is not packed unless `-Pdriftzero.packLearnedImu=true`. Δp MAE is worse than freeze. Do not claim it. `gru.json` is not packed. TimesFM never enters the APK. The TimesFM experiment is designed, not run. ONNX is not a phone dependency and nothing exports it.
3. `MotionPseudoRuntime` infers on the navigation worker (10 Hz tick or engine emit). Sensor callbacks only copy samples.
4. The ESKF consumes `MotionPseudoMeasurement` through `ingestMotionPseudo`: ZUPT when idle/stop is high, otherwise a gated forward-speed update with `R = exp(logSpeedVariance)`.
5. The same tick may inject `DisplacementPseudoMeasurement` through `ingestDisplacementPseudo`: RoNIN/TLIO Δp in the start-of-window HACF, `R_ii = exp(2 log σ)`, χ² gate 11.345 (TLIO, 3 dof, 99th percentile), overlapping windows inflate R ×10. A cloned ENU pose at window start is the prior. Full 15-state stochastic cloning is not implemented. A rejected update inflates position P instead of injecting. The student must not overwrite state.

## Consequences

- Filter-only fallback remains if the window is too short or inference is skipped.
- Heuristic speed is a weak measurement. High log-variance must keep the update from dominating GNSS or strapdown.
- IO-VNBD training waits on a local LFS checkout. Synthetic metrics are not product scores.
