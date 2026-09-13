# ADR 016: Causal joint distance and heading student

Date: 2026-09-06. Status: accepted for this experiment. Not an accuracy result.

## Context

Task A showed seed error is 0 m on the locked suite. Reference heading, then
distance, is the remaining gap. Speed-only training is not the next justified
experiment. The frozen split is on main after PR 17.

## Decision

1. Train only the 24 frozen train session groups. Validate on development.
   Keep the 10 fresh holdout groups closed. Keep S-Vtb3 excluded.
2. Predict travelled-distance increment and heading change jointly, plus
   uncertainty. Fall back to persist-speed/heading when heads are missing or
   uncertainty is above the preregistered gate.
3. Report blackout position drift (endpoint error over truth path length),
   along-track, cross-track, and heading error. Training loss and speed MAE
   are not sufficient to declare a candidate.
4. Reuse the existing causal IMU student (`learned_imu.py`) for the linear
   baseline. Preregister compact TCN and GRU configs. Do not assume GPU.
   Do not import TimesFM. Do not change the Android default coast.
5. D owns shared runner wiring and candidate selection. C does not edit
   `eval_kotlin_replay.py`.

## Consequences

- Full IO-VNBD training is a later measured run, not implied by this ADR.
- Synthetic CPU smoke is not candidate evidence.
- Map-only overlays stay rejected per Task B. Combined work waits for an
  eligible motion candidate.
