# Cursor implementation phases

## Phase 0: repository and continuous validation

**Prompt:**

```text
Create the minimal monorepo build structure for Python research tools, a Kotlin navigation core, and an Android app. Preserve existing files. Add CI for Python tests, schema validation, Kotlin tests, and Android lint where the toolchain permits. Add version catalogs and pinned dependencies. Do not implement algorithms yet. Exit when a clean clone can run documented validation commands.
```

Acceptance: reproducible setup, no large binaries/data, contracts validate, CI has no fake green steps.

## Phase 1: IO-VNBD evidence path

**Prompt:**

```text
Implement a schema-inspecting IO-VNBD importer. Do not assume columns before inspecting a pinned sample. Produce a data manifest, unit/timestamp QA report, complete-trip split assignment, deterministic GNSS blackout masker, and freeze/constant-velocity baselines. Add leakage tests. Generate a position plot and per-blackout CSV from a named synchronized subset. Ground truth must be score-only after inference.
```

Acceptance: satisfies SIH-13 and SIH-14; rerun is deterministic; per-trip split and no-label assertions pass.

## Phase 2: navigation core

**Prompt:**

```text
Implement platform-neutral coordinate, quaternion, timestamp, sensor-frame, and navigation-state types. Add synthetic stationary, straight, turning, and biased-IMU fixtures. Implement calibration quality, strapdown propagation, stable covariance, guarded GNSS position/velocity updates, and deterministic replay. Use an ESKF first unless an ADR changes the choice. Add finite/range checks and a filter-only fallback.
```

Acceptance: synthetic truth tests, covariance/finite tests, replay determinism, schema mapping.

## Phase 3: outage controller and constraints

**Prompt:**

```text
Implement GNSS health features, innovation gating, the five-state outage controller, hysteresis, non-holonomic constraints, high-confidence stop updates, and reacquisition blending. Add scripted degraded, missing, inconsistent-return, and low-confidence tests. Do not claim spoofing detection.
```

Acceptance: every transition and fallback has a test; no single fix teleports the state.

## Phase 4: learned motion model

**Prompt:**

```text
Build a causal feature pipeline and two compact baselines: depthwise TCN and GRU. Targets are forward motion, yaw correction or another ADR-approved pseudo-measurement, stop probability, and bounded uncertainty. Fit all transforms on training trips only. Compare filter-only and learned variants across held-out trips. Export the winning compact independent model to ONNX with schema and normalization metadata. If neither model improves the locked metric, retain the filter fallback and document the failure.
```

Acceptance: leakage checks, per-trip metrics, uncertainty calibration, ONNX parity, size report.

## Phase 5: TimesFM 3 research

**Prompt:**

```text
Implement the optional TimesFM adapter described in docs/03_TIMESFM3_STRATEGY.md. Run preregistered zero-shot multivariate forecasts against simple and compact baselines. Add a teacher-distillation experiment confined to training folds. Report accuracy, quantiles/calibration, wall time, memory, and per-trip effect. Keep TimesFM only if the documented gate passes. Do not introduce any TimesFM dependency into Android or navigation-core packages.
```

Acceptance: reproducible keep/reject report, multiple trips/seeds, no production dependency.

## Phase 6: offline maps and HMM matching

**Prompt:**

```text
Build a pinned regional OSM pipeline producing local PMTiles plus a compact directed road graph. Implement spatial candidate lookup, covariance-aware emissions, topology/displacement transitions, rolling Viterbi beam, unmatched/ambiguous states, and soft filter feedback. Add parallel-road, flyover, service-road, tunnel, U-turn, roundabout, stationary-junction, and stale-map fixtures.
```

Acceptance: airplane-mode assets, package hashes, bounded runtime, fixture suite, separate visual/feedback ablations.

## Phase 7: Android product

**Prompt:**

```text
Implement Android foreground navigation, sensor and GNSS adapters, bounded queues, calibration flow, area package manager, ONNX runtime adapter, MapLibre PMTiles UI, NavigationState presentation, local trip logger, and replay/judge mode. Default UI is a map with blue dot, heading cone, route, mode chip, confidence halo, and expandable status sheet. Technical controls must not distract a driver.
```

Acceptance: fresh install, area install, airplane-mode live/replay, 10 Hz trace, permissions review, no TimesFM runtime.

## Phase 8: performance and submission

**Prompt:**

```text
Run the locked evaluation protocol, on-device latency/memory/battery/soak tests, India pilot slices, and failure review. Produce the result bundle and populate the demo video with measured values only. Rehearse fresh install and all contingency paths. Update the requirement matrix and README status honestly.
```

Acceptance: official drift target evaluated, tail metrics and worst cases included, video values verified, clean repository.

