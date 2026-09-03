# Gate plan, SIH26168, 3 Sep to 20 Sep 2026

Written 2026-09-03. Numbers below are from `results/io_vnbd_screening_v1/summary.md`, `physics_notes.md`, and `results/timesfm/summary.md`, plus the Kotlin and Python files named in each section. Estimates are labeled. No new experiment was run for this document. No code was changed.

Owner goal: meet the official median drift gate of 0.10, cover every PS module, add only extras that survive a license and leakage check. The locked IO-VNBD suite does not meet 0.10 today. Best row is persist 0.5168. The product filter is worse. Winning on 20 Sep means a honest Pune 100 Hz table that can meet 0.10, an IO-VNBD table that is no longer self-sabotaged, and a 6-slide PDF that does not hide either table.

---

## 1. Why the filter scores 6.69 when constant velocity scores 0.52

Persist is not a Kalman filter. `screening.py` `_coast` holds last unique-fix speed and the 10 m GNSS course, then adds `gyro_vertical_radps * dt` to heading. It never integrates accelerometer. It never applies NHC. It never applies ZUPT. Drift p50 0.5168, endpoint p50 234.47 m, speed MAE p50 3.439 m/s, heading MAE p50 0.673 rad, n=35.

`filter_only` in the same table is that same Python coast with the gravity-vertical gyro, not `DeadReckoningFilter`. It scores 0.5531. The product row is different: JVM replay of `DeadReckoningFilter.strapdown` plus ESKF updates on 10 Hz `unspecified`-frame JSONL. `kotlin_eskf` 6.6863 on 33/35, endpoint p50 2669.27 m, speed MAE p50 26.764 m/s, heading MAE p50 1.633 rad. `kotlin_eskf_v2` 7.5650 on 35/35, endpoint p50 2996.50 m, speed MAE p50 88.600 m/s.

The 13x gap is not "ESKF is a bad idea". It is gravity leaking into horizontal velocity because attitude is wrong, then the filter treating that leaked acceleration as motion. Persist cannot do that. A 1 deg tilt error couples about 0.17 m/s² of gravity into the horizontal. In 60 s that is about 10 m/s of false speed and about 300 m of position. The Kotlin speed MAE of 26 to 88 m/s is what a large, growing tilt error looks like. Diagnosis already saw the pre-mask wreck: S-Vta2 left `GNSS_FUSED` at 18 s with speed 31.8 m/s, gated the rest of the trip, and was 5629 m off at mask start.

v2 helped S-Vta2:d50 (5676.28 m to 173.38 m) and destroyed S-S1:mid (21322 m to 145748 m). Aggregate got worse. Work stopped. The next Kotlin row must be an ablation with one switch at a time, scored on the same 35 IDs, not another silent bundle.

### 1.1 Replay path, so the defects are in the right layer

1. Python `export_sensorframe.export_sensor_frames` writes 10 Hz SensorFrame JSONL. Accel is rotated by `phone_align`. Frame label is `unspecified`. Gyro is not rotated as a device XYZ vector. GNSS is unique-fix rows only.
2. `eval_kotlin_replay.replay_mask_ns` advances mask start 1 ns and steps end back 1 ns so the interval-start fix can seed.
3. `Replay.runFilter` in `Replay.kt` calls `setGnssHeld` inside the mask, drops GNSS kinds, then `DeadReckoningFilter.consume`. It does not construct `DeadReckoningEngine`. It never calls `ingestMotionPseudo`.
4. `eval_navstate` scores unique-fix epochs.

The live phone is not that path. `PoseStore` feeds `VectorFrame.ANDROID_DEVICE`, loads `linear.json` on the 10 Hz tick, and overlays `HmmRoadMatcher` without writing lat/lon back. Official `kotlin_eskf` is not the APK.

### 1.2 Defects

Each item is **certain** or **hypothesis**. Fix, test, expected effect.

**1. Strapdown accelerometer integration with a tumbling attitude. Certain.**

Evidence: `DeadReckoningFilter.predictTo` calls `strapdown` whenever gyro and accel exist and `dt <= maxIntegrateS` (0.40 s). `strapdown` passes the gyro vector as received into `NFrameMechanization.advance`. `NFrameMechanization.navAccelFromSpecificForce` adds gravity in ENU. If `q` is wrong, `g` does not cancel. Kotlin speed MAE p50 26.764 then 88.600 versus persist 3.439. Python `filter_only` 0.5531 never integrates accel.

Fix: during `gnssHeld` or GNSS age over `STALE_AFTER_S`, do not integrate specific force. Hold horizontal speed, integrate heading from the gravity-vertical gyro only, grow P. Same reduced model as persist, inside the ESKF so ZUPT, NHC, and later road updates still have a state. Keep full strapdown only while GNSS is accepted and tilt innovation is small.

Test: seed 15 m/s north, identity quaternion, 10 Hz accel = gravity on +Z, gyro = 0. After 20 s held GNSS, speed stays within 0.5 m/s and horizontal displacement is within 5 m of 300 m. Second case: 5 deg tilt error and full strapdown must show speed growing past 2 m/s in 10 s, so the leak is visible.

Expected effect: Kotlin drift on IO-VNBD should move from ~7 toward persist 0.52. Estimate: p50 in 0.5 to 1.5 if heading seed and gyro axis are right. It will not reach 0.10 on this suite.

**2. NHC on `unspecified` frames. Certain in v1. Gated off in v2. Live phone still on.**

Evidence: `maybeConstraints` returns before `applyNhc` only when `imuFrame == VectorFrame.UNSPECIFIED`. `bodyToVehicle(UNSPECIFIED)` is `Mat3.IDENTITY`, so vehicle +X is whatever +X `phone_align` produced, not vehicle forward. H2: S-Vta2 blackout NHC 153/178, first fused second dropped speed 7.44 to 1.85 m/s. v2 skip is tested in `unspecifiedFrameDoesNotApplyNhc`. Replay synthetic tests set `nhcMinSpeedMps = 100`, so they never catch this. Live `PoseStore.ingestAccel` passes `ANDROID_DEVICE`, so NHC still runs on the phone using `ANDROID_Y_FORWARD`.

