# DriftZero product design

About-sheet and India-paragraph facts: check `docs/refs/SIH26168_EVIDENCE.md` (fetched 2026-09-03) before shipping `about_india`.

Status: production-path spec for the Android app. Written 2026-09-03 against the tree at that date. Every number on screen comes from `NavigationState` (`contracts/navigation_state.schema.json`), `NavicMonitor`, `AreaPackStore`, or a pure function named in section 12. Nothing here is a mock.

Binding rules: `.cursor/rules/design-anti-vibecode.mdc`, `android.mdc`, `code-quality.mdc`, `core.mdc`, PRD sections 12, 13, 16, 18.

Implementation status as of 2026-09-03 (phases from the phase plan in section 14): A design (this file) done. B theme, brand, icon, splash, settings store done. C mode lamp, five-state filter modes (ADR 006), metre-true halo, heading cone, puck interpolation, route casing, matched road overlay, day and night sheets done. D status sheet, E judge overlay, F first run, offline areas, settings and about screens, G trips: not implemented. Sections below carry a one-line status where the work is not in the tree.

## 1. Thesis

DriftZero is a field instrument that keeps a blue own-vehicle mark honest when satellites cannot see the phone. In the first 10 seconds the driver sees a street map, a solid blue puck with a heading cone, a green lamp that reads `GNSS 0.4 s`, and a `Where to?` field. In a tunnel the lamp turns amber and reads `Dead reckoning 14 s`, the puck keeps moving on the matched road, the halo around it grows in true metres, and one line says why: `No fix for 14 s`. When GNSS returns the lamp reads `Reacquiring`, the puck slides to the corrected position over a bounded interval instead of teleporting, and the halo shrinks. The map is the product; every other surface exists to state confidence and the reason for it.

## 2. Why India needs this

