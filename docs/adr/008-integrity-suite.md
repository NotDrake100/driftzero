# ADR 008: Integrity suite

- Status: accepted
- Date: 2026-09-04
- Product path: production (`packages/navigation-core`, live Android). Not research-only.
- Requirements: SIH-02, SIH-06, SIH-09, SIH-17, SIH-21, SIH-23 in `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`. Language for GNSS inconsistency follows `docs/09_SECURITY_PRIVACY_SAFETY.md`.

## Context

The live path already has a 15-state ESKF, a geometric GNSS gate, still-reject, reacquire, road heading aid, and a first-run still capture. Drivers still need a remaining-integrity number, a blackout risk that does not invent C/N0, a quarantine for sideways GNSS jumps, a map signature that can pull along-track error without snapping off the road, a local shadow map, and a placement class that does not require a dash mount.

The official SIH26168 aggregate gate is median drift ratio under 0.10. Screening source of record `results/io_vnbd_screening_v1/summary.md` shows no system met it. Persist drift p50 is 0.5168. `kotlin_eskf` is worse. This ADR does not replace that table.

## Decision

Keep the integrity suite as causal Kotlin in `in.driftzero.core`. Do not add fields to `navigation_state.schema.json`. PoseStore publishes an `IntegritySnapshot` for the sheet. Replay default `InsConfig` stays strapdown and does not enable live-only coast flags. Live `PoseStore.LIVE_INS_CONFIG` keeps `YAW_SPEED_HOLD` and turns on honest P, student forward speed, and GNSS speed latch.

1. **Drift budget.** `confidence = clamp(1 - horizontal95 / 120, 0, 1)`. Remaining radius is `max(0, 120 - r95)`. Growth is dr/dt from consecutive r95 samples. If r95 is not growing, remaining time and range are Unavailable. Lamp: HIGH at confidence >= 0.80, CAUTION if remaining range < 650 m or mid confidence, EXHAUSTED if r95 >= 120 m. `sihRemainM = max(0, 0.10 * travelledM - r95)` is a lab remainder, not a gate claim.

2. **Blackout risk.** Combine only available factors: used sats, mean used C/N0 (Unavailable if none), horizontal accuracy, accuracy trend, tunnel ahead, shadow occupancy. Missing C/N0 or map is not 0. Walk `RoadGraph` from the current match along heading to the first `GraphEdge.tunnel`. `ShadowMap.occupancyAhead` walks 50 m cells along heading to 400 m. Preconditioning is true when mean risk >= 0.55, or when the tunnel factor is at least 0.70 (280 m or closer), or when look-ahead shadow occupancy is at least 0.55. Healthy GNSS must not hide a near tunnel. High risk calls `DeadReckoningFilter.armCoastPrecondition` on the live filter so `YAW_SPEED_HOLD` latches the current GNSS or filter speed now. The next coast uses that speed even if the last GNSS report is older than `coastLatchGnssMaxS` (2 s). Replay default `InsConfig.coastLatchGnssSpeed` stays off, so arm is a no-op there. The sheet still shows preconditioning copy.

3. **GNSS trust.** After the existing still-reject and geometric gate, a cross-track firewall quarantines a residual that is unrealistic versus `speed * dt` and `pHoriz`. Heading is `atan2(ve, vn)` (0 = north). Do not apply GNSS while quarantined. Release after `reacquireFixes` (3) consistent fixes. Flags `gnss_quarantine`. Default `InsConfig.gnssQuarantine` is off so replay hashes stay. Live `LIVE_INS_CONFIG` turns it on. This is observable inconsistency, not certified spoofing detection.

4. **Road DNA.** Causal events (TURN, STRAIGHT, GRADE, CURVE, BUMP, STOP) match a map-derived signature with DTW. Ambiguous alignment does not snap. A unique match may apply `applyAlongTrack`, a 1-dof Joseph inject that moves only along heading. The 90 degree / 1270 m versus 1320 m case is a fixture proof, not an IO-VNBD row.

5. **Shadow map.** 50 m cells from lat/lon. Visits, losses, mean accuracy, mean C/N0. occupancy = losses / visits. Persist as `shadow_map.json` in app files. Not cloud.

6. **Mountless placement.** `PhonePlacement`: UNKNOWN, DASH_FLAT, CUP_OR_VENT, PASSENGER_SEAT, HANDHELD. Gravity plus vibration plus remount. Passenger seat is a valid first-run placement. Yaw-from-motion still resolves vehicle +X without a dash assumption.

## Consequences

- The default status sheet gains Navigation confidence, Safe range, Blackout, GNSS trust, and Placement rows. Lab adds SIH remain and raw risk. No 3-up icon row.
- Replay hashes stay on default `InsConfig`. Live APK coast is yaw-speed-hold with honest P and the packed linear student.
- Live `PoseStore` and `DeadReckoningEngine` (only when a graph is attached) apply `MapCoastSession` while coasting. MATCHED, unambiguous edges get a heading prior and may get `applyAlongTrack`. Unmatched or multi-hypothesis coasts call `noteMapUnconstrained` and do not snap. Official `Replay.kt` still builds the engine without a graph. IO-VNBD has no team OSM pack.
- `graph.bin` is gitignored. A Ready pack on the phone or a fixture graph in tests is required. No demo map was added.
- The official 10 percent gate on IO-VNBD remains unmet. Evidence stays `results/io_vnbd_screening_v1/summary.md`. New Kotlin tests cover the suite and a labeled 50 m yaw-speed-hold fixture. Road DNA 1270/1320 remains a fixture proof, not an IO-VNBD claim.