Fix: keep the unspecified skip. Do not enable NHC until `MountProfile` quality is `ALIGNED` or `ALIGNED_HIGH`, or the frame is `VEHICLE_FLU` with a documented mount. On the phone, call `MountProfile.toVehicleAccel` / `toVehicleGyro` and set frame to `VEHICLE_FLU` only after that profile exists.

Test: already have unspecified skip. Add: `ANDROID_DEVICE` without a profile does not apply NHC. `VEHICLE_FLU` east-heading with lateral north leak drops vn. Remount flag disables NHC.

Expected effect: already in v2 for IO-VNBD. S-Vta2:d50 5676 to 173 m. Combined with defect 1, stops the speed-kill then still-ZUPT spiral. Does not by itself beat persist. On the phone, this is the difference between a dash mount that works and a portrait pocket that invents sideways velocity.

**3. Heading rate on the wrong gyro axis after gravity align. Certain on S-Vta2. v2 remap is exporter-only and uses the whole trip.**

Evidence: H5. After align, S-Vta2 gravity is already +Z. Course rate versus aligned gx/gy/gz was 0.435 / 0.032 / -0.001. Mechanization applies gz as yaw. Heading rate was on pitch. v2 `export_sensorframe._heading_rate_gyro` copies the course-correlated column onto +Z and zeros gx, gy. `DeadReckoningFilter.strapdown` is unchanged. Live `PhoneImuSource` copies SensorEvent xyz with no remap. `select_heading_gyro` needs at least 8 unique-fix hops and |r| >= 0.25. It is called on the full trip in `export_sensor_frames`, including unique fixes that later sit inside the mask. That is hidden GNSS used to choose the blackout gyro axis. Leak.

Fix: pick the heading column using only pre-mask unique-fix hops. Freeze the pick. If the pick is weak, fall back to gravity-vertical gyro and raise heading P, do not guess. Do not copy the remap onto a live phone. Wire `MountAlignment` yaw instead.

Test: exporter unit test, pick using prefix before `start_ns` differs from pick using the full trip on a fixture where the blackout contains a 90 deg turn. Filter test: gyro on +X at 0.1 rad/s with gz=0 must not yaw the ENU heading when the documented heading axis is Z.

Expected effect: S-S1:mid blow-up is the leading hypothesis for this pick or its sign. If true, v2-style remap with a pre-mask pick should bring S-S1 back toward the v1 21 km error, which is still a fail, then defect 1 should finish the job. If S-S1 stays exploded after a frozen pre-mask pick plus no-accel coast, the trip is a data problem, report it, do not chase it.

**4. Official Kotlin replay never injects the speed student. Certain.**

Evidence: `Replay.runFilter` only `consume`s frames. `DeadReckoningEngine.maybeEmit` is the path that calls `inferAt` / `ingestMotionPseudo`. `applyMotionPseudo` only applies forward speed when `gnssHeld` or GNSS age > 2 s. Replay sets `gnssHeld`, but nothing produces the measurement. `kotlin_eskf` is IMU + GNSS + ZUPT/NHC, not the packed `linear.json`.

Fix: drive replay through `DeadReckoningEngine` or call the same `MotionPseudoRuntime` tick Replay already has timestamps for. Ablate student on versus off. Default blackout speed measurement should be last accepted GNSS speed with R around 1 m/s std, not the 5.26 m/s student.

Test: JSONL with a speed student that outputs 0 must zero velocity when held. JSONL with student disabled and last GNSS speed 10 m/s must hold 10. Golden hash of states with student off must match persist within a stated bound on a 20 s north coast fixture.

Expected effect: plugging `linear.json` into the filter during blackout is likely to hurt. Locked-test speed MAE 5.257 m/s versus persist blackout speed MAE 3.439. Do this as an ablation, not as the headline.

**5. Still-ZUPT skip is tied to `gnssHeld`, not to "we are coasting". Certain.**

Evidence: `skipStillZupt = gnssHeld && speedBeforeHold >= zuptHeldSkipMps`. `poseAt` treats GNSS age > 2 s as coasting without setting `gnssHeld`. A tunnel without Judge hold can still-ZUPT once speed has fallen below 1.5 m/s. `still` uses `aNav.norm() < 0.45` and `omega < 0.05`. After gravity is well cancelled, constant-velocity driving looks still. The speed < 1.5 gate is the only brake. v1 H2 dropped speed to 1.85 m/s, one NHC step from that gate. Diagnosis: S-Vta2 blackout ZUPT 0/178, so ZUPT was not the v1 explosion on that trip. It is still a live-path trap.

Fix: skip still-ZUPT whenever coasting and `speedBeforeCoast >= 1.5`. Keep forced ZUPT from stop probability. Natural stops during a blackout still need the student or a separate idle flag, not raw `aNav.norm()`.

Test: GNSS age 3 s, no `setGnssHeld`, pre-coast speed 8 m/s, gravity-only IMU, velocity must not go to 0. Idle student stopProbability 0.95 must still zero velocity.

Expected effect: prevents a second death spiral on the phone. Small on the official Kotlin row because Replay already sets `gnssHeld`.

**6. Inclusive mask ate the S-S3b seed. Certain. v2 patched the mask, not the CSV rewind.**

Evidence: S-S3b CSV starts at 2503320000000 ns then rewinds to 8 ms. `keep_nondecreasing_rows` keeps the 2043-row prefix. Inclusive mask dropped the seed. `kotlin_eskf` wrote 0 states on S-S3b:d1000. `replay_mask_ns` now returns `start+1, end-1`. Test in `ml/tests/test_eval_kotlin_replay.py`.

Fix: keep the 1 ns shift. Also refuse to score a trip whose timestamp rewind discarded the suffix, or export the suffix as a second session. S-S3b persist drift 204 and 19.87 is a tail, not a filter.

Test: already have mask arithmetic. Add exporter test that a rewinding fixture emits a quality flag and does not silently keep only the prefix without a log line.

Expected effect: 35/35 scored. Does not move p50.

**7. Init heading. Not the v1 bug. Certain that H1 was dropped.**

