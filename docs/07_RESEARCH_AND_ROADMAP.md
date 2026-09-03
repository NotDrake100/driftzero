# DriftZero research and roadmap to Sep 20 (SIH26168)

| Field | Value |
|---|---|
| Status | Revision 2026-09-03. Official SIH26168 text and independently fetched references folded from `docs/refs/SIH26168_EVIDENCE.md`. |
| Written | 2026-09-03 (IST) |
| Repo snapshot | commit `069e74b` plus the uncommitted working tree as read between 00:00 and 00:30 IST. Several files (`PRD.md`, `README.md`, `AGENTS.md`, `Makefile`, `ci.yml`, `build.gradle.kts`) were being edited by other agents during the audit. Re-check anything marked "as read". |
| Web evidence | Fetched 2026-09-03 and recorded in `docs/refs/SIH26168_EVIDENCE.md`. Official facts and literature URLs below cite that file. Local measurements remain `[verified locally 2026-09-03]`. Items the evidence file could not verify are listed in section 8. |
| Deadline | 2026-09-20, 17 working days from the first draft |

## 0. Summary for the owner

1. The repo is not a toy in its skeleton. It has a 15-state ESKF with ZUPT, NHC, a gated learned speed pseudo-measurement, a chi-squared gated displacement update, an HMM road matcher with beam and an explicit `AMBIGUOUS` state, contract schemas, a leak-free blackout masker, and 77 passing Python tests plus a JVM test suite. Most SIH teams will not have this.
2. It is a toy in its evidence on the old evaluator. Do not cite `results/io_vnbd_blackout_eval.md`. The screening source of record is `results/io_vnbd_screening_v1/summary.md`. Persist drift p50 is 0.5168 on 35 intervals. `kotlin_eskf` is 6.6863 on 33. `kotlin_eskf_v2` is 7.5650 on 35. Linear speed MAE beats freeze and heuristic. GRU is analysis-only. The official 0.10 gate is not met. IO-VNBD 10 Hz tables and 9 s truth still limit that suite.
3. The instrument layer is in the tree: mode lamp, halo, status sheet, Judge, first-run still mount, trips, offline-areas list. Streets, search, and routing still use the network until a Ready pack exists. No Pune pack. No airplane-mode field video.
4. The product filter has been run on IO-VNBD. Official `kotlin_eskf` drift p50 is 6.6863 on 33 of 35 intervals (endpoint p50 2669.27 m), worse than persist 0.5168 / 234.47 m. `kotlin_eskf_v2` is 7.5650 on all 35 (endpoint p50 2996.50 m), worse than persist and worse than `kotlin_eskf`. Source: `results/io_vnbd_screening_v1/summary.md`. Diagnosis stopped on that suite. Persist is the held-out IO-VNBD coast to beat. The product filter is not the screening headline.
5. Owner path to Sep 20: P1 truth and heading-rate quality, curve speed and self-cal measured and rejected as coasts; P2 eight Pune drives at 100 Hz (owner); P3 make ESKF beat persist on held-out Pune drives and still report IO-VNBD; P3b TimesFM 3.0 desktop zero-shot, reject, timesfm_coast drift p50 0.6048 worse than persist 0.5168; P4 offline Pune pack, tunnel/layer matcher, halo PICP, parking pin; P5 airplane-mode video, PPT, failure taxonomy, two fresh-install rehearsals. See section 3.4.

## 1. Official statement and judging

Official SIH26168 text in this section is from `https://www.sih.gov.in/sih2026PS`, modal `#ViewProblemStatement26168`, fetched 2026-09-03, and logged in `docs/refs/SIH26168_EVIDENCE.md` section A. Community archive `https://sih2026.vuce.in/ps/SIH26168` (HTTP 200) matches the official modal. Quoted phrases below are the sponsor's wording from that page.

### 1.1 What the official page contains

| Field | Official value | Citation |
|---|---|---|
| Problem Statement ID | 26168 | fetched 2026-09-03, `https://www.sih.gov.in/sih2026PS` |
| Portal code | SIH26168 | same |
| Title | "AI-ML based Intelligent Dead Reckoning system for seamless navigation" | official title, same page |
| Organization | Indian Space Research Organisation(ISRO) | same |
| Department | Department of Space / Indian Space Research Organisation | same |
| Category | Software | same |
| Theme | Smart Vehicles | same |
| Idea deadline | 20 September 2026 | official PS table and 2026 guidelines PDF |
| Mandatory dataset | IO-VNBD at `https://github.com/onyekpeu/IO-VNBD` | official modal, fetched 2026-09-03 |

Requirements as the official Description states (SIH-01 to SIH-26 in `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`):

- Failure environments, verbatim: "when a vehicle enters a long underground tunnel/ underpass, a multi-level parking lot, a dense forested highway, or a deep urban canyon surrounded by skyscrapers, GNSS connectivity drops entirely." Also: "GNSS signals are inherently weak and vulnerable to structural blockage (urban canyons, dense foliage, tunnels, deep valleys) and unintentional electromagnetic interferences from variety of sources such as jamming." Jamming is named. Spoofing is not named. Fetched 2026-09-03, official modal.
- MEMS disturbance, verbatim: "The smartphone is subjected to severe chassis vibrations, engine harmonics, sudden braking, and road potholes." Fetched 2026-09-03.
- OBD and vehicle computer: a hard prohibition, not a soft "should not depend on". Verbatim: "Without an external speedometer feed from the vehicle's OBD-II port, calculating distance and velocity exclusively from consumer-grade smartphone sensors results in exponential error accumulation" and "maintaining lane-level accuracy without requiring any physical connection to the vehicle’s internal computer". Fetched 2026-09-03.
- Lightweight engine, verbatim: "The goal is to develop a lightweight, edge-deployable software engine and mobile application that transforms a standalone smartphone into an Intelligent Dead Reckoning (IDR) system with GNSS Fusion." Fetched 2026-09-03.
- Lane-level verb is "maintaining", not "aim for". The official sentence is "maintaining lane-level accuracy without requiring any physical connection to the vehicle’s internal computer". Team posture: "maintaining" lane-level is an aim for the pitch, not a claim from consumer MEMS. Fetched 2026-09-03.
- Speed and vibration, verbatim: "the solution must employ AI/ML models trained on vehicle kinematics to accurately predict vehicle speed and acceleration profiles solely from the smartphone’s noisy accelerometer/gyro inputs. It must dynamically detect and filter out non-navigation motions such as engine idling vibrations, pothole shocks, bumps, and accidental phone misalignments on the mount." Fetched 2026-09-03.
- Offline maps and NHC, verbatim: "By overlaying the inertial trajectory onto an offline map database (e.g., Open Street Map), the system should use the road layout as a constraint. For instance, it can apply Non-Holonomic Constraints (NHC), assuming a car cannot slide sideways or fly upwards". Open Street Map is named. Matcher example later on the same page: "AI-ML framework or Unscented Kalman Filter + Hidden Markov Map Matching". Fetched 2026-09-03.
- External IMU and FOG, verbatim: "These algorithms/models should also work with any other external IMU sensors data (Edge deployable software engine)." and "Position update rate of 10Hz with processing on smartphones (Mobile application) and higher update rates on Edge deployable software engine using FOG based IMU sensors data (around 200Hz)." FOG is named. About 200 Hz is named. The PS requires algorithms that accept external IMU data. It does not say a live FOG unit must be demonstrated at screening. Fetched 2026-09-03.
- Mandatory dataset and screening, verbatim: "This dataset should be used to train & test the models and submit for screening of proposals. Teams are required to include the preliminary AI models and the results of the position plot inferenced from the subset of IO-VNBD dataset as part of their proposals submitted for evaluation. During the screening process more datasets will be provided for further evaluation of the AI models." The official page does not define whether "preliminary AI models" must be a neural network. The Expected Solution allows "A deep-learning or statistical signal-processing model" for the speed filter. Fetched 2026-09-03.
- Training versus inference, verbatim: "Complex training happens in the cloud/desktop apriori, while inference happens on the smartphone." No foundation-model wording and no TimesFM wording appear in the official PS text. Fetched 2026-09-03.
- Named inputs: phone accelerometer, gyroscope, magnetometer/compass, and GNSS if available (official Description, fetched 2026-09-03). Magnetometer/compass is a named input.
- Expected Solution modules, verbatim names from the official page (fetched 2026-09-03): "In-Vehicle Alignment & Calibration Engine"; "AI Speed & Vibration Filter"; "Advanced Map-Matching & Kinematic Constraints"; "GNSS+INS Fusion Engine"; "Seamless GNSS Deficit Handler"; "Real-time Navigation Interface".
- Dead-reckoning drift, verbatim: "The solution must restrict positional drift to less than 10% of the total distance travelled using smartphone IMUs sensors during GNSS signals blackout (for e.g., in case of smartphones IMU, a drift of less than 5 meters is desired over 50m GNSS denied environment in <1 minutes OR less than 100m of drift over a 1km GNSS denied environment at a speed of 60kmph in tunnels/underground metro OR similar simulated environments where GNSS signals are unavailable)." Reading: "must restrict" applies to the 10 percent figure. The 5 m / 50 m and 100 m / 1 km figures are introduced by "for e.g." and "is desired". Phone output rate is 10 Hz. Fetched 2026-09-03.

### 1.2 Misreads in `docs/01` that the official text now settles

