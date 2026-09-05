# ADR 009: Persist-like YAW_SPEED_HOLD coast seed

- Status: accepted (picker). Filter wiring aborted 2026-09-04 after named-interval guard.
- Date: 2026-09-04
- Product path: picker is library code. `DeadReckoningFilter` does not apply it. Replay default `InsConfig` stays `STRAPDOWN`.
- Requirements: SIH-13, SIH-23 in `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`.

## Context

Persist (`screening.py` `_coast`) seeds from unique GNSS with `t < blackout start_ns`, holding that unique's speed and the 10 m GNSS course. Replay advances the mask by 1 ns so the unique at interval start can be ingested. On S-Vw16b that unique is 0 m/s.

The official SIH26168 aggregate gate is median drift ratio under 0.10. Persist p50 on the locked 35-interval table is 0.5168. This ADR does not claim that gate.

## Decision

1. `PersistCoastSeed.pick` walks the unique-fix trail from newest to oldest. A unique qualifies only with column speed >= 0.4 m/s and a 10 m course ending at that unique. A trailing 0 m/s unique is skipped. Tests lock that pick.

2. Wiring that pick into `DeadReckoningFilter` YAW_SPEED_HOLD initialize/coast was aborted. Leak-free `frames_premask` scoring of that wiring (`results/io_vnbd_screening_v1/kotlin_replay/seed_premask/`) made S-Vta2:d50 13.48 m (v5 12.46 m) and S-S1:mid 873.41 m (v5 55.01 m). Drift p50 was 0.6366 (n=35), worse than v5 0.6107 and persist 0.5168. Official 0.10 is unmet.

3. Official leak-free scoring stays `frames_premask/`. Extra args for a future retry must stay v5a: `YAW_SPEED_HOLD`, HOLD_COURSE on weak picks. No latch, honest P, student, decay, stop detector, or unique-gap reseed. Do not copy `PoseStore.LIVE_INS_CONFIG` onto that row.

4. Live `PhoneImuSource` requests `SENSOR_DELAY_FASTEST`. Measured Hz is logged from `SensorEvent.timestamp` deltas. Do not cite 100 Hz without that measurement. NHC stays skipped unless IMU frame is `VEHICLE_FLU` (ALIGNED).

## Consequences

- Live APK YAW_SPEED_HOLD still uses last accepted GNSS speed, including a 0 m/s unique at a gap start.
- Integrity UI, Drift Budget, and Road DNA fixtures are not this gate.
- TimesFM stays off the phone path.
- `results/io_vnbd_screening_v1/summary.md` is not overwritten.
