# Android application specification

The `apps/android/app` module ships the travel map chrome plus a MapLibre Native street map (`AndroidView` `MapView`, OpenFreeMap liberty, bright fallback). Live pose comes from `PoseStore` / `DeadReckoningFilter` (strapdown INS plus ESKF). `PhoneImuSource` copies accel/gyro only. The 10 Hz tick runs `ZuptAccelMotionModel` into `ingestMotionPseudo`. Assemble packs `motion_student_v1/linear.json` into assets when that file exists. `learned_imu_v1/linear_dp.json` is not packed unless you pass `-Pdriftzero.packLearnedImu=true`; its train report is worse than freeze. `gru.json` is never packed and there is no Kotlin GRU runtime. No TimesFM and no ONNX Runtime in the APK. The hosted style is a stand-in until an installed PMTiles area package owns rendering. No Google Maps SDK.

## Proposed identity

- Application name: DriftZero
- Package placeholder: `in.driftzero.app`
- Minimum SDK: choose after a device survey; API 29 is a useful initial baseline because Android exposes the IRNSS constellation constant from that level.
- Target SDK: current stable required by the chosen toolchain at implementation time.

## Recommended stack

- Kotlin and coroutines/Flow.
- Jetpack Compose for application UI, with the mapping SDK's supported surface integration.
- Foreground service for an active navigation session.
- MapLibre Native Android for the local vector map.
- ONNX Runtime Mobile for the compact PyTorch-derived student, CPU/XNNPACK first.
- Room or a chunked binary/session format for metadata and replay index. Avoid writing one database row for every high-rate sensor sample if profiling shows contention.
- Android Keystore-backed protection for sensitive local exports.

Pin versions after running a dependency and SDK compatibility check. Software versions listed in research sources are snapshots, not instructions to auto-upgrade without tests.

## Module target

```text
apps/android/
  app/                 activity, navigation, permissions, DI
  feature-map/         MapLibre surface and NavigationState rendering
  feature-calibrate/   guided mount calibration flow
  feature-replay/      judge mode, blackout controls, result viewer
  sensor-android/      SensorManager, LocationManager, GNSS adapters
  runtime-onnx/        student model loading and inference
  area-packages/       PMTiles/graph package install and validation
  trip-log/            local session capture and export
```

The platform-neutral filter and map-matching logic belongs under `packages/`, not in an Activity or ViewModel.

## Runtime flow

1. User installs or selects an offline area.
2. App checks sensors and phone capability tier.
3. User secures the phone and completes calibration.
4. Foreground service starts sensor/GNSS capture.
5. Android adapter emits canonical frames with monotonic timestamps.
6. Navigation core emits immutable 10 Hz state.
7. UI displays mode and confidence; logger records identical states.
8. Session stop finalizes an integrity manifest.

## Permission design

- Ask for precise foreground location in context when navigation starts.
- Avoid background location until a real, disclosed feature requires it.
- Declare any high sensor sampling permission only when measured need justifies it.
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
  GpsChip          (GPS on / No GPS, estimating)
  NavicChip        (NavIC N, only if IRNSS used in the current fix)
  LocateControl    (long-press queues the visible bbox for an area pack)
  RouteDock        (speed, distance, ETA, Stop; only while routing)
```

Idle: map, search, GPS chip, locate. No empty speed, DIST, or ETA slab.
Navigating: the same, plus a route line and a bottom dock with real distance, ETA, and speed.

Search uses Photon, then Nominatim, biased to fused pose/GPS or the camera. Routing uses public OSRM. Tiles are hosted OpenFreeMap until an installed area pack owns rendering. Long-press Locate queues the visible bbox for an offline pack.

## Android acceptance tests

- Sensor timestamps are monotonic and callback arrival time is not substituted.
- Axis transform fixtures pass for portrait and landscape default display rotation.
- Permission denial has a clear fallback and no crash loop.
- Airplane-mode area install replay has zero network calls.
- Killing and restoring the Activity does not reset the active navigation worker.
- Model checksum/schema mismatch falls back safely.
- PMTiles/graph corruption does not activate the package.
- Two-hour replay has no unbounded queue or memory growth.
- UI state transitions match logged `NavigationState`.
- Reference phone sustains 10 Hz output with measured p95 gap.

## NavIC integration

`GnssLocationSource` registers `GnssStatus.Callback` and copies each satellite into `NavicMonitor`. When Android reports `CONSTELLATION_IRNSS`, logcat tag `DriftZeroNavIC` prints `IRNSS visible=… used=… GPS used=… Galileo used=…` on change. If any IRNSS SV is used in the fix, a small `NavIC N` chip appears next to GPS. Idle without that count stays map, search, GPS chip. Counts do not enter the filter. Do not imply that seeing IRNSS proves a trustworthy fix, anti-jam, or safety-of-life. Missing IRNSS does not mean NavIC failed. Details: `docs/refs/NAVIC.md`.

Tester:

```text
adb logcat -s DriftZeroNavIC
```

Outdoors, precise location on, India coverage, NavIC-capable chipset. Cross-check constellation counts in a GNSS status app. Hold GPS chip to clear NavIC during a simulated outage.