Evidence: first NavigationState heading matched the 10 m course: S-Vta2 1.34 deg circular, S-S1 2.65 deg. `initializeFromGnss` uses `fix.headingRad` and `speedMps`. Unique-fix GNSS on those trips had speed and bearing on every exported fix.

Do not spend days on north-init. Python persist used to seed north from repeated GNSS rows. That is fixed in `seed_heading_rad`. Kotlin init is fine when the exporter writes bearing.

**8. Learned speed weight. Certain that there is no χ² gate. Hypothesis that current R is too tight if the student is wired.**

Evidence: `applyForwardSpeed` Joseph-updates with `R = sigma²`, sigma from `exp(logSpeedVariance)` clipped to 0.05 to 8 m/s. No `innovationChiSquared`. Displacement Δp does have χ² 11.345. Default `speedPseudoStdMps = 0.50` is unused when the student supplies variance. A 5.26 m/s student with sigma 1 m/s will haul the filter off persist.

Fix: χ² gate on 1-dof speed. Floor sigma at 2 m/s until a held-out residual model beats persist. Prefer measuring speed change from last GNSS speed.

Test: student 30 m/s versus persist 10 m/s must reject. Student 11 m/s versus 10 must accept.

Expected effect: only after Replay injects the student. Prevents a 5 m/s MAE head from dominating.

**9. Gravity alignment at 10 Hz. Certain that accel and gyro were treated inconsistently in v1.**

Evidence: exporter rotates accel, does not rotate gyro as XYZ. v1 fed that pair into strapdown. Attitude from `attitudeFromGravityAndHeading` assumes body-forward is `bodyForward(frame)`. For `UNSPECIFIED`, body-forward is +X. After `phone_align`, +Z is up, +X is not vehicle forward. Init quaternion and NHC then disagree with the road.

v2 zeros gx, gy, puts heading rate on gz. That is a yaw-only gyro. Tilt is frozen. Combined with gravity-aligned accel, strapdown can work only if yaw rate is the true heading rate. When the pick is wrong, yaw runs on a pitch sensor and the frozen tilt is still wrong in ENU.

Fix: treat IO-VNBD replay as yaw-only plus speed hold. Do not pretend 10 Hz AndroSensor is a 100 Hz IMU.

Test: 10 Hz fixture, dt 0.10 s, count of dt>0.40 s is 0, as on S-Vta2. Hold-last 100 Hz is sensitivity only. Official row stays table rate.

Expected effect: honesty. Sensitivity on S-Vta2:d50 was still worse than persist at 100 Hz hold-last (21.73 versus 1.19). Upsampling will not save IO-VNBD.

**10. Timestamp gaps and process noise. Hypothesis, except the latch is certain.**

Evidence: `maxIntegrateS = 0.40`. Longer dt sets `imu_gap`, `coastVelocity`, `inflateForGap`. Regular IO-VNBD dt is 0.10 s. H3 dropped on S-Vta2 and S-S1. 1465 latched `imu_gap` flags were S-Vta17 and S-Vtb1. `accelNoise = 0.20`, `gyroNoise = 0.015`. Process noise grows P. It does not stop a wrong mean. Over-confident P plus a diverged mean makes `applyGnss` reject good fixes (`gate = 6 * (sigma + sqrt(P))`). That is how S-Vta2 gated itself while GNSS was still in the file.

Fix: when GNSS innovation is large, inflate P and accept a down-weighted fix or switch to coast, but do not keep a tiny P around a 5 km error. Log the gate. Do not tune Q to hide IO-VNBD.

Test: plant 500 m error with P_h = 25 m², next 5 m GNSS must either inflate or flag gated, not silently ignore forever while `filter_ok` stays true.

Expected effect: recovers fused mode after a glitch. Secondary.

**11. Unit bugs. Certain, mostly fixed for the student, still a judge trap.**

GPS SPEED header is km/h. Column is m/s. Retrained `linear.json` uses m/s. Old MAE lives in `train_report_invalid_kmh_labels.json`. Do not cite it. `infer_speed_unit` raises if the column matches neither. `S-Vtb3` excluded.

**12. `MountAlignment.kt` exists and is not on the consume path. Certain.**

ADR 007 says the filter is not edited there. `toVehicleAccel` / `toVehicleGyro` are unused by `DeadReckoningFilter` and `PoseStore`. First-run still is `StationaryCalibrator`, 5 s. Default live rotation is `ANDROID_Y_FORWARD`.

This is why NHC on the phone is a guess, and why IO-VNBD `unspecified` cannot legally run NHC.

### 1.3 What "ESKF beats persist" actually means

Match persist first: last GNSS speed, 10 m course, gravity-vertical gyro, no accel integration in the mask. Python already does this at 0.55. Then add only aids that persist lacks: real-stop ZUPT, NHC after mount, road-heading when `MATCHED`. If those aids lose to persist on a locked slice, revert that aid. Do not add the linear student until it wins as a residual.

---

## 2. Error budget for the 10 percent gate

Official sentence: restrict positional drift to less than 10 percent of distance travelled. The 5 m / 50 m and 100 m / 1 km figures are "for e.g." / "is desired". Plan against 100 m in 60 s at 15 m/s, and also against the official 1 km at 60 km/h (16.67 m/s).

At 15 m/s, 60 s of travel is 900 m, so 10 percent is 90 m. At 16.67 m/s, 60 s is 1 km, 10 percent is 100 m. The two budgets are close. Use 100 m as the working cap.

Decompose endpoint error as along-track plus cross-track, treating errors as persistent bias, which is the conservative case:

```
along  = dv * T
cross  = v * dtheta * T     (dtheta in radians, small-angle)
end    = hypot(along, cross)
```

| If the whole 100 m is this term | Allowed bias | Notes |
|---|---|---|
| Speed only | dv < 100/60 = 1.67 m/s | Same as initial-velocity error held for 60 s |
| Heading only at 15 m/s | dtheta < 100/(15*60) = 0.111 rad = 6.4 deg | Gyro bias 0.0018 rad/s over 60 s is already 6.4 deg |
| Heading only at 16.67 m/s | dtheta < 0.100 rad = 5.7 deg | Official 60 km/h example |
| Split, hypot=100 m | dv < 1.18 m/s and dtheta < 4.5 deg | Estimate, equal along/cross |

