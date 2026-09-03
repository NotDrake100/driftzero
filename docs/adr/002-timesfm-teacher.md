# ADR 002: TimesFM 3 as research teacher, not mobile runtime

- Status: accepted. Desktop 3.0 run rejected.
- Date: 2026-09-02

## Context

TimesFM 3 supports native multivariate zero-shot forecasting and quantiles, but its roughly 0.3B-parameter checkpoint is far larger than an appropriate 10 Hz mobile navigation component. Domain transfer from general time series to phone IMU is unproven.

## Decision

Evaluate TimesFM 3.0 on desktop for short-horizon motion and uncertainty forecasting. Distill only demonstrated held-out benefit into a compact independent causal student. Distillation from 3.0 is ambiguous under the weight license, so it is not used. 2.5 is the Apache-2.0 fallback teacher. No Android module depends on TimesFM or its checkpoint. 3.0 cannot ship.

## Consequences

- The foundation-model use is scientifically testable.
- Mobile latency, reliability, and packaging remain credible.
- A negative experiment is acceptable and must be reported.
- Separate terms for source, weights, data, and student artifacts still require review before deployment.
- As of 2026-09-03, TimesFM 3.0 is the current public release (Hugging Face `google/timesfm-3.0-pytorch`). 3.0 weights use `timesfm-non-commercial-license-v1.0` (non-commercial, non-production). 2.5 weights remain Apache-2.0. This does not change the decision that TimesFM is not on the phone.
- Desktop run 2026-09-03: timesfm==3.0.1, checkpoint `google/timesfm-3.0-pytorch`, seed 26168, MPS, 24.1 min. Research use is allowed. Distillation was not attempted. timesfm_coast drift p50 0.6048 on 35 gated intervals, worse than persist 0.5168, better than linear 0.7132. Ridge beat TimesFM on speed at 1 s, 2 s, and 5 s. TimesFM beat persist on yaw only. Speed PICP 68 was 0.01 to 0.05. Verdict: reject. It stays off the phone. Source: `results/timesfm/summary.md`. Screening row: `results/io_vnbd_screening_v1/summary.md` timesfm_coast.

