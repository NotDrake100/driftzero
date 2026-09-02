# Requirements traceability

> Internal engineering context only: DriftZero maps to an ISRO intelligent dead-reckoning (IDR) problem for seamless navigation when GNSS is unavailable or degraded. This note is not product branding. Do not put contest identifiers or problem codes in app chrome, GitHub About text, or other user-facing copy.

This document converts the IDR problem framing into testable product requirements. If the problem framing changes, record the retrieval date and update this matrix.

| ID | Requirement or context | DriftZero response | Verification evidence |
|---|---|---|---|
| REQ-01 | Intelligent dead reckoning for seamless navigation | Hybrid learned motion model, probabilistic navigation filter, outage state machine, offline map matching | Blackout replay plus live Android field run |
| REQ-02 | GNSS can fail in tunnels, underpasses, parking, forests, urban canyons, weak signal, or jamming | Scenario taxonomy and GNSS integrity-risk detector | Per-scenario evaluation table and state timeline |
| REQ-03 | Smartphone MEMS is affected by chassis vibration, engine harmonics, braking, potholes, and bumps | Vehicle-frame alignment, causal filters, vibration features, robust measurement noise, tagged-event tests | Bump/idling/braking fixtures and India pilot ablation |
| REQ-04 | No external OBD-II or speedometer | Consumer path uses only phone sensors and offline map data | Airplane-mode, no-cable demo and dependency audit |
| REQ-05 | Lightweight edge-deployable software engine and mobile application | Platform-neutral navigation core, compact quantized student, Android UI | On-device model/latency/memory report |
| REQ-06 | Standalone phone and instant transition to inertial tracking | Explicit fused, degraded, DR, reacquiring, low-confidence state machine | Scripted degradation/recovery integration test |
| REQ-07 | Aim for lane-level accuracy and seamless return | Covariance-aware HMM matcher, topology/layer constraints, gradual recovery blend | Parallel-road/flyover fixtures and recovery-jump metric |
| REQ-08 | Estimate speed and acceleration from noisy accelerometer and gyroscope | Learned causal speed/yaw pseudo-measurement model, filter fallback | Held-out speed MAE and ablation versus filter-only |
| REQ-09 | Filter idling, potholes, bumps, and phone misalignment | Stop detector, transient-impact feature, alignment monitor, uncertainty inflation | `MisalignmentMonitor` tests: 3 s hold triggers remount; 0.3 s spike does not |
| REQ-10 | Smart offline OpenStreetMap matching using road and non-holonomic constraints | Local PMTiles plus compact OSM road graph; online HMM/Viterbi; NHC in filter | Airplane-mode map test and road-hypothesis test suite |
| REQ-11 | AI-based GNSS and INS fusion to mitigate drift | Learned noise/pseudo-measurement adapter inside auditable state estimator | AI-plus-filter ablation and covariance calibration |
| REQ-12 | Models must also accept external IMU data | `SensorFrame` contract and Android/file/external adapters | 200 Hz recorded-stream adapter test |
| REQ-13 | IO-VNBD is the locked evaluation dataset | Versioned importer, trip-level split manifest, locked blackout suite | Reproducible IO-VNBD result bundle |
| REQ-14 | Baseline evaluation needs a preliminary AI model and inferred position plot on an IO-VNBD subset | M0 deliverable generates baseline and learned-model plot from a named subset | Script, configuration, plot, and per-trip CSV |
| REQ-15 | Complex model training can run on cloud or desktop, inference on phone | TimesFM 3 desktop teacher, compact independent student on phone | Research ablation plus ONNX mobile benchmark |
| REQ-16 | Inputs include phone accelerometer, gyro, magnetometer/compass, GNSS | Canonical schema includes each sensor and quality/availability flags | Capture-contract tests on reference devices |
| REQ-17 | Alignment and calibration module | Guided stationary calibration, gravity/bias estimate, vehicle-frame transform | `StationaryCapture` / `YawFromMotion` / `MountProfile` tests; ADR 007 |
| REQ-18 | AI speed and vibration-filtering module | Causal TCN/GRU candidate with uncertainty head and robust preprocessing | Per-event and per-device held-out results |
| REQ-19 | Advanced map matching plus kinematic constraints, example UKF and HMM | ESKF/InEKF candidate with NHC plus HMM road matcher | ADR, filter tests, and map test cases |
| REQ-20 | Innovative AI GNSS and INS fusion | Dynamic measurement/noise adapter and learned pseudo-measurements, experimentally gated | Controlled ablations and rejection criteria |
| REQ-21 | Seamless deficit handler | Deterministic state machine with GNSS gating and recovery blending | State-transition test and UI recording |
| REQ-22 | Real-time smooth UI | 10 Hz output, interpolated display, confidence halo, compact status sheet | Frame/output timing trace and UX rehearsal |
| REQ-23 | Dead-reckoning drift below 10 percent of distance | Locked primary metric and release gate | Median, p90, p95, worst-case per-trip report |
| REQ-24 | Examples: under 5 m over 50 m or under 100 m over 1 km | Include these examples as scenario checks, not as a substituted aggregate metric | Short and long blackout tables |
| REQ-25 | 10 Hz smartphone operation | Sensor capture at supported rate, navigation output at 10 Hz | On-device timestamp audit |
| REQ-26 | Higher rates for external FOG IMU around 200 Hz | Rate-independent core, adapter batching, configurable propagation | Recorded external-stream load test |

## Required baseline evidence package

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
