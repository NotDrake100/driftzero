# DriftZero

Phone-only vehicle navigation that keeps a blue puck moving when GPS drops. The map is the product. LastKnown builds it.

Open the app, allow precise location, tap locate, type **Where to?**, pick a place, and follow the route. You do not flip a dead-reckoning switch. When GNSS is stale or held, `DeadReckoningFilter` keeps propagating the same `NavigationState` at 10 Hz.

Street tiles still come from the network (`StreetMapConfig.STYLE_LIBERTY` on OpenFreeMap) until a Ready `AreaPack` with `tiles.pmtiles` is sideloaded. TimesFM is not in the APK.

## Trip on the phone

`MainActivity` starts MapLibre, builds `rememberPoseStore()`, and shows `TravelMapScreen`. The map is `StreetMap` on a TextureView (`StreetMapConfig.TEXTURE_MODE`) so Compose can composite it. Chrome is `TravelSearchBar` (`destination_hint` = Where to?), locate (`StreetMapController.locateOwnVehicle`), and a route sheet after a pick.

Photon is the geocoder. The query is biased to the live pose (`searchBiasLatLon` from `hudFix`, else map origin, else camera). Nominatim is fallback. A hit calls `TravelSearchClient.route` against public OSRM, draws a GeoJSON polyline, and `followPuck()`.

```mermaid
flowchart TD
    subgraph launch [Open]
        A["MainActivity.onCreate"] --> B["MapLibre.getInstance"]
        B --> C["rememberPoseStore"]
        B --> D["TravelMapScreen"]
        C --> E["PoseStore.state: NavigationState?"]
        E --> D
        D --> F["StreetMap AndroidView MapView"]
        F --> G["textureMode true OpenFreeMap liberty"]
        D --> H["TravelSearchBar R.string.destination_hint"]
        D --> I["MapControls action_locate"]
    end

    subgraph locate [Locate]
        I --> J["StreetMapController.locateOwnVehicle"]
        J --> K["StreetMapSession.recenterOnPuck"]
        K --> L["puckLatitudeDeg puckLongitudeDeg from pose"]
        L --> M["else LastFixStore / newestLastKnownLocation / CameraStartResolver"]
    end

    subgraph search [Where to?]
        H --> N["TravelSearchClient.search query, near lat lon"]
        N --> O["searchBiasLatLon hudFix or origin or camera"]
        O --> P["photonUrl StreetMapConfig.PHOTON_API"]
        P -->|PlaceQuery.Hits| Q["TravelMapScreen.pickPlace"]
        P -->|miss| R["nominatimUrl StreetMapConfig.NOMINATIM_SEARCH"]
        R --> Q
        P -->|PlaceQuery.Network| S["search_network string"]
    end

    subgraph route [Route and follow]
        Q --> T["routeOrigin from pose or map origin"]
        T --> U["TravelSearchClient.route"]
        U --> V["osrmUrl StreetMapConfig.OSRM_ROUTE driving"]
        V --> W["parseOsrmRoute TravelRoute"]
        W --> X["setRoute GeoJson LineString ROUTE_LAYER_ID"]
        X --> Y["setDestination DEST_LAYER_ID"]
        Y --> Z["StreetMapController.followOwnVehicle followPuck"]
        Z --> AA["TravelHud speed km/h after route exists"]
    end

    subgraph coast [GPS loss is not a mode you learn]
        AB["GnssLocationSource.stop or CoastFix age"] --> AC["DeadReckoningFilter gnssHeld or ageS greater than STALE_AFTER_S 2s"]
        AC --> AD["NFrameMechanization propagate only"]
        AD --> AE["same NavigationState at OUTPUT_HZ 10"]
        AE --> AF["StreetMap puck stays on ESKF lat lon"]
        AF --> G
    end
```

Long-press the GPS chip to hold GNSS (`PoseStore.setSimulateGpsOff`). Accel and gyro keep copying. The puck does not freeze. Long-press again to resume `ingestGnss`.

## Live engine

`rememberPoseStore` owns `PoseStore`, starts `PhoneImuSource` and `GnssLocationSource`, and calls `PoseStore.tick()` at `DeadReckoningFilter.OUTPUT_HZ` (10). Sensor callbacks only copy samples. Inference and filter updates run on that tick.

`DeadReckoningFilter` is strapdown INS in ENU (`NFrameMechanization`, Groves local-tangent) plus a 15-state ESKF (`EskfMath` Joseph update: δp, δv, δθ, ba, bg). Healthy GNSS younger than 2 s updates. Otherwise the filter propagates. ZUPT and NHC come from IMU statistics. `NavicMonitor` tallies IRNSS from `GnssStatus` and logs. Those counts do not enter the filter.

`MotionStudentAssets` loads `models/motion_student_v1/linear.json` when packed. Missing weights leave `ZuptAccelMotionModel`. Optional `LearnedImuAssets` / `linear_dp.json` injects a chi-squared gated Δp. `HmmRoadMatcher` (Newson-Krumm) writes `mapMatch` / display pose only. It does not replace ESKF lat/lon.