1. SIH-07. Official verb is "maintaining" lane-level accuracy. Team posture stays: this is an aim, not a claim from consumer MEMS.
2. SIH-15. Official text has no foundation-model wording and no TimesFM wording. TimesFM is the team's own optional desktop experiment, not a requirement response.
3. SIH-12 and SIH-26. FOG and around 200 Hz are named as an interface for the edge-deployable engine. The PS does not say a live FOG unit must be demonstrated at screening. Whether a live external FOG IMU is required at the finale is not independently verified on 2026-09-03.
4. SIH-14. Screening text requires "preliminary AI models" and "the results of the position plot inferenced from the subset of IO-VNBD". Whether that excludes a filter plus a learned or statistical speed component is not independently verified on 2026-09-03. The Expected Solution allows "A deep-learning or statistical signal-processing model" for the speed filter.
5. Magnetometer/compass is a named input. The current code ignores `SensorKind.MAGNETOMETER` in `DeadReckoningFilter.consume`. Document it as captured and gated off until a real use exists.
6. Official page retrieval date is 2026-09-03. Source log: `docs/refs/SIH26168_EVIDENCE.md`.

### 1.3 How SIH 2026 is judged

Fetched 2026-09-03 from `https://www.sih.gov.in/letters/2026/SIH%202026%20Guidelines.pdf` (26 pages), `https://www.sih.gov.in/letters/2026/SIH2026-IDEA-Presentation-Format.pptx`, and `https://www.sih.gov.in/faqs`. Full log: `docs/refs/SIH26168_EVIDENCE.md` section B.

- SPOC (faculty) registers students. Students do not register themselves. Only students selected in an internal hackathon may be nominated. Institute cap: 50 teams (45 shortlisted + 5 waitlisted). University cap: 100 teams. Team: 6 members including the leader, same college, at least one female member. No inter-college teams. Each team may submit ideas against a maximum of 2 problem statements.
- Idea counter starts August 2026. The PDF says only 500 ideas will be submitted for a particular PS. Official table showed `0/500` for SIH26168 on 2026-09-03. Last date for team nomination and idea submission is 20 September 2026.
- Team leader uploads: idea title, idea description, idea presentation (PDF). Official 2026 template has six content slides: TITLE PAGE; IDEA TITLE / Proposed Solution; TECHNICAL APPROACH; FEASIBILITY AND VIABILITY; IMPACT AND BENEFITS; RESEARCH AND REFERENCES. Slide 7 is instructions and may be deleted. The instructions say: "Kindly keep the maximum slides limit up to six (6). (Including the title slide)". Upload as PDF only.
- Idea selection criteria in the guidelines PDF (no numeric weights): "novelty of the idea, complexity, clarity and details in the prescribed format, feasibility, practicability, sustainability, scale of impact, user experience and potential for future work progression." Official 2026 scoring weights are not independently verified on 2026-09-03.
- "4-5 teams per problem statement may be selected for the grand finale, but the final decision rests with the problem statement creating organization, which isn't obligated to declare a winner unless student proposals meet their expectations."
- Grand Finale: "held offline at various nodal centers across pan India", "proposed to be organized in December 2026". FAQ: "First stage i.e. idea screening is online. SIH Grand Finale would be conducted in an offline mode." Prize: Rs 1,50,000 per problem statement, paid only if the organisation likes the idea.
- Official idea upload is a PDF of the 6-slide template. A mandatory screening video is not independently verified on 2026-09-03. The guidelines list "Idea presentation (PDF)" as the screening artefact. An Image/Video Link appears only in the internal-hackathon report fields for the SPOC.
- Official text requires travel to a nodal centre and a working prototype. The PS says the trained model will need to be exported to the smartphone and receive live phone IMU and GNSS. Whether recorded demos are accepted at the 2026 Grand Finale is not independently verified on 2026-09-03.
- Secondary schedule (Times Now, 21 August 2026, `https://www.timesnownews.com/education/smart-india-hackathon-2026-over-220-problem-statements-released-article-155949432`, fetched as a press report of the launch, not a `sih.gov.in` PDF): idea submission 21 August to 20 September 2026; evaluation 10 September to 30 October 2026; result first week of November 2026; mentoring 10 to 30 November 2026; Grand Finale December 2026 (tentative). 226 problem statements in the first lot (172 software, 54 hardware).

Design implication: the screening PPT must contain a real IO-VNBD plot with a drift table, an architecture figure, a no-external-hardware statement, and a failure analysis. A team with an honest 15 percent and a clean method beats a team with a suspicious 2 percent.

### 1.4 NavIC (visibility, not integrity)

Fetched 2026-09-03 from `https://www.isro.gov.in/FAQ_Navigation.html`, `https://www.isro.gov.in/SatelliteNavigationServices.html`, `https://www.isro.gov.in/GSLV_F12_Landingpage.html`, and Android `GnssStatus` references. Full log: `docs/refs/SIH26168_EVIDENCE.md` section E.

Android can log IRNSS space vehicles when the chipset HAL reports constellation 7 (`CONSTELLATION_IRNSS = 7`, API 29). `usedInFix` and `getCn0DbHz` (API 24) are OS reports. They are not an integrity service. ISRO states NavIC "Does not provide integrity information" and "Does not support safety-of-life operations". GAGAN "Provides integrity information" and "Provides safety-of-life operation support". GAGAN is the GPS SBAS integrity path. L1 from NVS-01 (29 May 2023) onward is the consumer-band addition. Many phones still never surface IRNSS. Visibility is not integrity.

A gazetted DoT mandate that all phones must support NavIC is not independently verified on 2026-09-03. A primary MediaTek NavIC announcement page is not independently verified on 2026-09-03.

## 2. Where the repo actually stands

### 2.1 Snapshot

- 5 commits. Last commit 2026-09-02 23:04 IST: "Snapshot travel map, ESKF pose, and student weights for landing on main."
- Code volume as read: `packages/navigation-core` 6,840 lines (main plus tests), Android app 3,451 lines main plus 972 lines tests, `ml/` 5,058 lines.
- Tests: `PYTHONPATH=ml/src python3 -m unittest discover -s ml/tests` ran 77 tests, OK `[verified locally 2026-09-03]`. JVM and Android tests were not run in this audit (JDK 17 required; not attempted).
- CI (`.github/workflows/ci.yml`, being edited during audit): `make validate`, `make lint`, `:navigation-core:test`, Android unit test, lint, assembleDebug.
- Data: 241 IO-VNBD `S-*.csv` pulled (762,583,010 bytes), 72 unique categorised synchronised smartphone tables. Manifest `data/manifests/io_vnbd_screening_v1.yaml` has trip splits and hashes but `blackout_interval_ids: []`.
- Results: `results/io_vnbd_screening_v1/summary.md` is the screening source of record (git `069e74b` plus the uncommitted `ml/` tree, seed 26168, IO-VNBD checkout `1189396`). 35 gated held-out intervals. Official product-filter row `kotlin_eskf` is drift p50 6.6863 on 33 of 35 intervals (endpoint p50 2669.27 m). `kotlin_eskf_v2` is 7.5650 on 35 intervals (endpoint p50 2996.50 m). Both worse than persist 0.5168 / 234.47 m. Keep both rows. timesfm_coast is in the same table: drift p50 0.6048, worse than persist 0.5168, better than linear 0.7132. Desktop report: `results/timesfm/summary.md`. Verdict: reject. Score-only plots: `plots/S-Vta2_mid.png`, `S-S1_mid.png`, `S-S3b_mid.png`. The older `results/io_vnbd_blackout_eval.md` table is an evaluator artifact. Do not cite it.
- Models: corrected `models/motion_student_v1/linear.json` (same 12 features, grouped session splits) is the packed speed student. `models/motion_student_v2/gru.json` is analysis-only. No Kotlin GRU runtime. Invalid-unit MAE numbers live in `models/motion_student_v1/train_report_invalid_kmh_labels.json` and must not be cited. `linear_dp.json` remains worse than freeze and is not a screening claim.

### 2.2 Gap table: PRD promise versus code versus evidence

Status words: IMPLEMENTED, PARTIAL, ABSENT. File names are relative to the repo root. Line numbers are as read.

