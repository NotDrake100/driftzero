# ADR 001: Hybrid learned model and navigation filter

- Status: accepted
- Date: 2026-09-02

## Context

Raw phone inertial integration drifts quickly, while an end-to-end black-box position model is difficult to validate, constrain, and recover. The SIH problem asks for AI-based fusion and gives UKF/HMM as examples rather than mandating a pure neural system.

## Decision

Use a compact causal student to produce motion pseudo-measurements, uncertainty, or bounded noise adaptation. The packed phone student is `linear.json`. GRU is analysis-only. Fuse them with GNSS, inertial propagation, and vehicle constraints in an ESKF. Keep a filter-only fallback. TimesFM is not this student. TimesFM 3.0 is designed, not run.

## Consequences

- We can ablate learning independently.
- Units, covariance, gating, and recovery remain explicit.
- The student must be calibrated, not merely accurate on average.
- More integration tests are required at neural/filter boundaries.

