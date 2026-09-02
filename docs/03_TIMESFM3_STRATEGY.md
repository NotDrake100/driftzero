# TimesFM 3 strategy

## Decision

Use TimesFM 3 as an optional desktop zero-shot multivariate benchmark, probabilistic teacher, and research tool. Do not use it as the Android runtime navigation model.

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

The TimesFM source and weights have separate terms. Source availability does not make every checkpoint a production dependency. DriftZero keeps the checkpoint out of the product and exports only an independently trained compact student after the team has reviewed all applicable terms. This is also the correct engineering boundary for app size, latency, and reliability. If a desktop experiment is ever run, it is research-only and cannot ship 3.0 weights.

