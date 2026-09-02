# This week: stop shipping a chassis

The APK is a MapLibre sheet, a blue puck on the default location engine, and instrument slots that still print `--.-`. Search does not geocode. There is no route. `NavigationEngine` has no estimator. That is a demo shell.

This week the phone has to do travel, then keep the mark moving when GPS dies. Chrome work belongs to the travel-UI sibling. `docs/FEATURES_WE_NEED.md` is the filter. The README sibling owns GitHub copy. Do not restyle Compose to look busy.

Need now, in this order, already named there:

| Need now | This week |
|---|---|
| Place search | Day 1 |
| Followable route, remaining distance, ETA | Day 1, on a trip only |
| Blue puck from our pose | Day 2 |
| GNSS-lost motion | Day 3 constant-velocity stub, then the ESKF |
| Offline area pack | Installed before the Day 3 airplane test |

If FEATURES_WE_NEED.md marks something later or never, do not build it this week. Speed on a trip is allowed. A parked SPEED / HDG / UNC brick is not.

## Stop

Do not do these, even if they photograph well.

- Empty telemetry. Do not leave SPEED / HDG / UNC / DIST / ETA on screen with `--.-`, `--- deg`, `-- m 95%`, or `NO ROUTE` while idle. Hide those until a trip is active. Do not hard-code `34.2 km/h` in the running app.
- SIH strings, contest IDs, and team names on the map, splash, or mode chip. Product name is DriftZero. LastKnown stays off the HUD.
- Screenshot galleries, shot-list expansion, in-app photo tiles, or capture-this-for-the-deck chores. Phone tests below are pass or fail by behavior. Nobody needs a new PNG in git.
- TimesFM, its checkpoint, or any `timesfm` Gradle/ONNX payload in the APK. Desktop research stays in `ml/`.
- Google Maps SDK, Play Places, or Google Directions. MapLibre Native plus OSM-based geocode/route only.
- A second pose path. MapLibre `useDefaultLocationEngine(true)` freezes the puck when GPS stops. Own pose in one publisher the map reads.
- New settings jungles, judge dashboards, extra Gradle feature modules, or firmware bezels. If it is not search, route, puck, honest GPS, record/replay, or the filter, it waits.

## Day 1. Travel map

Wire geocode and a route line into the existing Where to? slot. Do not rewrite `InstrumentChassis` or `InstrumentChrome`.

Build

- Geocode from the search field. A Pune query must return real candidates a person can tap. An empty Where to? that does not resolve is not search.
- Draw a route polyline from the current puck to that place. Start of trip is choosing a destination or an explicit Go, not a baked demo line.
- Remaining distance and ETA come from that geometry and a live speed prior. If you cannot compute them, show nothing. Do not print `-- km` or `-- min`.
- Speed may appear only while a trip is active and the vehicle is moving. Idle is map plus search.
- Keep the 22 dp `#1E6BFF` puck, white ring, heading cone. Day 1 may still ride GPS for the dot. Day 2 takes ownership of pose.
- Network geocode/route is allowed today. Do not block Day 1 on PMTiles. Start a Pune pack in parallel so Day 3 airplane mode still has streets.

Do not

- Touch travel-UI layout files to improve spacing.
- Add Google Maps.
- Restore HDG or UNC slabs. Uncertainty is not a parked telemetry brick.

Done when, on a phone

1. Cold start shows streets and Where to?, not a wall of `--.-`.
2. Type a real Pune place, pick it, see a route line. DIST and ETA are hidden until that line exists, then they change as you move.
3. Allow precise location. Blue puck is on you, not stuck on the default camera.
4. Walk or ride 30 seconds on the trip. Speed is present while moving and gone when idle.
5. Locate recenters on the puck.

## Day 2. Live GPS, labeled honestly

GPS is the only pose source. Say so. Do not emit a fake `NavigationState` that looks like a filter.

Build

- One pose publisher from `LocationManager` / GNSS fixes. Map puck and camera follow that pose, not a second LocationComponent engine.
- Mode chip in plain English: GPS on when the last trusted fix is fresh. Do not default to LOW CONFIDENCE.
- GNSS age is measured from the last accepted fix, not a string constant.
- Speed on an active trip comes from the GPS fix, with quality flags. Missing speed is absent, not `0.0`.

Do not

- Call this dead reckoning, assisted, or fused.
- Print `12 m 95%` unless you compute it from reported accuracy and you label it as GPS accuracy.
- Claim 10 Hz fusion. GPS rate is whatever the phone gives.

Done when, on a phone

1. Outdoors with GPS on, the chip reads as GPS on, or equivalent honest GNSS copy.
2. Walk a block. The puck follows you. Speed matches a glance at the vehicle or a walking pace, not a placeholder.
3. Deny location once on a fresh install. The app does not crash-loop. The puck does not pretend to be a filter estimate.
4. Developer options mock location, if used, must be visible as mock in logs. Do not silently treat mock as a trusted fix in the product story.

## Day 3. GPS off, mark still moves

Proof of intent: when GNSS is masked, the blue mark keeps moving. Constant-velocity from last course and speed is enough. Label it as estimating, not as an ESKF.

Build