| FR / PRD item | Promise | What the code does today | Evidence that exists | Status |
|---|---|---|---|---|
| FR-01 capture | Accel, uncalibrated gyro, magnetometer, GNSS with accuracy, satellite status, optional raw GNSS; monotonic timestamps; 50 to 100 Hz raw, 10 Hz output | `pose/PhoneImuSource.kt` registers `TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE` at `SENSOR_DELAY_GAME`. No uncalibrated gyro, no magnetometer. `pose/GnssLocationSource.kt` uses `LocationManager` fixes plus `GnssStatus.Callback` (constellation and used-in-fix). No `GnssMeasurement`, no AGC, no C/N0. `PoseStore.tick()` runs at `OUTPUT_HZ = 10`. Sensor timestamps are `SensorEvent.timestamp`. | `PoseStoreTest`, `GnssProvidersTest`. No 30-minute replay determinism test. No dropped-sample report. | PARTIAL |
| FR-02 calibration and alignment | Stationary bias, gravity, phone-to-vehicle orientation from straight driving and turns, remount detection | First-run still window (`StationaryCalibrator`, 5 s) and `MountMonitor` exist. Full phone-to-vehicle yaw from driving is planned (P3). Default mount is still `Mat3.ANDROID_Y_FORWARD` until a profile is stored. | `StationaryCalibratorTest` | PARTIAL |
| FR-03 preprocessing | Causal filtering, saturation, outliers, vibration features, gravity separation, bump handling | `CausalImu.kt` builds a 1 s causal window with gravity low-pass and 12 features (`accel_mag_mean/std`, `accel_energy`, `gyro_energy`, `vibration_energy`, `specific_force_rms`, `jerk_rms`, `idle_flag`, `bump_flag`, `gyro_z_mean`, `dt_mean_s`, `n_norm`). `ZuptAccelMotionModel.kt` has a bump flag. No saturation flag. | `ZuptAccelMotionModelTest` (bump, idle, future-sample reject) | PARTIAL |
| FR-04 learned motion model | Compact on-device model for speed, yaw, uncertainty; versioned; app works without it | `LinearMotionStudent.kt` ridge speed, stop logit, log variance from the 12 features; loaded from `linear.json` by `MotionStudentAssets`. Fallback `ZuptAccelMotionModel` heuristic. No GRU on device. `gru.json` is analysis-only. | Retrained linear, seed 26168, grouped session splits. Speed MAE: validation 5.913 vs freeze 13.940 vs heuristic 7.421; public_test 4.391 vs 8.803 vs 5.236; locked_test 5.257 vs 10.651 vs 7.263. Old wrong-unit MAE is in `train_report_invalid_kmh_labels.json`. See section 2.3. | PARTIAL. Corrected linear is packed. SIH position gate not met. |
| FR-05 navigation filter | Position, velocity, orientation, biases, covariance; inertial propagation; learned, GNSS, NHC, ZUPT updates; uncertainty growth | `DeadReckoningFilter.kt`: ENU strapdown (`NFrameMechanization`), 15-state ESKF with Joseph update (`EskfMath`), `applyGnss` position (geometric gate `6 * (sigma + sqrt(P))`, lines 565 to 569, silent reject), `applyGnssVelocity`, `applyZupt`, `applyNhc`, `applyForwardSpeed`, `applyDisplacement` with chi-squared 11.345 and P inflation on reject, bias states with random walk, IMU gap inflation, finite checks, `numericalOk` latch. | `DeadReckoningFilterTest` (strapdown, gravity, GNSS covariance shrink, stale to DR, ZUPT, NHC), `DisplacementPseudoTest`, `NFrameMechanizationTest`, `VecMathTest`, `Wgs84Test`. No covariance positive-definite soak. No negative-timestamp test. | IMPLEMENTED for the maths, PARTIAL for the tests |
| FR-06 GNSS health and outage handling | Five states with hysteresis; fix age, accuracy, satellites, innovation, map consistency; reacquisition needs consecutive plausible fixes and blends | `poseAt` assigns five modes (ADR 006): `LOW_CONFIDENCE` if `horizontal95 > 120 m`, `DEAD_RECKONING` if held or fix age over 2 s, `REACQUIRING` until `InsConfig.reacquireFixes` (3) accepted fixes, `GNSS_DEGRADED` if accuracy exceeds 30 m or a gate reject is recent, else `GNSS_FUSED`. Kalman correction is still one step. Display blend is `PuckInterpolator`. `configs/base.yaml` is not loaded. `fused_to_degraded_consecutive` and `blend_duration_s` are documentation only. | `DeadReckoningFilterTest` covers degraded, reacquiring, held release | PARTIAL |
| FR-07 offline maps and matching | Local basemap and road graph; candidates in the uncertainty region; heading, speed, class, layer, topology; keep hypotheses when ambiguous | `HmmRoadMatcher.kt`: covariance-derived search radius (15 to 250 m), Gaussian emission with sigma at least half the 95 percent radius, heading term off below 1 m/s, Dijkstra transition, beam width 8, posterior and entropy, `MATCHED`, `AMBIGUOUS`, `UNMATCHED`, display-only projection. `OsmGraphLoader.kt` reads OSM XML and PBF, emits directed edges from `oneway`, stores `highway` class. Layer, bridge, tunnel are not stored. Soft feedback into the ESKF is absent. Default tiles are network OpenFreeMap. `data/area-packs/manhattan-sample/` holds a `manifest.json` only. `tools/maps/pack_bbox.py` writes a manifest and prints Planetiler and osmium commands; it does not build tiles. | `HmmRoadMatcherTest` and `OsmGraphLoaderTest` on a two-parallel-road fixture. No flyover, tunnel, U-turn, roundabout, service-road fixture. No airplane-mode test. | PARTIAL |
| FR-08 navigation output | 10 Hz `NavigationState` with covariance, mode, matched road, map-match confidence, GNSS health, model version, flags; conforms to schema | `NavigationTypes.kt` and `ContractMaps.kt` map to `contracts/navigation_state.schema.json`. Flags: `eskf`, `gps_held`, `zupt`, `nhc`, `motion_pseudo`, `displacement_pseudo`, `displacement_gated`, `imu_gap`, `no_imu`. `provenance.configHash` is a SHA-256 of a config string. | `ContractMapsTest`, `test_contracts.py` | IMPLEMENTED |
| FR-09 offline area management | Download or sideload PMTiles plus graph, checksums, size, expiry | `maps/AreaPackStore.kt` queues a bbox and can mark a sideloaded directory Ready. No downloader, no checksum verification, no size display. No pack is bundled. | `AreaPackStoreTest` (queue, sideload Ready versus Queued) | PARTIAL |
| FR-10 driver interface | Map, blue dot, heading cone, route, mode chip, confidence halo, expandable status sheet, large touch targets | Mode lamp, halo, cone, status sheet, Judge, first-run still mount, trips, offline-areas list, Settings, About are in the app tree. Streets still come from OpenFreeMap until a Ready pack. No Pune pack. `NavicMonitor.chipLabel` is a count string, not integrity. | `ModeLampTest`, `StatusCopyTest`, `InstrumentContrastTest`, `StreetMapConfigTest` | PARTIAL |
| FR-11 replay and judge mode | Replay recorded sessions through the same pipeline, mask GNSS, compare traces, inspect transitions, export a signed bundle | Trip record/replay and Judge overlay exist. `ReplaySensorSource` is the file adapter. Signed result bundles are not implemented. | `TripRecorderTest`, `TripReplayTest`, `PoseStoreTest` | PARTIAL |
| FR-12 external IMU interface | Generic `SensorFrame` stream; adapters declare units, axes, clock, rate; a recorded higher-rate stream demonstrates it | `ReplaySensorSource` reads SensorFrame JSONL at a declared rate. Synthetic 200 Hz vs 100 Hz test exists. No live FOG or recorded external IMU in `results/`. | `ReplaySensorSourceTest.twoHundredHzTrajectoryMatchesOneHundredHz` | PARTIAL |
| PRD 7 TimesFM experiment | Zero-shot forecasts, baselines, keep-or-reject gate, distillation | Desktop run rejected. timesfm==3.0.1, checkpoint `google/timesfm-3.0-pytorch`, seed 26168, MPS, 24.1 min. timesfm_coast drift p50 0.6048 on 35 gated intervals, worse than persist 0.5168, better than linear 0.7132. Ridge beat TimesFM on speed at 1 s, 2 s, and 5 s. TimesFM beat persist on yaw only. Speed PICP 68 was 0.01 to 0.05. Distillation was not attempted. 3.0 cannot ship. | `results/timesfm/summary.md`. Screening row in `results/io_vnbd_screening_v1/summary.md`. | REJECT as a teacher |
| PRD 9 quality gates | Median drift below 10 percent on a locked suite; p50, p90, p95, worst; no leakage; recovery jump; 10 Hz on a reference phone | Python screening bundle exists. Drift ratio p50: freeze 1.1200, persist 0.5168, timesfm_coast 0.6048, filter_only 0.5531, zupt_accel 0.8636, linear 0.7132, gru 0.7550, kotlin_eskf 6.6863 on 33 of 35 intervals, kotlin_eskf_v2 7.5650 on 35 intervals. No system met 0.10. `filter_only` is the Python coast. `kotlin_eskf` and `kotlin_eskf_v2` are the product `DeadReckoningFilter`. Persist is the held-out coast to beat. timesfm_coast lost to persist. | `results/io_vnbd_screening_v1/summary.md` | PARTIAL. Gate not met. Both Kotlin rows worse than persist. Diagnosis stopped. Do not start another silent filter tweak. |
| PRD 10 dataset plan | Split by complete journey and group correlated drives; India collection | Splits now hash `session_group_id`. `S-Vtb3` excluded (unit matches neither m/s nor km/h). No India data. | Screening manifest and `summary.md` | PARTIAL. Session grouping fixed. No India collection. |
| NFR-01/02/05/06 | 10 Hz p95 gap below 150 ms; model p95 below 20 ms; battery report; 2-hour soak | No measurement exists. | None | ABSENT |
| NFR-04 offline | No network after area package install | Tiles, geocoding, and routing are all network calls: `tiles.openfreemap.org`, `photon.komoot.io`, `nominatim.openstreetmap.org`, `router.project-osrm.org` (`ui/StreetMapConfig.kt`). | `StreetMapConfigTest` asserts these URLs | ABSENT until a pack exists |

### 2.3 Evaluator diagnostics `[verified locally 2026-09-03]`

Method: throwaway Python scripts in `/tmp` importing the repo's own `eval_iovnbd_blackout` helpers, run on the 30 held-out trips selected by `assign_trip_splits(seed="26168")`, on the local `data/raw/io_vnbd` checkout at IO-VNBD commit `1189396`. Nothing in the repo was changed. These are diagnostics, not benchmark results.

D1. Heading is initialised to north. `choose_blackout` picks one interval per trip, and `_coast` seeds heading with `course_rad` between the last two GNSS rows before the blackout. In IO-VNBD the GNSS columns repeat the previous fix on most 10 Hz rows: 385,259 of 470,698 consecutive GNSS row pairs (81.8 percent) have identical latitude and longitude. When the two rows are identical, `course_rad` returns 0.0, which is due north. Result: `h0_eval_deg` was 0 on 26 of 30 trips. The committed "filter_only" and "speed_student" trajectories mostly drove north at the last GPS speed regardless of the road. Replacing the two-row course with the course over the last 10 m of GNSS motion moved drift ratio p50 from 0.881 to 0.747 on the same 28 ratio-eligible intervals. Still bad, because of D3.