What we have:

- IMU-only linear speed MAE, locked_test, 16241 windows: 5.257 m/s. If that were a 60 s bias, along-track 315 m. Useless as an absolute speed observer.
- Persist blackout speed MAE p50: 3.439 m/s. Still 206 m if it were a 60 s bias. Persist endpoint p50 is 234 m on a median path of about 454 m (234.47 / 0.5168). Median windows are shorter than 60 s, and some speed error is not a full-horizon bias. Persist is the right speed prior anyway: last GNSS speed, not a 5 m/s student.
- Persist heading MAE p50: 0.673 rad (38.6 deg). If that were true yaw error at 15 m/s for 60 s, cross-track about 606 m. Persist endpoint p50 is 234 m, so 0.673 rad is inflated by scoring heading against GNSS course that updates every 1 to 9 s. Do not treat 0.673 rad as the gyro's heading error.
- Kotlin heading MAE p50 1.633 rad then 1.227 rad. That one is real enough: the attitude is lost.

Bottleneck, bluntly:

1. On the product filter, attitude/gravity leak is the bottleneck. Speed MAE 26 to 88 m/s. Nothing else matters until that stops.
2. After the filter matches persist, heading is the bottleneck that 10 Hz MEMS can still lose. A few degrees in 60 s is a calibrated gyro-bias problem, not a neural-net problem.
3. Absolute IMU speed at 5.26 m/s MAE is not a path to 100 m. Persist speed, or a residual on persist, is.
4. Map heading on a single road, when the matcher is actually `MATCHED`, removes most of the cross-track term in cities. That is how phone DR ever hits 10 percent in an underpass.

### 2.1 Can IO-VNBD even measure the gate?

AndroSensor tables are 10 Hz. Phone GPS in this dataset is often 1 Hz, and on several trips unique-fix spacing is 9 s. Screening now scores unique-fix / fresh-fix epochs, so D3 stale-endpoint scoring is partly fixed. Path length L_truth is still a polyline of those unique fixes. A 9 s hop at 20 m/s is 180 m of path in one chord. The 5 m / 50 m example is not measurable on 9 s truth. It is barely measurable on 1 Hz truth.

Official example rows that exist in the locked suite:

- d50: 3 intervals passed the truth gate. Persist on S-Vta2:d50 was 2.99 m over 53.13 m. That is under 10 percent, and under 5 m. Filter-only 6.48 m. Linear 30.60 m. The other two d50 intervals ended 21 to 51 m.
- d1000: 10 intervals. Persist endpoint p50 540 m. None under 100 m.

S-S3b:mid and S-S3b:d1000 are timestamp-rewind tails. Persist drift 204 and 19.87. They belong in the table. They should not drive model selection.

`physics_notes.md` splits the 35 as d50 n=3, mid n=22, d1000 n=10. Calibration budget under 60 s: 13 intervals. I did not re-open `metrics_per_interval.csv` here. `.gitignore` ignores `results/*` except emulator shots, so the CSV may exist locally and is not in git.

Estimate, labeled as such: **about 5 to 10 of 35 intervals are clean enough to be a fair 10 percent test.** That set is the three d50 rows plus mids on 1 Hz GNSS trips such as S-Vta2. The rest mix 9 s truth, short parked motion, or the S-S3b rewind. Report two IO-VNBD summaries: all 35 gated, and the 1 Hz / d50 slice. Do not drop the dirty 25 from the official denominator. Put them in a second table with the reason.

IO-VNBD will probably still miss median 0.10 after the filter is honest. Persist is already 0.52. The gate that can be won in 17 days is Pune 100 Hz with score-only truth on underpass and basement drives.

---

## 3. Levers, ranked by expected drift reduction per day

Rank is expected metres saved on a 60 s urban outage, divided by agent-days, with a penalty if it cannot be shown to a judge. Curve-speed and self-cal coasts already lost to persist. They are not re-opened as coasts.

### a. Fix the ESKF so it at least beats persist

Expected effect: Kotlin p50 from 6.7 toward 0.5 on IO-VNBD. Estimate. Will not reach 0.10 there. On Pune, this is the difference between a diverging puck and a persist-quality coast the matcher can then constrain.

Work: 3 to 4 agent-days. One day for reduced-order coast. One day for Replay using `DeadReckoningEngine` plus persist-speed measurement. One day for pre-mask gyro pick and S-S1 autopsy. One day for live `gnssHeld` on age-out.

Risk: medium. v2 already proved a bundle of fixes can help one trip and destroy another. Mitigation: one flag per replay, `metrics_kotlin_eskf_v3.json` as a new row, never overwrite v1/v2.

Dependencies: none. Do this before the student or the matcher feedback.

Files: `DeadReckoningFilter.kt`, `InsConfig`, `Replay.kt`, `export_sensorframe.py`, `eval_kotlin_replay.py`, `DeadReckoningFilterTest.kt`, `ReplaySensorSourceTest.kt`, `ml/tests/test_eval_kotlin_replay.py`, screening summary.

Test: north-coast fixture in section 1. Per-interval CSV must show S-Vta2:d50 and S-S1:mid both not worse than v1. Abort the bundle if S-S1 is worse.

Judge: yes. This is the GNSS+INS Fusion Engine. Show persist versus kotlin_eskf versus kotlin_eskf_v3 on one plot.

### b. Speed model: residual, not absolute

Current linear MAE 5.257 m/s on locked_test. GRU 5.208. Persist blackout speed MAE 3.439. TimesFM 3.0 coast 0.6048 lost to persist 0.5168. Ridge beat TimesFM on speed at 1 s, 2 s, and 5 s. Curve-speed correlation with GNSS speed was -0.02 to -0.17. 10 Hz a_lat std is about 1 m/s². Absolute IMU speed from this dataset is a dead end.

Target: `delta_v` from last accepted GNSS speed, causal 1 s window, trip/session splits, m/s labels. Features to add: windowed accel mean on the vehicle-forward axis after mount, gyro-derived |omega_z|, idle_flag already in the 12. Sequence model (GRU or TCN) only if residual MAE beats persist-delta=0 by a clear margin on locked trips. Kotlin runtime or TFLite, not ONNX, unless export exists. Nothing exports ONNX today.

