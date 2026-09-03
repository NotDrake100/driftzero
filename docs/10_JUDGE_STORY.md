# Judge story, SIH26168 / ISRO

Written 2026-09-03. Official title, from `docs/refs/SIH26168_EVIDENCE.md` section A: "AI-ML based Intelligent Dead Reckoning system for seamless navigation". Official words that are marketing in any other sentence stay inside quotation marks. Numbers below come from `results/io_vnbd_screening_v1/summary.md` and name the row. Architecture is `docs/figures/architecture.md`.

## 1. What the driver sees, what the judge should look at

The driver sees a banner, a lamp, and a dot. The banner is the collapsed instrument strip: mode word, speed, metre radius marked 95 percent. The lamp is the five-state chip. The dot is the blue puck. In a gap the lamp goes amber, the halo grows, and one reason line names the cause. That is the whole driving surface. The judge is looking at the machine behind it. Same `DeadReckoningFilter` on the phone and in `./gradlew :navigation-core:replay`. A 15-state ESKF with Joseph updates. Aids that can be refused. An HMM that is allowed to say AMBIGUOUS and is not allowed to overwrite lat/lon. A linear speed student that can be deleted. A mask that drops GNSS keys before inference. Hidden truth is scored after the estimate exists. The product filter lost to persist on the locked suite. Persist is the headline. The filter row stays in the table.

## 2. Five things to show, in this order

### 2.1 Hidden-truth reveal

Desktop first. Open `results/io_vnbd_screening_v1/plots/S-Vta2_mid.png`. Then `S-S1_mid.png` and `S-S3b_mid.png`. The black line is labelled "hidden truth, score only" in `ml/src/driftzero_ml/screening.py`. The estimate is drawn first. Truth is not an input.

On the phone, long-press the collapsed instrument line. That opens `JudgeOverlay` (`apps/android/app/src/main/java/in/driftzero/app/ui/JudgeOverlay.kt`). Hold GNSS. The fused trail keeps moving. The dotted trail is labelled "Phone GNSS (not an input while held)". Watch the 60 s mode strip change colour. Dataset truth is not drawn on the phone. `docs/08_PRODUCT_DESIGN.md` section 4.7 says so on purpose. A `JudgeReplayScreen` that loads an IO-VNBD trip and reveals score-only truth after the estimate is pending: `ui/JudgeReplayScreen.kt`.

What to notice. The mask is real. The phone GNSS line is a display of what the filter was not allowed to use while held.

### 2.2 Halo coverage

The halo is a 48-vertex polygon of radius `uncertainty.horizontal95` metres (`MapGeometry.circle`, `StreetMap` layers `driftzero-halo-fill` and `driftzero-halo-line`). It is true under zoom. Minimum drawn radius is 3 m. There is no maximum.

The coverage number is pending until the coverage report exists. `docs/07_RESEARCH_AND_ROADMAP.md` section 3.3 item A is that report: fraction of blackout epochs where truth lies inside the 95 percent circle, plus a reliability curve. Do not read the speed PICP rows in `results/io_vnbd_screening_v1/summary.md` section "Window-level speed" as halo coverage. Those PICP values are the speed-uncertainty head, not position.

What to notice. The circle grows when the lamp is amber. Growing is the claim you can show today. Calibrated coverage is not a number you can say yet.

### 2.3 Pipeline panel

Expand the bottom sheet (`BottomInstrument`, values from `StatusCopy.of`). Today the sheet has reason, GNSS age, last trusted fix, confidence radius, heading, map match, sensors, model, NavIC, area pack, and output rate when 20 ticks exist.

What exists today.

- Aid flags on `NavigationState.health.flags`: `zupt`, `nhc`, `motion_pseudo`, `displacement_pseudo`, `displacement_gated`, `imu_gap`, `no_imu`, `gps_held`. The Sensors row prints ZUPT and NHC. The Model row prints Speed student, Heuristic speed, or Filter only.
- Gated GNSS. Reason "Fix disagreed with estimate" (`ModeReason.GatedFix`). Risk flag `gated_fix`. The gate in `DeadReckoningFilter.applyGnss` is `6 * (σ + √P)`, not a printed chi-squared.
- Map match status and one confidence. MATCHED, AMBIGUOUS, Off road, or No area pack. `PoseStore.matchedRoad` returns a polyline only when status is MATCHED. The puck is not snapped.

What is queued.