D2. GPS speed unit. The header reads `GPS SPEED (Kmh)`. The column is metres per second. Old `linear.json` labels were 3.6 times too small. Those MAE numbers live in `models/motion_student_v1/train_report_invalid_kmh_labels.json` and must not be cited as results. The Data in Brief paper (arXiv PDF `https://arxiv.org/pdf/2005.01701.pdf`, fetched 2026-09-03) declares `GPS speed` as `km/hr` in Table 5. Tables A6-1 / A6-2 are `V-Vfa` trip metadata, not unit tables. The paper unit is km/hr. The local m/s finding stands as a local measurement. Retraining after this finding is in the screening note below.

D3. Truth staleness dominates the endpoint metric. Median interval between unique GNSS fixes was 9.0 s in `S-Vw4`, `S-Vtb1`, `S-S3a`, and 1.0 s in `S-Vta2`. In a 20 s blackout most trips have only 2 to 4 unique fixes. `score_trip` compares the estimate at the last blackout row with that row's GNSS columns, which can be a fix up to 9 s old. At 20 m/s that is up to 180 m of "error" that is not the estimator's. Freeze scoring 1.00 exactly and truth path lengths of 84 to 659 m over 20 s are consistent with this. The evaluator must start and end intervals on rows where the GNSS position actually changed, and should prefer trips with 1 Hz GNSS for the screening plot.

D4. Truth quality gate missing. `S-S4` has a truth path length of 211,196 m over a 20 s window (a GPS jump of about 211 km). It is in the table as a valid interval. `S-Vta20` and `S-Vw15` are parked (zero path); `S-Vta25` and `S-Vta26` move 9 to 11 m. The ratio is meaningless for those, and `configs/blackout_protocol.yaml` already says `minimum_truth_quality: valid` but nothing implements it.

D5. Gyro axis is not the labelled one, at least for one mount. In `S-Vta2` (1 Hz GNSS, 914 turn triples) the GNSS course rate correlates with the column labelled `GYROSCOPE Pitch` at r = -0.73, with `GYROSCOPE Yaw` at 0.06, and with `GYROSCOPE Roll` at 0.02. The repo maps `gx = Yaw` and integrates `gx` as heading rate (`_coast`), and `ZuptAccelMotionModel` uses `gyro_z_mean`. The sign is negative, which matches Android gyro convention (counter-clockwise positive) against compass course (clockwise positive) for a phone held upright with its y axis vertical. The `GRAVITY X/Y/Z` columns give the vertical axis per trip and should drive a per-trip alignment instead of a fixed label. The paper (same PDF, fetched 2026-09-03) labels gyro columns "Gyroscope (Yaw)", "Gyroscope (Pitch)", "Gyroscope (Roll)" in rad/s and cites AndroSensor. It does not publish an Android `TYPE_GYROSCOPE` axis-to-column map. Gravity columns are provided for acceleration correction. That supports per-trip gravity alignment. It does not prove which named gyro column is vehicle yaw.

D6. Blackout selection is not locked and not stratified. One deterministic mid-trip window per trip, at most 20 s. No 50 m or 1 km official example intervals. `blackout_interval_ids: []` in the manifest.

D7. Split grouping leaks sessions. See PRD 10 row in the gap table.

What the first audit meant: the 87 to 100 percent drift in the old `results/io_vnbd_blackout_eval.md` table is not evidence about dead reckoning. It is an evaluator artifact (D1 to D4).

Screening v1, source of record `results/io_vnbd_screening_v1/summary.md` (git `069e74b` plus the uncommitted `ml/` tree, seed 26168, IO-VNBD checkout `1189396`):

- GPS SPEED is metres per second despite a Kmh header. Corrected linear student (same 12 features, splits hash `session_group_id`) beats freeze and the ZUPT heuristic on every held-out split. Pack `models/motion_student_v1/linear.json`. Speed MAE: validation 5.913 vs freeze 13.940 vs heuristic 7.421; public_test 4.391 vs 8.803 vs 5.236; locked_test 5.257 vs 10.651 vs 7.263.
- GRU (11 epochs, seed 26168) beats freeze and heuristic but does not beat linear on every split (validation 6.582, public_test 5.164, locked_test 5.208). `gru.json` is analysis-only. No Kotlin runtime.
- 35 gated held-out intervals. Drift ratio p50: freeze 1.1200, persist 0.5168, timesfm_coast 0.6048, filter_only 0.5531, zupt_accel 0.8636, linear 0.7132, gru 0.7550, kotlin_eskf 6.6863 on the 33 intervals it scored, kotlin_eskf_v2 7.5650 on all 35. Source: `results/io_vnbd_screening_v1/summary.md`. timesfm_coast lost to persist 0.5168 and beat linear 0.7132. Desktop report: `results/timesfm/summary.md`. Verdict: reject. No system met the official median drift gate of 0.10. `filter_only` is the Python coast. `kotlin_eskf` and `kotlin_eskf_v2` are the product `DeadReckoningFilter` replayed at 10 Hz table rate and scored by `eval_navstate`. Keep both Kotlin rows.
- Product filter, official row in the same `summary.md`. `kotlin_eskf` scored 33 of 35 gated intervals. Drift p50 6.6863, worse than persist 0.5168. Drift p90 130.9102, p95 177.8337, worst 225.9308. Endpoint p50 2669.27 m. Speed MAE p50 26.764. Heading MAE p50 1.633 rad. `S-S3b:mid` had no pose at the first unique-fix epoch, first state 4.0 s later. `S-S3b:d1000` wrote 0 NavigationState rows because the mask covered the init GNSS on that backward-jump trip. Blackout-window modes: DEAD_RECKONING 5999, LOW_CONFIDENCE 5326 of 11325 states. `health.filter_ok` stayed true. `kotlin_eskf_v2` scored all 35. Drift p50 7.5650, endpoint p50 2996.50 m, worse than persist and worse than `kotlin_eskf`. Diagnosis stopped. Persist is the held-out coast to beat. The product filter is not the screening headline.
- Official examples: persist on `S-Vta2` d50 was 2.99 m over 53.13 m. No 1 km interval had endpoint under 100 m.
- Plots, truth labelled score only: `results/io_vnbd_screening_v1/plots/S-Vta2_mid.png`, `S-S1_mid.png`, `S-S3b_mid.png`.
- Bump-gated R raised bump-window PICP 68 and did not change speed MAE. That is the only novelty claim.
- `S-Vtb3` excluded (unit matches neither m/s nor km/h).

The product `DeadReckoningFilter` has now been run on the locked suite. Keep both rows. Do not delete `kotlin_eskf`.

| System | n | drift p50 | endpoint p50 m |
|---|---:|---:|---:|
| persist | 35 | 0.5168 | 234.47 |
| kotlin_eskf | 33 | 6.6863 | 2669.27 |
| kotlin_eskf_v2 | 35 | 7.5650 | 2996.50 |

Diagnosis: H1 and H4 dropped (init heading within 3 deg of the 10 m course, GNSS speed present). H3 dropped on S-Vta2 and S-S1 (dt 0.10 s). H2 held as NHC on `unspecified` frame, not as blackout ZUPT. H5 held: heading rate on the wrong gyro axis after gravity align. H6 held: inclusive mask ate the S-S3b seed. v2 applied the fixes those measurements support (heading-rate gyro on +Z from the per-trip course-correlated column, NHC off when the frame is `unspecified`, still-ZUPT skipped when GNSS is held and pre-hold speed was at least 1.5 m/s, Replay `setGnssHeld` during the mask, mask start advanced 1 ns). S-Vta2:d50 endpoint fell from 5676.28 m to 173.38 m. S-S1:mid rose from 21322 m to 145748 m. Aggregate `kotlin_eskf_v2` is worse than persist and worse than `kotlin_eskf`. Stopped. Do not invent a better number. Do not start another silent filter tweak. Persist is the held-out coast to beat. The product filter is not the screening headline.

Live Android path after v2, read on the current tree. NHC still runs on the phone. `PhoneImuSource` copies `SensorEvent` xyz with no axis remap. `PoseStore.ingestAccel` / `ingestGyro` pass `VectorFrame.ANDROID_DEVICE`. `TripFrames` writes the same frame. NHC is skipped only when `imuFrame == UNSPECIFIED`, which is the IO-VNBD exporter label, not the live phone. Ordinary GNSS age-out (fix older than 2 s) does not set `gnssHeld`, so still-ZUPT still applies on a natural coast. The still-ZUPT skip is gated on `gnssHeld` and pre-hold speed at least 1.5 m/s. JVM `Replay.runFilter` sets that flag for every timestamp in the mask. Live `PoseStore.setSimulateGpsOff` also calls `setGnssHeld`, so a user GPS-hold after a moving fix skips still-ZUPT the same way. That is the one live-phone coast change. It is not a crash and not a silent zero. On-device `TripReplay.playInto` drops masked GNSS rows but does not call `setGnssHeld`. Heading-axis remap is not global and does not run on live Android IMU. It lives in `export_sensorframe._heading_rate_gyro` only. `select_heading_gyro` requires at least 8 unique-fix hops and absolute correlation 0.25. A weak pick falls back to the gravity-axis gyro component, still in the exporter. `DeadReckoningFilter.strapdown` uses the gyro vector as received. Do not copy that remap onto a dash-mounted phone without a course-correlation quality score. `poseAt` now assigns `GNSS_DEGRADED` when last fix accuracy is above 30 m or a gate reject is recent, and `REACQUIRING` after a coast until three accepted fixes. That is a live lamp change, not a screening number. v2 code was not reverted. No silent zero was found on the phone IMU path.

