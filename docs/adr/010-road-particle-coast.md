# ADR 010: Road-constrained particle coast during GNSS blackout

- Status: accepted (research path). Python fixture only. Not on the live phone. Not an IO-VNBD row.
- Date: 2026-09-04
- Product path: research in `ml/` until a named `results/pune_v1/` table beats persist. Then port to `packages/navigation-core`. Live GNSS-fused estimator stays the ESKF (ADR 001).
- Requirements: SIH-10, SIH-19, SIH-23 in `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`. Map feedback rules in ADR 003.

## Context

Locked IO-VNBD persist median drift is 0.5168. Free strapdown, absolute-speed ML, and gyro-only persist all miss the official median \(E/D < 0.10\) gate. `docs/11_GATE_PLAN.md` section 3.d already names road geometry as the remaining observable. Live `MapCoastConstraint` plus `RoadDna` pull heading and a one-shot along-track heal when MATCHED. They still estimate free \(x,y\). They do not keep a cloud on \((e, s, v)\).

## Decision

1. **Fused GNSS** stays ADR 001: 15-state ESKF, packed `linear.json` optional, filter-only fallback. Do not delete strapdown for the fused path. Replay default `InsConfig` stays `STRAPDOWN`.

2. **Blackout coast** (research) estimates
   \(X = [e, s, v, b_a, b_g]\)
   on the OSM graph. Lat/lon is the weighted particle position on the centerline. That is not a lateral snap off the graph. `AMBIGUOUS` / split weights grow the halo. Official Replay still builds `DeadReckoningEngine` without a graph.

3. **Python first.** `driftzero_ml.road_particle` runs on a labeled fixture graph (straight, one bend, one fork). Hidden GNSS inside the blackout is score-only. The module must refuse to write `results/pune_v1/`. No SIH 10% claim from the fixture.

4. **Phone logger** records gravity, linear acceleration, and uncalibrated gyro as new `SensorKind` values. The ESKF does not consume them. Magnetometer stays captured and unused. Game rotation vector is deferred until a quaternion payload exists in the contract. GNSS speed and bearing accuracy are copied when Android reports them.

5. **Later, only if Pune wins.** Gravity-axis yaw \(\omega\cdot\hat g\), \(\omega \approx v\kappa(s)\), intersection spawn, 1D \((v, b_a)\), rigid vs non-rigid, then a \(\Delta v\) student. Port to Kotlin when Python p50 on real Pune Holds is below persist, then below 0.10. TimesFM stays off the phone.

## Consequences

- IO-VNBD `summary.md` is unchanged. The fixture is not that table.
- ADR 003 still forbids replacing filter lat/lon with an HMM display pose. Particle lat/lon is the blackout state on the graph, not a parallel-road teleport.
- A straight empty highway with unpredictable speed still cannot guarantee 10%. Report median on a named suite.
- Valhalla/Meili stay out of the APK. An offline reference matcher for truth path length, if added later, must not feed the live PF.
