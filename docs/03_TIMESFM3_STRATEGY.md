# TimesFM 3 strategy

Status as of 2026-09-03: desktop 3.0 run rejected. Desktop 2.5 rerun also rejected. Source: `results/timesfm/summary.md`. timesfm==3.0.1. 3.0 checkpoint `google/timesfm-3.0-pytorch`, seed 26168, MPS, 24.1 min, timesfm_coast 0.6048 vs persist 0.5168. 3.0 weights use `timesfm-non-commercial-license-v1.0` and cannot ship. 2.5 checkpoint `google/timesfm-2.5-200m-pytorch`, Apache-2.0, MPS, 6793.3 s, timesfm_2.5_coast 1.1951 vs persist 0.5168. Distillation from 3.0 was not attempted. 2.5 wrote train-only teacher targets. Neither model is on the phone. The official SIH26168 text does not mention TimesFM.

## Decision

Use TimesFM 3.0 as an optional desktop zero-shot multivariate benchmark only. Do not use it as the Android runtime navigation model. The keep/reject number is reject. timesfm_coast drift p50 was 0.6048 on 35 gated intervals, worse than persist 0.5168 and better than linear 0.7132. Ridge beat TimesFM on speed at 1 s, 2 s, and 5 s. TimesFM beat persist on yaw only. Speed PICP 68 was 0.01 to 0.05. The intervals were too narrow. It stays off the phone.

