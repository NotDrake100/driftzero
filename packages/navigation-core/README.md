# Navigation core contract

The navigation core is platform-neutral and contains no Android UI, `SensorManager`, filesystem, network, or TimesFM dependency.

It implements a strapdown INS in the local-tangent n-frame (ENU) and a 15-state error-state Kalman filter (δp, δv, δθ, ba, bg) with Joseph covariance updates. Gravity is WGS84 Somigliana plus the Groves height term. Earth rotation is omitted at phone scale. Equation map: `docs/refs/INS_ESKF.md`. GNSS position and velocity updates run when the fix is healthy. When GNSS age exceeds 2 s, the filter propagates only (dead reckoning). ZUPT and NHC are applied from IMU statistics. `MotionPseudoRuntime` infers a causal IMU student (`linear.json` speed weights when loaded, else the ZUPT/vibration heuristic) and injects `MotionPseudoMeasurement` via `ingestMotionPseudo` and optional `DisplacementPseudoMeasurement` via `ingestDisplacementPseudo`. TimesFM is not in this package.

## Input

`SensorSource` produces ordered measurements conforming semantically to `contracts/sensor_frame.schema.json`. Concrete adapters:

- Android phone sensors and GNSS;
- deterministic session replay;
- IO-VNBD importer/replay;
- file replay of a declared-rate IMU stream. The adapter accepts 10 Hz, 100 Hz, or 200 Hz. Only synthetic 200 Hz tests exist. No live FOG or external IMU run is in `results/`.

Each adapter declares units, axes, time base, expected rate, calibration source, and quality mapping.

### File replay (FR-11, FR-12, SIH-26)

`ReplaySensorSource` reads one `SensorFrame` JSON object per line. An optional first line may be a header object with `declared_rate_hz`, `clock_domain`, `frame`, and `source_id`. Otherwise those fields come from the first IMU frame and the IMU period. Units are the contract strings (`m/s^2`, `rad/s`, `uT`). Timestamps are integer nanoseconds. The adapter does not care whether the file is 10 Hz, 100 Hz, or 200 Hz. Rate independence is tested on synthetic constant-velocity trips (`ReplaySensorSourceTest.twoHundredHzTrajectoryMatchesOneHundredHz`). That is not a FOG demonstration.

IO-VNBD frames are produced by the Python side (`ml/`) as aligned, unit-checked SensorFrame JSONL. This adapter stays dataset-agnostic. Hidden truth GNSS is never present in the input during a mask. Scoring is Python (`ml/src/driftzero_ml/eval_navstate.py`).

Drive the same `DeadReckoningFilter` used on the phone:

```
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:replay --args="--input frames.jsonl --output states.jsonl"
```

Optional `--mask-start-ns` and `--mask-end-ns` drop GNSS kinds in that inclusive nanosecond interval before the filter sees them. Optional `--declared-rate-hz` and `--config name=value` override the header and `InsConfig`. Optional `--road-graph path` loads OSM XML, OSM PBF, or `graph.bin` and applies MATCHED heading-only feedback while coasting. Lat/lon are never snapped. Default is no graph, so official IO-VNBD hashes stay map-free. Output is one `NavigationState` JSON object per line at 10 Hz on the recorded clock.

## Output

The core emits `NavigationState` at a configured rate, normally 10 Hz on phone. It includes estimate, motion, 95-percent uncertainty, GNSS health, map status, component health, and provenance.

## Proposed Kotlin boundary

```kotlin
interface SensorSource {
    val descriptor: SensorSourceDescriptor
    fun frames(): Flow<SensorFrame>
}

interface MotionModel {
    fun infer(window: CausalImuWindow): MotionPseudoMeasurement
}

fun interface DisplacementModel {
    fun infer(window: CausalImuWindow): DisplacementPseudoMeasurement?
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

`DeadReckoningFilter` is the live estimator. `DeadReckoningEngine` implements `NavigationEngine` on top of it. `MotionPseudoRuntime` calls `infer` on the 10 Hz emit path and `ingestMotionPseudo` applies ZUPT or a gated forward-speed update. `ingestDisplacementPseudo` applies a gated HACF Δp update when a `DisplacementModel` is present. The Δp χ² gate is 11.345. Do not treat `linear_dp.json` as beating freeze. `HmmRoadMatcher` is Newson-Krumm Viterbi on a directed OSM graph. It writes `mapMatch` and a display pose only. It does not replace the ESKF lat/lon. `RoadHeadingFeedback` may apply a MATCHED heading prior while coasting. `OsmGraphLoader` reads OSM XML or PBF for any WGS84 bbox. Do not put TimesFM in this package.

Use strongly typed wrappers for nanoseconds, metres, radians, metres/second, geographic coordinates, and frames. Avoid bare `Double` across package boundaries where units can be confused.

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
4. Recorded trip regression tests (planned for Pune JSONL; IO-VNBD Kotlin replay is scored in `results/io_vnbd_screening_v1/`).
5. Cross-language parity against Python reference (SensorFrame export plus `eval_navstate`).
6. Android soak and performance tests (planned. No battery or p95-gap report exists).
