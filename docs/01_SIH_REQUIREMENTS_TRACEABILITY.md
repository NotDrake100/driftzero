# SIH26168 requirements traceability

Source of record: the [official SIH 2026 problem statement page](https://www.sih.gov.in/sih2026PS), problem statement ID 26168, modal `#ViewProblemStatement26168`, retrieved 2026-09-03. Independent fetch log: [docs/refs/SIH26168_EVIDENCE.md](refs/SIH26168_EVIDENCE.md). This document converts the official prose into testable requirements. Quoted phrases are the sponsor's wording. If the official page changes, record the retrieval date and update this matrix.

| ID | Official requirement or context | DriftZero response | Verification evidence |
|---|---|---|---|
| SIH-01 | Official title: "AI-ML based Intelligent Dead Reckoning system for seamless navigation" (ISRO, Department of Space, Software, Smart Vehicles) | Hybrid learned motion model, probabilistic navigation filter, outage state machine, map matching after a Ready pack | IO-VNBD screening in `results/io_vnbd_screening_v1/summary.md`. Live Android field run planned (docs/07 P2). |
| SIH-02 | GNSS drops in "long underground tunnel/ underpass, a multi-level parking lot, a dense forested highway, or a deep urban canyon". Signals are vulnerable to blockage and "unintentional electromagnetic interferences from variety of sources such as jamming." Jamming is named. Spoofing is not named. | Scenario taxonomy, `BlackoutRisk` (C/N0 and map are Unavailable when missing), `GnssTrust` quarantine, local `ShadowMap`. Not certified spoofing detection. | `BlackoutRiskTest`, `GnssTrustTest`, `ShadowMapTest`. ADR 008. |
| SIH-03 | "The smartphone is subjected to severe chassis vibrations, engine harmonics, sudden braking, and road potholes." | Vehicle-frame alignment, causal filters, vibration features, measurement-noise inflation, tagged-event tests | `ZuptAccelMotionModelTest` bump/idle fixtures. India pilot ablation planned (docs/07 P2). |
| SIH-04 | Hard prohibition of an OBD-II speedometer feed and of "any physical connection to the vehicle’s internal computer" | Consumer path uses only phone sensors. Map data is offline only after a Ready pack. | No-cable path is the APK. Airplane-mode demo planned (docs/07 P5). |
| SIH-05 | "lightweight, edge-deployable software engine and mobile application" that turns a standalone smartphone into an IDR system with GNSS Fusion | Platform-neutral navigation core, packed `linear.json` speed student, Android UI | On-device latency/memory report planned. No battery or p95-gap artifact. |
| SIH-06 | Standalone phone and transition to inertial tracking when GNSS drops | Explicit fused, degraded, DR, reacquiring, low-confidence state machine. Driver sees the word and reason on the mode lamp and status sheet. Judge Hold GNSS and mode strip for rehearsal. | `DeadReckoningFilterTest`, `ModeLampTest`, `StatusCopyTest`. Lamp and sheet on device. |
| SIH-07 | Official verb: "maintaining lane-level accuracy" without a vehicle-computer connection. Not "aim for". Team treats this as an aim, not a claim from consumer MEMS. | Covariance-aware HMM matcher, topology/layer constraints, gradual recovery blend | Parallel-road/flyover fixtures and recovery-jump metric |
| SIH-08 | Predict speed and acceleration "solely from the smartphone’s noisy accelerometer/gyro inputs" | Learned causal speed/yaw plus TLIO-style Δp, filter fallback | `ml/tests/test_causal_imu.py`, `ml/tests/test_motion_student.py`, `ml/tests/test_learned_imu.py`, `ZuptAccelMotionModel` + `ingestMotionPseudo` + `ingestDisplacementPseudo` |
| SIH-09 | Filter "engine idling vibrations, pothole shocks, bumps, and accidental phone misalignments on the mount" | Stop detector, transient-impact feature, alignment monitor, uncertainty inflation | Tagged scenario tests and misalignment-trigger test |
| SIH-10 | Overlay onto "an offline map database (e.g., Open Street Map)" and apply Non-Holonomic Constraints (NHC). Matcher example: "AI-ML framework or Unscented Kalman Filter + Hidden Markov Map Matching" | Local PMTiles plus compact OSM road graph; online HMM/Viterbi; NHC in filter. After a Ready `graph.bin`, `MapCoastSession` applies MATCHED heading and along-track Road DNA only. Research particle coast on \((e, s, v)\) is ADR 010 Python fixture only. Offline areas screen lists Ready or queued packs and says streets still use the network until a pack is Ready. | `AreaPackStoreTest`, `HmmRoadMatcherTest`, `MapCoastConstraintTest`, `DeadReckoningEngineTest`, `ml/tests/test_road_particle.py`. Airplane-mode tiles still need a sideloaded pack. IO-VNBD has no team OSM pack. |
| SIH-11 | Official module "GNSS+INS Fusion Engine" | Learned noise/pseudo-measurement adapter inside auditable state estimator | AI-plus-filter ablation and covariance calibration |
| SIH-12 | Algorithms "should also work with any other external IMU sensors data (Edge deployable software engine)." FOG named at "around 200Hz". Interface requirement. Live FOG at screening is not stated. | `SensorFrame` contract and `ReplaySensorSource` file adapter | Synthetic 200 Hz vs 100 Hz test in `ReplaySensorSourceTest`. No live FOG or recorded external IMU in `results/`. |
| SIH-13 | IO-VNBD is mandatory. Official link: https://github.com/onyekpeu/IO-VNBD | Versioned importer, trip-level split manifest, locked blackout suite | `results/io_vnbd_screening_v1/summary.md` |
| SIH-14 | Teams "are required to include the preliminary AI models and the results of the position plot inferenced from the subset of IO-VNBD dataset as part of their proposals submitted for evaluation." | M0 deliverable generates baseline and learned-model plot from a named subset | `results/io_vnbd_screening_v1/` plots and CSVs. Truth labelled score only. |
| SIH-15 | "Complex training happens in the cloud/desktop apriori, while inference happens on the smartphone." No TimesFM wording in the PS. TimesFM is a team experiment, not a requirement response. | TimesFM 3.0 desktop teacher, designed, not run. Compact student on phone is packed `linear.json`. | No `results/timesfm/`. 3.0 cannot ship. Distillation from 3.0 needs a license read. 2.5 is Apache-2.0. No ONNX export. |
| SIH-16 | Named inputs: phone accelerometer, gyroscope, magnetometer/compass, and GNSS if available | Schema includes each sensor. Live path copies accel, gyro, GNSS. Magnetometer is captured and gated off. Gravity, linear acceleration, and uncalibrated gyro are logged and not consumed by the ESKF. Speed and bearing accuracy are copied when Android reports them. | Contract tests. `PhoneImuSourceTest`. No reference-phone capture audit. |
| SIH-17 | Official module name: "In-Vehicle Alignment & Calibration Engine" | First-run still capture plus `MountSession` yaw-from-motion. `MountPlacement` classifies dash, cup or vent, passenger seat, handheld. Passenger seat is a valid placement. First-run copy does not require a physical mount. | `MountAlignmentTest`, `MountPlacementTest`. First-run place-the-phone step. |
| SIH-18 | Official module name: "AI Speed & Vibration Filter". Expected Solution allows "A deep-learning or statistical signal-processing model". | Linear ridge speed student on device. GRU is analysis-only. No TCN on device. | `results/io_vnbd_screening_v1/summary.md`. Linear speed MAE beats freeze and heuristic. GRU not packed. |
| SIH-19 | Official module name: "Advanced Map-Matching & Kinematic Constraints" | ESKF with NHC plus HMM road matcher. Soft heading and along-track Road DNA when MATCHED. Unmatched coasts raise P. Blackout particle coast is ADR 010 research, not the live filter. | ADR 008, ADR 010, `MapCoastConstraintTest`, `HmmRoadMatcherTest`, `ml/tests/test_road_particle.py` |
| SIH-20 | Official module name: "GNSS+INS Fusion Engine" | Dynamic measurement/noise adapter and learned pseudo-measurements, experimentally gated | Controlled ablations and rejection criteria |
| SIH-21 | Official module name: "Seamless GNSS Deficit Handler" | Deterministic state machine with GNSS gating and recovery blending. Live `BlackoutRisk` preconditioning arms the yaw-speed-hold GNSS speed latch before the outage when mean risk is high or tunnel / shadow look-ahead is already strong. | `DeadReckoningFilterTest.coastPreconditionArmsLatchAfterGnssSpeedAgesOut`, `PoseStoreTest.highBlackoutRiskArmsLiveCoastLatch`, `PoseStoreTest.tunnelLookAheadArmsCoastWhileGnssHealthy`, `BlackoutRiskTest.healthyGnssPlusNearTunnelStillArms` |
| SIH-22 | Official module name: "Real-time Navigation Interface" | 10 Hz output, interpolated puck, metre-true halo, heading cone, status sheet, Judge overlay, first run, settings, About, trip record and replay. | `PuckInterpolatorTest`, `InstrumentFormatTest`, `StatusCopyTest`, `TickIntervalsTest`, `TripRecorderTest`, `TripStoreTest`, `TripReplayTest`. Compose surfaces in `apps/android`. |
| SIH-23 | Official: "must restrict positional drift to less than 10% of the total distance travelled using smartphone IMUs sensors during GNSS signals blackout" | Locked primary metric and release gate. ADR 008 integrity suite is production-path. Live coast is yaw-speed-hold with honest P and the packed student. Replay `InsConfig` stays strapdown. ADR 009 persist-like seed picker exists. Filter wiring was aborted after seed_premask named-interval guard. Map heading and along-track heal run only when a road graph is MATCHED. ADR 010 particle coast is a Python fixture. Pune phone-drive harness is `ml/src/driftzero_ml/eval_pune.py`. | `results/io_vnbd_screening_v1/summary.md`. Median gate of 0.10 not met. Persist drift p50 0.5168. Aborted leak-free seed row: `results/io_vnbd_screening_v1/kotlin_replay/seed_premask/` (n=35, drift p50 0.6366, S-Vta2:d50 13.48 m, S-S1:mid 873.41 m). Road DNA 1270/1320, Drift Budget, and `road_particle` fork fixture are labeled fixtures, not IO-VNBD claims. No real Pune metrics in `results/pune_v1/`. |
| SIH-24 | The 5 m / 50 m and 100 m / 1 km figures are introduced by "for e.g." and "is desired", not as the aggregate gate. | Include official examples as scenario checks, not as substituted aggregate metric. Pune Hold windows of 50 m and 1 km are the planned phone-rate checks. | Persist on S-Vta2 d50 was 2.99 m over 53.13 m. No 1 km interval had endpoint under 100 m. No Pune 50 m or 1 km field row exists yet. |
| SIH-25 | "Position update rate of 10Hz with processing on smartphones (Mobile application)" | `PoseStore.tick` at `OUTPUT_HZ = 10` | Code path exists. No on-device p95-gap report. |
| SIH-26 | "higher update rates on Edge deployable software engine using FOG based IMU sensors data (around 200Hz)" | Rate-independent file replay adapter | Synthetic 200 Hz test only. No recorded FOG stream. |

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


### 2026-09-06 placement and correctness milestone

SIH-09 / SIH-17: physical mounting is optional for a resting phone. Gravity-axis
yaw projection and handling uncertainty are wired to live PoseStore. A remount
preserves the trajectory. See [ADR 011](adr/011-coast-and-phone-continuity.md).
Passenger-seat, cup-holder and arbitrary resting orientations are supported by
alignment; arbitrary handheld accuracy remains unvalidated.

SIH-13 / SIH-23: physical yaw sign, exact coast arc integration, pre-blackout
calibration and once-per-epoch map feedback have regression coverage. These are
correctness results, not a new IO-VNBD score. The 10% drift objective remains open.

SIH-19 / SIH-23: [ADR 014](adr/014-real-map-research.md) adds a real OSM road-particle
research experiment with fixed development selection, causal fallback, snapshot
provenance and unchanged truth gates. It does not change live Android estimation.


### 2026-09-07 real-data joint-motion milestone

SIH-13 / SIH-23: [ADR 018](adr/018-real-data-joint-sequence.md) and the
[joint-motion report](20_JOINT_SEQUENCE_EXPERIMENT.md) add executable MLP, TCN and
GRU training on frozen train groups, with training provenance, actual gradient
and prefix-invariance tests, per-checkpoint development evidence and a strict
no-regression gate. Research scores are not Android or mountless field accuracy.


SIH-13 / SIH-23: [ADR 019](adr/019-long-outage-motion.md) extends the neural
research experiment to long outages with a longer-memory causal TCN, training-only
motion increments and tail-focused loss. See the [measured report](21_LONG_OUTAGE_EXPERIMENT.md).
