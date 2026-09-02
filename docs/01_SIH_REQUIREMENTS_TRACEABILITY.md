# SIH26168 requirements traceability

Source of record: the [official SIH 2026 problem statement page](https://www.sih.gov.in/sih2026PS), problem statement ID 26168. This document converts the prose into testable requirements. If the official page changes, record the retrieval date and update this matrix.

| ID | Official requirement or context | DriftZero response | Verification evidence |
|---|---|---|---|
| SIH-01 | Intelligent dead reckoning for seamless navigation | Hybrid learned motion model, probabilistic navigation filter, outage state machine, offline map matching | Blackout replay plus live Android field run |
| SIH-02 | GNSS can fail in tunnels, underpasses, parking, forests, urban canyons, weak signal, or jamming | Scenario taxonomy and GNSS integrity-risk detector | Per-scenario evaluation table and state timeline |
| SIH-03 | Smartphone MEMS is affected by chassis vibration, engine harmonics, braking, potholes, and bumps | Vehicle-frame alignment, causal filters, vibration features, robust measurement noise, tagged-event tests | Bump/idling/braking fixtures and India pilot ablation |
| SIH-04 | No external OBD-II or speedometer | Consumer path uses only phone sensors and offline map data | Airplane-mode, no-cable demo and dependency audit |
| SIH-05 | Lightweight edge-deployable software engine and mobile application | Platform-neutral navigation core, compact quantized student, Android UI | On-device model/latency/memory report |
| SIH-06 | Standalone phone and instant transition to inertial tracking | Explicit fused, degraded, DR, reacquiring, low-confidence state machine | Scripted degradation/recovery integration test |
| SIH-07 | Aim for lane-level accuracy and seamless return | Covariance-aware HMM matcher, topology/layer constraints, gradual recovery blend | Parallel-road/flyover fixtures and recovery-jump metric |
| SIH-08 | Estimate speed and acceleration from noisy accelerometer and gyroscope | Learned causal speed/yaw pseudo-measurement model, filter fallback | Held-out speed MAE and ablation versus filter-only |
| SIH-09 | Filter idling, potholes, bumps, and phone misalignment | Stop detector, transient-impact feature, alignment monitor, uncertainty inflation | Tagged scenario tests and misalignment-trigger test |
| SIH-10 | Smart offline OpenStreetMap matching using road and non-holonomic constraints | Local PMTiles plus compact OSM road graph; online HMM/Viterbi; NHC in filter | Airplane-mode map test and road-hypothesis test suite |
| SIH-11 | AI-based GNSS and INS fusion to mitigate drift | Learned noise/pseudo-measurement adapter inside auditable state estimator | AI-plus-filter ablation and covariance calibration |
| SIH-12 | Models must also accept external IMU data | `SensorFrame` contract and Android/file/external adapters | 200 Hz recorded-stream adapter test |
| SIH-13 | IO-VNBD is mandatory | Versioned importer, trip-level split manifest, locked blackout suite | Reproducible IO-VNBD result bundle |
| SIH-14 | Screening needs preliminary AI model and inferred position plot on IO-VNBD subset | M0 deliverable generates baseline and learned-model plot from a named subset | Script, configuration, plot, and per-trip CSV |
| SIH-15 | Complex model training can run on cloud or desktop, inference on phone | TimesFM 3 desktop teacher, compact independent student on phone | Research ablation plus ONNX mobile benchmark |
| SIH-16 | Inputs include phone accelerometer, gyro, magnetometer/compass, GNSS | Canonical schema includes each sensor and quality/availability flags | Capture-contract tests on reference devices |
| SIH-17 | Alignment and calibration module | Guided stationary calibration, gravity/bias estimate, vehicle-frame transform | Calibration report and transform fixtures |
| SIH-18 | AI speed and vibration-filtering module | Causal TCN/GRU candidate with uncertainty head and robust preprocessing | Per-event and per-device held-out results |
| SIH-19 | Advanced map matching plus kinematic constraints, example UKF and HMM | ESKF/InEKF candidate with NHC plus HMM road matcher | ADR, filter tests, and map test cases |
| SIH-20 | Innovative AI GNSS and INS fusion | Dynamic measurement/noise adapter and learned pseudo-measurements, experimentally gated | Controlled ablations and rejection criteria |
| SIH-21 | Seamless deficit handler | Deterministic state machine with GNSS gating and recovery blending | State-transition test and UI recording |
| SIH-22 | Real-time smooth UI | 10 Hz output, interpolated display, confidence halo, compact status sheet | Frame/output timing trace and UX rehearsal |
| SIH-23 | Dead-reckoning drift below 10 percent of distance | Locked primary metric and release gate | Median, p90, p95, worst-case per-trip report |
| SIH-24 | Examples: under 5 m over 50 m or under 100 m over 1 km | Include official examples as scenario checks, not as substituted aggregate metric | Short and long blackout tables |
| SIH-25 | 10 Hz smartphone operation | Sensor capture at supported rate, navigation output at 10 Hz | On-device timestamp audit |
| SIH-26 | Higher rates for external FOG IMU around 200 Hz | Rate-independent core, adapter batching, configurable propagation | Recorded external-stream load test |

## Required SIH screening package

Before polishing the full app, produce:

1. exact IO-VNBD download/source manifest and integrity hashes;
2. named synchronized subset and trip-level split manifest;
3. filter-only and preliminary learned-model configurations;
4. artificial blackout intervals selected without looking at model error;
5. predicted trajectory plot with ground truth clearly labeled score-only;
6. drift ratio and endpoint error by blackout;
7. a one-page architecture figure and no-external-hardware statement;
8. a short failure analysis for the worst trajectories.

## Interpretation notes

- “Lane-level” is treated as an objective whose confidence must be measured. It is not a universal guarantee from consumer sensors.
- “AI-based fusion” does not require replacing the state estimator with a black box. A learned pseudo-measurement or adaptive covariance module integrated with a filter is a stronger, safer implementation.
- The consumer product is phone-only. The external IMU input is a reusable engine boundary, not a hidden dependency in the demo.
- GNSS interference is described as a condition the system observes. The app does not claim certified jamming or spoofing classification.