- Per-update chi-squared on screen. The only χ² in the filter is displacement Δp at 11.345 (`LinearDpConstants.CHI2_99_3DOF`). Forward speed has no `innovationChiSquared` call.
- Covariance numbers beyond the 95 percent radius and heading 95 percent.
- HMM candidate list with posteriors. `HmmRoadMatcher` computes softmax posteriors, entropy, and `candidateCount`. The sheet does not list them. A second road when AMBIGUOUS is not drawn.

What to notice. The matcher can say Ambiguous 0.52. It does not invent a lane.

### 2.4 One core, three inputs

Same `DeadReckoningFilter`. Three ways in.

1. Live phone. `PhoneImuSource` registers accelerometer and gyroscope at `SENSOR_DELAY_GAME`, frame `ANDROID_DEVICE`. `GnssLocationSource` copies `Location` into `CoastFix` and copies `GnssStatus` into `NavicMonitor`. Status counts do not enter the filter.
2. IO-VNBD file. Python `ml/src/driftzero_ml/export_sensorframe.py` writes SensorFrame JSONL after per-trip `phone_align`. Frame is `unspecified`. Official scored rate is the 10 Hz table. Do not invent IMU samples for that row.
3. Higher-rate file. `ReplaySensorSource` accepts a header `declared_rate_hz` from 10 to 200. It does not resample. A scored 200 Hz FOG run is pending. Sensitivity at 100 Hz on `S-Vta2:d50` is in `results/io_vnbd_screening_v1/summary.md` section "Kotlin DeadReckoningFilter replay" and is not the official row.

Show the replay that is already in the summary Reproduce block.

```
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:replay --args="--input results/io_vnbd_screening_v1/kotlin_replay/frames/S-Vta25.jsonl --output results/io_vnbd_screening_v1/kotlin_replay/states/S-Vta25_mid.jsonl --mask-start-ns 954514000000 --mask-end-ns 976514000000"
```

Then score.

```
PYTHONPATH=ml/src python3 -m driftzero_ml.eval_navstate --states results/io_vnbd_screening_v1/kotlin_replay/states/S-Vta25_mid.jsonl --start-ns 954514000000 --end-ns 976514000001 --truth-jsonl results/io_vnbd_screening_v1/kotlin_replay/truth/S-Vta25.jsonl
```

`--mask-start-ns` / `--mask-end-ns` drop GNSS kinds in that interval. Replay also calls `setGnssHeld` so still-ZUPT does not zero a moving coast. Hidden truth is not in the input during the mask.

What to notice. One filter class. The file does not know IO-VNBD column names.

### 2.5 The evaluator that caught itself

Start with the unit. `results/io_vnbd_screening_v1/summary.md` section "Window-level speed" and the paragraph above the Kotlin diagnosis: GPS SPEED is metres per second despite a Kmh header. Old km/h labels are in `models/motion_student_v1/train_report_invalid_kmh_labels.json`. Do not cite those MAE numbers.

The Python coast used to seed heading from the last two GNSS rows. Identical positions produced due north. That is `docs/07_RESEARCH_AND_ROADMAP.md` section 2.3 D1. Screening persist now uses a 10 m unique-fix course. On the product filter, hypothesis H1 was dropped. First `NavigationState` heading matched the 10 m course: S-Vta2 319.84 deg versus -38.82 deg (1.34 deg circular), S-S1 241.65 deg versus -121.00 deg (2.65 deg). Source: `results/io_vnbd_screening_v1/summary.md` section "kotlin_eskf diagnosis and v2".

Truth used to be the last blackout row even when that GNSS fix was stale. Screening now scores unique-fix / fresh-fix epochs (`eval_navstate`). `docs/07_RESEARCH_AND_ROADMAP.md` section 2.3 D3 is the stale-truth note.

A truth-quality gate was missing. `docs/07_RESEARCH_AND_ROADMAP.md` section 2.3 D4 names trip S-S4 as a GPS jump of about 211 km inside a 20 s window. That trip is not a row in the screening Held-out table. `S-Vtb3` was excluded because the speed column matched neither m/s nor km/h (`results/io_vnbd_screening_v1/summary.md`, last paragraph before the blank lines).

Then the product filter was run. Official row `kotlin_eskf` in the Held-out table: drift p50 6.6863, endpoint p50 2669.27 m, 33 of 35 intervals. `persist` on the same table: drift p50 0.5168, endpoint p50 234.47 m, 35 intervals. `kotlin_eskf_v2`: drift p50 7.5650, endpoint p50 2996.50 m, 35 intervals. Both Kotlin rows worse than persist. SIH gate section: no system met median drift 0.10.