- If GNSS age is older than about 2 s, propagate last velocity and heading at 10 Hz. This stub is proof of intent, not the product. FEATURES_WE_NEED.md still requires sensors and a real `NavigationState` after Day 4.
- Install one Pune PMTiles area pack before the airplane test so the map does not go white in the underpass. Hosted OpenFreeMap is not that pack.
- In-app control: Simulate GPS off. Stops consuming new fixes. Leaves the stub running. This is the reliable test. Airplane mode is the harsh test. The mark must still move.
- Lamp copy: No GPS, estimating. GNSS age keeps climbing.
- Re-enable GPS. The puck follows fixes again. One new fix must not teleport from a long stub coast. Blend or wait for consecutive fixes. If you cannot blend yet, freeze updates until two gated fixes, and say that in the chip.

Do not

- Leave MapLibre's default location engine in charge. That engine freezes. That is the failure we are killing.
- Call the stub an error-state filter, strapdown INS, or learned model.
- Feed hidden GPS into the stub during a mask. Score-only truth, if you log it, stays out of pose.

Done when, on a phone

1. Start a short trip with GPS on. Get moving so last speed is not zero.
2. Tap Simulate GPS off, or enable airplane mode after the Pune pack is installed. Streets stay. The mark still moves.
3. Watch 10 to 20 seconds. The blue mark continues along last course. It does not freeze on the last fix.
4. Chip says No GPS, estimating. Age of last GPS increases.
5. Turn GPS back on. Pose returns to live fixes without a one-sample jump across the city.
6. Repeat once at a standstill. The mark does not slide away on a stale highway speed. Zero or near-zero last speed must coast as a stop.

## Day 4. One real Pune trip

Record one street trip in Pune. Replay it on the same APK. That is the product loop. Not a screenshot pack, not IO-VNBD on the phone.

Build

- Foreground session so killing the Activity does not drop the log.
- Write `SensorFrame` and pose/`NavigationState` JSONL that matches `contracts/`. IMU plus GNSS, monotonic timestamps, quality flags. No silent zeros.
- Hidden engineering replay: choose that session, play it with GPS ignored. Puck follows the log. This is not a traveler screen and not a screenshot gallery.
- Keep files under `data/raw/` or app storage. Those paths are gitignored. Do not commit tracks.

Do not

- Collect a fleet. One trip, one phone, one mount note in the session header.
- Drive and operate the phone. Passenger records, or the phone is in a fixed mount.
- Treat this replay as a published accuracy claim. No invented drift percentages.
- Put side-by-side truth traces or drift-ratio chips on the map.

Done when, on a phone

1. Record 10 to 20 minutes on real Pune streets, GPS on, trip active.
2. Stop. Session file is on the device and openable. Frame count is not zero.
3. Open the hidden replay. Airplane mode on. Puck retraces the recorded path. Live GPS is not the pose source.
4. During replay, fire the same GPS-off mask used on Day 3. Logged GNSS drops out. The mark still coasts on the stub. Hidden fixes, if stored for later scoring, do not move the puck.

## Then the estimator, not more chrome

After Day 4, the work is `packages/navigation-core`, not another strip on the map.

Build

- Implement `NavigationEngine` as an ESKF. ADR 001. Strapdown propagate on gyro and accelerometer. Guarded GNSS position and velocity updates. Joseph-form or another stable covariance update. Finite checks. Filter-only fallback.
- Replace the constant-velocity stub with this engine on the live and replay paths. Same pose publisher. Same UI.
- Tests first: stationary, straight, turn, time gap, GNSS drop, no teleport on first reacquisition.
- Output `NavigationState` at 10 Hz. UI only renders it.

Do not

- Add TimesFM to the phone.
- Snap to roads this week. HMM is later. Ambiguous geometry stays unmatched.
- Open new screens, calibration theater, or a metrics gallery until a turn during GPS-off looks different from Day 3.

Done when, on a phone

1. Replay the Day 4 Pune trip with GNSS masked on a curve. Constant-velocity would go straight. The ESKF puck yaws with the gyro. If it still goes straight, the filter is not in the loop.
2. Live: GPS on, then Simulate GPS off mid-turn. Same yaw behavior. Chip still says No GPS, estimating until you have evidence for a tighter name.
3. `./gradlew :navigation-core:test` and `:android-app:assembleDebug` pass. No `timesfm` in the APK dependency tree. No `play-services-maps`.

## Order and owners

Days are sequence, not calendar religion. Do not start the ESKF UI celebration before Day 3 is visible on a device.

| Slice | Owns | Does not own |
|---|---|---|
| FEATURES_WE_NEED.md | Need now / later / never | Calendar |
| Travel UI sibling | Where to?, hide idle speed/dist | Pose, routing math |
| Android session | Geocode/route, GPS publisher, GPS-off control, record/replay, Pune pack | Filter math |
| Navigation core | ESKF, contracts, replay determinism | Compose chrome, TimesFM |
| README sibling | GitHub story, mermaid | App strings on the map |

Python `freeze_baseline` and `constant_velocity_baseline` already exist in `ml/`. Day 3 is that idea on the phone. Day 4 gives the engine a real log. The ESKF is the product.