Blend: `v_hat = v_last_gnss + clip(delta, ±3 m/s)`, R large when bump_flag or idle disagreement.

Expected effect: small on IO-VNBD. Estimate: speed MAE p50 from 3.44 toward 2.5 if the residual is real, which is still 150 m along-track in 60 s. The PS asks for an AI speed filter. The packed student already exists. The upgrade is so the student cannot beat persist by being a worse coast.

Work: 4 agent-days train plus 2 for a Kotlin GRU or TFLite path if, and only if, locked residual wins. If it loses, keep `linear.json`, document it, stop.

Risk: high that it loses. Then you spent four days. Cap at two days for residual ridge. GRU only after ridge residual wins.

Dependencies: mount or at least gravity-vertical features. Correct m/s labels, already done.

Files: `ml/src/driftzero_ml/student/`, `CausalImu.kt`, `LinearMotionStudent.kt`, maybe a small GRU runtime, `models/motion_student_v1/`.

Test: locked residual MAE versus zero-residual. Leakage test: no GNSS names in features. Trip split, not row split.

Judge: yes as "preliminary AI model". Show the window-level table you already have. Do not claim the student is how you meet 10 percent.

### c. Vehicle self-calibration while GNSS is available

This is the word "Intelligent" in the PS that is legitimate. Pre-blackout only.

Already measured as a coast and rejected: persist_selfcal 0.6220, linear_selfcal 0.6649 versus persist 0.5168. Online affine on the 12 IMU features onto GNSS speed overfitted or under-determined. Two S-S3b intervals had n=0. Several scales were negative.

What can still work, and is not that coast:

1. Gyro bias from `StationaryCapture` while still. `MountAlignment.kt` already returns `gyroBias`.
2. Mount yaw from `YawFromMotion` plus GNSS speed-delta sign. Same file. Not wired.
3. Accel scale along vehicle-forward from GNSS dv/dt during straight accel events, frozen at mask start, applied only if |scale-1| is within a documented band, else identity.

Expected effect: heading drift over 60 s. Estimate: consumer gyro bias 0.01 to 0.05 rad/s uncalibrated is 34 to 170 deg in 60 s. A 30 s still plus a few accel events can take bias into the 0.002 rad/s region on a phone, estimate, which is about 7 deg in 60 s, near the heading budget. This is the only IMU-side path to 10 percent without the map.

Work: 3 agent-days to wire `MountProfile` into `PoseStore` and Replay. 1 owner-day of still-plus-straight in Pune.

Risk: medium. Yaw 180 deg flip on brake. ADR 007 already requires a speed-delta sign or `Pending`. Do not apply NHC on `STATIONARY_ONLY`.

Dependencies: filter coast mode so a bad mount does not strapdown-explode.

Files: `MountAlignment.kt`, `PoseStore.kt`, `DeadReckoningFilter` hook as ADR 007 wrote, `MountAlignmentTest.kt`, first-run copy only if the still window must get longer than 5 s.

Test: already in `MountAlignmentTest`. Add replay: profile applied, NHC on, unspecified without profile, NHC off. Remount raises reason and disables NHC.

Judge: yes. Module name is on the PS. Demo: rotate the phone 90 deg, lamp reason remount, then still.

### d. Road-network-constrained DR during blackout

Status 2026-09-03: heading-only feedback is on the live path and on Replay `--road-graph`. Lat/lon snap is still forbidden. IO-VNBD is not scored with a map (no pinned extract). See ADR 008 and `RoadHeadingReplayTest`.

Largest drift cut on real Pune roads. Explicitly permitted. OSM is named. NHC is named. Matcher example on the PS is UKF + Hidden Markov Map Matching.

What exists: `HmmRoadMatcher` is display-only for position. `RoadHeadingFeedback` may apply yaw when MATCHED. Search radius from covariance 15 to 250 m. Gaussian emission, heading term off below 1 m/s, Dijkstra transition, beam 8, posterior, entropy, `MATCHED` / `AMBIGUOUS` / `UNMATCHED`. `displayPose` is the centreline for the overlay. `PoseStore.matchedRoad` returns a polyline only when `MATCHED`. `OsmGraphLoader` stores highway class and oneway. Layer, bridge, tunnel: not stored. Soft feedback into the ESKF: absent. `docs/05` section 5 and ADR 001 / 003 say never replace filter lat/lon with a snap.

Safety rule so this does not become map-derived truth:

1. The hidden GNSS trace is never a map input. The graph is public OSM, loaded before the trip.
2. Feedback is a heading and optional along-track speed prior on the chosen edge, not a position teleport.
3. Gate: status `MATCHED` and posterior at least the existing `matchedMinPosterior`, and second-best below `ambiguousSecondRatio`, and not within a configured distance of a junction node.
4. On `AMBIGUOUS` or `UNMATCHED`, zero map information into the filter. Draw both hypotheses. Grow the halo.
5. Ablate filter-only versus display-only versus soft-heading on Pune and on IO-VNBD. IO-VNBD has no team OSM pack for those UK/Nigeria/France roads in the APK. Do not score IO-VNBD with a map unless you build a pack from a pinned extract and say so.

Expected effect: urban underpass. Estimate: cross-track from tens of metres to a lane-ish error on a single carriageway, still not a lane claim. Junctions remain `AMBIGUOUS`. This is how persist 0.52 becomes something near 0.10 on Pune. It will not invent GNSS. A wrong edge is a parallel-road failure. Show that failure in the deck.

Work: 4 agent-days. One day tunnel/layer tags. One day soft heading update with the gate above. One day fixtures: parallel, flyover, tunnel, U-turn. One day Pune pack via `tools/maps/build_pune_pack.sh`.

Risk: high if someone writes lat/lon back. Medium if heading-only and gated. Dependency: filter must not be 2 km off or the search radius cannot see the road.

Files: `HmmRoadMatcher.kt`, `OsmGraphLoader.kt`, `RoadGraph.kt`, `DeadReckoningFilter` or a small `applyRoadHeading` next to `applyNhc`, `PoseStore.kt`, `HmmRoadMatcherTest.kt`, `docs/05` one paragraph when feedback exists.