Diagnosis in that same summary file. H2 held as NHC on frame `unspecified` (S-Vta2 NHC 153/178). H5 held: heading rate on the wrong gyro axis after gravity align (S-Vta2 course-rate versus aligned gx/gy/gz 0.435 / 0.032 / -0.001). H6 held: inclusive mask ate the S-S3b seed. v2 applied the fixes those measurements support. S-Vta2:d50 endpoint fell from 5676.28 m to 173.38 m. S-S1:mid rose from 21322 m to 145748 m. Aggregate got worse. Stopped.

What to notice. The evaluator found its own bugs, published both Kotlin rows, and did not start another silent filter tweak. Persist is the held-out coast to beat.

## 3. Screening deck, mapped to the official wording

Official 2026 idea file is six slides including the title (`docs/refs/SIH26168_EVIDENCE.md` section B, template `SIH2026-IDEA-Presentation-Format.pptx`). Official screening sentences, same evidence file section A:

> "Teams are required to include the preliminary AI models and the results of the position plot inferenced from the subset of IO-VNBD dataset as part of their proposals submitted for evaluation."

> "The solution must restrict positional drift to less than 10% of the total distance travelled using smartphone IMUs sensors during GNSS signals blackout (for e.g., in case of smartphones IMU, a drift of less than 5 meters is desired over 50m GNSS denied environment in <1 minutes OR less than 100m of drift over a 1km GNSS denied environment at a speed of 60kmph in tunnels/underground metro OR similar simulated environments where GNSS signals are unavailable)."

"must restrict" applies to 10 percent. The 5 m / 50 m and 100 m / 1 km figures are the official "for e.g." / "is desired" examples.

### Slide 1. Title page

Problem Statement ID 26168. Portal code SIH26168. Title the official string above. Organization Indian Space Research Organisation. Category Software. Theme Smart Vehicles. Team ID and team name as issued.

Artifact: `docs/refs/SIH26168_EVIDENCE.md` section A. pending: assigned Team ID.

### Slide 2. Idea title / proposed solution

Phone-only dead reckoning. No OBD-II. No vehicle computer. Six official module names from the same evidence file: In-Vehicle Alignment and Calibration Engine; AI Speed and Vibration Filter; Advanced Map-Matching and Kinematic Constraints; GNSS+INS Fusion Engine; Seamless GNSS Deficit Handler; Real-time Navigation Interface.

Map each name to a file, not a slogan.

- Alignment. File path: `ml/src/driftzero_ml/features/phone_align.py`. Live still: `packages/navigation-core/src/main/kotlin/in/driftzero/core/StationaryCalibrator.kt`, `FirstRunScreen`. pending: mount yaw from GNSS course on the live phone (`docs/07_RESEARCH_AND_ROADMAP.md` section 3.3 item B).
- Speed filter. Packed student `models/motion_student_v1/linear.json`. Runtime `LinearMotionStudent.kt`. This is the "preliminary AI model" you can point at. The official Expected Solution allows "A deep-learning or statistical signal-processing model". Whether that phrase excludes a linear student is not independently verified (`docs/refs/SIH26168_EVIDENCE.md` Could not verify item 4).
- Map matching. `HmmRoadMatcher.kt`. Display only. AMBIGUOUS is a legal output.
- Fusion. `DeadReckoningFilter.kt`, `NFrameMechanization.kt`, `EskfMath.kt`.
- Deficit handler. `DeadReckoningFilter.poseAt`, ADR `docs/adr/006-navigation-mode-machine.md`. Five modes. Reacquire needs 3 accepted fixes.
- Interface. `TravelMapScreen.kt`, `ModeLamp.kt`, `BottomInstrument.kt`.

Artifact: `docs/figures/architecture.md`. Also `docs/08_PRODUCT_DESIGN.md` sections 1 and 4.

### Slide 3. Technical approach

Put the mermaid from `docs/figures/architecture.md` on this slide. Say the three inputs share one consume path. Say TimesFM is desktop research only, license `timesfm-non-commercial-license-v1.0`, not on the phone, experiment not run (`docs/adr/002-timesfm-teacher.md`).

This is also the slide for "preliminary AI models". Window-level speed table in `results/io_vnbd_screening_v1/summary.md`, row `linear` versus `freeze` and `heuristic`:

| Split | n | freeze MAE | heuristic MAE | linear MAE |
|---|---:|---:|---:|---:|
| validation | 23420 | 13.940 | 7.421 | 5.913 |
| public_test | 7696 | 8.803 | 5.236 | 4.391 |
| locked_test | 16241 | 10.651 | 7.263 | 5.257 |