India operates NavIC, its own regional constellation, and GAGAN, its GPS augmentation for aviation. Neither helps a phone that cannot see the sky. Civil NavIC signals are in L5 and S band, with an interoperable L1 signal only from the NVS-01 satellite onward (launched 29 May 2023), and phone support depends on the chipset: Qualcomm and MediaTek added NavIC to selected SoCs, Qualcomm announced L1 support for select platforms for the second half of 2024 with commercial devices in 2025, and the government told Parliament that "60+" smartphone models support NavIC ([ISRO ICG-17 update](https://www.unoosa.org/documents/pdf/icg/2023/ICG-17/icg17.01.05.pdf), [GPS World on Qualcomm L1](https://www.gpsworld.com/qualcomm-chipsets-support-navic-l1-signals/), [Swarajya on the Parliament reply](https://swarajyamag.com/news-brief/indias-desi-gps-navic-capability-present-in-over-10000-trains-30000-fishing-vessels-15-lakh-vehicles-and-over-60-smartphones)). ISRO's own FAQ states that NavIC does not provide integrity information and does not support safety-of-life operations, and that GAGAN augments GPS ([ISRO Navigation FAQ](https://www.isro.gov.in/FAQ_Navigation.html), summarised in `docs/refs/NAVIC.md`). A perfect constellation still cannot see inside the 9.02 km Atal Tunnel ([BRO](https://marvels.bro.gov.in/AtalTunnel/OverView)), the 2.07 km twin tunnels of the Mumbai Coastal Road ([Wikipedia](https://en.wikipedia.org/wiki/Coastal_Road_(Mumbai))), a mall basement in Bengaluru, a Delhi underpass, a Western Ghats forest road, or a Hyderabad flyover stack where the matcher must choose between deck and service road. Google Maps' answer on Android is Bluetooth tunnel beacons, which only work in tunnels that have beacons installed ([The Verge, Jan 2024](https://www.theverge.com/2024/1/16/24039896/google-maps-android-tunnels-bluetooth-beacons)). DriftZero is the layer that carries any GNSS the phone already uses (GPS, NavIC, GAGAN-corrected GPS) through those gaps on an ordinary Android phone, offline, with a confidence radius in metres and a plain reason for every degradation. It shows NavIC visibility as a count when Android reports `CONSTELLATION_IRNSS` ([Android GnssStatus](https://developer.android.com/reference/android/location/GnssStatus#CONSTELLATION_IRNSS)). It does not claim integrity, anti-jam, or anti-spoof.

This paragraph is the About sheet copy (`about_india`) and the first pitch slide.

## 3. What adjacent products do, and what DriftZero takes

| Product | Pattern observed | Source | DriftZero decision |
|---|---|---|---|
| Google Maps | Blue dot, light blue circle "you could be anywhere within", grey dot when location is unknown, calibration prompt when the beam is wide | [Google Maps Help](https://support.google.com/maps/answer/2839911?co=GENIE.Platform%3DAndroid&hl=en) | Adopt blue puck and metre-true halo. Reject the silent grey dot: DriftZero names the mode and the reason instead. Adopt "beam narrows as heading certainty improves" as the cone rule. |
| Google Maps and Waze tunnels | Bluetooth beacons, off by default, only where installed | [The Verge](https://www.theverge.com/2024/1/16/24039896/google-maps-android-tunnels-bluetooth-beacons) | Reject infrastructure dependence. DriftZero coasts on IMU plus road graph. |
| Apple Maps | Motion sensors and map matching continue the dot through tunnels, with user reports of freezes and rerouting | [Apple discussions](https://discussions.apple.com/thread/253838387) | Adopt continuation. Add what Apple hides: the mode lamp, the radius, and no reroute while `DEAD_RECKONING` or `LOW_CONFIDENCE`. |
| Garmin G1000 | Yellow `DR` annunciation over the own-aircraft symbol and on the HSI, `GPS NAV LOST` alert, deviation bar removed, automatic return when GPS is valid | [G1000 guide, Dead Reckoning](https://garmin.manymanuals.com/navigators/software-version-0552-05/users-guide-17932/73) | Adopt directly. The lamp is the annunciation, amber not yellow for contrast, the reason line is the alert, and turn guidance suppresses lane-level claims in DR. Automatic return via `REACQUIRING`. |
| Emlid Flow, survey controllers | Solution status `SINGLE`, `FLOAT`, `FIX` shown at top right at all times, RMS shown next to the value, collection gated on fix type | [Emlid docs](https://docs.emlid.com/emlid-studio/reference/glossary/) | Adopt the always-visible status word plus number pairing (`12 m 95%`). Adopt gating: route recalculation and lane text only in `GNSS_FUSED`. |
| OsmAnd, Organic Maps | Region downloads listed with size, stored on device, work fully offline, no expiry (Organic) | [OsmAnd](https://github.com/osmandapp/Osmand), [Organic Maps](https://github.com/organicmaps/organicmaps/) | Adopt the installed-packs list with exact bytes. Add honesty they do not need: a line that says streets and routing still come from the network until a pack is Ready. |

Rejected across the board: Material You purple, translucent panels over the map, badges above headlines, decorative motion, a boolean "GPS on/off" chip that hides four of the five modes.

## 4. Information architecture

One Activity, one `Screen` state, no navigation library.

```text
FirstRun (once, or when no valid calibration profile)
  Location permission -> Sensor check -> Mount and calibrate
Map (home)
  StreetMap (puck, halo, cone, route casing and fill, matched road, destination)
  WhereTo search pill and suggestion list
  ModeLamp (top left, under the pill)
  MapControls (compass, locate; right edge above the sheet)
  BottomInstrument
    collapsed line: lamp dot, mode word, speed, `12 m 95%`
    route rows when routing: destination, distance, ETA, Stop
    expanded rows: reason, GNSS age, last trusted fix, confidence radius, heading,
      map match, sensors, model, NavIC, area pack
    links: Trips, Offline areas, Settings, About
    hidden: long-press the collapsed line opens Judge
  Judge overlay (trails, 60 s mode strip, Hold GNSS, output interval)
Trips (list, replay, delete, export)
Offline areas (installed, queued, sideload path, network notice)
Settings (units, theme, reduce motion, cues, data and privacy)
About (wordmark, India paragraph, limitations, third-party notices)
```

Back always returns to Map. Nothing opens a modal over the map while a route is active except the Stop confirmation.

### 4.1 First run

Not implemented as of 2026-09-03; see phase plan (F). `SettingsStore.firstRunDone` exists and is never set.

Three full-screen steps on the chassis colour. Each has a title (Plex Sans semibold 24), one paragraph, one primary button, and at most one secondary text button.

1. Location. Title `Precise location`. Body `The blue mark starts from a satellite fix. Precise location stays on this phone.` Primary `Allow`. Secondary `Not now`. Denied path: body changes to `Map opens without a fix. The blue mark waits for location.` Primary `Continue`.
2. Sensors. Title `Phone sensors`. Rows: `Accelerometer`, `Gyroscope`, `Magnetometer`, `GNSS` each with `Present` or `Missing` in mono. Missing gyroscope adds a caution row `Dead reckoning limited to speed and last heading`. Missing accelerometer blocks dead reckoning: caution `Dead reckoning unavailable on this phone`. Primary `Continue`.
3. Mount and calibrate. Title `Mount the phone`. Body `Fix the phone in its mount. Keep the vehicle still for 5 seconds.` A progress bar (0 to 100, mono) and a quality word from `CalibrationResult`: `Still`, `Moving`, `Done 0.3 deg`. Primary `Start`, then `Done` when complete. Secondary `Skip` only when a valid profile exists for the current mount (gravity direction within 10 degrees of the stored profile). Never asks the driver to do this while moving: the step refuses to start when speed exceeds 1 m/s and says `Stop the vehicle first`.

### 4.2 Main map

Full-bleed `StreetMap`. Chrome is opaque and sits on the 8 dp grid: search pill at `statusBars + 16 dp`, lamp at `pill bottom + 8 dp` left, controls right edge `16 dp`, bottom instrument at `navigationBars + 0` full width with 16 dp internal padding.

### 4.3 Mode lamp

48 dp tall chip, `panel` fill, 4 dp corner, 16 dp horizontal padding. Left: 10 dp filled dot in the lamp colour. Middle: mode word in `label` (Plex Sans medium 14). Right: readout in `readout` mono 14, `0.4 s` age or `no fix 14 s`. Tap expands the sheet. Long-press toggles Hold GNSS with one haptic tick; disabled above 8 m/s unless Judge is open (PRD 13).

Implemented: `ModeLamp` in `TravelChrome.kt` fed by `lampFor(state, permission)` (`ModeLamp.kt`) and `lampReadoutText` (`InstrumentStrings.kt`). Age formats through `InstrumentFormat.formatSeconds` (`0.4 s`, `14 s`, `3 min 20 s`). Long-press calls `PoseStore.toggleSimulateGpsOff` when speed is under `HOLD_MAX_SPEED_MPS` (8 m/s) or already held; the haptic tick is not implemented. Tap is wired to a no-op until the sheet exists (phase D). The lamp hides while the suggestion list is open.

| Mode | Word | Dot | Readout | Halo stroke |
|---|---|---|---|---|
| `GNSS_FUSED` | `GNSS` | lamp ok | `0.4 s` | solid, marker blue |
| `GNSS_DEGRADED` | `Assisted` | lamp caution | `1.6 s` | solid, lamp caution |
| `DEAD_RECKONING` | `Dead reckoning` | lamp caution | `14 s` | dashed 6/4, lamp caution |
| `REACQUIRING` | `Reacquiring` | lamp caution | `0.8 s` | dashed 6/4, lamp caution |
| `LOW_CONFIDENCE` | `Low confidence` | lamp alert | `48 s` | dashed 6/4, lamp alert |

The dash is the non-colour cue. The word is the TalkBack label.

### 4.4 Puck, halo, cone

- Puck: 22 dp marker blue disk, 3 dp paper ring (`StreetMapConfig.PUCK_DISK_DP`, `PUCK_RING_DP`). Implemented as two MapLibre `CircleLayer`s (`driftzero-puck-ring`, `driftzero-puck-disk`) on the `driftzero-puck` GeoJSON source. The MapLibre `LocationComponent` is no longer used: it animated on its own clock and drew a pixel accuracy disc, so the puck, halo, and cone could not share one frame. Its `LocationComponentNotInitializedException` guard and `StreetMapLocationTest` went with it.
- Halo: GeoJSON 48-vertex polygon of radius `uncertainty.horizontal95` metres built with `Wgs84.offsetMetres` (`MapGeometry.circle`), so it is true under every zoom. Fill is marker blue at alpha 0.14 day, 0.18 night (`driftzero-halo-fill`). Stroke 1.5 dp (`driftzero-halo-line`), colour and dash from the table above; dash array `4, 2.67` line widths, which is 6 dp on 4 dp. Minimum radius drawn is 3 m so it never hides under the puck; no maximum, a 400 m halo is information.
- Cone: GeoJSON wedge from the puck centre (`MapGeometry.wedge`, `driftzero-cone-fill`), length 36 dp converted to metres at the current zoom with `MapGeometry.metresPerDp(lat, zoom) = 40075016.686 / 512 * cos(lat) / 2^zoom`. MapLibre zoom uses 512 px tiles and Android dp equals one map pixel, so there is no 256 px constant and no density division; an earlier draft of this paragraph said otherwise. Half-angle `clamp(heading95Rad, 5 deg, 60 deg)`. Fill marker blue alpha 0.35. Heading from `puckHeadingRad()` (matched heading when `MATCHED`, else filter heading). Hidden when speed is below 0.5 m/s and heading95 exceeds 60 degrees, because then the wedge would be a disk.
- Display pose: `PuckInterpolator` (`PuckInterpolator.kt`) runs at frame rate from a `withFrameNanos` loop in `TravelMapScreen`, moves from the last drawn pose toward the newest `NavigationState` and reaches it within 100 ms (`DEFAULT_CATCH_UP_NS`). Heading is interpolated on the shortest arc, longitude across the antimeridian. Reduce motion draws the newest state directly. The interpolator never writes back into `NavigationState`. `StreetMapSession.applyDisplayPose` skips the frame when puck, zoom, and lamp are unchanged and the camera is not following.

### 4.5 Route and turn guidance dock

Implemented: route casing and fill layers, destination circle with paper stroke, no reroute (OSRM is called once per pick). Not implemented as of 2026-09-03; see phase plan (D): moving the route rows into the bottom instrument. Today `RouteDock` still shows destination, distance, ETA, speed (in the unit from `SettingsStore`), and Stop.

The route rows live inside the bottom instrument while a route exists: destination name (`body`), `12.4 km, 18 min` (`label`), and a `Stop` secondary button 48 dp. Speed appears in the collapsed line, not in the dock. The route line is drawn as two MapLibre layers: casing 9 dp `routeCasing` under fill 5 dp `routeFill`. The destination is a 7 dp marker blue circle with a 3 dp paper stroke, the same stroke as the puck ring. While mode is `DEAD_RECKONING` or `LOW_CONFIDENCE` the app does not request a new route and the distance row keeps the last value with the reason line above it. OSRM is only called when a destination is picked.

### 4.6 Bottom instrument (status sheet)

Not implemented as of 2026-09-03; see phase plan (D). The reason line is ready: `modeReason(state)` in `ModeLamp.kt` and `reasonText` in `InstrumentStrings.kt` have no caller yet.

Opaque `panel`, 8 dp top corners, hairline top edge. Drag handle 32 x 4 dp, `hairline`.

Collapsed line, 56 dp: lamp dot 10 dp, mode word (`label`), speed (`readoutLarge` mono 20, `34 km/h`, hidden below 0.8 m/s with hysteresis at 1.4 m/s), `12 m 95%` (`readout`).

Expanded rows, each 48 dp, label `caption` left, value mono right:

| Row | Value | Source |
|---|---|---|
| Reason (only when degraded) | `No fix for 14 s`, `GNSS held`, `Radius 130 m over 120 m limit`, `Fix accuracy 45 m`, `Phone moved, realign when stopped`, `IMU gap 0.6 s` | `ModeReason.of(state, held, mountOk)` |
| GNSS age | `0.4 s` | `PoseStore.lastGnssSeenNs` |
| Last trusted fix | `12 s ago` | `gnssHealth.lastTrustedFixAgeS` |
| Confidence radius | `12 m 95%` | `uncertainty.horizontal95` |
| Heading | `247 deg, 6 deg 95%` | `motion.heading`, `uncertainty.heading95Rad` |
| Map match | `Matched 0.91`, `Ambiguous 0.52`, `Off road`, `No area pack` | `mapMatch.status`, `mapMatch.confidence`, `roadSegmentId` |
| Sensors | `Accel, gyro ok`, `No IMU 0.6 s`, `ZUPT`, `NHC` | `health.sensorOk`, `health.flags` |
| Model | `Speed student`, `Heuristic speed`, `Filter only` | `health.modelOk`, `FLAG_MOTION_PSEUDO`, `provenance.modelVersion` |
| NavIC | `3 visible, 2 used`, `Not reported by chipset` | `NavicMonitor.visibility` |
| Area pack | `example-india, 412 MB`, `None. Streets and routing from network` | `AreaPackStore.active()` |
| Output rate | `p95 gap 108 ms` | `TickIntervals` ring buffer, only when 20+ samples exist |

Links row at the bottom: `Trips`, `Offline areas`, `Settings`, `About`. Long-press on the collapsed line opens Judge.

### 4.7 Judge overlay

Not implemented as of 2026-09-03; see phase plan (E). Only Hold GNSS exists, as the lamp long-press.

Opens over the map; the sheet collapses. Adds: raw GNSS trail (dotted 2 dp `inkDim`, 600 points), fused trail (solid 3 dp marker blue, 600 points), a 60 s mode strip (one 2 dp column per 10 Hz sample, lamp colour), a `Hold GNSS` toggle button with `Held 32 s, 410 m dead reckoned` while held, `p95 output gap 108 ms` from real tick intervals, and a `Close` button. Nothing in Judge writes into the filter. Raw GNSS is display only and is labelled `Phone GNSS (not an input while held)`. Ground truth from a dataset is never drawn on the phone; that belongs to the desktop report.

### 4.8 Trips

Not implemented as of 2026-09-03; see phase plan (G).

List of recorded trips: start time, duration, distance, `DR 14 %` share of samples not `GNSS_FUSED`. Row actions: `Replay`, `Delete`. `Export` asks for consent in a dialog that names the contents (`NavigationState at 10 Hz, sensor frames, no contacts, no identifiers`) before an Android share sheet. Replay feeds the recorded `SensorFrame` stream to a fresh `DeadReckoningEngine` and draws the result as a trail. Recording is opt-in from Settings and shown by a `REC` mono tag in the sheet.

### 4.9 Offline areas

Not implemented as of 2026-09-03; see phase plan (F). `AreaPackStore.styleUri(pack, night)` now returns the night sheet when no pack owns rendering.

Two lists from `AreaPackStore`: Installed (id, label, bbox, bytes, `Ready` or `Corrupt`) and Queued (id, bbox). Header notice while no Ready pack: `Streets from OpenFreeMap and routes from OSRM use the network until a pack is installed.` Sideload row shows the exact directory path `files/area-packs/installed/<id>/` and the required files `manifest.json`, `tiles.pmtiles`, `graph.bin`, `style.json`. No expiry field exists in `AreaPackManifest` today; the row shows `No expiry recorded` rather than inventing a date.

### 4.10 Settings

Not implemented as of 2026-09-03 as a screen; see phase plan (F). The store exists: `settings/SettingsStore.kt` (`AppSettings`, `ThemeMode`, `SpeedUnit`, `MotionMode`, SharedPreferences `driftzero_settings`), and `MainActivity` already reads theme, reduce motion, and units from it.

Rows with a toggle or a segmented choice: Units (`km/h`, `mph`), Theme (`System`, `Day`, `Night`), Reduce motion (`System`, `On`), Haptic cue on mode change (on/off), Audio cue on Low confidence (on/off), Record trips (on/off), Delete all trips (destructive, confirm). A footer states `Logs stay on this phone. Export asks first.`

### 4.11 About

Not implemented as of 2026-09-03; see phase plan (F). Re-check the section 2 paragraph against `docs/refs/SIH26168_EVIDENCE.md` before it becomes `about_india`.

Wordmark, version and core version, the India paragraph, Limitations (`Not certified for safety-of-life or autonomous control`, `NavIC count is a chipset report, not integrity`, `Streets and routing use the network until a pack is installed`, `Drift grows without GNSS; the radius shows how much`), and a link to third-party notices (`NOTICE.md`).

## 5. Controls

All targets at least 48 dp. Pressed state is a darker solid fill (`panelPressed`) plus scale 0.97; no opacity fade. Marks are single 2 dp strokes or solid fills, no icon font.

| Control | Label | Mark | Position | Action | Blocked while moving |
|---|---|---|---|---|---|
| Where to? | `Destination` field | destination pin (circle plus stem) | top, full width | Photon then Nominatim search | keyboard allowed; the driver is expected to be stopped, no lockout |
| Clear | `Clear` | X | inside pill, right | clears query | no |
| Mode lamp | mode word | filled dot | under pill, left | tap expands sheet (no-op until phase D), long-press Hold GNSS | long-press disabled above 8 m/s outside Judge |
| Compass | `North` | ring plus needle | right, above locate, appears when bearing over 8 deg | reset bearing | no |
| Locate | `My location` | ring plus blue dot | right, above sheet | recenter and follow | no |
| Sheet handle | `Status` | 32 x 4 bar | sheet top | expand or collapse | no |
| Stop | `Stop` | text | route rows, right | clears route, confirm dialog `Stop route?` | no |
| Hold GNSS | `Hold GNSS` / `Resume GNSS` | text | Judge (today: lamp long-press only) | toggles `PoseStore.setSimulateGpsOff` | Judge only |
| Close judge | `Close` | X | Judge top right | closes overlay | no |
| Replay, Delete, Export | text | none | Trips rows | as named; Delete and Export confirm | no |
| Primary button | text | none | first run, dialogs | as named | first run refuses to start calibration above 1 m/s |

## 6. States and transitions

- Entering a blackout: `gnssHealth.lastTrustedFixAgeS` passes `STALE_AFTER_S` (2 s) or the hold is armed. Lamp goes amber `Dead reckoning`, halo stroke turns dashed, haptic tick once (if enabled), reason `No fix for 2 s` counts up. Route rows freeze their last value. No reroute.
- Growing uncertainty: the halo polygon is rebuilt each frame from `horizontal95`. The cone widens with `heading95Rad`. Speed keeps showing because it is estimated, not measured; the sheet says so with the Model row.
- `LOW_CONFIDENCE`: `horizontal95 > 120 m` (`InsConfig.lowConfidenceRadiusM`). Lamp red, reason `Radius 130 m over 120 m limit`, optional audio cue once, lane-level text is suppressed, the route line stays but the puck is not snapped (`puckLatitudeDeg` only uses the matched pose when `MATCHED`).
- `GNSS_DEGRADED` and `REACQUIRING`: produced by the filter after this change (ADR 006). Degraded when the last accepted fix is fresher than 2 s but its accuracy exceeds 30 m or a fix was innovation-gated in the last 5 s. Reacquiring when a plausible fix arrives after a coast and fewer than 3 consecutive gated-in fixes have been accepted. The display pose blends via `PuckInterpolator`; the filter itself applies the Kalman correction in one step, which is correct, and the interpolator bounds what the eye sees to 0.5 s.
- Phone remount: `MountMonitor` compares low-pass gravity direction to the calibration profile; more than 25 degrees for 2 s sets `mountOk = false`, reason `Phone moved, realign when stopped`, calibration step offered again when speed is below 1 m/s.
- Missing sensors: `FLAG_NO_IMU` when no IMU sample for 0.5 s. Sensors row reads `No IMU 0.6 s`; if gyro is absent at first run the caution is stated up front.
- No area pack: Map match row `No area pack`, Offline areas header notice, matched-road highlight absent. Puck is the ESKF pose.
- No network: search returns `Search needs a network connection.`, routing returns `Can't make a route from here.`, tiles stop updating; the engine, lamp, halo, and sheet are unaffected.
- Permission denied: map opens at the last fix or the world view, lamp reads `No location permission` in alert colour, locate button opens the permission request again.

## 7. Copy deck

Nouns and numbers. Periods and commas. Keys are `strings.xml` names. Format strings use `%1$s`.

In `strings.xml` as of 2026-09-03: `app_name` through `search_network`, `action_hold_gnss`, `action_resume_gnss`, the seven `mode_*` keys, `mode_lamp_label`, `readout_no_fix` (`no fix %1$s`, the number already carries its unit), `readout_radius` (`%1$s 95%%`), and the `reason_*` keys as implemented: `reason_no_fix` (`No fix for %1$s`), `reason_held`, `reason_radius_limit` (`Radius %1$s over %2$s limit`), `reason_fix_accuracy` (`Fix accuracy over %1$s`, because the accepted fix accuracy is not on `NavigationState`, only the 30 m rule is), `reason_gated_fix` (`Fix disagreed with estimate`, new), `reason_reacquiring` (`Fix back, confirming`, because the reacquire count is not on the schema), `reason_imu_gap` (`No IMU data`, the gap length is not on the schema). `reason_remount` is dropped until a mount monitor exists. Every other key below is still to add.

| Key | Text |
|---|---|
| `app_name` | DriftZero |
| `destination_hint` | Where to? |
| `destination_field` | Destination |
| `action_locate` | My location |
| `action_compass` | North |
| `action_stop` | Stop |
| `action_clear` | Clear |
| `action_close` | Close |
| `action_allow` | Allow |
| `action_not_now` | Not now |
| `action_continue` | Continue |
| `action_start` | Start |
| `action_done` | Done |
| `action_skip` | Skip |
| `action_back` | Back |
| `action_hold_gnss` | Hold GNSS |
| `action_resume_gnss` | Resume GNSS |
| `action_replay` | Replay |
| `action_delete` | Delete |
| `action_export` | Export |
| `action_cancel` | Cancel |
| `search_empty` | No places found. |
| `route_fail` | Can't make a route from here. |
| `search_network` | Search needs a network connection. |
| `stop_route_title` | Stop route? |
| `mode_gnss` | GNSS |
| `mode_assisted` | Assisted |
| `mode_dead_reckoning` | Dead reckoning |
| `mode_reacquiring` | Reacquiring |
| `mode_low_confidence` | Low confidence |
| `mode_no_permission` | No location permission |
| `mode_waiting_fix` | Waiting for fix |
| `readout_age_s` | %1$s s |
| `readout_no_fix_s` | no fix %1$s s |
| `readout_radius` | %1$s m 95%% |
| `readout_heading` | %1$s deg, %2$s deg 95%% |
| `reason_no_fix` | No fix for %1$s s |
| `reason_held` | GNSS held |
| `reason_radius_limit` | Radius %1$s m over %2$s m limit |
| `reason_fix_accuracy` | Fix accuracy %1$s m |
| `reason_remount` | Phone moved, realign when stopped |
| `reason_imu_gap` | IMU gap %1$s s |
| `reason_reacquiring` | Checking %1$s of 3 fixes |
| `row_reason` | Reason |
| `row_gnss_age` | GNSS age |
| `row_last_trusted` | Last trusted fix |
| `row_radius` | Confidence radius |
| `row_heading` | Heading |
| `row_map_match` | Map match |
| `row_sensors` | Sensors |
| `row_model` | Model |
| `row_navic` | NavIC |
| `row_area_pack` | Area pack |
| `row_output_rate` | Output rate |
| `value_ago_s` | %1$s s ago |
| `value_matched` | Matched %1$s |
| `value_ambiguous` | Ambiguous %1$s |
| `value_off_road` | Off road |
| `value_no_map` | No area pack |
| `value_sensors_ok` | Accel, gyro ok |
| `value_no_imu` | No IMU %1$s s |
| `value_model_student` | Speed student |
| `value_model_heuristic` | Heuristic speed |
| `value_model_filter` | Filter only |
| `value_navic_counts` | %1$d visible, %2$d used |
| `value_navic_none` | Not reported by chipset |
| `value_pack_none` | None. Streets and routing from network |
| `value_pack_ready` | %1$s, %2$s |
| `value_p95_gap` | p95 gap %1$s ms |
| `value_present` | Present |
| `value_missing` | Missing |
| `link_trips` | Trips |
| `link_offline` | Offline areas |
| `link_settings` | Settings |
| `link_about` | About |
| `judge_title` | Judge |
| `judge_raw_trail` | Phone GNSS (not an input while held) |
| `judge_fused_trail` | DriftZero fused |
| `judge_held_status` | Held %1$s s, %2$s m dead reckoned |
| `judge_mode_strip` | Mode, last 60 s |
| `firstrun_location_title` | Precise location |
| `firstrun_location_body` | The blue mark starts from a satellite fix. Precise location stays on this phone. |
| `firstrun_location_denied` | Map opens without a fix. The blue mark waits for location. |
| `firstrun_sensors_title` | Phone sensors |
| `firstrun_sensor_accel` | Accelerometer |
| `firstrun_sensor_gyro` | Gyroscope |
| `firstrun_sensor_mag` | Magnetometer |
| `firstrun_sensor_gnss` | GNSS |
| `firstrun_no_gyro` | Dead reckoning limited to speed and last heading |
| `firstrun_no_accel` | Dead reckoning unavailable on this phone |
| `firstrun_mount_title` | Mount the phone |
| `firstrun_mount_body` | Fix the phone in its mount. Keep the vehicle still for 5 seconds. |
| `firstrun_mount_moving` | Stop the vehicle first |
| `calib_still` | Still |
| `calib_moving` | Moving |
| `calib_done` | Done, %1$s deg tilt |
| `calib_failed_short` | Too short, start again |
| `calib_failed_moving` | Vehicle moved, start again |
| `calib_failed_no_gyro` | No gyroscope |
| `calib_progress` | %1$d%% |
| `trips_title` | Trips |
| `trips_empty` | No trips recorded. Turn on Record trips in Settings. |
| `trips_row` | %1$s, %2$s km, DR %3$d%% |
| `trips_delete_title` | Delete this trip? |
| `trips_export_title` | Export this trip? |
| `trips_export_body` | The file contains NavigationState at 10 Hz and sensor frames. No contacts, no identifiers. |
| `trips_recording` | REC |
| `offline_title` | Offline areas |
| `offline_notice` | Streets from OpenFreeMap and routes from OSRM use the network until a pack is installed. |
| `offline_installed` | Installed |
| `offline_queued` | Queued |
| `offline_none` | None |
| `offline_sideload` | Sideload directory |
| `offline_sideload_files` | manifest.json, tiles.pmtiles, graph.bin, style.json |
| `offline_no_expiry` | No expiry recorded |
| `offline_state_ready` | Ready |
| `offline_state_corrupt` | Corrupt |
| `offline_state_queued` | Queued |
| `settings_title` | Settings |
| `settings_units` | Units |
| `settings_units_kmh` | km/h |
| `settings_units_mph` | mph |
| `settings_theme` | Theme |
| `settings_theme_system` | System |
| `settings_theme_day` | Day |
| `settings_theme_night` | Night |
| `settings_reduce_motion` | Reduce motion |
| `settings_haptic` | Haptic tick on mode change |
| `settings_audio` | Sound on Low confidence |
| `settings_record` | Record trips |
| `settings_delete_all` | Delete all trips |
| `settings_privacy` | Logs stay on this phone. Export asks first. |
| `about_title` | About |
| `about_version` | Version %1$s, core %2$s |
| `about_india` | (section 2 paragraph) |
| `about_limits_title` | Limitations |
| `about_limit_1` | Not certified for safety-of-life or autonomous control. |
| `about_limit_2` | NavIC count is a chipset report, not integrity. |
| `about_limit_3` | Streets and routing use the network until a pack is installed. |
| `about_limit_4` | Drift grows without GNSS. The radius shows how much. |
| `about_notices` | Third-party notices |

## 8. Motion

Every animation has one purpose. Nothing animates on a keyboard action. Reduce motion (system animator scale 0 or the Settings toggle) removes movement and keeps opacity. Implemented rows: puck, halo, cone, lamp colour, press, suggestion list. Sheet expand and Judge rows wait on phases D and E. Reduce motion is `rememberSystemReduceMotion() || settings.motion == REDUCED`, provided through `InstrumentTheme.reduceMotion`.

| Element | Purpose | Spec | Interrupt | Exit | Reduce motion |
|---|---|---|---|---|---|
| Puck, halo, cone | prevent jarring 10 Hz steps | per-frame interpolation, catch-up within `min(0.1 s, 0.5 s)` of target, shortest-arc heading | retarget each new state, no restart | none | draw newest state |
| Halo radius | state indication | follows interpolated radius, same clock | same | none | same as puck |
| Lamp colour and word | state indication | colour tween 120 ms `TravelEaseOut`; word swaps instantly | retarget | none | instant |
| Sheet expand | spatial consistency | height via `animateContentSize` 200 ms `TravelEaseOut` | retarget | collapse 160 ms | instant |
| Route draw | prevent teleport of a 12 km line | none. The line appears in one frame; camera `fitRoute` eases 250 ms | camera interrupt | none | camera jump |
| Press | feedback | scale 0.97 120 ms, solid darker fill | retarget | 120 ms | fill only |
| Suggestion list | prevent jarring | fade plus scale 0.95 from top, 120 ms | existing | same path | fade 0 ms |
| Judge trails | data | none, redraw on data | none | none | none |

Nothing pulses. Nothing fades in on scroll. No spinner: loading states are words (`Searching`, `Routing`).

## 9. Map style

Day sheet: OpenFreeMap liberty (current). Night sheet: OpenFreeMap dark, same hosted tiles, not the OSM tile server. The theme setting selects the sheet; System follows `uiMode`. A Ready area pack with `style.json` overrides both until a night variant ships in the pack (`style.json` today, `style-night.json` when built).

Route: casing `routeCasing` 9 dp under fill `routeFill` 5 dp, round caps and joins, inserted below the first symbol layer so labels stay legible. Matched road: when `mapMatch.status == MATCHED`, the full `GraphEdge.points` polyline of `roadSegmentId` is drawn 7 dp in `routeFill` at alpha 0.35 under the route. Destination: 7 dp marker blue circle, 3 dp paper stroke.

Camera: north-up at rest. Following moves the camera to the interpolated puck each frame (`StreetMapSession.followCamera`; there is no LocationComponent camera mode any more) so north stays up; heading is the cone. Zoom by speed while following (`MapGeometry.zoomForSpeed`): 17 below 5 m/s, 16 below 15 m/s, 15 above, with 1 m/s hysteresis so it never hunts, eased 500 ms when the band changes. A gesture (`REASON_API_GESTURE`) stops following; Locate resumes it. Matched road, casing, night sheet: implemented (`driftzero-matched-line`, `driftzero-route-casing`, `StreetMapConfig.STYLE_DARK`, `hostedStyle(night)`).

## 10. Accessibility

- Contrast pairs asserted in `InstrumentContrastTest`, body text 7:1, secondary 4.5:1, non-text marks 3:1, for both palettes: `ink/chassis`, `ink/panel`, `ink/well`, `ink/panelPressed`, `inkDim/chassis`, `inkDim/panel`, `lampOk/panel`, `lampCaution/panel`, `lampAlert/panel`, `marker/panel`, `paper/marker` (destination stroke on blue).
- Non-colour cues: mode word always next to the dot; halo dash pattern; reason line; haptic tick.
- Touch: 48 dp minimum, 8 dp between targets, right-edge column reachable with the right thumb in a windshield mount, sheet handle full width.
- TalkBack: every icon control has `contentDescription`; the lamp announces `Dead reckoning, no fix 14 seconds`; the sheet handle announces `Status, collapsed` or `expanded`; the map view is `Map`.
- Type scales with system font size up to 1.3x without clipping; readouts use `tnum`.

## 11. Features beyond search, prioritised for 18 days

| Order | Feature | User problem | SIH evidence | Engine data | Days | Toy trap avoided |
|---|---|---|---|---|---|---|
| 1 | Theme, brand, icon | app has no identity, white flash, default purple | SIH-22 | none | 1 | no gradient, no glow |
| 2 | Five-state lamp plus age | driver cannot tell estimate from fix | SIH-06, SIH-21 | `mode`, `lastTrustedFixAgeS` (exists); `GNSS_DEGRADED`, `REACQUIRING` production (ADR 006) | 1.5 | boolean GPS chip hid four modes |
| 3 | Metre-true halo and cone | uncertainty was a fixed alpha disc | SIH-22, SIH-07 | `horizontal95`, `heading95Rad` (exist) | 1 | fake smoothness by hiding the radius |
| 4 | Puck interpolation | 10 Hz steps look broken | SIH-22 | display only | 0.5 | interpolation must not touch logs |
| 5 | Status sheet with reason | "why is it amber" | SIH-06, SIH-22 | exists plus `lastGnssSeenNs` | 1 | decorative badges |
| 6 | Route casing, matched road, night sheet | legibility on dark roads, judge sees the matcher | SIH-10 | `GraphEdge.points` via `PoseStore` | 1 | snapping the puck |
| 7 | Judge overlay | judges verify without adb | SIH-06, SIH-21, SIH-25 | ring buffers, tick intervals | 1.5 | drawing truth as an input |
| 8 | First run and calibration | FR-02 missing, mounts differ | SIH-17, SIH-09 | new `StationaryCalibrator`, `MountMonitor` | 2 | silent zeros for missing gyro |
| 9 | Offline areas, Settings, About | honesty about network, privacy | SIH-10, SIH-04 | `AreaPackStore` | 1.5 | pretending a pack exists |
| 10 | Trips record, replay, export | evidence and privacy control | SIH-13, SIH-22, NFR-07 | `ContractMaps`, `DeadReckoningEngine` | 2.5 | uploading by default |

Total 13.5 days, leaving margin for device testing before 2026-09-20.

## 12. Brand

Mark: a blue heading wedge carrying through a gap in a paper ring. The ring is the satellite lock; the gap is the tunnel; the wedge is the vehicle that keeps its heading through the gap. Reads at 48 dp because it is two solid shapes: a ring stroke and a notched wedge.

Geometry as implemented on a 108 unit adaptive canvas (safe zone is the 66 unit circle):

- Background: chassis `#0B0D0A`, full canvas.
- Ring: centre (54, 54), radius 22, stroke 5, paper `#F4F1E8`, butt caps, drawn as the 270 degree arc from east (76, 54) clockwise round to north (54, 32), leaving a 90 degree gap centred on north-east. Path `M76,54 A22,22 0 1 1 54,32`.
- Wedge: marker blue `#1E6BFF`, four points: tip at radius 32 on the 45 degree bearing (76.63, 31.37), tail corners at radius 12 on bearings 165 and 285 degrees ((59.07, 64.88) and (43.12, 48.93)), and a notch at the centre (54, 54). Path `M76.63,31.37 L59.07,64.88 L54,54 L43.12,48.93 Z`. The tip passes through the gap.
- Monochrome layer: ring arc and wedge in one colour for themed icons.
- Legacy and store: same drawing on `ic_launcher_foreground` with the `ic_launcher_background` solid chassis.

Wordmark: `DriftZero` in IBM Plex Sans semibold 24 sp, ink colour, no italics, no gradient, letter spacing default. In About it sits left of the 48 dp mark with 16 dp gap.

Colour usage: marker blue is reserved for own vehicle, route, mark. Lamp colours are reserved for state. Do not use blue for buttons or links; links are ink underlined by a hairline row.

Do: solid shapes, one accent, chassis or paper behind the mark. Do not: gradient, glow, shadow, outline-only mark, rotation animation of the mark, emoji next to the wordmark.

Files: `apps/android/app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`, `docs/brand/driftzero-mark.svg`.

## 13. Theme

Tokens live in `InstrumentPalette` (packed ARGB, tested) and `InstrumentTheme` (Compose roles). `themes.xml` and `values-night/themes.xml` mirror chassis, ink, and marker so the window matches before Compose draws. Splash uses `androidx.core:core-splashscreen` with the mark on chassis.

### Colour roles

| Role | Day | Night | Use |
|---|---|---|---|
| chassis | `#F2EFE9` | `#0B0D0A` | window, first run, full screens |
| panel | `#FFFFFF` | `#161A14` | pill, sheet, chips, rows |
| well | `#EBE7DF` | `#1F241D` | secondary button, inset |
| panelPressed | `#E8E6DC` | `#0F120E` | pressed fill |
| hairline | `#D4D0C8` | `#2E342B` | dividers, handle |
| ink | `#1A1C19` | `#F4F1E8` | body, readouts |
| inkDim | `#3D413A` | `#B8B4A8` | captions |
| lampOk | `#2E7D32` | `#4CCB5A` | GNSS |
| lampCaution | `#B86A00` | `#E6A317` | Assisted, Dead reckoning, Reacquiring |
| lampAlert | `#C62828` | `#FF5A4E` | Low confidence, permission |
| marker | `#1E6BFF` | `#1E6BFF` | puck, destination, mark |
| routeFill | `#1E6BFF` | `#1E6BFF` | route line |
| routeCasing | `#0E3E9C` | `#0E3E9C` | route casing |
| haloFill | marker at 0.14 | marker at 0.18 | halo interior |
| haloStroke | lamp colour of mode | same | halo edge |
| paper | `#FFFFFF` | `#F4F1E8` | puck ring, destination stroke |

### Type roles (IBM Plex, bundled)

| Role | Face | Size | Weight | Use |
|---|---|---|---|---|
| readoutLarge | Mono | 20 sp | semibold | speed |
| readout | Mono | 14 sp | medium | age, radius, heading, timestamps, row values |
| title | Sans | 24 sp | semibold | screen titles, wordmark |
| body | Sans | 16 sp | regular | paragraphs, place names |
| label | Sans | 14 sp | medium | mode word, buttons, row labels |
| caption | Sans | 12 sp | regular, inkDim | detail lines |

### Shape and component

Corners 4 dp (chips, buttons), 8 dp (sheet, lists), 24 dp (search pill only). Components: `PrimaryButton` (ink fill, chassis text, pressed inkDim), `SecondaryButton` (well fill, ink text, pressed panelPressed), `LampChip`, `InstrumentSheet`, `ListRow` (48 dp, label plus mono value), `TextField` (panel, hairline underline on focus), `Toggle` (32 x 20 track, ink knob, no ripple). All pressed states are solid fills plus scale 0.97.

Implemented as of 2026-09-03: colour roles (`InstrumentPaletteSet`, `InstrumentPalette.DAY` and `NIGHT`, `InstrumentColors`), type roles (`InstrumentType`), `DriftZeroTheme(night, reduceMotion)`, the CompositionLocals `LocalInstrumentColors`, `LocalInstrumentType`, `LocalInstrumentNight`, `LocalReduceMotion`, `values/colors.xml`, `values-night/colors.xml`, `values/themes.xml`, `values-night/themes.xml`, `Theme.DriftZero.Splash`, `travelClickable` pressed state, `ModeLamp`. Named components `PrimaryButton`, `SecondaryButton`, `InstrumentSheet`, `ListRow`, `TextField`, `Toggle` are not implemented; see phase plan (D, F). `InstrumentContrastTest` asserts the pairs in section 10 for both palettes.

## 14. Implementation plan (phase two)

Order matters; the build must be green after each step.

Status as of 2026-09-03: steps 1 to 5 done with the names noted in brackets; step 6 partial (`ModeLamp` done, `BottomInstrument`, `ListRow`, buttons, screen switching not started); steps 7 to 11 not started; step 12 partial (this file and ADR 006 written as `docs/adr/006-navigation-mode-machine.md`; `apps/android/README.md`, `demo/TESTER_SIDELOAD.md`, and the traceability cells untouched). Actual names: `InstrumentPaletteSet` with `InstrumentPalette.DAY` and `NIGHT` (step 1); `lampFor`, `modeReason`, `MapGeometry.circle`, `MapGeometry.wedge`, `MapGeometry.metresPerDp`, `MapGeometry.zoomForSpeed`, `PuckInterpolator`, `InstrumentFormat` (step 4; `RingBuffer` and `TickIntervals` not started); layer ids in `StreetMapConfig` (step 5; trail layers not started; the LocationComponent was removed rather than muted, so `LocationUpdate` and `accuracyAlpha` do not apply).

1. `InstrumentPalette.kt`: add `Night` object and `Day` object with the roles above, keep the contrast helpers. `InstrumentTheme.kt`: `InstrumentColors` gains routeFill, routeCasing, haloFill, haloStroke helper, paper; `InstrumentType` becomes readoutLarge, readout, title, body, label, caption; `DriftZeroTheme(night: Boolean)`. `themes.xml` plus `values-night/themes.xml` plus splash theme. Delete `Type.chip`, `Type.place`, `Type.detail`, `Type.note`, `Type.search` in favour of the six roles. Tests: `InstrumentContrastTest` for both palettes.
2. Icon: the four vector files, two mipmap XMLs, manifest, `docs/brand/driftzero-mark.svg`. Delete `drawable/ic_launcher.xml`.
3. Core: `NavigationModeMachine` inside `DeadReckoningFilter.poseAt` producing `GNSS_DEGRADED` and `REACQUIRING` with hysteresis and a `reacquireCount`; `ModeReasonInput` does not change the schema. Tests in `DeadReckoningFilterTest`. ADR 006.
4. UI pure functions with tests: `ModeLamp.of(mode)`, `ModeReason.of(...)`, `HaloGeometry.circle(lat, lon, radiusM, n)`, `HaloGeometry.cone(lat, lon, headingRad, halfAngleRad, lengthM)`, `MapScale.metresPerPixel(lat, zoom, density)`, `PuckInterpolator`, `RingBuffer<T>`, `TickIntervals.p95Ms()`, `InstrumentFormat` (speed, distance, eta, age, radius, heading, bytes). `TravelHud` is folded into `InstrumentFormat`; `TravelHud.gpsOn` and `TravelFix.gpsProviderOn` are deleted because the lamp replaces the boolean.
5. `StreetMap.kt`: new sources and layers `driftzero-halo`, `driftzero-halo-line`, `driftzero-cone`, `driftzero-route-casing`, `driftzero-matched`, `driftzero-trail-raw`, `driftzero-trail-fused`; `applyDisplayPose(display)` called per frame from a `withFrameNanos` loop in `TravelMapScreen`; `LocationUpdate.animationDuration(0)`; `accuracyAlpha(0f)`; `setStyleForTheme(night)`.
6. `TravelChrome.kt`: `ModeLamp`, `BottomInstrument` (replaces `RouteDock`), `ListRow`, buttons. `TravelMapScreen.kt`: wires lamp, sheet, judge, screen switching.
7. `PoseStore.kt`: `lastGnssSeenNs`, `mountOk`, raw and fused trail buffers (600), mode strip buffer (600), `TickIntervals`; `rememberPoseStore` records tick intervals.
8. Judge overlay `JudgeOverlay.kt`.
9. Core: `StationaryCalibrator`, `CalibrationProfile`, `CalibrationResult`, `MountMonitor` with tests. App: `CalibrationStore` (SharedPreferences JSON), `FirstRunScreen.kt`.
10. `OfflineAreasScreen.kt`, `SettingsScreen.kt` plus `SettingsStore`, `AboutScreen.kt`.
11. Trips: `TripRecorder`, `TripStore`, `TripsScreen.kt`, replay via `DeadReckoningEngine`.
12. Docs: this file, `docs/adr/006-mode-machine.md`, `apps/android/README.md`, `demo/TESTER_SIDELOAD.md`, SIH-06, SIH-10, SIH-17, SIH-22 cells.

Delete list: `TravelHud.gpsOn`, `TravelFix.gpsProviderOn` and its `providerEnabled` plumbing, `RouteDock`, the accuracy circle of `LocationComponent`, `drawable/ic_launcher.xml`, unused type roles, the `NavicSnapshot.chipLabel` string builder (the sheet formats counts from `navicVisible` and `navicUsed`).

Deleted as of 2026-09-03: `TravelHud.kt` and `TravelHudTest.kt` (folded into `InstrumentFormat`), `TravelFix` and `toTravelFix`, `routeOrigin` (the map session's `originOrNull` now prefers the drawn puck), `StreetMapLocationTest.kt` and the `locationComponentValueOrNull` guard, `drawable/ic_launcher.xml`, `own_vehicle_puck.xml`, `own_vehicle_puck_ring.xml`, `own_vehicle_heading.xml`, the GPS providers-changed receiver, `StreetMapConfig.MAP_LOAD_COLOR_ARGB`. Still present: `RouteDock`, `NavicSnapshot.chipLabel`.

## 15. Sources

- ISRO ICG-17 NavIC and GAGAN update: https://www.unoosa.org/documents/pdf/icg/2023/ICG-17/icg17.01.05.pdf
- ISRO Navigation FAQ: https://www.isro.gov.in/FAQ_Navigation.html
- GPS World, Qualcomm NavIC L1: https://www.gpsworld.com/qualcomm-chipsets-support-navic-l1-signals/
- Swarajya, Parliament reply on NavIC devices: https://swarajyamag.com/news-brief/indias-desi-gps-navic-capability-present-in-over-10000-trains-30000-fishing-vessels-15-lakh-vehicles-and-over-60-smartphones
- Android GnssStatus: https://developer.android.com/reference/android/location/GnssStatus#CONSTELLATION_IRNSS
- Atal Tunnel, BRO: https://marvels.bro.gov.in/AtalTunnel/OverView
- Mumbai Coastal Road: https://en.wikipedia.org/wiki/Coastal_Road_(Mumbai)
- Google Maps Help, location accuracy: https://support.google.com/maps/answer/2839911?co=GENIE.Platform%3DAndroid&hl=en
- The Verge, Google Maps tunnel beacons: https://www.theverge.com/2024/1/16/24039896/google-maps-android-tunnels-bluetooth-beacons
- Apple Support Communities, tunnel rerouting: https://discussions.apple.com/thread/253838387
- Garmin G1000 Dead Reckoning: https://garmin.manymanuals.com/navigators/software-version-0552-05/users-guide-17932/73
- Emlid glossary, solution status and RMS: https://docs.emlid.com/emlid-studio/reference/glossary/
- OsmAnd: https://github.com/osmandapp/Osmand
- Organic Maps: https://github.com/organicmaps/organicmaps/
- Android SplashScreen: https://developer.android.com/develop/ui/views/launch/splash-screen
- OpenFreeMap styles: https://tiles.openfreemap.org/styles/liberty and https://tiles.openfreemap.org/styles/dark