```mermaid
flowchart TD
    subgraph adapters [Android adapters]
        IMU["PhoneImuSource TYPE_ACCELEROMETER TYPE_GYROSCOPE"] -->|"SensorEvent.timestamp ingestAccel ingestGyro"| PS["PoseStore"]
        GNSS["GnssLocationSource LocationManager fused/gps/network"] -->|"CoastFix ingestGnss"| PS
        GNSS -->|"GnssStatus rows"| NAV["NavicMonitor.ingest log only"]
        NAV -.->|"not a filter input"| PS
    end

    subgraph tick [10 Hz worker rememberPoseStore]
        PS --> TICK["PoseStore.tick clockNs elapsedRealtimeNanos"]
        TICK --> MPR["MotionPseudoRuntime.inferAt"]
        MPR --> W["CausalImuBuffer.windowEndingAt"]
        W --> ST{"MotionStudentAssets.load linear.json"}
        ST -->|present| LMS["LinearMotionStudent MotionModel"]
        ST -->|absent| ZAM["ZuptAccelMotionModel heuristic"]
        LMS --> PM["ingestMotionPseudo"]
        ZAM --> PM
        TICK --> DP["inferDisplacementAt"]
        DP --> LDS{"LearnedImuAssets linear_dp.json"}
        LDS -->|present and finite| DPM["ingestDisplacementPseudo chi2 11.345"]
        LDS -->|absent| SKIP["skip delta-p"]
    end

    subgraph filter [packages/navigation-core]
        PM --> DRF["DeadReckoningFilter"]
        DPM --> DRF
        PS --> DRF
        DRF --> NF["NFrameMechanization Groves ENU strapdown"]
        DRF --> ESKF["15-state ESKF EskfMath Joseph"]
        DRF --> AIDS["ZUPT FLAG_ZUPT / NHC FLAG_NHC"]
        NF --> POSE["filter.poseAt NavigationState"]
        ESKF --> POSE
        AIDS --> POSE
    end

    subgraph mapmatch [Display only]
        POSE --> GRAPH{"RoadGraph AreaPackStore OsmGraphLoader"}
        GRAPH -->|graph.bin Ready| HMM["HmmRoadMatcher.update FilterSnapshot"]
        HMM --> MM["NavigationState.withMapMatch displayPose"]
        GRAPH -->|empty or unused| POSE
        MM --> NS["NavigationState sequence mode position motion uncertainty"]
        POSE --> NS
    end

    subgraph ui [Map surface]
        NS --> TMS["TravelMapScreen pose"]
        TMS --> SM["StreetMap puckHeadingRad puck lat lon"]
        SM --> STYLE{"AreaPackStore.active Ready styleUri"}
        STYLE -->|no pack| OFM["StreetMapConfig.STYLE_LIBERTY tiles.openfreemap.org still networked"]
        STYLE -->|tiles.pmtiles| PMT["local style URI"]
        OFM --> TV["MapLibre TextureView"]
        PMT --> TV
    end
```

Modes on `NavigationState.mode` are `GNSS_FUSED`, `GNSS_DEGRADED`, `DEAD_RECKONING`, `REACQUIRING`, `LOW_CONFIDENCE`. The HUD chip reports them. The estimator does not wait for a user gesture to coast.

## Start here

1. Read [PRD.md](PRD.md) and [PRODUCT.md](PRODUCT.md) (five-step phone test).
2. Sideload notes: [demo/TESTER_SIDELLOAD.md](demo/TESTER_SIDELLOAD.md).
3. Equation map: [docs/refs/INS_ESKF.md](docs/refs/INS_ESKF.md). Filter ADR: [docs/adr/001-hybrid-filter.md](docs/adr/001-hybrid-filter.md). Motion student: [docs/adr/005-motion-pseudo-measurement.md](docs/adr/005-motion-pseudo-measurement.md).
4. Requirement IDs: [docs/01_REQUIREMENTS_TRACEABILITY.md](docs/01_REQUIREMENTS_TRACEABILITY.md).

```bash
PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v
make validate
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:test --no-daemon
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --no-daemon
```

Python uses the standard library for unit tests. JVM and Android builds need JDK 17. Do not commit `data/raw` IO-VNBD CSVs.

## Repository map

| Path | What runs |
|---|---|
| `apps/android/app` | `MainActivity`, `TravelMapScreen`, `StreetMap`, `PoseStore`, Photon/OSRM |
| `packages/navigation-core` | `DeadReckoningFilter`, `HmmRoadMatcher`, `LinearMotionStudent` |
| `models/motion_student_v1/linear.json` | Optional on-device speed weights |
| `models/learned_imu_v1/linear_dp.json` | Optional gated Δp. Not a screening claim |
| `ml/` | Train/eval, IO-VNBD loaders, student export |
| `contracts/` | `SensorFrame` / `NavigationState` JSON Schema |
| `data/area-packs/` | Sideload target for PMTiles + `graph.bin` |
| `docs/adr/` | Filter, offline maps, CV stub, motion pseudo-measurement |
| `docs/CUT_VS_KEEP.md` | What to ship vs cut |
| `docs/RELATED_APPS.md` | Puck size and Where to? research |

## Rules that stay true

Ground-truth GNSS may score a blackout. It must not enter features, the filter, the matcher, or recovery during that interval. No OBD, vehicle speedometer, cloud inference, or TimesFM on the phone path. Ambiguous road hypotheses stay uncertain. User-facing numbers include confidence and a reason.
