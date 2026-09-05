# Android application specification

The `apps/android/app` module ships the travel map chrome plus a MapLibre Native street map (`AndroidView` `MapView`, OpenFreeMap liberty, bright fallback). Live pose comes from `PoseStore` / `DeadReckoningFilter` (strapdown INS plus ESKF). `PhoneImuSource` copies accel, gyro, mag, and when present gravity, linear acceleration, and uncalibrated gyro. Only accel and gyro enter the ESKF. The 10 Hz tick runs `ZuptAccelMotionModel` into `ingestMotionPseudo`. Assemble packs `motion_student_v1/linear.json` into assets when that file exists. `learned_imu_v1/linear_dp.json` is not packed unless you pass `-Pdriftzero.packLearnedImu=true`; its train report is worse than freeze. `gru.json` is never packed and there is no Kotlin GRU runtime. No TimesFM and no ONNX Runtime in the APK. The hosted style is a stand-in until an installed PMTiles area package owns rendering. After a Ready pack is sideloaded, MapLibre loads local `tiles.pmtiles` via `pmtiles://file://` and local glyphs. Routing still uses public OSRM. No Google Maps SDK.

## Proposed identity

- Application name: DriftZero
- Package: `in.driftzero.app` (ships)
- Minimum SDK: choose after a device survey; API 29 is a useful initial baseline because Android exposes the IRNSS constellation constant from that level.
- Target SDK: current stable required by the chosen toolchain at implementation time.

## Recommended stack

- Kotlin and coroutines/Flow.
- Jetpack Compose for application UI, with the mapping SDK's supported surface integration.
- Foreground service for an active navigation session.
- MapLibre Native Android for the local vector map.
- JSON speed weights (`linear.json`) on device today. ONNX Runtime Mobile is planned. Nothing exports ONNX. Gradle has no ONNX runtime.
- Room or a chunked binary/session format for metadata and replay index. Avoid writing one database row for every high-rate sensor sample if profiling shows contention.
- Android Keystore-backed protection for sensitive local exports.

Pin versions after running a dependency and SDK compatibility check. Software versions listed in research sources are snapshots, not instructions to auto-upgrade without tests.

## Module target

The live tree is one `apps/android/app` module plus `packages/navigation-core`. The split below is a proposed later layout, not the current Gradle graph. Do not add empty feature shells.

```text
apps/android/app/      activity, map, pose, trips, settings (what ships today)
# proposed later, not present:
  feature-map/
  feature-calibrate/
  feature-replay/
  sensor-android/
  runtime-onnx/        not started. No ONNX on the phone.
  area-packages/
  trip-log/
```

The platform-neutral filter and map-matching logic belongs under `packages/`, not in an Activity or ViewModel.

## Runtime flow

1. User may sideload an area pack. Until Ready, tiles, search, and routing use the network.
2. App checks sensors. First-run still mount is a 5 s window, not a full calibration profile.
3. User secures the phone and completes the still step when stopped.
4. PoseStore starts IMU and GNSS capture. No separate foreground-service session yet.
5. Android adapter copies frames with `SensorEvent.timestamp`.
6. Navigation core emits immutable 10 Hz state.
7. UI displays mode and confidence. Trip recording is opt-in in Settings.
8. Signed integrity manifests are planned. Not implemented.

## Permission design

- Ask for precise foreground location in context when navigation starts.
- Avoid background location until a real, disclosed feature requires it.
- Declare `HIGH_SAMPLING_RATE_SENSORS` so `SENSOR_DELAY_FASTEST` may exceed 200 Hz on Android 12+. Logged IMU Hz is still measured from timestamp deltas.
- Keep the core useful at 50 to 100 Hz raw sensor sampling and 10 Hz output.
- Explain why GNSS/status data is used and retain it locally by default.

## UI hierarchy

```text
TravelMapScreen
  StreetMap
    OwnVehiclePuck
    HeadingCone
    RoutePolyline
    DestinationPoint
  WhereToSearch
  ModeLamp         (GNSS / Assisted / Dead reckoning / Reacquiring / Low confidence)
  LocateControl    (long-press queues the visible bbox for an area pack)
  BottomInstrument (collapsed status, route rows, expanded sheet)
  JudgeOverlay     (trails, mode strip, Hold GNSS)
```

Idle: map, search, lamp, locate, collapsed sheet. No empty speed, DIST, or ETA slab.
Navigating: the same, plus a route line and destination, distance, and ETA in the sheet.

Opt-in trip recording writes `sensors.jsonl` and `states.jsonl` under `files/trips/<id>/`. IMU timestamps are integer nanoseconds. Accel is m/s^2. Gyro is rad/s. GNSS includes speed_mps and bearing_rad. Hold GNSS rows carry `gnss_held` and stay score-only. Manifest `measured_*_hz` is median 1/dt, not the preferred 100 Hz request. Replay uses `ReplaySensorSource` from that sensor file. Export is a local zip after a consent dialog. The APK packs `motion_student_v1/linear.json` only.

Search uses Photon, then Nominatim, biased to fused pose/GPS or the camera. Routing uses public OSRM. Tiles are hosted OpenFreeMap until an installed area pack owns rendering. Long-press Locate queues the visible bbox for an offline pack.

## Android acceptance tests

- Sensor timestamps are monotonic and callback arrival time is not substituted.
- Axis transform fixtures pass for portrait and landscape default display rotation.
- Permission denial has a clear fallback and no crash loop.
- Airplane-mode area install replay has zero network calls (planned. No Ready pack is bundled).
- Killing and restoring the Activity does not reset the active navigation worker.
- Model checksum/schema mismatch falls back safely.
- PMTiles/graph corruption does not activate the package.
- Two-hour replay has no unbounded queue or memory growth (planned. No soak report).
- UI state transitions match logged `NavigationState`.
- Reference phone 10 Hz p95 gap (planned. `TickIntervals` can compute it. No phone measurement exists).

## NavIC integration

`GnssLocationSource` registers `GnssStatus.Callback` and copies each satellite into `NavicMonitor`. When Android reports `CONSTELLATION_IRNSS`, logcat tag `DriftZeroNavIC` prints `IRNSS visible=… used=… GPS used=… Galileo used=…` on change. NavIC counts belong in the status sheet (`value_navic_counts`). `NavicMonitor.chipLabel` exists as a string builder. Do not compose it as an integrity lamp. Counts do not enter the filter. Seeing IRNSS is not a trustworthy fix, anti-jam, or safety-of-life. Missing IRNSS does not mean NavIC failed. Details: `docs/refs/NAVIC.md`.

Tester:

```text
adb logcat -s DriftZeroNavIC
```

Outdoors, Android precise-location permission on, India coverage, NavIC-capable chipset. Cross-check constellation counts in a GNSS status app. Hold GNSS on the mode lamp to clear NavIC during a simulated outage.

