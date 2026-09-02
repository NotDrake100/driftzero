# Product Requirements Document: DriftZero

| Field | Value |
|---|---|
| Product | DriftZero |
| Tagline | AI-Assisted Resilient Navigation Beyond GNSS |
| Problem | Intelligent dead reckoning for seamless navigation when GNSS is unavailable or degraded |
| Domain | ISRO complementary PNT / intelligent dead reckoning |
| Category | Software |
| Theme | Smart Vehicles |
| Primary platform | Android |
| PRD version | 1.0 |
| Date | 2026-09-02 |

## 1. Executive summary

Modern phone navigation can become unreliable in tunnels, underground parking, flyovers, dense urban streets, forests, or during RF interference. A normal application may freeze the marker, jump to a parallel road, or teleport when GNSS returns. Dedicated inertial systems avoid this but are too expensive for mass deployment, while vehicle-bus access is unavailable or inconsistent across India's heterogeneous fleet.

DriftZero is a standalone Android navigation engine that estimates vehicle motion using only sensors already present in a phone. It combines calibrated inertial measurements, a small learned speed and attitude model, a probabilistic navigation filter, vehicle kinematic constraints, GNSS health checks, and offline road-network matching. A Google Maps-like interface communicates current mode and confidence without overwhelming the driver. The same core exposes a generic sensor interface for higher-rate external IMUs, satisfying the broader edge-engine requirement without making external hardware part of the consumer product.

The product is designed for Indian roads and operational realities: mixed vehicles, two-wheelers, old vehicles without a usable diagnostic port, variable phone quality, potholes and speed breakers, irregular mounting, intermittent data connectivity, dense flyovers, service roads, tunnels, and privacy-sensitive government use.

## 2. Problem and opportunity

### 2.1 User problem

When GNSS degrades, drivers and field operators need continuous, understandable navigation. They do not need a mathematically impressive trace that silently becomes wrong. They need:

- a position that continues smoothly through a short or medium outage;
- road and direction consistency without inappropriate snapping;
- an explicit warning as uncertainty grows;
- a seamless, non-teleporting recovery when GNSS returns;
- offline operation for the map, model, and navigation engine;
- no vehicle modification, special antenna, or network dependency.

### 2.2 Why current phone-only approaches fail

- Low-cost MEMS inertial bias integrates into rapidly growing velocity and position error.
- Engine vibration, phone cases, loose mounts, potholes, braking, and idling contaminate motion estimates.
- A phone may be mounted in portrait, landscape, tilted, or moved during a trip.
- A magnetometer is affected by the vehicle body and nearby electronics.
- A raw nearest-road snap can select a flyover, service lane, or parallel carriageway incorrectly.
- GNSS quality indicators may look plausible even as position becomes inconsistent.
- A model trained with random rows from the same journey in train and test can appear excellent while failing on a new route or phone.

### 2.3 Opportunity

A hybrid system can use learning where hand-tuned models are weakest, while preserving physical constraints, covariance, replayability, and safety behavior. Offline OpenStreetMap data adds road topology and legal motion hypotheses. Local inference gives predictable latency and privacy. This creates value for consumer navigation, logistics, emergency response, public transport, defence-adjacent field operations, mining, forests, and disaster response without replacing GNSS or NavIC.

## 3. Vision and product principles

**Vision:** make resilient, trustworthy navigation available on an ordinary Indian Android phone, and provide a reusable complementary PNT engine for edge systems.

Product principles:

1. **Complement GNSS and NavIC.** DriftZero bridges loss and degradation; it does not portray any constellation as a failure.
2. **Physics plus learning.** A neural model proposes motion and uncertainty. A navigation filter and road graph enforce temporal and physical consistency.
3. **Confidence is a product feature.** The interface must distinguish an estimate from a known location.
4. **Offline means complete.** Core inference, maps, replay, and logging work without a server.
5. **Measure generalization.** Hold out trips, routes, vehicles, phones, and blackout scenarios.
6. **Fail visibly and safely.** Never hide growing uncertainty or snap aggressively to make a trace look good.
7. **Reproducible demonstrations.** Live and recorded demos use the same production pipeline and versioned configurations.

## 4. Users and jobs to be done