GRU is analysis-only. No Kotlin runtime. `gru.json` is not packed.

Artifact: `models/motion_student_v1/linear.json`, `packages/navigation-core/src/main/kotlin/in/driftzero/core/LinearMotionStudent.kt`, `results/io_vnbd_screening_v1/summary.md` section "Window-level speed".

### Slide 4. Feasibility and viability

This is the slide for "the results of the position plot inferenced from the subset of IO-VNBD". Three plots: `results/io_vnbd_screening_v1/plots/S-Vta2_mid.png`, `S-S1_mid.png`, `S-S3b_mid.png`. Held-out table, same summary file:

| System | drift p50 | endpoint p50 m | n |
|---|---:|---:|---:|
| persist | 0.5168 | 234.47 | 35 |
| filter_only | 0.5531 | 193.83 | 35 |
| linear | 0.7132 | 286.98 | 35 |
| gru | 0.7550 | 292.76 | 35 |
| kotlin_eskf | 6.6863 | 2669.27 | 33 |
| kotlin_eskf_v2 | 7.5650 | 2996.50 | 35 |

SIH gate section of that file: no system met 0.10. Best median drift is persist 0.5168.

Official 50 m example, same file, section "kotlin_eskf diagnosis and v2": persist on `S-Vta2` d50 was 2.99 m over 53.13 m. Filter-only 6.48 m. Linear 30.60 m. GRU 32.26 m. Only three `d50` intervals passed the truth gate. Official 1 km example: ten `d1000` intervals. Persist endpoint p50 was 540 m. None under 100 m.

State the risk in one line. The product ESKF is worse than persist. Diagnosis stopped. Do not hide `kotlin_eskf` or `kotlin_eskf_v2`.

Artifact: `results/io_vnbd_screening_v1/summary.md`. pending: coverage report. pending: Indian underpass recording.

### Slide 5. Impact and benefits

A phone in a mount. No cable to the car. NavIC count when Android reports constellation 7, not integrity. ISRO FAQ: NavIC "Does not provide integrity information" (`docs/refs/SIH26168_EVIDENCE.md` section E). The driver gets a lamp and a radius, not a snapped lie on a flyover.

Artifact: `docs/08_PRODUCT_DESIGN.md` section 2 (`about_india` facts from the evidence file). pending: one Ready Indian corridor pack with measured bytes.

### Slide 6. Research and references

IO-VNBD paper, Newson and Krumm, Dissanayake NHC, Solà ESKF, TLIO displacement-plus-uncertainty, TimesFM paper as a desktop citation only. Fetch log: `docs/refs/SIH26168_EVIDENCE.md` section D.

Artifact: that evidence file. Do not paste KITTI 1.10 percent or pedestrian TLIO numbers as phone-in-car results.

## 4. Questions judges will ask

### Why not 10 percent yet?

Because the locked suite says so. SIH gate section of `results/io_vnbd_screening_v1/summary.md`: no system met median drift 0.10. Best row is persist 0.5168. Linear is 0.7132. The product filter is 6.6863 on 33 intervals. The official 50 m example is met by persist on one gated interval (S-Vta2 d50, 2.99 m over 53.13 m) and not by a 1 km interval (persist endpoint p50 540 m, none under 100 m). Saying 10 percent would be a lie.

### Why did the filter lose to persist?

Persist holds last unique-fix speed and the 10 m course. The ESKF integrates IMU, then applies NHC and ZUPT that can be wrong when the frame is `unspecified` or the heading-rate gyro is the wrong column. H2 and H5 in the summary diagnosis are those two. v2 turned NHC off on `unspecified`, remapped heading-rate gyro in the exporter, skipped still-ZUPT on a held moving coast, and advanced the mask 1 ns. S-Vta2:d50 got better. S-S1:mid blew up. Aggregate `kotlin_eskf_v2` drift p50 7.5650 is worse than `kotlin_eskf` 6.6863 and worse than persist 0.5168. We stopped. Persist is the coast to beat. The product filter is not the screening headline.

### What "AI" is in the loop?

A ridge linear student on 12 causal IMU features. Speed, stop logit, log variance. Packed as `models/motion_student_v1/linear.json`. Loaded by `LinearMotionStudent`. Features ban GNSS names (`GNSS_FEATURE_BAN`). It writes a pseudo-measurement. It does not overwrite position. If the file is missing, `ZuptAccelMotionModel` remains. GRU exists as `models/motion_student_v2/gru.json` for analysis. There is no Kotlin GRU. TimesFM is not in the loop.

