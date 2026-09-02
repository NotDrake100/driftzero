# Evaluation protocol

## 1. Purpose

This protocol prevents the two easiest ways to overstate dead-reckoning performance: correlated train/test windows and hidden access to ground-truth GNSS during an artificial outage. It also measures product behavior that a single endpoint error misses.

## 2. Pre-registration

Before running a locked test, commit or hash:

- dataset and file manifest;
- complete-trip split assignments;
- blackout interval manifest;
- feature schema and normalization source;
- model/filter/map configuration;
- primary and secondary metrics;
- seed and software commit;
- exclusion rules;
- reference device and runtime settings.

Do not change a locked test after seeing results. Create a new version and retain the old result.

## 3. Baseline ladder

| ID | System | Question answered |
|---|---|---|
| B0 | Freeze last GNSS point | What does no DR do? |
| B1 | Constant velocity/course | Does any temporal extrapolation help? |
| B2 | Strapdown/filter only | What does physics alone contribute? |
| B3 | B2 plus NHC/stop constraints | What do vehicle constraints contribute? |
| B4 | B3 plus compact learned motion | Does task-specific learning help? |
| B5 | B4 plus HMM map matching | Does road topology help without hiding errors? |
| B6 | B5 plus TimesFM-distilled student | Did the foundation-model experiment add held-out value? |

TimesFM zero-shot forecasting is also scored separately against forecasting baselines. It is not compared as if the full desktop checkpoint were a deployable phone system.

## 4. Primary metric

For a blackout beginning at \(t_0\) and ending at \(t_1\):

\[
r_{drift} = \frac{d(\hat{p}_{t_1}, p_{t_1})}{\max(L_{truth}(t_0,t_1), \epsilon)}
\]

where \(d\) is horizontal geodesic endpoint error and \(L_{truth}\) is the ground-truth path length during the blackout. Report as both a fraction and percentage. Very short or stationary intervals require a separate absolute-error view because the ratio can be unstable.

The SIH target is below 10 percent. Report every interval and aggregate p50, p90, p95, maximum, bootstrap confidence intervals, and count. Never remove failures from the denominator without a predeclared data-quality reason.

## 5. Secondary metrics

- endpoint error in metres;
- trajectory RMSE and absolute trajectory error;
- along-track and cross-track errors;
- speed MAE and p95 error;
- heading circular MAE and p95 error;
- road-segment accuracy, route-edge edit distance, wrong parallel-road rate;
- calibrated confidence: coverage at declared radii, reliability curve, NLL where valid;
- time until low-confidence threshold;
- outage detection delay and false transition rate;
- reacquisition time, correction distance, maximum displayed jump;
- 10 Hz continuity, p50/p95 latency, dropped samples;
- peak memory, package/model size, CPU, thermal and battery measurements.

## 6. Required slices

Report by:

- blackout duration and distance;
- straight, turn, U-turn, junction, roundabout;
- stationary/idle, slow stop-go, urban, highway;
- smooth versus rough road, bump/speed breaker;
- phone, IMU vendor, mount, vehicle class;
- route seen/unseen and country;
- GNSS absent versus degraded/inconsistent;
- map topology complexity;
- confidence tier.

## 7. Leakage checklist

The evaluator must assert:

- all input GNSS fields are absent or masked in the blackout;
- no future samples enter causal windows;
- no bidirectional model or full-sequence smoother is used in live metrics;
- scalers and alignment-learning parameters derive from train data or causal trip history only;
- map matching does not use the hidden truth route;
- route instruction or destination data, if used, is declared and separately ablated;
- train and test contain no shared trip or duplicate segment recording;
- student training never uses teacher outputs generated from test labels.

## 8. Statistical reporting

- Treat trip or route group as the resampling unit, not individual 10 Hz rows.
- Use paired per-interval comparisons for ablations.
- Report effect size and confidence interval, not only a p-value.
- Repeat stochastic training with at least three seeds for final comparisons.
- Identify model-selection versus locked-test results.
- Publish worst-case traces and explain failure modes.

## 9. Confidence validation

A 95 percent confidence region should contain truth near 95 percent of evaluated timestamps in comparable conditions. Measure empirical coverage and sharpness. Recalibrate on validation data only. Use confidence to trigger UI modes and map feedback gates, then verify those thresholds on locked data.

## 10. Mobile benchmark

On each reference device:

1. record model warm-up separately;
2. run a representative 30-minute replay in airplane mode;
3. capture p50/p90/p95/max inference and end-to-end output latency;
4. capture CPU, memory, thermal throttling, and battery delta;
5. test foreground and screen-on navigation conditions;
6. test two-hour soak for NaN, queue growth, output gaps, and crash;
7. rerun after int8 quantization and compare accuracy/calibration.

Android sensor rates must respect platform behavior. The [Android sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) documents listener sampling limits and the high-sampling-rate permission. The product target is 10 Hz output, not an unjustified maximum raw rate.

## 11. Result bundle

Every published report contains:

```text
manifest.json
config.yaml
split_manifest.yaml
blackout_manifest.yaml
metrics_per_interval.csv
metrics_summary.json
state_timeline.csv
trajectory_estimate.geojson
trajectory_truth_score_only.geojson
latency.csv
failures.md
checksums.sha256
```

Plots must label interpolation, smoothing, and score-only truth. A demo screenshot is not a benchmark.

## 12. Release decision

Release only when the primary SIH drift gate, leakage checks, mobile timing, deterministic replay, offline behavior, and safety fallback pass. A visually attractive trace cannot override a failed integrity check.