### 2.4 Documentation rot to clean before screening

- `README.md` now links `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`. `CUT_VS_KEEP.md` and `RELATED_APPS.md` still do not exist. Do not add them.
- Mode lamp words are GNSS / Assisted / Dead reckoning / Reacquiring / Low confidence. Old "GPS on" / "No GPS, estimating" copy is retired.
- `models/README.md` and ADR 005 no longer promise a live ONNX runtime. Nothing exports ONNX and Gradle has no runtime.
- TimesFM 3.0 desktop run rejected. Source: `results/timesfm/summary.md`. timesfm_coast drift p50 0.6048, worse than persist 0.5168. 3.0 weights cannot ship. Distillation was not attempted. It stays off the phone.

## 3. Ranked plan to Sep 20

### 3.1 Assumptions

- Two to three people, about 15 working days each after this draft. Estimates are person-days.
- Official 2026 screening artefact is the idea presentation PDF (6 content slides). A mandatory screening video is not independently verified on 2026-09-03. The team may still record a video for communication. It is not an official upload requirement.
- The Kotlin core builds and its tests pass on JDK 17 (not run in this audit).
- TimesFM 3.0 desktop work is P3b only. The run is reject. Do not put it on the phone. Do not polish the geocoder or router.

### 3.2 Must ship before Sep 20 (screening evidence)

Ranked by judge impact divided by effort.

| Rank | Deliverable | Why a judge cares | SIH IDs | Files | Days | Risk |
|---|---|---|---|---|---|---|
| 1 | Correct evaluator: intervals start and end on fresh-fix rows; heading seeded from GPS course over at least 10 m or the `GPS ORIENTATION` column; speed unit fixed; truth quality gate; locked IDs; per-interval CSV; p50, p90, p95, worst; PNG plots of estimate versus score-only truth | Done as the Python screening bundle in `results/io_vnbd_screening_v1/`. SIH median drift gate of 0.10 is not met. Keep `kotlin_eskf` and `kotlin_eskf_v2` in that file. Persist is the held-out coast to beat. Curve speed and self-cal were measured on the same 35 intervals and lost to persist. Do not ship either as a coast. The product filter is not the screening headline. | SIH-13, 14, 23, 24 | `ml/src/driftzero_ml/eval_iovnbd_blackout.py`, `eval_navstate.py`, `results/io_vnbd_screening_v1/` | 3 | Low for the Python fix. Kotlin scoring is done. Do not start another silent filter tweak. |
| 2 | Kotlin replay of IO-VNBD: a `SensorFrame` file adapter in `navigation-core` (CSV or JSONL, declared units, axes, rate), a JVM main that drives `DeadReckoningFilter` through the masked trip, writes 10 Hz `NavigationState` JSONL; Python scores it with `eval_navstate` | Done and diagnosed. `kotlin_eskf` drift p50 6.6863 on 33 intervals, endpoint p50 2669.27 m. `kotlin_eskf_v2` drift p50 7.5650 on 35 intervals, endpoint p50 2996.50 m, after H2/H5/H6 fixes. S-S1:mid rose from 21322 m to 145748 m. Both worse than persist 0.5168 / 234.47 m. Stopped. Do not add a learned model to chase this row. Do not start another silent filter tweak. | SIH-05, 08, 11, 12, 13, 26 | `results/io_vnbd_screening_v1/kotlin_replay/`, `eval_navstate` | 3 | High. Honest persist-beats-filter story. |
| 3 | Retrain `linear.json` with corrected m/s labels and grouped session splits; keep `linear_dp.json` out of the screening claim until it beats freeze | Done 2026-09-03. Corrected `linear.json` is packed. GRU is not packed and has no Kotlin runtime. | SIH-08, 14, 18 | `models/motion_student_v1/linear.json`, `train_report_invalid_kmh_labels.json` | 1 | Low |
| 4 | Screening PPT artifacts: architecture figure from `docs/02`, no-external-hardware statement, drift table, position plots, failure analysis of the three worst intervals, leakage checklist, dataset manifest hash | Official screening text requires "preliminary AI models" and "the results of the position plot inferenced from the subset of IO-VNBD". Use `results/io_vnbd_screening_v1/` and say the 0.10 gate is not met. | SIH-04, 13, 14, 23 | `results/io_vnbd_screening_v1/`, deck | 2 | Low |
| 5 | Group-aware splits: hash `session_group_id`; exclude `S-Vtb3` | Done. Splits hash `session_group_id`. `S-Vtb3` excluded (unit matches neither m/s nor km/h). | SIH-13 | `ml/src/driftzero_ml/io_vnbd/splits.py` | 0.5 | Low |
| 6 | Five-state filter state machine with hysteresis and reacquisition blend: `GNSS_DEGRADED` after N poor fixes, `REACQUIRING` after M consistent fixes, correction applied over `blend_duration_s`, `riskFlags` with reasons; read thresholds from `configs/base.yaml` or mirror them in `InsConfig` | Makes the mode lamp truthful and gives the official module "Seamless GNSS Deficit Handler" a name and a test | SIH-06, 21 | `DeadReckoningFilter.kt` (`poseAt`, `applyGnss`), `InsConfig`, `DeadReckoningFilterTest.kt` | 2 | Medium. Blend must keep covariance consistent; simplest safe form is to cap displayed correction per output and let the filter converge. |
| 7 | Instrument layer on the map: mode lamp (five labels from `NavigationMode`), halo radius from `horizontal95`, heading cone width from `heading95Rad`, one expandable status sheet (GNSS age, 95 percent radius, last trusted fix, map-match status and confidence, sensor flags, model version), long-press on the lamp wired to `PoseStore.toggleSimulateGpsOff` | This is the difference between a Maps clone and a navigation instrument. It also makes the video honest without narration. | SIH-22, FR-10 | `ui/TravelMapScreen.kt`, `ui/TravelChrome.kt`, `ui/StreetMap.kt`, `res/values/strings.xml` | 3 | Low. Follow `design-anti-vibecode.mdc`: solid panel, mono readouts, no glass. |
| 8 | Trip logger and file replay in the app: write `SensorFrame` and `NavigationState` JSONL per trip; a replay entry point that feeds the same `PoseStore` from a file at recorded timestamps with a GNSS-mask toggle | Judge mode for free. Also the only way to collect the India drive and rerun it deterministically. | FR-11, SIH-13, 21 | new `pose/TripLogger.kt`, `pose/FileSensorSource.kt`, reuse item 2's adapter | 2 | Low |

Subtotal about 16.5 person-days. With two people this is done by about Sep 12.

### 3.3 Differentiators, ranked, to fill Sep 12 to Sep 19

