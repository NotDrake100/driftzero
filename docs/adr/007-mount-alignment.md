# ADR 007: Phone-to-vehicle mount alignment

- Status: accepted
- Date: 2026-09-02
- Product path: production (navigation-core). Not research-only.
- Requirements: PRD FR-02, FR-03 (potholes are not remounts), SIH-09, SIH-17 in `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`

## Context

A phone may sit in portrait, landscape, or a tilted mount, and it can be moved during a trip. Vehicle-frame inertial navigation needs a phone-to-vehicle rotation, a still-phase gyro bias, and a way to notice that the mount changed. Magnetometer heading is not trusted by default. `DeadReckoningFilter.kt` already exists in this tree and is not edited here. First-run still UI stays on `StationaryCalibrator` and `MountMonitor`. Those types do not emit a phone-to-vehicle `Mat3`.

## Decision

Keep mount alignment as a pure Kotlin, causal helper in `packages/navigation-core` (`in.driftzero.core`) with no Android types. Reuse `Vec3`, `Mat3`, `Mat3.ANDROID_Y_FORWARD`, `bodyToVehicle(VectorFrame)`, and `bodyForward(VectorFrame)` from `VecMath.kt`. Persist profiles with the existing `ContractJson` helper. Do not add a second vector type or a second JSON parser.

1. **Frames.** Phone is `VectorFrame.ANDROID_DEVICE` (x right, y toward the top of the screen, z out of the screen). Vehicle is x forward, y left, z up. Output is `rotationPhoneToVehicle: Mat3` plus `ProfileQuality`.
2. **Stationary phase.** `StationaryCapture` consumes accelerometer (m/s^2) and gyroscope (rad/s) samples with integer-nanosecond timestamps on the same domain as `SensorFrame.timestamp`. It returns `Ok`, `Insufficient(reason)`, or `Moving(reason)`. Defaults: at least 2 s and 50 samples; RMS component sample std below 0.40 m/s^2 and 0.06 rad/s; mean specific-force magnitude within 2.0 m/s^2 of `Wgs84.STANDARD_G` (9.80665 m/s^2). No zero-fill. `Vec3` construction already rejects non-finite components.
3. **Roll and pitch.** Gravity in the phone frame is mapped onto vehicle +z. The remaining yaw uses the `Mat3.ANDROID_Y_FORWARD` completion: `bodyForward(ANDROID_DEVICE)` (phone +Y) projected onto the horizontal plane becomes vehicle +X. Gravity on phone +Z therefore yields the `ANDROID_Y_FORWARD` family (flat dash, screen up, top of the phone forward).
4. **Yaw from motion.** During straight driving, horizontal specific force during accel or brake is the forward axis up to a 180-degree flip. `YawFromMotion` accepts events only when gravity-aligned gyro rate is below 0.10 rad/s and horizontal accel is at least 0.35 m/s^2. The flip is resolved only by a GNSS speed-delta sign (magnitude at least 0.25 m/s) or by majority among signed events. Without a speed-delta sign the result stays `Pending`.
5. **Profile.** `MountProfile` stores the rotation, still gravity, gyro bias, yaw confidence, `createdNs`, and `STATIONARY_ONLY` / `ALIGNED` / `ALIGNED_HIGH` (`ALIGNED_HIGH` when confidence is at least 0.80). Persistence is a `ContractJson` object with schema `mount_profile_1.0.0`.
6. **Misalignment.** `MisalignmentMonitor` compares low-passed phone-frame accel to the profile gravity. Default trigger: direction change above about 8 degrees (`thresholdRad`) for 3 s (`holdNs`) while `| |a| - |g_profile| |` is at most 1.5 m/s^2. Recovery hysteresis is 5 degrees so a 0.3 s spike or pothole does not emit `Remount`. `clear()` when a new profile is set.

## Filter hook

`DeadReckoningFilter` should not import Android and should not re-estimate this rotation internally. After this module lands, the filter needs:

- `MountProfile.toVehicleAccel(accelPhoneMps2)` and `toVehicleGyro(gyroPhoneRadps)` on every IMU epoch (bias removed in the phone frame, then rotated).
- `MisalignmentMonitor.update(timestampNs, lowPassedAccelPhoneMps2)` on the same clock.
- On `MisalignmentUpdate.Remount`, call `NavigationEngine.reset(ResetReason.REMOUNT)` (`EngineApi.kt`), drop the profile, and run stationary plus yaw capture again.

## Consequences

- Alignment works without a learned checkpoint and without GNSS labels inside a blackout (speed deltas are unused when GNSS is masked).
- Ambiguous yaw stays visible as `STATIONARY_ONLY` / `Pending` instead of a silent 180-degree snap.
- Remount is a held, hysteresis-gated event, not a single bump.
- The filter can consume this API later without this change touching `DeadReckoningFilter.kt`.
- First-run 5 s still (`StationaryCalibrator`) is unchanged. It is not a substitute for the rotation profile.
