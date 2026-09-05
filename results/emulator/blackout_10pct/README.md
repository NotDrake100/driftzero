# Emulator 50 m GNSS-gap fixture

**Emulator fixture only.** Mock `adb emu geo fix` and SwiftShader IMU. Not IO-VNBD. Not SIH screening. Not phone accuracy. Do not put these metres in a screening deck.

Official gate language: drift / distance_travelled < 0.10 during a GNSS blackout (example: 5 m over 50 m).

## Route that was sent

`tools/emulator/drive_route.py` on emulator-5554 (AVD `Medium_Phone_API_36.0`).

- From 18.51090730,73.88510180 toward 18.51315000,73.88510180
- OSRM length 254.8 m at 18 km/h (5 m/s), 1 Hz
- `--gap-start-s 12 --gap-s 9`
- Last emitted fix before the gap: 18.51138401,73.88472887 at 2026-09-04T07:22:41Z
- First resume fix: 18.51182914,73.88478024 at 2026-09-04T07:22:51Z
- `adb emu geo fix` order is longitude then latitude
- Emitted fixes (gap seconds omitted, timestamps jump): `emitted.gpx`, `emitted.csv`

`path_m` is the GPX chord across that jump: **49.7917 m**.

## Live APK on the emulator: 0.10 not met

| Field | Value |
|---|---|
| path_m | 49.7917 |
| error_m | 3110.2982 |
| ratio | 62.4662 |
| met 0.10 | **no** |
| coast pose | 18.52039833,73.85670000 (LOW_CONFIDENCE, speed 0) |
| truth (resume fix) | 18.51182914,73.88478024 |

The fused/DR mark never followed the geo line. It stayed on an older last-known point about 3.1 km west of the resume fix. Lamp: Low confidence. Radius grew through 306 m (mid-gap screencap) to 501 m (gap-end screencap), over the 120 m limit. `health.flags` included `zupt`, `imu_gap`, `no_imu`, `gated_fix`.

### What blocked a real 10 percent on the live APK

1. Emulator `geo fix` reports `vel=0.0`, `bear=0.0`. Yaw-speed-hold has no speed to latch.
2. Recorded IMU is table-still: accel about (0, 9.78, 0.81) m/s^2, gyro (0, 0, 0) rad/s. No yaw.
3. `LiveStillDetector` treats that as still. Moving geo hops over 8 m are rejected. The filter never locked the 1 Hz line.
4. Fixes that did arrive were innovation-gated (`gated_fix`) against the parked 18.52040,73.85670 state.
5. SwiftShader IMU is not a vehicle. This is not a phone coast.

Hold GNSS / Simulate GPS off was not required. The emit gap was real (9 s with no new `geo fix`). Keepalives were not the failure mode. The failure is fake IMU plus speed-0 fixes.

`score_live.json` is this live row. `pose_gap.log` and `logcat.txt` are the same run. `trip/states.jsonl` matches the parked pose.

## JVM emulator-route fixture: 0.10 met

Same emitted GPX, leak-free GNSS mask, gravity plus zero gyro in vehicle FLU. Not SwiftShader. Not IO-VNBD.

`EmulatorRouteFixture` in `packages/navigation-core` tests. Coast mode `YAW_SPEED_HOLD`. Student off.

| Field | Value |
|---|---|
| path_m | 49.7917 |
| error_m | 1.5883 |
| ratio | 0.0319 |
| met 0.10 | **yes** |
| coast | 18.51182863,73.88476519 |
| truth | 18.51182914,73.88478024 |

`score_jvm.json` is this row. The 10 percent check for this fixture is the filter on the polyline, not the live APK.

A second labeled line, `officialFiftyMetreNorth` (5 m/s north, 12 s warmup, 9 s gap, 50 m path), is in the same test class. Same rule: emulator-route fixture, not screening.

## Files

| File | What it is |
|---|---|
| `emitted.gpx` | Score-only truth of what `geo fix` sent. Gap seconds omitted. |
| `emitted.csv` | Same points as CSV. |
| `score_live.json` | Live APK score. ratio 62.5. Not a 10 percent claim. |
| `score_jvm.json` | JVM filter on the same GPX. ratio 0.0319. |
| `logcat.txt` | Device log around the drive. |
| `pose_gap.log` | `DriftZeroPose` / `DriftZero` lines only. |
| `gap_mid.png`, `gap_end.png` | Emulator screencaps during the gap. |
| `gap_mid_ui.xml`, `gap_end_ui.xml` | uiautomator dumps. |
| `trip/states.jsonl`, `trip/sensors.jsonl` | Debug trip record. IMU still. GNSS gated. |
