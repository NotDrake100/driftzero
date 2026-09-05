# DriftZero architecture (as of 2026-09-03)

Phone sensors and a `SensorFrame` JSONL file both enter `DeadReckoningFilter`. Output is `NavigationState` at 10 Hz (`contracts/navigation_state.schema.json`). TimesFM sits on the desktop only. It is not imported by `packages/navigation-core` or `apps/android`.

Live `PhoneImuSource` registers accelerometer, gyroscope, and magnetometer. Mag is stored in uT and `consume()` no-ops `MAGNETOMETER`. Mount still plus straight can rotate IMU into `VEHICLE_FLU`. NHC runs only in that frame. `HmmRoadMatcher` writes `mapMatch` / `displayPose`. Lat/lon are never snapped into the ESKF. `RoadHeadingAid` is a heading-only prior when MATCHED. Live `PoseStore` applies it through `MapCoastSession` while coasting, plus along-track Road DNA when the signature is unique. `LocalRouter` is the graph router when a Ready pack has `graph.bin`. Airplane-mode streets were captured. Offline tap-to-route on the emulator was not captured.

Eval replay uses `--coast-mode=yaw_speed_hold`. Live `PoseStore` still constructs `DeadReckoningFilter()` with default `InsConfig` (`STRAPDOWN`). Chi-squared 11.345 is the Δp displacement gate. Forward speed from `LinearMotionStudent` uses `R = exp(logSpeedVariance)` and runs only while GNSS is held or older than 2 s. GNSS position uses a geometric innovation gate `6 * (σ + √P)`.

Visitor copy of this figure is in the README under Phone live path. Outage and eval: [outage.md](outage.md), [eval.md](eval.md).

```mermaid
flowchart TD
  ACC["Accel m/s2"] --> PS["PoseStore"]
  GYR["Gyro rad/s"] --> PS
  GNSS["GNSS fix"] --> PS
  HOLD["Hold GNSS"] --> PS
  MAG["Magnetometer uT"] -.->|"captured, unused"| PS
  FILE["ReplaySensorSource JSONL"] --> DRF
  PS --> MNT["Mount still plus straight"]
  MNT -->|"ALIGNED: VEHICLE_FLU"| DRF["DeadReckoningFilter 15-state ESKF"]
  MNT -->|"else ANDROID_DEVICE, NHC off"| DRF
  PS --> DRF
  DRF --> NS["NavigationState 10 Hz"]
  NS --> HMM["HmmRoadMatcher display"]
  HMM -->|"mapMatch, no lat lon write-back"| NS
  NS --> MAP["StreetMap puck and halo"]
  NS --> LAMP["Mode lamp"]
  NS --> SHEET["Status sheet"]
  NS --> GUID["Guidance banner and voice"]
  PACK["Ready AreaPack tiles.pmtiles plus graph.bin"] --> HMM
  PACK --> LR["LocalRouter when pack Ready"]
  LR --> GUID
  GST["NavIC count"] -.->|"display only"| SHEET
  subgraph DESKTOP [Not on this path]
    TFM["TimesFM 3.0 non-commercial. 2.5 Apache. Desktop only"]
  end
```