Test: two parallel roads, posterior split, no filter heading pull. One road, posterior 0.9, heading residual 30 deg, after updates heading within 5 deg, position not equal to centreline. Junction fixture stays `AMBIGUOUS`.

Judge: yes. This is Advanced Map-Matching and Kinematic Constraints. Show AMBIGUOUS on a flyover.

### e. Pune 100 Hz phone drives with score-only truth

Emulator IMU is fake. IO-VNBD is 10 Hz with 1 to 9 s truth. The 10 percent gate is realistic here, not there.

Protocol:

1. Owner, 5 to 8 Sep. Windshield or dash mount. First-run still, then 30 s of straight driving before any Hold GNSS.
2. Eight drives, not more: underpass, basement, flyover with service road, stop-go, remount, plus three repeats of the underpass at different times. 100 Hz raw IMU, 10 Hz `NavigationState`. A second phone or the same phone's GNSS before and after the structure is score-only truth. Airplane mode after a Ready pack, or tiles cached then airplane, stated in the log.
3. Split by complete drive. Do not train on the scoring underpass. Train self-cal only on pre-blackout of that drive, which is allowed.
4. Artificial blackout: mask GNSS in Replay the same as IO-VNBD. Natural blackout: the structure itself. Report both, labeled.
5. Bundle: `results/pune_v1/` with the same filenames as `docs/06` section 11. No invented metres.

Expected effect: this is the table that can show 0.10. Estimate, not a promise: a 200 to 400 m underpass at 30 km/h with map heading and calibrated gyro has a plausible shot at <10 percent. A 1 km tunnel at 60 km/h on a phone without a map is still unlikely.

Work: 4 owner-days recording. 2 agent-days importer plus scoring. Cannot be parallelized with "owner is in the car".

Risk: rain, permission, mount slip, no Ready pack so the video still has network tiles. Record SensorFrame JSONL either way. Map pack can be built after.

Files: trip logger already in the app, `ReplaySensorSource`, new `results/pune_v1/`, `docs/06` pointer.

Test: leakage assertion on the masked JSONL. Repeat replay hash.

Judge: yes. This is the Indian corridor. Emulator screenshots stay in `results/emulator/` and are labeled fake IMU.

### f. TimesFM 2.5 as teacher

TimesFM is not in the PS. 3.0 weights are non-commercial/non-production. Desktop 3.0 run rejected: timesfm_coast 0.6048 versus persist 0.5168. Distillation not attempted. 2.5 is Apache-2.0.

2.5 is an older, smaller checkpoint of the same idea: forecast a speed profile from IMU-like channels. Given 3.0 lost to persist and lost to ridge on speed, 2.5 is unlikely to beat a GRU residual. Do not spend gate time on it.

Where it fits: one deck sentence. "Desktop TimesFM 3.0 zero-shot was run, lost to persist, 3.0 cannot ship, 2.5 remains the Apache fallback, neither is on the phone." Optional 2.5 rerun only if P3 filter work is done and an agent is idle. Cap 1 agent-day. Keep-or-reject, same protocol as 3.0.

Judge: yes as research honesty. No as a gate lever.

### g. Evaluator changes that are fair

Already done: unique-fix scoring, 10 m heading seed, m/s labels, truth gate, session_group_id, S-Vtb3 excluded, S-S4 211 km jump not in the 35, mask 1 ns, Replay `setGnssHeld`.

Still unfair, fix without cheating:

1. Publish a 1 Hz GNSS slice and a 9 s slice. Same 35 remain in the main table.
2. Write locked interval IDs into the data manifest. `screening.py` can fill `blackout_interval_ids: []`. Confirm it is filled after the next run. `configs/blackout_protocol.yaml` is documentation only. Either load it or stop pretending.
3. Heading MAE against 9 s course is a noisy secondary. Prefer endpoint and along/cross. Keep heading MAE, do not select models on it.
4. `select_heading_gyro` must use pre-mask hops only. That is a leakage fix, not a score hack.
5. Do not upsample IMU for the official IO-VNBD row.

Work: 1 agent-day. Low risk. Judge: yes. The "evaluator that caught itself" story is already in `docs/10`. Keep it true.

### Extra lever: ablation harness

Not in the owner's list. Without it, v3 repeats v2. A JVM flag or `--config` already overrides InsConfig. Add `--coast-mode=strapdown|yaw_speed_hold` and write per-interval CSV every time. Half an agent-day. Do this before v3.

### Ranked list

| Rank | Lever | Effect on 60 s urban DR | Agent-days | Judge |
|---|---|---|---:|---|
| 1 | Reduced-order ESKF coast, beat persist | IO-VNBD 6.7 toward 0.5. Estimate | 3-4 | Yes |
| 2 | Pune 100 Hz + hidden truth | Only realistic 0.10 table | 2 + 4 owner | Yes |
| 3 | OSM heading when MATCHED | Biggest Pune cut | 4 | Yes |
| 4 | Wire MountAlignment + gyro bias | Heading budget | 3 + 1 owner | Yes |
| 5 | Fair evaluator slices + pre-mask gyro pick | Honest p50, may move S-S1 | 1 | Yes |
| 6 | Residual speed student | Small, PS coverage | 2 then stop if lose | Yes |
| 7 | TimesFM 2.5 | Story | 0-1 | Yes, as reject |
| skip | Curve-speed / self-cal coasts | Already lost | 0 | Do not ship |

---

## 4. Calendar, 3 Sep to 20 Sep 2026

Owner-only: anything in a car, a fresh-install phone, a video take, the SPOC upload. Agent-parallel: filter, matcher, packs, tests, slides, TimesFM note.

Freeze: **16 Sep**. After that, bugfixes and copy only. Two fresh-install rehearsals **18 Sep** and **19 Sep**. Upload **20 Sep**.

### 3 Sep, Thursday. Today

Agents: land this document. Open v3 as yaw-speed-hold behind a flag. Do not rerun the 35 until the north-coast test passes.

