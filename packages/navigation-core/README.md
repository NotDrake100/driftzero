# Navigation core contract

The navigation core is platform-neutral and contains no Android UI, `SensorManager`, filesystem, network, or TimesFM dependency.

## Input

`SensorSource` produces ordered measurements conforming semantically to `contracts/sensor_frame.schema.json`. Concrete adapters:

- Android phone sensors and GNSS;
- deterministic session replay;
- IO-VNBD importer/replay;
- generic external IMU stream around 200 Hz.

Each adapter declares units, axes, time base, expected rate, calibration source, and quality mapping.

## Output

The core emits `NavigationState` at a configured rate, normally 10 Hz on phone. It includes estimate, motion, 95-percent uncertainty, GNSS health, map status, component health, and provenance.

## Proposed Kotlin boundary

```kotlin
interface SensorSource {
    val descriptor: SensorSourceDescriptor
    fun frames(): Flow<SensorFrame>
}

interface MotionModel {
    fun infer(window: CausalFeatureWindow): MotionPseudoMeasurement
}

interface RoadMatcher {
    fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult
}

interface NavigationEngine {
    suspend fun consume(frame: SensorFrame)
    fun states(): Flow<NavigationState>
    fun reset(reason: ResetReason)
}
```

Use strongly typed wrappers for nanoseconds, metres, radians, metres/second, geographic coordinates, and frames. Avoid bare `Double` across package boundaries where units can be confused.

## Mount alignment (FR-02)

`in.driftzero.core` ships a causal phone-to-vehicle alignment helper (`MountAlignment.kt`) that does not depend on Android:

- `StationaryCapture` estimates gravity (m/s^2) and gyro bias (rad/s) while still, or returns `Insufficient` / `Moving` with a reason.
- `YawFromMotion` resolves yaw from straight accel/brake events. A 180-degree flip is resolved only with a GNSS speed-delta sign; otherwise the result stays `Pending`.
- `MountProfile` is the persisted rotation plus quality (`STATIONARY_ONLY`, `ALIGNED`, `ALIGNED_HIGH`).
- `MisalignmentMonitor` emits `Remount` after a held gravity-direction change (default 8 degrees for 3 s) and ignores short spikes.

The dead-reckoning filter should consume `MountProfile.toVehicleAccel` / `toVehicleGyro` and map `MisalignmentUpdate.Remount` to `ResetReason.REMOUNT`. See `docs/adr/007-mount-alignment.md`.

## Determinism contract

Given the same ordered sensor frames, map/model/config artifacts, and initial state, replay emits the same state sequence within declared floating-point tolerance. UI frame rate and asynchronous logging must not change core output.

## Numerical protections

- Reject non-finite and out-of-range input.
- Bound time deltas and handle gaps explicitly.
- Normalize quaternions.
- Preserve positive covariance with stable update form.
- Gate measurements using normalized innovation.
- Clip learned covariance/noise multipliers to configured safety bounds.
- Emit a health flag and fallback on inference error.
- Stop unsupported propagation beyond a confidence or time limit.

## Testing pyramid

1. Math and coordinate unit tests.
2. Synthetic sensor fixtures with exact truth.
3. State-machine transition tests.
4. Recorded trip regression tests.
5. Cross-language parity against Python reference.
6. Android soak and performance tests.

