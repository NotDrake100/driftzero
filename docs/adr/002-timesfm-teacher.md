# ADR 002: TimesFM 3 as research teacher, not mobile runtime

- Status: accepted
- Date: 2026-09-02

## Context

TimesFM 3 supports native multivariate zero-shot forecasting and quantiles, but its roughly 0.3B-parameter checkpoint is far larger than an appropriate 10 Hz mobile navigation component. Domain transfer from general time series to phone IMU is unproven.

## Decision

Evaluate TimesFM 3 on desktop for short-horizon motion and uncertainty forecasting. Distill only demonstrated held-out benefit into a compact independent causal student. No Android module depends on TimesFM or its checkpoint.

## Consequences

- The foundation-model use is scientifically testable.
- Mobile latency, reliability, and packaging remain credible.
- A negative experiment is acceptable and must be reported.
- Separate terms for source, weights, data, and student artifacts still require review before deployment.
- As of 2026-09-03, TimesFM 3.0 is the current public release (Hugging Face `google/timesfm-3.0-pytorch`). 3.0 weights use `timesfm-non-commercial-license-v1.0` (non-commercial, non-production). 2.5 weights remain Apache-2.0. This does not change the decision that TimesFM is not on the phone. If the experiment is ever run, it is research-only and cannot ship 3.0 weights.

