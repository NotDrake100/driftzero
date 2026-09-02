# SIH26168 requirements traceability

Source of record: the [official SIH 2026 problem statement page](https://www.sih.gov.in/sih2026PS), problem statement ID 26168, modal `#ViewProblemStatement26168`, retrieved 2026-09-03. Independent fetch log: [docs/refs/SIH26168_EVIDENCE.md](refs/SIH26168_EVIDENCE.md). This document converts the official prose into testable requirements. Quoted phrases are the sponsor's wording. If the official page changes, record the retrieval date and update this matrix.

| ID | Official requirement or context | DriftZero response | Verification evidence |
|---|---|---|---|
| SIH-01 | Official title: "AI-ML based Intelligent Dead Reckoning system for seamless navigation" (ISRO, Department of Space, Software, Smart Vehicles) | Hybrid learned motion model, probabilistic navigation filter, outage state machine, offline map matching | Blackout replay plus live Android field run |
| SIH-02 | GNSS drops in "long underground tunnel/ underpass, a multi-level parking lot, a dense forested highway, or a deep urban canyon". Signals are vulnerable to blockage and "unintentional electromagnetic interferences from variety of sources such as jamming." Jamming is named. Spoofing is not named. | Scenario taxonomy and GNSS integrity-risk detector | Per-scenario evaluation table and state timeline |
| SIH-03 | "The smartphone is subjected to severe chassis vibrations, engine harmonics, sudden braking, and road potholes." | Vehicle-frame alignment, causal filters, vibration features, measurement-noise inflation, tagged-event tests | Bump/idling/braking fixtures and India pilot ablation |
| SIH-04 | Hard prohibition of an OBD-II speedometer feed and of "any physical connection to the vehicle’s internal computer" | Consumer path uses only phone sensors and offline map data | Airplane-mode, no-cable demo and dependency audit |
| SIH-05 | "lightweight, edge-deployable software engine and mobile application" that turns a standalone smartphone into an IDR system with GNSS Fusion | Platform-neutral navigation core, compact quantized student, Android UI | On-device model/latency/memory report |
| SIH-06 | Standalone phone and transition to inertial tracking when GNSS drops | Explicit fused, degraded, DR, reacquiring, low-confidence state machine | Scripted degradation/recovery integration test |
| SIH-07 | Official verb: "maintaining lane-level accuracy" without a vehicle-computer connection. Not "aim for". Team treats this as an aim, not a claim from consumer MEMS. | Covariance-aware HMM matcher, topology/layer constraints, gradual recovery blend | Parallel-road/flyover fixtures and recovery-jump metric |
| SIH-08 | Predict speed and acceleration "solely from the smartphone’s noisy accelerometer/gyro inputs" | Learned causal speed/yaw plus TLIO-style Δp, filter fallback | `ml/tests/test_causal_imu.py`, `ml/tests/test_motion_student.py`, `ml/tests/test_learned_imu.py`, `ZuptAccelMotionModel` + `ingestMotionPseudo` + `ingestDisplacementPseudo` |
| SIH-09 | Filter "engine idling vibrations, pothole shocks, bumps, and accidental phone misalignments on the mount" | Stop detector, transient-impact feature, alignment monitor, uncertainty inflation | Tagged scenario tests and misalignment-trigger test |
| SIH-10 | Overlay onto "an offline map database (e.g., Open Street Map)" and apply Non-Holonomic Constraints (NHC). Matcher example: "AI-ML framework or Unscented Kalman Filter + Hidden Markov Map Matching" | Local PMTiles plus compact OSM road graph; online HMM/Viterbi; NHC in filter | Airplane-mode map test and road-hypothesis test suite |
| SIH-11 | Official module "GNSS+INS Fusion Engine" | Learned noise/pseudo-measurement adapter inside auditable state estimator | AI-plus-filter ablation and covariance calibration |
| SIH-12 | Algorithms "should also work with any other external IMU sensors data (Edge deployable software engine)." FOG named at "around 200Hz". Interface requirement. Live FOG at screening is not stated. | `SensorFrame` contract and Android/file/external adapters | 200 Hz recorded-stream adapter test |
| SIH-13 | IO-VNBD is mandatory. Official link: https://github.com/onyekpeu/IO-VNBD | Versioned importer, trip-level split manifest, locked blackout suite | `results/io_vnbd_screening_v1/summary.md` |
| SIH-14 | Teams "are required to include the preliminary AI models and the results of the position plot inferenced from the subset of IO-VNBD dataset as part of their proposals submitted for evaluation." | M0 deliverable generates baseline and learned-model plot from a named subset | `results/io_vnbd_screening_v1/` plots and CSVs. Truth labelled score only. |
| SIH-15 | "Complex training happens in the cloud/desktop apriori, while inference happens on the smartphone." No TimesFM wording in the PS. TimesFM is a team experiment, not a requirement response. | TimesFM 3 desktop teacher (optional, research-only), compact independent student on phone | Research ablation plus ONNX mobile benchmark |
| SIH-16 | Named inputs: phone accelerometer, gyroscope, magnetometer/compass, and GNSS if available | Canonical schema includes each sensor and quality/availability flags | Capture-contract tests on reference devices |
| SIH-17 | Official module name: "In-Vehicle Alignment & Calibration Engine" | Guided stationary calibration, gravity/bias estimate, vehicle-frame transform | Calibration report and transform fixtures |
| SIH-18 | Official module name: "AI Speed & Vibration Filter". Expected Solution allows "A deep-learning or statistical signal-processing model". | Causal TCN/GRU candidate with uncertainty head | Heuristic + linear + optional GRU in `ml/src/driftzero_ml/student/`; vibration/bump/idle features. Linear packed. GRU analysis-only. |
| SIH-19 | Official module name: "Advanced Map-Matching & Kinematic Constraints" | ESKF/InEKF candidate with NHC plus HMM road matcher | ADR, filter tests, and map test cases |
| SIH-20 | Official module name: "GNSS+INS Fusion Engine" | Dynamic measurement/noise adapter and learned pseudo-measurements, experimentally gated | Controlled ablations and rejection criteria |
| SIH-21 | Official module name: "Seamless GNSS Deficit Handler" | Deterministic state machine with GNSS gating and recovery blending | State-transition test and UI recording |
| SIH-22 | Official module name: "Real-time Navigation Interface" | 10 Hz output, interpolated display, confidence halo, compact status sheet | Frame/output timing trace and UX rehearsal |
| SIH-23 | Official: "must restrict positional drift to less than 10% of the total distance travelled using smartphone IMUs sensors during GNSS signals blackout" | Locked primary metric and release gate | `results/io_vnbd_screening_v1/summary.md`. Median gate of 0.10 not met on 35 gated intervals. |
| SIH-24 | The 5 m / 50 m and 100 m / 1 km figures are introduced by "for e.g." and "is desired", not as the aggregate gate. | Include official examples as scenario checks, not as substituted aggregate metric | Persist on S-Vta2 d50 was 2.99 m over 53.13 m. No 1 km interval had endpoint under 100 m. |
| SIH-25 | "Position update rate of 10Hz with processing on smartphones (Mobile application)" | Sensor capture at supported rate, navigation output at 10 Hz | On-device timestamp audit |
| SIH-26 | "higher update rates on Edge deployable software engine using FOG based IMU sensors data (around 200Hz)" | Rate-independent core, adapter batching, configurable propagation | Recorded external-stream load test |

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

- Official verb is "maintaining" lane-level accuracy. DriftZero treats that as an aim whose confidence must be measured. It is not a claim from consumer MEMS.
- “AI-based fusion” does not require replacing the state estimator with a black box. A learned pseudo-measurement or adaptive covariance module integrated with a filter is a stronger, safer implementation.
- The consumer product is phone-only. The external IMU input is a reusable engine boundary, not a hidden dependency in the demo.
- GNSS interference is described as a condition the system observes. The app does not claim certified jamming or spoofing classification.
- NavIC/IRNSS membership is logged when Android `GnssStatus` reports `CONSTELLATION_IRNSS`. Visibility is not certified integrity. GAGAN is the safety-of-life GPS-augmentation path. See `docs/refs/NAVIC.md`.