| User | Job | Critical need |
|---|---|---|
| Everyday driver | Continue route guidance through a tunnel or underpass | Smooth dot, correct exit direction, no setup burden |
| Delivery or fleet operator | Preserve trip continuity in weak-signal zones | Offline operation, logs, low battery impact |
| Emergency responder | Maintain situational awareness in a covered or damaged area | Rapid start, confidence warning, privacy |
| Government field team | Use resilient local navigation without data connectivity | Local maps, secure export, deterministic behavior |
| Navigation engineer | Integrate the engine with a different IMU | Stable sensor and output contracts, configurable rates |
| Engineering reviewer | Verify the result and innovation | Replay, blackout toggle, metrics, ablations, source traceability |

## 5. Product scope

### 5.1 Minimum winning product

- Android app with a blue position marker, heading cone, route polyline, current speed, GNSS/DR mode, and confidence halo.
- Live sensor ingestion with monotonic timestamps and trip logging.
- Guided stationary and short-motion calibration.
- Automatic phone-to-vehicle frame alignment and misalignment monitoring.
- Causal learned forward-speed, yaw-rate, and uncertainty estimation.
- Error-state or invariant EKF with inertial propagation, learned pseudo-measurements, non-holonomic constraints, stop updates, and guarded GNSS updates.
- Explicit GNSS quality and innovation-based outage state machine.
- Offline vector map and road graph for a selected Indian demo corridor.
- Online HMM/Viterbi map matching with heading, speed, topology, and uncertainty-aware candidate scoring.
- Seamless outage entry and bounded recovery blending.
- Deterministic replay of IO-VNBD and team-collected India routes.
- Artificial blackout tool that retains labels only for scoring.
- Dashboard/report for endpoint error, drift ratio, along/cross-track error, heading error, continuity, recovery jump, latency, and power.
- Desktop TimesFM 3 zero-shot evaluation and teacher-distillation experiment.
- Generic external IMU adapter demonstrated by replay at a configurable rate.

### 5.2 Stretch product

- NavIC-specific satellite and raw-measurement diagnostics where supported by the phone.
- Route-aware hazard or outage-zone prediction.
- Federated or privacy-preserving improvement workflow.
- Multi-phone robustness calibration and automated device capability profiling.
- Secure signed offline map/model bundles.
- Accessibility-grade voice and haptic status cues.
- Operations dashboard for consented, anonymized fleet health statistics.

### 5.3 Explicit non-goals

- Replacing GNSS, NavIC, certified INS, or automotive safety systems.
- Autonomous lane keeping or collision avoidance.
- Guaranteeing lane-level accuracy on every phone and road.
- Using OBD-II, wheel-speed, LiDAR, drone imagery, or a custom antenna in the consumer flow.
- Cloud inference in the critical loop.
- Presenting a research-only model checkpoint as production-ready.
- Claiming certified anti-jamming or anti-spoofing. DriftZero reports signal-integrity risk based on observable inconsistency.

## 6. Functional requirements

### FR-01 Sensor capture and synchronization

The app shall capture accelerometer, uncalibrated gyroscope where available, magnetometer, GNSS fixes, accuracy, speed, bearing, satellite status, and optional raw GNSS measurements. Sensor timestamps shall use the Android monotonic clock domain. Each frame shall preserve sensor accuracy and availability flags. Raw capture should normally be 50 to 100 Hz on a phone, with a 10 Hz fused navigation output.

**Acceptance:** a 30-minute replay has no negative timestamp deltas, reports dropped samples, and produces identical output within numerical tolerance on repeated runs.

### FR-02 Calibration and vehicle-frame alignment

The app shall estimate stationary bias, gravity direction, phone-to-vehicle orientation, and sensor noise. Alignment shall use a guided stationary phase plus motion evidence from straight driving and turns. It shall detect likely remounting or phone movement and reduce confidence until realigned.

**Acceptance:** controlled rotations and a known straight segment produce a documented frame transform; changing phone orientation triggers the misalignment state.

### FR-03 Motion preprocessing

The pipeline shall apply causal filtering, saturation detection, outlier flags, vibration features, gravity separation, and missing-sample handling. Potholes and speed breakers shall not be treated as sustained longitudinal acceleration.

**Acceptance:** tagged bump events show bounded speed perturbation and an uncertainty increase.

### FR-04 Learned motion model

A compact on-device model shall estimate forward speed or speed increment, yaw rate or attitude correction, relevant bias/noise parameters, and calibrated uncertainty from a causal window. The model shall not receive future samples or hidden GNSS labels during an outage.