Owner: confirm Pune underpass and basement sites, mount, second phone for truth if any. Charge phones. Do not drive yet.

### 4 Sep, Friday. Filter week starts

Agents, parallel:

- Stream A: reduced-order coast + still-ZUPT on age-out + Replay through Engine with student off. Tests in `DeadReckoningFilterTest`.
- Stream B: pre-mask `select_heading_gyro`. Exporter test.
- Stream C: 1 Hz versus 9 s slice script, no new numbers until v3 runs.

Owner: still-calibration practice at home. 5 s may be short. Time a 15 s still and a 30 s straight.

### 5 Sep, Saturday. Pune day 1

Owner: drive 1 underpass, drive 2 stop-go. 100 Hz. No Hold GNSS until after still plus straight. Log mount, vehicle, weather.

Agents: keep Stream A. Do not block on drives.

### 6 Sep, Sunday. Pune day 2

Owner: basement, flyover with service road.

Agents: if north-coast test is green, run kotlin_eskf_v3 on the 35. Write a new summary section. Keep v1 and v2 rows.

### 7 Sep, Monday. Pune day 3

Owner: remount drive, underpass repeat.

Agents: start OSM layer/tunnel tags and parallel-road fixture. Start Pune bbox pack build. Do not wait for all eight drives.

### 8 Sep, Tuesday. Pune day 4, last recording day

Owner: two remaining repeats. Copy JSONL off the phone that night.

Agents: Pune importer skeleton. MountAlignment hook on a branch, not mixed into v3 until v3 p50 is not worse than persist.

### 9 Sep, Wednesday

Decision point. If kotlin_eskf_v3 drift p50 is still above 2.0, stop map feedback. Debug S-S1 and gravity leak only. If v3 is at or below persist, enable persist-speed measurement as the default blackout aid.

Owner: rest. Label drives. Pick one underpass as locked test. The rest are train/calibration.

### 10 Sep, Thursday

Agents: wire MountProfile into PoseStore. Residual ridge, two-day cap starts.

Owner: one more still-plus-straight if Monday remount was messy.

### 11 Sep, Friday

Agents: soft road-heading behind MATCHED gate. Fixtures. Pune pack sideload path.

Owner: sideload pack on the recording phone. Confirm airplane-mode tiles if the pack is Ready. If not Ready, record that fact.

### 12 Sep, Saturday

Agents: score Pune locked underpass, filter-only versus map-heading. Halo PICP script on that drive.

Owner: watch one replay on the phone. Judge overlay Hold GNSS.

### 13 Sep, Sunday

Buffer. If Pune 0.10 is not in sight, cut residual GRU, cut TimesFM 2.5, keep map heading and calibration.

### 14 Sep, Monday

Agents: FOG/200 Hz synthetic replay as a labeled row. Magnetometer: register `TYPE_MAGNETIC_FIELD`, gate it off in the filter, show a Sensors row "mag captured, unused". That is the smallest honest SIH-16 close.

Owner: draft 6 slides from `docs/10` using real Pune numbers if they exist, else IO-VNBD persist and the new Kotlin row.

### 15 Sep, Tuesday

Agents: failure taxonomy, worst three Pune and worst three IO-VNBD. PICP plot if the script ran.

Owner: voice-over script. No invented metres.

### 16 Sep, Wednesday. Freeze

Tag a freeze commit. After this, copy, crash, and pack bugs only. No filter retune.

### 17 Sep, Thursday

Slides PDF. Architecture figure from `docs/figures/architecture.md`. License footnote for TimesFM 3.0. No-cable statement.

### 18 Sep, Friday. Rehearsal 1

Owner: uninstall, install, first-run still, underpass replay, Hold GNSS, airplane if pack Ready. Time it. Agents: fix only what broke.

### 19 Sep, Saturday. Rehearsal 2

Same as Friday on a cold phone. Record the video for communication. Official screening artefact is the 6-slide PDF. A mandatory video is not independently verified.

### 20 Sep, Sunday. Deadline

Owner: SPOC path, PDF upload. Do not rebuild the filter.

Parallel map:

```
        Sep 3-6          Sep 7-11         Sep 12-16        Sep 17-20
Owner   sites, drives    last drives      sideload, watch  slides, 2 rehearsals, upload
A       ESKF v3          mount hook       freeze bugfixes  rehearsal fixes
B       gyro pick        OSM + pack       PICP, FOG row    deck numbers
C       eval slices      residual ridge   mag capture      copy freeze
```

If owner drives slip past 8 Sep, slip map feedback, not the filter freeze. A persist-quality ESKF plus three Pune drives beats a clever matcher on a diverging filter.

---

## 5. Requirement completeness

Source: `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md` and `docs/refs/SIH26168_EVIDENCE.md` section A. Status: done, partial, missing. Smallest honest close.