| Rank | Feature | What it is | Why a judge cares | SIH IDs | Files | Days | Risk |
|---|---|---|---|---|---|---|---|
| A | Coverage report for the halo | For every blackout, fraction of epochs where truth lies inside the 95 percent circle; reliability curve at 50, 68, 95 percent; one number: PICP at 95 | Every team shows a blue circle; almost none can say it is calibrated. This converts a UI element into a measured claim. | SIH-11, 22 | `metrics.py`, eval, `results/` | 1 | Low. Needs item 2. |
| B | Mount alignment from gravity plus GNSS course, and a misalignment monitor | Roll and pitch from low-passed accel during stops; yaw from the sign and axis of longitudinal acceleration correlated with GNSS speed change during straight driving; store as a transform; flag `REMOUNT` when the gravity direction in the phone frame shifts by more than a threshold for more than a few seconds; inflate heading covariance | The PS names alignment and calibration as a module. IO-VNBD's `GRAVITY` columns make it testable offline, and D5 shows the current fixed mount assumption is wrong for real trips. | SIH-03, 09, 17 | new `MountAlignment.kt` in core, `DeadReckoningFilter` hook, replay adapter | 3 | Medium. Yaw sign ambiguity during deceleration; use several events and a quality score. |
| C | Judge replay screen | Load a logged trip, choose an interval, mask GNSS, show estimate, filter-only, and score-only truth as three polylines with a state timeline and the drift number | The demo becomes verifiable on stage. | FR-11, SIH-14 | app `ui/JudgeReplayScreen.kt`, item 8 | 2 | Low |
| D | Flyover and tunnel awareness in the matcher | Store `layer`, `bridge`, `tunnel`, `highway=*_link` on `GraphEdge`; add a layer-continuity term to the transition; fixtures for flyover over surface road, service road beside main road, tunnel; show the second-best hypothesis on the map when `AMBIGUOUS` | India-specific failure mode judges know from their own phones. Showing two candidates instead of snapping is a visible honesty signal. | SIH-07, 10, 19 | `OsmGraphLoader.kt`, `RoadGraph.kt`, `HmmRoadMatcher.kt`, `RoadFixtures.kt`, `StreetMap.kt` | 3 | Medium |
| E | One Indian corridor offline pack and one recorded underpass drive | Run the Planetiler and osmium commands `pack_bbox.py` prints for a 10 by 10 km bbox around the team's city; sideload; record two drives through an underpass or covered parking with the trip logger in airplane mode with a clear-sky GNSS truth from a second phone or the same phone before the blackout | Airplane-mode demo on Indian roads with real data is what "not a toy" looks like. | SIH-04, 10, NFR-04 | `tools/maps/`, `AreaPackStore.kt`, `data/area-packs/` | 3 | Medium. Planetiler needs Java and a few GB. Keep the bbox small. |
| F | GNSS integrity lamp from innovations | Normalised innovation squared of each GNSS update against a chi-squared threshold; count of consecutive rejects; C/N0 mean and satellites used from `GnssStatus`; AGC from `GnssMeasurementsEvent` where the chipset provides it; state `GNSS_DEGRADED` with `riskFlags` naming the reason | The PS lists jamming and weak signal. This is the honest version: observable inconsistency, not certified spoofing detection. | SIH-02, 06, 20 | `DeadReckoningFilter.applyGnss`, `GnssLocationSource.kt`, `NavicMonitor.kt` | 2 | Medium. AGC availability varies by phone. |
| G | Latency, power, and soak on one mid-range phone | 30-minute replay in airplane mode: p50 and p95 tick time, output gap, memory, battery delta; 2-hour soak for NaN or gaps | NFR evidence. One table. | SIH-05, 25 | app debug logging, `results/` | 1 | Low |
| H | 200 Hz external IMU replay demo | Any 200 Hz IMU CSV (TUM VI or EuRoC `imu0/data.csv`, both already have loaders in `ml/`) through item 2's adapter with declared axes and rate, showing the core is rate-independent | Answers SIH-12 and SIH-26 with a run, not a sentence. | SIH-12, 26 | replay adapter, `datasets/tumvi.py` | 0.5 | Low, after item 2 |
| I | Vibration and stop cues | Stop detection already exists (`idle_flag`, ZUPT). Add a speed-breaker and pothole event flag that inflates speed measurement noise and suppresses NHC for 1 s (`nhcDropLateralMps2` exists). Engine-harmonic speed estimation is research and should not be attempted in this window. | Matches "filter idling, potholes, bumps" wording with a visible event in the status sheet. | SIH-03, 09 | `ZuptAccelMotionModel.kt`, `DeadReckoningFilter.kt` | 1 | Low |
| J | TimesFM 3.0 desktop zero-shot versus persistence, linear, and GRU (P3b, parallel) | Reject. `results/timesfm/summary.md`. timesfm_coast drift p50 0.6048 on 35 gated intervals, worse than persist 0.5168, better than linear 0.7132. Ridge beat TimesFM on speed at 1 s, 2 s, and 5 s. TimesFM beat persist on yaw only. Speed PICP 68 was 0.01 to 0.05. Distillation was not attempted. 3.0 weights cannot ship. Official PS does not mention TimesFM. | SIH-15 (team's own framing) | `results/timesfm/`, `timesfm_adapter.py` | 2 | Done. Reject. Stays off the phone. |
| K | Curve speed and per-trip self-cal coasts (P1) | Measured and rejected as coasts. persist_curve drift p50 0.9727, linear_curve 0.7244. 10 Hz a_lat is too noisy. Correlation with GNSS speed is -0.02 to -0.17. Median relative error is 0.63 to 0.66. persist_selfcal 0.6220, linear_selfcal 0.6649. Small speed MAE win. Median drift is still worse than persist 0.5168. Do not ship either as a coast. linear_selfcal may still be a student bias correction later. It is not a screening headline. | SIH-08, 09 | `results/io_vnbd_screening_v1/physics_notes.md`, `summary.md` | 1 | Done. Reject as coasts. |

### 3.4 Owner calendar (2026-09-03)

Ranked tables in 3.2 and 3.3 stay. This calendar replaces the earlier Sep 3 to 19 item list.

| Phase | Dates | Work |
|---|---|---|
| P1 | Sep 3 to 5 | Truth and heading-rate quality. Keep IO-VNBD scoring on fresh-fix epochs. Do not invent a better Kotlin row on that suite. Curve speed and per-trip self-cal were measured on the same 35 intervals and lost to persist. persist stays 0.5168. persist_curve 0.9727, linear_curve 0.7244. persist_selfcal 0.6220, linear_selfcal 0.6649. Do not ship either as a coast. linear_selfcal may still be a student bias correction later. It is not a screening headline. Notes: `results/io_vnbd_screening_v1/physics_notes.md`. |
| P2 | Sep 5 to 8 | Record eight Pune drives with the app. Scenes: underpass, basement, flyover with service road, stop-go, one remount. Capture at 100 Hz. Owner does this. |
| P3 | Sep 8 to 12 | Make ESKF beat persist on held-out Pune drives. Mount alignment, NHC only when aligned, ZUPT gating, chi-squared gated learned speed. Report IO-VNBD alongside. Do not hide the IO-VNBD persist-beats-filter row. |
| P3b | Parallel with P3 | TimesFM 3.0 desktop zero-shot versus persistence, linear, and GRU. Run rejected. timesfm_coast drift p50 0.6048, worse than persist 0.5168, better than linear 0.7132. Distillation not attempted. 3.0 cannot ship. Stays off the phone. |
| P4 | Sep 12 to 16 | Offline Pune pack. Tunnel and layer attributes in the matcher with a tunnel-exit readout. Halo PICP report. Parking pin. |
| P5 | Sep 16 to 19 | Airplane-mode video, PPT, failure taxonomy, two fresh-install rehearsals. |

### 3.5 What will hurt

- IO-VNBD 10 Hz tables and 9 s truth likely keep the official 10 percent gate unmet on that suite.
- TimesFM 3.0 was rejected. Ridge beat it on speed at 1 s, 2 s, and 5 s.
- Owner time in P2 is the schedule risk.
- Emulator screenshots say nothing about accuracy. Files under `results/emulator/` are emulator, mock GPS, fake IMU.

### 3.6 Stop doing

- Stop presenting `results/io_vnbd_blackout_eval.md` anywhere. Cite `results/io_vnbd_screening_v1/summary.md`.
- Corrected `linear.json` is packed. Do not pack `gru.json`. Do not cite `train_report_invalid_kmh_labels.json` as a result. Keep `linear_dp.json` out of the screening claim.
- Stop describing UI that does not exist in `PRODUCT.md`, `README.md`, and the sideload guide.
- Stop adding geocoder, router, or map style work. Search and routing are not scored.
- Stop calling TimesFM a requirement response. The desktop run is reject. 3.0 weights cannot ship. Distillation was not attempted. It stays off the phone.
- Stop writing the SIH 10 percent gate as met. It is not. `kotlin_eskf` and `kotlin_eskf_v2` are both worse than persist. Diagnosis stopped. Persist is the held-out coast to beat. The product filter is not the screening headline. Do not start another silent filter tweak.
- Stop shipping persist_curve, linear_curve, persist_selfcal, or linear_selfcal as a coast. Measured on the same 35 intervals. Lost to persist 0.5168. linear_selfcal may still be a student bias correction later. It is not a screening headline.

## 4. Novel differentiators with references

What this team can credibly implement in 17 days that most SIH teams will not. Each maps to code that already exists or to items in section 3. URLs and fetch status are from `docs/refs/SIH26168_EVIDENCE.md` section D, fetched 2026-09-03 unless marked otherwise. The only measured novelty in the screening bundle is bump-gated R (section 2.3).

1. Covariance-aware HMM with explicit ambiguity output. Already implemented in `HmmRoadMatcher.kt`: search radius from the filter covariance, emission sigma tied to the 95 percent radius, posterior entropy, `AMBIGUOUS` state, display-only projection. The extension is layer and tunnel attributes plus showing the second hypothesis. Basis: Newson and Krumm, "Hidden Markov Map Matching Through Noise and Sparseness", ACM SIGSPATIAL GIS 2009, Seattle, 4-6 November, pages 336-343, DOI 10.1145/1653771.1653818, `https://dl.acm.org/doi/10.1145/1653771.1653818` (ACM page fetched 2026-09-03). Numeric emission σ_z = 4.07 m and the β estimator are not independently verified on 2026-09-03 (PDF body not opened). Quddus, Ochieng, Noland, "Current map-matching algorithms for transport applications", Transportation Research Part C, 2007, DOI 10.1016/j.trc.2007.05.002, `https://doi.org/10.1016/j.trc.2007.05.002` (search snippet 2026-09-03).

2. Calibrated uncertainty with a coverage report. The filter already emits `horizontal95`. Item A measures whether truth falls inside it 95 percent of the time and shows a reliability curve. Basis: TLIO, Liu, Caruso, Ilg, Dong, Mourikis, Daniilidis, Kumar, Engel, "Tight Learned Inertial Odometry", arXiv 2007.01867, `https://arxiv.org/abs/2007.01867` (fetched 2026-09-03). The abstract says the network "regresses 3D displacement estimates and its uncertainty" and can produce "statistically consistent measurement and uncertainty" for the filter update. The words "diagonal covariance" and "NLL" are not independently verified on 2026-09-03 from the abstract alone. Pedestrian headset, not vehicle. Prediction-interval coverage: Khosravi, Nahavandi, Creighton, Atiya, "Lower Upper Bound Estimation Method for Construction of Neural Network-Based Prediction Intervals", IEEE Transactions on Neural Networks, 2011, vol. 22, no. 3, pp. 337-346, DOI 10.1109/tnn.2010.2096824, `https://doi.org/10.1109/tnn.2010.2096824` (search snippet 2026-09-03). Post-hoc recalibration: Kuleshov, Fenner, Ermon, "Accurate Uncertainties for Deep Learning Using Calibrated Regression", ICML 2018, arXiv 1805.10216. Not independently verified on 2026-09-03 (HTML resolved to an unrelated paper twice).

3. Learned pseudo-measurement gated by chi-squared inside an auditable ESKF. Already implemented: `applyDisplacement` with a 3-dof 99 percent gate (11.345) and covariance inflation on reject, `applyForwardSpeed` with `R = exp(logSpeedVariance)`. The differentiator is that the learned part can be removed and the system still runs, and every rejection is a logged flag. Basis: AI-IMU Dead-Reckoning, Brossard, Barrau, Bonnabel, IEEE Transactions on Intelligent Vehicles, vol. 5, no. 4, pp. 585-595, December 2020, arXiv 1904.06064, `https://arxiv.org/abs/1904.06064` (arXiv fetched 2026-09-03; journal volume/pages search snippet). Paper quotes "on average a 1.10% translational error" on KITTI, not a phone. TLIO as above. Gating theory: Bar-Shalom, Li, Kirubarajan, "Estimation with Applications to Tracking and Navigation", Wiley 2001. NIS chapter number is not independently verified on 2026-09-03.

4. Misalignment monitor and gravity-based mount alignment. Item B. Basis: Vinande, Axelrad, Akos, "Mounting-Angle Estimation for Personal Navigation Devices", IEEE Transactions on Vehicular Technology, DOI 10.1109/tvt.2009.2034667, `https://doi.org/10.1109/tvt.2009.2034667` (search snippet 2026-09-03). Snippet: "pitch, roll, and yaw mounting angles are estimated to within 2° of truth when utilizing consumer-grade accelerometers." Wahlström, Skog, Händel, "Smartphone-Based Vehicle Telematics: A Ten-Year Anniversary", IEEE T-ITS 2017. Not independently verified on 2026-09-03 (page not opened).

5. Innovation-based GNSS integrity state. Item F. Basis: NIS test as in item 3. Groves, "Principles of GNSS, Inertial, and Multisensor Integrated Navigation Systems", 2nd ed., Artech House 2013. Integrity-monitoring chapter number is not independently verified on 2026-09-03. Android observables, fetched 2026-09-03: `CONSTELLATION_IRNSS = 7` added in API 29 (`https://developer.android.com/reference/android/location/GnssStatus`, API-29 diff `https://developer.android.com/sdk/api_diff/29/changes/android.location.GnssStatus`); `getCn0DbHz` added in API 24; `usedInFix(int)` reports whether that satellite was used in the most recent fix; `GnssMeasurement` AGC helpers exist and are deprecated in favour of `GnssMeasurementsEvent.getGnssAutomaticGainControls()` (API 33). A phone that reports constellation 7 and a C/N0 is reporting OS/HAL visibility. That is not RAIM, not SBAS integrity, and not a NavIC integrity flag.

6. Vehicle constraints done properly. Already implemented: `applyZupt`, `applyNhc` with lateral-acceleration dropout. Basis: Dissanayake, Sukkarieh, Nebot, Durrant-Whyte, "The aiding of a low-cost strapdown inertial measurement unit using vehicle model constraints for land vehicle applications", IEEE Transactions on Robotics and Automation, vol. 17, no. 5, pp. 731-747, October 2001, `https://www-personal.acfr.usyd.edu.au/nebot/publications/gps_ins_constraints.pdf` (fetched 2026-09-03). Skog, Händel, Nilsson, Rantakokko, "Zero-Velocity Detection: An Algorithm Evaluation", IEEE TBME vol. 57, no. 11, pp. 2657-2666, 2010, DOI commonly 10.1109/tbme.2010.2060723 (venue/pages search snippet 2026-09-03). Detector names SHOE / ARE / MV are not independently verified on 2026-09-03 (PDF not opened).

7. Evaluation discipline as a feature. Fresh-fix epoch scoring, locked interval IDs, trip and session grouping, truth quality gate, per-interval CSV. The IO-VNBD structure demands it. Screening v1 is in `results/io_vnbd_screening_v1/`.

Related work for the deck's literature slide. Fetch status from `docs/refs/SIH26168_EVIDENCE.md` section D, 2026-09-03.

- Learned inertial odometry lineage: IONet, Chen, Lu, Markham, Trigoni, arXiv 1802.02209, `https://arxiv.org/abs/1802.02209` (arXiv fetched 2026-09-03; AAAI 2018 venue stamp not re-opened). RIDI, Yan, Shan, Furukawa, arXiv 1712.09004, `https://arxiv.org/abs/1712.09004` (fetched 2026-09-03). RoNIN, Yan and Herath (equal contribution), Furukawa, arXiv 1905.12853, `https://arxiv.org/abs/1905.12853` (arXiv fetched 2026-09-03; ICRA 2020 venue not re-opened on IEEE). TLIO as above.
- Vehicle-specific: RINS-W, Brossard, Barrau, Bonnabel, arXiv 1903.02210, `https://arxiv.org/abs/1903.02210` (fetched 2026-09-03). Paper quotes "20 m for a 21 km long trajectory" with a 10 deg/h gyro. Car IMU, not phone MEMS. "Denoising IMU Gyroscopes with Deep Learning for Open-Loop Attitude Estimation", Brossard, Bonnabel, Barrau, IEEE RA-L 2020, arXiv 2002.10718, `https://arxiv.org/abs/2002.10718` (fetched 2026-09-03). EuRoC and TUM-VI, not phone-in-car. OdoNet, Tang et al., arXiv 2109.03811: full text not independently verified on 2026-09-03 (HTML resolved to the wrong paper).
- Survey: Chen and Pan, "Deep Learning for Inertial Positioning: A Survey", arXiv 2303.03757, `https://arxiv.org/abs/2303.03757` (arXiv fetched 2026-09-03). IEEE T-ITS 2024 venue stamp not re-opened.
- Phone-in-vehicle speed: Yu et al., SenSpeed. IEEE TMC DOI 10.1109/tmc.2015.2411270, `https://doi.org/10.1109/tmc.2015.2411270` (search snippet plus PDF fetch 2026-09-03). Integrates accelerometer and corrects bias at reference points from turns, stops, and uneven surfaces. Quoted average errors 2.1 km/h real-time / 1.21 km/h offline on local roads.
- Recent smartphone vehicular DR: Xiao, Ren, Li, "An Inertial Sequence Learning Framework for Vehicle Speed Estimation via Smartphone IMU", arXiv 2505.18490, `https://arxiv.org/abs/2505.18490` (fetched 2026-09-03). AVNet (Satellite Navigation 2025) full text is not independently verified on 2026-09-03.
- Dataset: Onyekpe, Palade, Kanarachos, Szkolnik, "IO-VNBD: Inertial and odometry benchmark dataset for ground vehicle positioning", Data in Brief 35 (2021) 106885. arXiv `https://arxiv.org/abs/2005.01701` (v2, 11 December 2021) and PDF fetched 2026-09-03. Christopoulos is not an author of this paper. Scale from the PDF: about 40 h / 1,300 km vehicle-extracted (`V-`) data; about 58 h / 4,400 km smartphone (`S-`) data; about 100 h total driving by 8 drivers. Countries: United Kingdom (England), Nigeria, France. AndroSensor at 10 Hz. Phone GPS 1 Hz. Table 5 `GPS speed` unit `km/hr`. Related article: Onyekpe, Palade, Kanarachos, Applied Sciences 2021, 11(3), 1270, `https://doi.org/10.3390/app11031270`. arXiv 2005.01701 is the dataset paper itself. WhONet was not opened. Not independently verified on 2026-09-03. GitHub `https://github.com/onyekpeu/IO-VNBD` fetched; no LICENSE file in the fetched README. Data in Brief CC BY 4.0 on the live ScienceDirect HTML is not independently verified on 2026-09-03 (page blocked).
- GSDC: ION page `https://www.ion.org/gnss/googlecompetition.cfm` (fetched 2026-09-03): 3rd challenge 12 September 2023 to 23 May 2024. "Over 150 new traces containing raw GNSS measurements, sensor data, and precise ground truth." Fu, Khider, van Diggelen, ION GNSS+ 2020: not independently verified on 2026-09-03. Kaggle 2022 and 2023 licence text: not independently verified on 2026-09-03.
- Foundation model: Das, Kong, Sen, Zhou, "A decoder-only foundation model for time-series forecasting", arXiv 2310.10688, `https://arxiv.org/abs/2310.10688` (fetched 2026-09-03). TimesFM 3.0 desktop run rejected. Source: `results/timesfm/summary.md`. timesfm_coast drift p50 0.6048 on 35 gated intervals, worse than persist 0.5168, better than linear 0.7132. Hugging Face `google/timesfm-3.0-pytorch` is correct. GitHub `https://github.com/google-research/timesfm` fetched 2026-09-03: "Latest Model Version: TimesFM 3.0". Code Apache-2.0. Weights up to 2.5 remain Apache-2.0. TimesFM 3.0 weights: `timesfm-non-commercial-license-v1.0`. Research desktop use is allowed. The checkpoint cannot ship. Distillation was not attempted. A single published hard cap for 3.0 max context and max horizon was not independently verified on 2026-09-03. The official SIH26168 text does not mention TimesFM. It stays off the phone.
- Open-source comparators, none of which is a production Android phone-in-vehicle dead-reckoning navigator with an offline OSM pack (`docs/refs/SIH26168_EVIDENCE.md` section H): mbrossar/ai-imu-dr (KITTI research, search snippet); CathIAS/TLIO (pedestrian headset, paper fetched, repo page not opened); Sachini/ronin (pedestrian Python, GPL-3.0, fetched earlier 2026-09-03); barbeau/gpstest (Android GNSS test app, fetched); google/gps-measurement-tools (GNSS analysis, fetched).

OSM public tile policy, fetched 2026-09-03 from `https://operations.osmfoundation.org/policies/tiles/`: must not bulk download, prefetch, or build offline archives from `tile.openstreetmap.org`. "Offline use is not permitted on tile.openstreetmap.org." MapLibre Native license BSD 2-Clause, `https://raw.githubusercontent.com/maplibre/maplibre-native/main/LICENSE.md` (fetched 2026-09-03). MapLibre Android PMTiles support is an implementation fact in this repo, not independently re-proven from MapLibre docs on 2026-09-03.

## 5. Traps that look impressive and lose points

1. Snapping the puck to the road to hide drift. The matcher is display-only today, which is correct. Never write the matched position back into the ESKF at high confidence. Show `AMBIGUOUS` on the map.
2. Showing truth as an input. The score-only GNSS trace must be visibly labelled and drawn after the estimate. `blackout.py` and `assert_no_gnss_leakage` already enforce it in Python; keep the same key ban in the Kotlin replay.
3. Random row splits or same-session leakage. Section 2.3 D7. Fix before any retraining.
4. Wrong-unit metrics. D2. A speed MAE of 1.1 m/s that is really 4 m/s is a disqualifying embarrassment if a judge asks.
5. Reporting a mean drift only. Report p50, p90, p95, worst, count, and the excluded intervals with reasons.
6. Claiming lane-level. Say "road-level with an explicit ambiguity state; lane-level is not claimed from consumer sensors".
7. TimesFM on the phone or as a headline. It is not in the APK (correct). Do not let it become the story.
8. Cloud inference or hidden network calls. Today geocoding, routing, and tiles are all network. The demo must run in airplane mode with a pack, or the video must show the tiles loading before airplane mode and the puck continuing after.
9. Purple gradient or Material default UI, glass panels, emoji chips, buzzword copy. `design-anti-vibecode.mdc` already bans them. The current chrome is compliant; keep it so when adding the lamp and sheet.
10. Hiding uncertainty. The halo must grow. If `horizontal95` exceeds the low-confidence radius, say so on screen.
11. A NavIC lamp that implies integrity. `docs/refs/NAVIC.md` already draws the line; keep "NavIC 3" as a count.
12. Fake smoothness on GNSS return. Today the filter accepts a returning fix in one step. Item 6 is the fix; until then do not show a recovery in the video.
13. Presenting KITTI or pedestrian-dataset numbers as phone-in-car results.

## 6. Demo script and limitations

### 6.1 Three-minute demo script (planned, P5)

Do not put invented speeds, ages, radii, or match confidences on the card. Read numbers from the live sheet or from `results/io_vnbd_screening_v1/summary.md`. Airplane mode waits on a Ready Pune pack (P4). Emulator shots are not this video.

0:00 to 0:20. Phone in a windshield cradle, no cable. Title card: DriftZero. Phone-only dead reckoning. IO-VNBD scored. Pune recorded if P2 finished. Show the instrument screen: dark olive map, blue puck, mode lamp.

0:20 to 0:50. Approach an underpass on a recorded Pune drive if one exists. Lamp goes Assisted then Dead reckoning. Puck keeps moving. Halo grows. Open the status sheet once and read the live GNSS age, 95 percent radius, and map-match row.

0:50 to 1:20. Exit. Lamp Reacquiring, then GNSS. Overlay score-only truth only if that trace was hidden during the blackout. Read endpoint error over distance from the scored file, not from memory.

1:20 to 2:00. Judge replay on IO-VNBD trip `S-Vta2` if the overlay is ready. Table from `summary.md`: persist 0.5168, kotlin_eskf 6.6863 on 33, kotlin_eskf_v2 7.5650 on 35. Gate 0.10 not met. Halo PICP only if P4 produced it.

2:00 to 2:30. Architecture in one figure. Say the file adapter accepts a declared-rate IMU. Synthetic 200 Hz tests exist. No live FOG run.

2:30 to 3:00. Limitations (6.3). Close: every number in this video comes from `results/` with a manifest hash.

### 6.2 One-slide "why this is not a toy" (claims that exist today)

- The filter is a 15-state ESKF with bias states, vehicle constraints, and gated learned measurements. Equation map: `docs/refs/INS_ESKF.md`. JVM tests exist. That is not a Groves/Titterton/Sola certification.
- The map matcher returns `AMBIGUOUS` instead of guessing.
- Ground truth is masked by code with a leakage assertion, and the truth trace is scored after inference.
- The evaluator found and fixed its own bugs (north heading, unit, stale truth) and says so in `results/io_vnbd_screening_v1/summary.md`.
- The learned model can be deleted and the app still navigates (`ZuptAccelMotionModel`).
- Airplane mode with a Pune pack is planned (P4). Not true today.
- The same core replays IO-VNBD. A Pune drive and a recorded 200 Hz IMU file are planned. Synthetic 200 Hz tests exist.

### 6.3 Honest limitations to state upfront

- IO-VNBD is UK, Nigeria, and France, one main driver, phone GNSS as truth at 1 to 9 s intervals. Metre-scale truth; no centimetre claims.
- Consumer MEMS drift grows without bound; the product bounds what it displays, not what physics does. Long outages end in `LOW_CONFIDENCE`.
- Lane-level is not claimed.
- Alignment assumes a fixed mount during a trip; a handheld phone degrades to low confidence.
- No certified spoofing or jamming detection. Integrity flags are observable inconsistency.
- TimesFM 3.0 desktop run rejected. timesfm_coast drift p50 0.6048, worse than persist 0.5168. 3.0 weights cannot ship. Distillation was not attempted. It stays off the phone.
- No India trip files exist yet. Pune drives are P2. Generalisation across phones and vehicles is not established.
- The SIH median drift gate of 0.10 is not met on the 35 gated intervals in `results/io_vnbd_screening_v1/`. `filter_only` in that table is the Python coast. `kotlin_eskf` is the product filter: drift p50 6.6863 on 33 of 35 intervals, endpoint p50 2669.27 m, worse than persist 0.5168 / 234.47 m. `kotlin_eskf_v2` is 7.5650 on 35 intervals, endpoint p50 2996.50 m, worse than persist and worse than `kotlin_eskf`. S-S1:mid rose from 21322 m to 145748 m. Keep both rows. Persist is the held-out coast to beat. The product filter is not the screening headline.

## 7. References

Official SIH26168 text, TimesFM 3.0 naming, NavIC FAQ, and literature URLs: `docs/refs/SIH26168_EVIDENCE.md`, fetched 2026-09-03.

Screening measurements: `results/io_vnbd_screening_v1/summary.md` (git `069e74b` plus the uncommitted `ml/` tree, seed 26168, IO-VNBD checkout `1189396`). TimesFM 3.0 desktop reject: `results/timesfm/summary.md`. timesfm_coast is the screening row.

Verified locally 2026-09-03 (diagnostics, not literature): the IO-VNBD observations in section 2.3 D1 to D5, made on the local checkout at IO-VNBD commit `1189396`.

Repo documents `docs/SOURCES.md`, `docs/refs/LEARNED_IMU.md`, `docs/refs/MAP_MATCHING.md`, `docs/refs/DATASETS.md`, and `docs/refs/NAVIC.md` remain useful indexes. Where they disagree with the evidence file, the evidence file's fetch status wins.

## 8. Still not independently verified

Items below were looked for on 2026-09-03 and not confirmed from a primary page. Full list: `docs/refs/SIH26168_EVIDENCE.md` section "Could not verify".

1. Official 2026 screening or finale numeric weights. Criteria names were fetched. Weights were not.
2. Mandatory screening video. Official idea upload is the 6-slide PDF template.
3. Whether recorded demos are accepted at the 2026 Grand Finale.
4. Whether "preliminary AI models" excludes a statistical or linear student inside a filter.
5. Live external FOG IMU as a finale hardware requirement.
6. IO-VNBD GitHub LICENSE file (none in the fetched README).
7. Data in Brief CC BY 4.0 line on the live ScienceDirect HTML (page blocked).
8. WhONet paper title, venue, and wheel-speed dependence.
9. Newson and Krumm numeric emission σ_z and β estimator (DOI and venue confirmed; PDF parameters not opened).
10. Skog 2010 detector names SHOE, ARE, MV from the PDF.
11. Groves 2013 integrity-monitoring chapter number.
12. Bar-Shalom, Li, Kirubarajan 2001 NIS chapter number.
13. Kuleshov, Fenner, Ermon, "Accurate Uncertainties for Deep Learning Using Calibrated Regression", ICML 2018.
14. Wahlström, Skog, Händel, "Smartphone-Based Vehicle Telematics: A Ten-Year Anniversary", IEEE T-ITS 2017.
15. OdoNet (Tang et al., arXiv 2109.03811) full text.
16. AVNet (Satellite Navigation 2025) full text.
17. Fu, Khider, van Diggelen, ION GNSS+ 2020 GSDC dataset paper.
18. Kaggle 2022 and 2023 licence text and a full IMU-column inventory.
19. TimesFM 3.0 single numeric max-context and max-horizon caps.
20. TLIO "diagonal covariance" / NLL wording (abstract confirms displacement plus uncertainty only).
21. RoNIN ICRA 2020 and IONet AAAI 2018 venue pages on IEEE/AAAI (arXiv texts fetched).
22. Chen and Pan IEEE T-ITS 2024 issue stamp (arXiv fetched).
23. MapLibre Android PMTiles support from MapLibre docs (repo fact only).
24. Gazetted DoT / Government of India mandate that all phones must support NavIC.
25. Primary MediaTek NavIC announcement page.