**Acceptance:** model artifact, input normalization, feature order, window length, quantization mode, and calibration metrics are versioned. The app remains operational if the model fails to load by using a conservative filter fallback.

### FR-05 Navigation filter

The navigation core shall maintain position, velocity, orientation, IMU biases, and covariance. It shall support inertial propagation, learned updates, GNSS updates gated by quality and innovation, non-holonomic constraints when valid, stationary updates, and uncertainty growth during outages.

**Acceptance:** simulated sensor sequences pass consistency tests; invalid measurements are rejected with a reason code; covariance remains finite and positive.

### FR-06 GNSS health and outage handling

The system shall classify `GNSS_FUSED`, `GNSS_DEGRADED`, `DEAD_RECKONING`, `REACQUIRING`, and `LOW_CONFIDENCE`. It shall consider fix age, reported accuracy, satellite status, speed/bearing consistency, filter innovation, and map consistency. Reacquisition shall require consecutive plausible fixes and blend corrections rather than instantly teleporting.

**Acceptance:** scripted quality degradation causes deterministic state transitions; a large inconsistent return fix is not immediately accepted.

### FR-07 Offline maps and map matching

The app shall render a local vector basemap and query a compact local road graph. The matcher shall generate candidates within the current uncertainty region, score perpendicular distance, heading, speed plausibility, road class, and transition-path consistency, and keep multiple hypotheses when ambiguous.

**Acceptance:** parallel-road, flyover/service-road, U-turn, junction, and tunnel fixtures have asserted outcomes. A low-confidence ambiguity remains visible rather than forcing a road.

### FR-08 Navigation output

The engine shall emit position, speed, heading, covariance/confidence radius, active mode, matched road reference, map-match confidence, GNSS health, model version, and diagnostic flags at 10 Hz on phone. External IMU configurations may use higher rates.

**Acceptance:** output conforms to `contracts/navigation_state.schema.json` and remains monotonic during replay.

### FR-09 Offline area management

Users shall download or sideload a city/corridor package containing a PMTiles visual map, road graph, metadata, checksums, and expiry/version information. The app shall show package size and storage impact.

**Acceptance:** after download, airplane-mode navigation and replay work with zero network requests.

### FR-10 Driver interface

The default screen shall show the map, moving blue dot, heading indicator, route, concise mode label, confidence halo, and one expandable status sheet. Technical metrics shall be hidden while driving but available in engineering replay mode.

**Acceptance:** mode and uncertainty are understandable at a glance; essential controls meet large touch-target and contrast requirements.

### FR-11 Replay and engineering mode

The app shall replay recorded sessions through exactly the same pipeline used live. An engineering reviewer can select an interval, mask GNSS, compare fused and DR traces, inspect state transitions, and export a signed result bundle.

**Acceptance:** a supplied IO-VNBD subset runs offline from a clean install and reproduces the documented report.

### FR-12 External IMU interface

The navigation core shall accept a generic `SensorFrame` stream independent of Android APIs. Adapters shall declare units, axes, clock, rate, calibration, and quality. A recorded higher-rate IMU stream shall demonstrate the interface.

**Acceptance:** the same core can consume the Android adapter and a file/replay adapter without algorithm changes.

## 7. TimesFM 3 requirement and experiment

TimesFM 3 is used as an experimentally gated research component:

1. Convert synchronized history into multivariate channels such as vehicle-frame acceleration, angular rate, vibration energy, recent accepted GNSS innovations, speed estimates, stop probability, and map curvature.
2. Ask TimesFM 3 for short-horizon point and quantile forecasts of forward speed, yaw rate, innovation trend, or drift-risk proxies.
3. Compare it with persistence, constant-turn-rate-and-velocity, Kalman-only, TCN, and GRU baselines on held-out trajectories.
4. Measure not only mean error, but interval coverage, tail error, inference cost, and performance by device/road/scenario.
5. If and only if it adds held-out value, distill its soft trajectories or quantile information into a compact causal student.
6. Export only the independent student to the app. Keep TimesFM optional and outside the production dependency graph.

This makes the foundation model meaningful without pretending that a large desktop model belongs in a low-latency mobile control loop.

## 8. Non-functional requirements