The official [TimesFM repository](https://github.com/google-research/timesfm) describes TimesFM 3 as the latest native multivariate model with covariate support and quantile outputs. The [official model card](https://huggingface.co/google/timesfm-3.0-pytorch) describes a roughly 0.3B-parameter model with a stacked mixing transformer and variate attention. The checkpoint repository is about 1.3 GB. That makes it scientifically interesting but unsuitable for a low-latency, low-memory 10 Hz phone loop.

## Why it can still add unique value

Vehicle inertial navigation contains coupled time series. Longitudinal acceleration, yaw rate, vibration, accepted GNSS innovations, recent speed, road curvature, and sensor-quality signals jointly describe motion regimes. A pretrained multivariate forecaster may offer useful priors or calibrated forecast shapes without task-specific fine-tuning.

The value is a hypothesis, not an assumption. TimesFM's broad time-series pretraining does not prove transfer to phone IMU dynamics. It must beat simple and domain-specific baselines on held-out trips.

## Research questions

1. Can zero-shot TimesFM predict 0.5 to 3 seconds of forward speed and yaw-rate evolution better than persistence and constant-turn-rate-and-velocity?
2. Do its quantiles improve uncertainty calibration during bumps, stops, or GNSS degradation?
3. Do forecasts add information beyond a compact supervised TCN or GRU?
4. Can teacher outputs improve a small student's tail errors or data efficiency?
5. Does improvement persist across unseen trips, phones, routes, and countries?

## Input design

Start with synchronized 10 Hz channels. Do not feed raw latitude and longitude as ordinary scalars. Prefer local, physically meaningful channels:

| Group | Candidate past-only channel |
|---|---|
| Inertial | vehicle-frame accel x/y/z, gyro x/y/z, norms, jerk |
| Vibration | band energy, rolling dispersion, bump probability |
| State | prior causal speed, yaw rate, stop probability, covariance summary |
| GNSS | accepted speed/heading, accuracy, innovation, fix age, health score |
| Map | road curvature candidates, heading disagreement, junction distance |
| Quality | missing flag, sensor accuracy, saturation, mount confidence |

Use explicit missing masks if supported by the experiment. Normalize using training partitions only. A feature derived from an accepted GNSS update becomes stale or masked after outage entry. Never insert score-only GNSS into an outage context.

## Target experiments

### Experiment A: short-horizon motion forecasting

- Context: 12.8, 25.6, and 51.2 seconds, aligned to patch requirements as needed.
- Horizon: 0.8, 1.6, and 3.2 seconds.
- Targets: forward speed, yaw rate, and optional acceleration trend.
- Metrics: MAE, p95 absolute error, CRPS or pinball loss, interval coverage, scenario slices.

### Experiment B: innovation and drift-risk forecasting

- Target the magnitude or direction of future accepted GNSS innovation during healthy periods.
- Use it only as a risk or covariance feature during outages.
- Avoid predicting absolute geographic truth.

### Experiment C: teacher distillation

- Train a causal TCN or GRU on ground-truth task targets.
- Add teacher point forecasts or quantile-derived soft targets.
- Compare identical students with and without teacher loss.
- Keep distillation only if several held-out seeds and groups improve without calibration regression.

## Baselines

Every TimesFM result must be paired with:

- persistence;
- constant velocity;
- constant turn rate and velocity;
- filter-only state prediction;
- linear/ridge autoregression;
- compact TCN;
- compact GRU.

The baseline budget should be lower than the TimesFM budget, and the comparison should include wall time, peak memory, and energy implications.

## Keep or reject gate

TimesFM earns a place in the project story only when:

- it improves a preregistered held-out primary metric or uncertainty calibration;
- improvement appears across multiple complete trips, not one dramatic trace;
- no leakage or future context is found;
- a small student retains a meaningful share of the benefit;
- the runtime student meets mobile latency and model-size gates.

If those gates fail, report the negative result. The architecture remains complete with the compact supervised model.

The 2026-09-03 desktop 3.0 run failed this gate. The same-day 2.5 rerun also failed (timesfm_2.5_coast 1.1951 vs persist 0.5168). Report: `results/timesfm/summary.md`. 3.0 screening row: `results/io_vnbd_screening_v1/summary.md` timesfm_coast. 2.5 artifacts: `results/timesfm/v2.5/`.

## Distillation objective sketch

For target \(y\), student prediction \(\hat{y}_s\), teacher point forecast \(\hat{y}_t\), and student log variance \(s\):

\[
\mathcal{L} = \lambda_1 \operatorname{Huber}(y, \hat{y}_s)
+ \lambda_2 \operatorname{Huber}(\hat{y}_t, \hat{y}_s)
+ \lambda_3 \left(e^{-s}(y-\hat{y}_s)^2 + s\right)
+ \lambda_4 \mathcal{L}_{temporal}
\]

Teacher loss weight must decay when the teacher is poorly calibrated or disagrees with reliable labels. Distillation partitions must remain inside the training fold.

## Mobile student target

- Causal TCN or GRU, chosen through measurement.
- Quantized int8 where accuracy permits.
- Preferred artifact below 3 MB and p95 inference below 20 ms on the reference phone.
- ONNX Runtime Mobile CPU/XNNPACK first; test NNAPI per device rather than assuming it is faster.
- Emit bounded uncertainty and validate quantization calibration.

The [ONNX Runtime Mobile documentation](https://onnxruntime.ai/docs/tutorials/mobile/) and [quantization guidance](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html) are the implementation references.

## Reproducibility manifest

Each run records:

- source code commit;
- TimesFM package and checkpoint identifier;
- dataset manifest and hashes;
- trip split and blackout manifest;
- feature schema and scaler hash;
- context/horizon and target definition;
- seed and hardware;
- baseline versions;
- per-trip metrics and failures;
- student export hash and mobile benchmark.

## Production boundary

As of 2026-09-03, TimesFM 3.0 is the current public release (Hugging Face `google/timesfm-3.0-pytorch`). TimesFM 3.0 weights use `timesfm-non-commercial-license-v1.0` (non-commercial, non-production). TimesFM 2.5 weights remain Apache-2.0. This does not change the decision that TimesFM is not on the phone.

The TimesFM source and weights have separate terms. Source availability does not make every checkpoint a production dependency. DriftZero keeps the checkpoint out of the product. This is also the correct engineering boundary for app size, latency, and reliability.

A desktop zero-shot run is in `results/timesfm/`. timesfm==3.0.1, checkpoint `google/timesfm-3.0-pytorch`, seed 26168, MPS, 24.1 min. Research use is allowed. Distillation was not attempted. timesfm_coast drift p50 0.6048 on 35 gated intervals, worse than persist 0.5168, better than linear 0.7132. Verdict: reject. Ridge beat TimesFM on speed at 1 s, 2 s, and 5 s. TimesFM beat persist on yaw only. Speed PICP 68 was 0.01 to 0.05. It stays off the phone.

## Addendum: TimesFM 2.5 (2026-09-03)

Same protocol, new files under `results/timesfm/v2.5/`. Checkpoint `google/timesfm-2.5-200m-pytorch` revision `1d952420fba87f3c6dee4f240de0f1a0fbc790e3`, Apache-2.0, MPS, wall 6793.3 s. Univariate speed only. timesfm_2.5_coast drift p50 1.1951 on 35 gated intervals, worse than persist 0.5168. XReg (`xreg + timesfm` numpy ridge on causal forward accel, |omega_z|, stop flag, horizon last-causal-hold) drift p50 1.1994. Coast speed mae p50: persist 3.439, 2.5 3.620, XReg 3.274. Held-out 5 s speed MAE: persist 3.994, 2.5 4.516, XReg 4.064, ridge 3.744. Speed PICP 68 at 5 s: 0.023 (2.5), 0.063 (XReg). Verdict: reject. ADR 002 is unchanged. 2.5 still cannot run on the phone.

Train-only teacher targets: `results/timesfm/v2.5/teacher_targets.npz`, 10356 windows, stride 50, schema in `teacher_targets.schema.json`. Apache-2.0. This run did not train a student.


