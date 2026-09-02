# Android application specification

The `app` module is a Kotlin/Compose scaffold: MapLibre Native 13.0.2 renders OpenFreeMap liberty, and a high-contrast location puck is always drawn at the camera target (Koregaon Park until GNSS). OpenFreeMap is a prototype online style until local PMTiles (ADR 003). The puck is production-path UI.

```bash
cd apps/android
./gradlew :app:assembleDebug
```


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
NavigationScreen
  OfflineMap
    PositionMarker
    HeadingCone
    ConfidenceHalo
    RoutePolyline
  TopModeChip
  SpeedAndInstructionCard
  StatusBottomSheet
    Confidence
    GNSSHealth
    LastTrustedFix
    SensorAndMountHealth
    MapPackage
  EngineeringOverlay (judge build or explicit mode)
```

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

Where supported, log constellation membership using Android `GnssStatus`, including `CONSTELLATION_IRNSS`. This is useful for visibility and coverage analysis. Do not imply that seeing an IRNSS satellite proves a trustworthy fix or that missing it means NavIC failed.