| ID | Requirement | Initial target |
|---|---|---|
| NFR-01 | Navigation output | 10 Hz, p95 inter-output gap below 150 ms |
| NFR-02 | On-device model latency | p95 below 20 ms on reference mid-range phone |
| NFR-03 | Learned model size | preferred below 3 MB quantized, hard gate below 10 MB |
| NFR-04 | Offline availability | no network needed after area package is installed |
| NFR-05 | Battery | report percentage/hour on reference phones; optimize after measurement |
| NFR-06 | Stability | no NaN, covariance explosion, or app crash in 2-hour replay soak |
| NFR-07 | Privacy | local processing by default; explicit consent before any export |
| NFR-08 | Security | signed/checksummed model and map bundles; no arbitrary model loading |
| NFR-09 | Reproducibility | configuration, code commit, data manifest, and seed in every result |
| NFR-10 | Accessibility | large touch targets, high contrast, non-color-only status cues |
| NFR-11 | Device support | declare capability tiers and degrade gracefully when sensors are missing |
| NFR-12 | Storage | corridor extracts, not an India-wide map inside the APK |

## 9. Accuracy and quality gates

The product accuracy target is dead-reckoning drift below 10 percent of distance traveled during GNSS loss. This is a necessary gate, not the whole quality definition.

### Release gates

- Median endpoint drift ratio below 10 percent across the locked IO-VNBD blackout suite.
- Report p50, p90, p95, and worst-case drift ratio, never only the mean.
- No split leakage by trip, route, driver, or vehicle.
- No ground-truth GNSS or future data in blackout inputs.
- Recovery jump p95 below the declared UI threshold and no instantaneous large snap.
- 10 Hz output and target latency on at least one reference mid-range Android phone.
- India pilot scenarios include tunnel/underpass, parking, urban canyon, rough road, stop-go, and phone remounting.
- Every metric links to a replayable trajectory and configuration.

### Metrics

- endpoint horizontal position error in metres;
- drift ratio: endpoint horizontal error divided by blackout path length;
- time-normalized and distance-normalized trajectory error;
- along-track and cross-track error;
- heading and speed MAE;
- road-segment accuracy and wrong-parallel-road rate;
- continuity gaps and maximum jerk in displayed marker;
- reacquisition time and correction magnitude;
- calibrated interval coverage and confidence reliability;
- latency, memory, CPU, thermal state, and battery drain.

## 10. Dataset plan

### Mandatory

IO-VNBD is the locked evaluation dataset. Baseline evidence must include preliminary AI results and an inferred position plot from a subset. Use synchronized phone data first, then vehicle data for diagnostic comparisons. Split by complete journey and group correlated drives.

### Supplemental

- Google Smartphone Decimeter Challenge for diverse phones, raw GNSS, inertial data, and precise ground truth.
- UrbanNav for dense urban GNSS degradation and reference trajectories.
- KITTI only for filter sanity checks and comparison with published vehicle-IMU work, not as the main phone model.
- Team-collected Indian data as the decisive generalization set.

### India collection matrix

Collect consented sessions across:

- vehicle: hatchback, sedan, SUV, auto-rickshaw where safely mountable, motorcycle/scooter, bus or truck if available;
- phone tier: low-cost, mid-range, flagship, at least two IMU vendors;
- mount: windshield, dashboard, handlebar, pocket only as a robustness test;
- road: expressway, city stop-go, service road/flyover, tunnel/underpass, parking ramp, rough road, speed breakers, forest or low-connectivity corridor;
- weather/time: day, night, rain only when safely feasible;
- states: stationary idle, slow crawl, hard brake, U-turn, roundabout, lane split, phone remount.

Full clear-sky GNSS can be retained as score-only truth for artificial blackouts. Better reference systems may be used for research labeling but never as an end-user dependency.

## 11. Offline map plan

- Source road topology from OpenStreetMap extracts.
- Build an Indian demo corridor or city extract, not a whole-country APK asset.
- Render with MapLibre Native and a local PMTiles archive.
- Store a separate compact road graph with stable OSM identifiers, geometry, class, direction, access, bridge/tunnel/layer, speed prior, and adjacency.
- Generate packages in CI or a build script from a pinned OSM snapshot and publish hashes.
- Do not bulk-download the public OSM tile server for offline use.

Map matching uses a bounded online HMM with a rolling Viterbi beam. Emission likelihood includes covariance-aware distance and heading. Transition likelihood compares dead-reckoned displacement with path distance and topology. Layer, tunnel, bridge, access, one-way, and turn restrictions reduce flyover and service-road mistakes. The matcher must return an unmatched or ambiguous state when evidence is weak.

## 12. UX specification

### Primary navigation screen