| ID | PS item | Status | Smallest honest close |
|---|---|---|---|
| SIH-01 | AI-ML IDR, phone | Partial | v3 coast + packed student + Pune plot. Do not claim 0.10 on IO-VNBD |
| SIH-02 | Tunnel, parking, forest, canyon, jamming | Partial | Scenario table on Pune underpass/basement/flyover. Jamming: innovation gate + `GNSS_DEGRADED`, no certified jammer claim |
| SIH-03 | Vibration, braking, potholes | Partial | bump_flag and bump-gated R exist. Add a tagged Pune breaker in the taxonomy |
| SIH-04 | No OBD, no vehicle computer | Done | APK has no cable path. Say it on slide 2 |
| SIH-05 | Lightweight engine + app | Partial | Core + APK exist. One mid-range p95 tick / battery page. 1 hour on device |
| SIH-06 | Standalone, inertial when GNSS drops | Partial | Five modes exist. Age-out must set the same ZUPT skip as Hold GNSS |
| SIH-07 | "maintaining" lane-level | Partial as aim | Keep the aim. Measure road-level plus AMBIGUOUS. Do not claim lane |
| SIH-08 | Speed from accel/gyro only | Partial | linear.json is the preliminary model. Residual if it wins. GRU not packed |
| SIH-09 | Idle, pothole, remount | Partial | Idle and bump exist. Wire MisalignmentMonitor to a remount reason |
| SIH-10 | OSM overlay + NHC | Partial | HMM display-only, NHC gated on unspecified. Add MATCHED heading. NHC only after mount |
| SIH-11 | GNSS+INS Fusion Engine | Partial | ESKF exists and loses to persist. v3 must beat persist or the module is a liability |
| SIH-12 | External IMU, FOG ~200 Hz | Partial | Synthetic 200 Hz test exists. Add a labeled TUM-VI or synthetic JSONL row in results |
| SIH-13 | IO-VNBD train/test | Partial | Screening v1 exists. Gate 0.10 not met. Keep both Kotlin rows |
| SIH-14 | Preliminary AI + position plot | Partial | Plots S-Vta2, S-S1, S-S3b exist. Add v3 overlay. Truth labeled score only |
| SIH-15 | Train desktop, infer phone | Partial | True for linear.json. TimesFM desktop reject. No ONNX |
| SIH-16 | Accel, gyro, mag/compass, GNSS | Partial | Mag is contract-only. `PhoneImuSource` does not register `TYPE_MAGNETIC_FIELD`. `consume` no-ops `MAGNETOMETER`. Register, log, keep gated off until a hard-iron plan exists |
| SIH-17 | In-vehicle alignment | Partial | 5 s still. MountAlignment not wired. Wire it |
| SIH-18 | AI Speed & Vibration Filter | Partial | Linear is allowed wording ("statistical signal-processing"). Pack GRU only if it wins |
| SIH-19 | Map-matching & kinematic constraints | Partial | HMM + NHC. Soft heading + layer tags |
| SIH-20 | Fusion engine, learned noise | Partial | Geometric GNSS gate, Δp χ². Add speed χ² |
| SIH-21 | Seamless GNSS deficit handler | Partial | Five modes, reacquire 3. Display blend is puck interpolation, not a Kalman blend. Good enough if recovery is not a teleport |
| SIH-22 | Real-time nav interface | Partial | Lamp, halo, sheet, Judge hold. JudgeReplayScreen for IO-VNBD truth overlay is still missing |
| SIH-23 | Drift < 10 percent | Missing on locked 35 | Meet on Pune if the drives allow. Report IO-VNBD as not met |
| SIH-24 | 5 m/50 m and 100 m/1 km examples | Partial | Persist met 5 m on one d50. No 1 km under 100 m. Keep as scenario checks |
| SIH-25 | 10 Hz on phone | Partial | `OUTPUT_HZ = 10`. No p95-gap artefact. Log it on rehearsal 1 |
| SIH-26 | ~200 Hz FOG engine | Partial | Same as SIH-12. Labeled file replay, not a live FOG unit |

Magnetometer: named input. Captured and unused is honest. Unused and not captured is a gap a judge can poke. Register the sensor.

---

## 6. Legitimate extras, ranked by value per effort

1. **Leak-free evaluator with a published defect log.** Already half-built. Finish the 1 Hz slice and pre-mask gyro pick. High value, 1 day. ISRO judges have seen too many mystery 2 percent plots.

2. **Offline Pune pack.** Airplane-mode puck. High value, 2 to 3 days plus Planetiler. Without it the demo still hits the network for tiles.

3. **Calibrated halo PICP.** Every team draws a circle. Almost none measure coverage. 1 day after Pune scoring. If PICP is 0.4, say so and recalibrate on validation drives only.

4. **NavIC visibility, not integrity.** Already a count from `CONSTELLATION_IRNSS`. One sentence on the slide. ISRO FAQ: NavIC does not provide integrity. GAGAN is the SBAS path. Do not upgrade this.

5. **FOG adapter at 200 Hz, synthetic or TUM-VI, labeled.** 0.5 day. Closes SIH-12/26 without a hardware lie.

6. **TimesFM desktop teacher with license note.** Already run and rejected. Keep on slide 6 as a citation plus reject. 3.0 cannot ship. Do not distill 3.0. 2.5 only if idle.

7. **JudgeReplayScreen** that loads an IO-VNBD trip and reveals score-only truth after the estimate. 2 days. Strong on stage, not a gate mover.

Skip: TimesFM on the phone, OBD, snapping the puck, NavIC integrity, KITTI 1.10 percent as a phone number, emulator accuracy claims.

---

## 7. If the gate is still not met on IO-VNBD on 20 Sep

Say this, and put both tables on slide 4:

IO-VNBD is the mandatory screening set. AndroSensor at 10 Hz, phone GNSS often 1 to 9 s, UK/Nigeria/France, one main driver. On the locked 35 intervals, median drift 0.10 was not met. Persist is the held-out coast at 0.5168. The product ESKF v1 was 6.6863 on 33 intervals. v2 was 7.5650 on 35. v3, if it exists, is the reduced-order coast, reported in the same file. No row is deleted.

Pune held-out drives, if they exist, are the phone-rate test the PS describes: underpass, basement, flyover, stop-go, remount, 100 Hz IMU, hidden GNSS score-only. If that median is under 0.10, say the gate is met on Pune and not on IO-VNBD, and why the two datasets disagree: sample rate, truth rate, mount calibration, OSM constraints on Indian roads.

If Pune is also above 0.10, say that too. Show persist, filter, and map-heading. Show the halo growing. Show AMBIGUOUS. Show the failure traces. A team that reports 0.52 with a method ISRO can replay beats a team that reports 0.08 with a snap and a leak.

Do not write 10 percent into the script until `results/` has that number on a named suite.

---

## Stop doing

- Silent filter bundles. v2 is the exhibit.
- Shipping curve-speed or self-cal as a coast.
- Citing `results/io_vnbd_blackout_eval.md` or `train_report_invalid_kmh_labels.json`.
- TimesFM on the phone or as the title of slide 2.
- Map snap of lat/lon.
- Emulator metres as accuracy.
- Claiming lane-level from MEMS.
- Inventing a better Kotlin number.

## First command after freeze of this plan

North-coast unit test, then `kotlin_eskf_v3` with `--coast-mode=yaw_speed_hold`, student off, pre-mask gyro pick, same 35 IDs, new metrics file. Compare to persist. Then stop and read S-S1:mid before any other switch.