### Why is TimesFM not on the phone?

ADR `docs/adr/002-timesfm-teacher.md`. The official PS does not name TimesFM (`docs/refs/SIH26168_EVIDENCE.md` section A). TimesFM 3.0 weights use `timesfm-non-commercial-license-v1.0` (non-commercial, non-production). The adapter is `ml/src/driftzero_ml/timesfm_adapter.py`. It fails closed when the package is missing. No forecast has been run. There is no keep-or-reject table in `results/`.

### NavIC?

Android can report `CONSTELLATION_IRNSS = 7`. The sheet prints visible and used counts from `NavicMonitor`. Those counts do not enter the ESKF. ISRO states NavIC does not provide integrity and does not support safety-of-life. GAGAN is the GPS SBAS integrity path. A lamp that implied NavIC integrity would be a false claim.

### Lane-level?

Official verb is "maintaining lane-level accuracy" (`docs/refs/SIH26168_EVIDENCE.md` section A). We do not claim it from consumer MEMS. The matcher returns AMBIGUOUS. The puck is not snapped. Road-level with an explicit second-best hypothesis on the map is still queued.

### How do you avoid leakage?

`ml/src/driftzero_ml/blackout.py` `assert_no_gnss_leakage` rejects a masked record that still has a GNSS input key. Kotlin replay drops GNSS kinds inside `--mask-start-ns` / `--mask-end-ns` and sets `setGnssHeld`. Student features are causal IMU only. Splits hash `session_group_id` (`ml/src/driftzero_ml/io_vnbd/splits.py`). Scorer `eval_navstate` sees truth after inference. Matcher does not read the hidden-truth route. `linear_dp.json` is worse than freeze and is not a screening claim.

### What breaks?

A handheld phone. Alignment assumes a fixed mount. `MountMonitor` flags a gravity shift. The lamp should go to a reason about remount when that path is live. A long tunnel. `LOW_CONFIDENCE` when `horizontal95` exceeds 120 m (`InsConfig.lowConfidenceRadiusM`). Drift is not bounded by hope. A 10 Hz IMU. Official `kotlin_eskf` is table-rate JSONL, not upsampled. `results/io_vnbd_screening_v1/summary.md` says regular IO-VNBD IMU dt is 0.10 s. That is a dataset limit. The 100 Hz hold-last sensitivity on S-Vta2:d50 is not the official row.

## 5. What is simple for the driver, and why

PRD section 13: never instruct a driver to interact with technical controls while moving. The driving surface is the map, the lamp, the puck, and one strip. Hold GNSS on the lamp is blocked above 8 m/s (`HOLD_MAX_SPEED_MPS`) unless Judge is already open. First-run still calibration refuses to start above 1 m/s. Judge is a long-press on the collapsed line, not a button in the driving chrome. Search is a keyboard. The driver is expected to be stopped. Pipeline flags, chi-squared, and candidate posteriors belong in the sheet or in Judge, not on the lamp. The halo and the reason line are the only things that must change while the vehicle is moving. That is how a survey console stays usable at 60 km/h. It is also how you keep a judge from watching someone tap a debug panel in a tunnel.

## Files created

- `docs/10_JUDGE_STORY.md` (this file)
- `docs/figures/architecture.md` (mermaid only; no mermaid-to-SVG tool was installed)

## Claims I wanted to make and could not back

- Halo PICP at 95 percent. No coverage report in `results/`.
- Live magnetometer capture. Contract kind exists. `PhoneImuSource` does not register `TYPE_MAGNETIC_FIELD`.
- Chi-squared gate on learned forward speed. The brief asked for it. `applyForwardSpeed` has none. χ² 11.345 is on displacement Δp.
- Hidden-truth overlay on the phone. `JudgeReplayScreen.kt` does not exist.
- A scored 200 Hz FOG or TUM-VI run. The adapter accepts the rate. `summary.md` has no 200 Hz official row.
- TimesFM keep or reject. Experiment not run.
- Lane-level accuracy. Not claimed, not measured.
- Meeting the official 10 percent median gate. Held-out table says no.
- Product ESKF beating persist. Held-out table says no.
- Official 2026 screening weights. Criteria names fetched. Weights could not verify.
- Whether "preliminary AI models" excludes a linear student. Official page does not define it.
- Indian underpass metres, airplane-mode hours, p95 tick ms, battery delta. No measurement file.
- Second HMM hypothesis drawn on the map. Matcher computes it. The map does not show it.