- Full-screen offline map.
- Blue dot for fused estimate; heading cone uses uncertainty-aware width.
- Confidence halo grows as uncertainty increases.
- Compact chip: `GNSS`, `Assisted`, `Dead reckoning`, `Reacquiring`, or `Low confidence`.
- Current speed and next route instruction.
- Expandable bottom sheet with GNSS age, confidence radius, last trusted fix, map-match confidence, sensor health, and downloaded area status.
- Audio/haptic cue only for significant transitions, such as entering low confidence.

### Calibration

Use plain instructions: secure phone, remain still, then drive straight when safe. Show progress and quality, not raw matrices. A user can skip only if a valid profile exists for the same mount state.

### Engineering replay mode

Side-by-side or overlay traces: hidden truth, ordinary GNSS behavior, filter-only, full DriftZero. Include a blackout timeline, state timeline, drift metrics, latency, and an explanation of what the TimesFM experiment did. Truth must be visually labeled and never displayed as an input.

## 13. Safety, privacy, and security

- The app is an aid, not a certified safety-of-life or autonomous-driving system.
- Never instruct a driver to interact with technical controls while moving.
- Default logs stay local and can be deleted by trip.
- Exports require explicit consent and redact direct identifiers where possible.
- Encrypt sensitive trip bundles at rest using platform facilities.
- Sign or checksum map, model, calibration, and configuration artifacts.
- Treat replay files and map metadata as untrusted input, with bounds and schema validation.
- Do not infer or advertise precise military-grade capability from consumer sensors.
- Report `LOW_CONFIDENCE` when the uncertainty exceeds a configured safe display threshold.

## 14. Analytics and observability

Offline diagnostic events include sensor availability, state transitions, rejected update reasons, map ambiguity, model fallback, output gaps, temperature/thermal status, and aggregate latency. No raw trip upload occurs by default. A consented export contains a manifest, events, result summary, and optional sensor stream.

## 15. Delivery milestones

| Milestone | Outcome | Exit gate |
|---|---|---|
| M0 Evidence | IO-VNBD subset, baseline trace, blackout evaluator | Reproducible position plot and leakage test |
| M1 Core | Calibration, propagation, GNSS gating, replay | Stable filter and deterministic replay |
| M2 Intelligence | Speed model, uncertainty, constraints | Held-out improvement over filter-only baseline |
| M3 Roads | Offline package and HMM matcher | Junction/parallel-road fixture suite passes |
| M4 Android | Live capture, 10 Hz UI, logging | Airplane-mode field run on reference phone |
| M5 Foundation model | TimesFM zero-shot and distillation ablation | Kept only if held-out value is demonstrated |
| M6 India validation | Multi-phone Indian pilot suite | Locked metrics plus failure taxonomy |
| M7 Demo | Scripted live/replay story and contingency capture | Fresh-install rehearsal succeeds twice |

## 16. Demo success story

The final video begins with a real Indian navigation problem, demonstrates an ordinary marker degrading at a tunnel or simulated blackout, then shows DriftZero continuing with visible confidence. The audience sees no external hardware and airplane mode remains enabled. Engineering replay reveals the sensor pipeline, outage state, road hypotheses, and metrics. A recovery sequence shows gradual GNSS re-entry without a jump. The close explains the TimesFM teacher/student experiment, offline privacy, external IMU interface, India data plan, and honest limitations.

The exact narration and shot timing are in `docs/07_DEMO_VIDEO_SCRIPT.md`.

## 17. Open decisions that must be settled with evidence

- ESKF versus InEKF implementation after numerical and replay comparison.
- Direct speed, speed increment, or pseudo-measurement target for the student.
- TCN versus GRU based on accuracy, calibration, latency, and power.
- Best short-horizon TimesFM targets and whether distillation adds value.
- Per-city corridor package size and graph indexing method.
- Confidence thresholds by road geometry and phone capability tier.
- Whether magnetometer updates help each device/mount class; they must be gated, not assumed.

## 18. Definition of excellent

DriftZero is excellent when it is hard to fool, easy to understand, and easy to verify. A polished animation alone is insufficient. The project must show a real pipeline, difficult failure cases, held-out metrics, ablations, uncertainty calibration, reproducible artifacts, offline execution, and a candid failure taxonomy. The most credible product claim is not that the phone never drifts. It is that DriftZero measures, constrains, communicates, and recovers from that drift better than defensible baselines under the published constraints.

