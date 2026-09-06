# ADR 017: Evaluation checks before candidate selection

Date: 2026-09-06. Status: accepted for this campaign. Not an accuracy result.

## Context

D may prepare parity and leakage checks after A is on main. B produced no
eligible map candidate. C is preregistered and has not trained a candidate.
Locked confirmation on the reused 35 must not run until one development
candidate passes the recorded relative gate.

## Decision

1. Freeze the comparison matrix now: deterministic baseline, model-only,
   map-only, combined. Every cell starts unselectable.
2. Reject DIAGNOSTIC_ONLY, oracle, and reference-substitution reports as
   candidate evidence. Recompute drift as endpoint error / truth path length.
3. Keep the locked 35 IDs, masks, and scoring denominator unchanged.
4. Keep the fresh holdout closed. Do not enable the B posterior-mean overlay.
5. Open selection and locked confirmation only after an eligible candidate
   exists. That decision is a later measured step, not this ADR.

## Consequences

- These checks can land without claiming a 10% pass.
- A later D runner may import B's adapter and C's joint student only when
  a preregistered config becomes eligible.
