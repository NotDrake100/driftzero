# Phone capture audit

Date: 2026-09-06. Production-path logger audit. Not a field accuracy result.
No owner drive exists in this tree. Model wiring stays blocked until D selects
a candidate.

## Five statuses

| Status | Value |
|---|---|
| Code merged | Logger audit merged in PR 18. Model wiring is not. |
| Experiment completed | No field drive scored. |
| Median target passed | No. Unchanged locked coast remains 48.83%. |
| All-interval target passed | No. |
| Field placements tested | No. Resting, pickup, and handheld remain untested. |

## What the app already records

When Settings, Record trips is on, the passenger zip contains
`sensors.jsonl`, `states.jsonl`, and `manifest.json`.

| Stream | Status | Units / clock |
|---|---|---|
| Accelerometer, gyroscope | Logged, phone frame | m/s², rad/s, `android_elapsed_realtime` ns |
| Gravity, linear accel, uncal gyro | Logged if the device exposes them | Same clock. ESKF does not consume them |
| Magnetometer | Logged, unused | uT, flag `unused` |
| GNSS fix | 1 Hz | lat/lon deg, HACC m, optional speed m/s and bearing rad |
| NavigationState | 10 Hz | includes `phone_handling` and `mount_remount` flags |
| Requested IMU delay | `SENSOR_DELAY_FASTEST` | Not a 100 Hz claim |
| Measured IMU Hz | Manifest `measured_*_hz` | Median 1/dt from timestamps |

## This audit closes

- Accel and gyro `accuracy_code` now copy `SensorEvent.accuracy` instead of
  hardcoding 2.
- Manifest writes `accel_min_dt_ns`, `accel_max_dt_ns`, gyro counterparts, and
  `dropped_sensor_frames` / `dropped_state_frames` when the ring overflows.
- GNSS trip rows now copy `is_mock` and `vertical_accuracy_m` when Android
  reports them.

Tests: `TripRecorderTest.imuAccuracyCodeSurvivesTripJsonl`,
`manifestRecordsGapsAndRingDrops`, `gnssMockAndVerticalAccuracySurviveTripJsonl`,
and `ContractMapsTest.gnssFixRoundTripsSpeedAndBearing`.

## Still missing from the export

| Gap | Why it matters |
|---|---|
| `gnss_status` / C/N0 rows | `NavicMonitor` sees them. Trip JSONL does not. |
| Placement label | Seat, cup, vent, handheld is not in the zip. Passenger must note it. |
| Device model / SDK / app version | Not in manifest. |
| Mount profile snapshot | Lives in SharedPreferences only. |

A pass at one resting placement does not certify pickup recovery or continuous
handheld motion.

## Owner capture procedure

Use the existing app. Cloud agents cannot collect drives.

1. Install the APK. Complete first-run still capture, then a short straight
   drive for yaw alignment. Precise location is required.
2. Map, overflow, Settings. Turn on Record trips. Confirm REC on the bottom
   strip.
3. Keep one placement for the whole trip. Resting passenger seat, console or
   cup holder is slice 1. Pickup and repositioning is a separate trip. Continuous
   handheld is a later trip.
4. The passenger writes a sidecar log: start placement, every pickup, device
   model, Android version, intended Hold intervals.
5. For a scored blackout, long-press the mode lamp when speed is below 8 m/s.
   GNSS keeps logging with `gnss_held`. Long-press again to release.
6. Settings, turn Record trips off. Map, overflow, Trips, Export. Share the zip.
7. After export, read `manifest.json`. Cite `measured_accel_hz` and
   `accel_max_dt_ns`. Do not cite 100 Hz from the request string.

Pilot target from the campaign plan: about 12 outdoor drives, mixed resting
placements, stops, turns, and longer straights. Keep some drives unopened.
Sparse GNSS cannot certify a 5 m error.

## Reproduction of logger tests

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :navigation-core:test --no-daemon
./gradlew :android-app:testDebugUnitTest --no-daemon
```

This coordinator workspace ran the navigation-core suite after installing
OpenJDK 17. `:android-app:testDebugUnitTest` did not run here: `ANDROID_HOME`
is unset and no Android SDK is installed. CI still runs that job.

No field zip is attached. The emulator trip under `results/emulator/` is not
mountless field evidence.
