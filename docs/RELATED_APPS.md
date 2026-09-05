# Related map apps: location puck, search, empty states, GPS lost

Status: **production-path UX specification**. Research-only in this commit (no Android code). The puck-fix sibling should copy the spec in §2 without reinterpretation.

Audience: Android/MapLibre implementer making DriftZero readable like Google Maps / Apple Maps on a **light OpenFreeMap** basemap under a **dark HUD**.

Problem this spec answers: a default MapLibre / small blue marker disappears on pale streets, sits under road labels, and gets covered by the SPEED panel. Consumer copy like `DESTINATION` reads as firmware, not as a map app.

## 1. Copy this: exact DriftZero puck spec

Do not implement a 12 dp Google-inner-dot or the MapLibre Compose default (`dotRadius = 6.dp` → **12 dp fill**). That size is the failure mode on OpenFreeMap Liberty/Bright/Positron roads.

| Token | Value | Do not use |
|---|---|---|
| Fill | `#1E6BFF` solid | `Color.Blue` (`#0000FF`), `#4285F4` (too light on water), gray while DR is alive |
| White ring | `#FFFFFF`, **3 dp** outside the fill | Hairline 1 dp stroke (illegible on white roads) |
| Fill diameter | **22 dp** | ≤16 dp fill |
| Outer diameter (fill + ring) | **28 dp** | MapLibre Compose default 12+3+3 shadow |
| Dark stem / ground contact | Ellipse **20×8 dp**, offset **+4 dp Y**, fill `#121417` @ **38%**; optional 3D cylinder 6×10 dp `#1A1D21` if pitch > 0 | No shadow (disc floats and vanishes on `#FFFFFF` streets) |
| Heading cone | 56 dp long from puck center; fill `#1E6BFF` @ 36% → 0%; full angle `clamp(2 * heading_95, 36°, 100°)` | Fixed 90° cone, red bearing tick, or no cone while moving |
| Accuracy halo | Geographic radius = `uncertainty.horizontal_95_m`; fill `#1E6BFF` @ 16%; stroke 1.5 dp `#1E6BFF` @ 40% | Pixel-fixed halo; halo that ignores covariance |
| Pulse | GNSS_FUSED only: outer 28→44 dp, 1800 ms, alpha 0.22→0 | Pulse during DEAD_RECKONING (reads as “still searching”) |
| Anchor | Center of the **blue disc** = lat/lon (not the stem tip) | Pin-style bottom anchor |
| Min scale | `minZoomIconScale = 0.9`, `maxZoomIconScale = 1.15` | Zoom-shrink below 18 dp outer |

### Z-order (back → front)

1. Basemap fills, water, buildings  
2. Roads / casings  
3. Route polyline (if any)  
4. Accuracy halo  
5. Heading cone  
6. Dark stem / contact shadow  
7. White ring + `#1E6BFF` disc  
8. **All MapLibre symbol/label layers stay below the puck**  
9. Compose HUD (search, mode chip, SPEED) is a **separate overlay**; the camera must be padded so the puck is never drawn under that overlay

MapLibre Native: set `LocationComponentOptions.layerAbove()` to the **last symbol/label layer id** in the OpenFreeMap style (inspect the style JSON; common names include `place-*`, `road_label`, `poi-*`). Do not insert the location component under labels. Do not rely on default layer insertion.

### SPEED panel must not cover the puck

Portrait phone camera padding (tracking / follow mode):

| Edge | Padding |
|---|---|
| Top | `72 dp` (search pill + 8 dp) |
| Bottom | `max(SPEED_panel_height, 112 dp) + 16 dp` |
| Start | `16 dp` |
| End | `16 dp` + locate-FAB width if the FAB sits on that edge |

SPEED stays **bottom-start**. The puck’s visual slot is the remaining map rectangle, roughly the lower-middle third of the **padded** viewport — the same place Google Maps puts the blue dot in driving follow mode. Recenter after padding changes.

If follow-mode still tucks the puck under SPEED on a 16:9 emulator, increase bottom padding, do not shrink the puck.

### Consumer copy (not firmware)

| Surface | Copy |
|---|---|
| Search placeholder | **Where to?** |
| Search expanded | Search DriftZero |
| Forbidden | `DESTINATION`, `DEST`, `ENTER DESTINATION`, `GNSS`, `DEAD_RECKONING`, `NAVIGATION_STATE` |
| SPEED | Number + `km/h` (or user unit). Caption `Speed` if a caption is required. Not `SPEED` as a device label. |
| Mode chip | `On GPS` / `Weak GPS` / `No GPS · estimating` / `GPS returning` / `Uncertain` |
| Mode chip reason | One short clause from health flags, e.g. `Tunnel · using sensors` |
| Recenter | Recenter (not `FOLLOW` / `TRACKING`) |

`NavigationState.mode` enum values stay in the contract and judge overlay. They are not HUD strings.

### Mode → puck (keep the disc blue while DR is alive)

| `mode` | Disc | Halo | Cone | Pulse | Chip |
|---|---|---|---|---|---|
| `GNSS_FUSED` | `#1E6BFF` 22 dp | Tight, from H95 | Narrow if heading confident | Yes | On GPS |
| `GNSS_DEGRADED` | same | Larger | Wider | No | Weak GPS |
| `DEAD_RECKONING` | same (**still blue, still moving**) | Grows with H95 | Wider with heading_95 | No | No GPS · estimating |
| `REACQUIRING` | same | Blend; do not teleport | Hold last cone until heading stable | No | GPS returning |
| `LOW_CONFIDENCE` | same + 2 dp `#FF9500` outer tick | Large | Very wide or hidden if heading unusable | No | Uncertain |
| No permission / no estimate | **Do not draw a puck** at (0,0) | — | — | — | Allow location |
| Stale last-fix with **no** DR (startup only, <2 s) | `#9AA0A6` | Last H95 | Hidden | No | Finding location… |

Do not copy Organic Maps’ gray frozen arrow for tunnels. DriftZero’s product is that the marker **continues**. Uncertainty is the halo + chip, not a dead gray glyph.

---

## 2. Short comparison

Light basemaps (Google, Apple, OpenFreeMap Bright/Liberty/Positron) use **white or cream roads**. The location mark is readable only with a **saturated blue fill + thick white ring + contact shadow**. Dark HUD chrome then requires **camera padding**, not a darker puck.

| App | Puck | Search | Empty / explore | GNSS lost / tunnel |
|---|---|---|---|---|
| **Google Maps** | ~12 pt inner `#4285F4` + 3 pt white ring + accuracy halo `rgba(66,133,244,0.18)` + **flashlight heading beam** (narrow = calibrated). Pulse ~1.8 s. Nav mode: larger chevron. | Top **pill**, placeholder **Search here** (historically also **Where to?**). | Map + puck + category chips. Destination is optional. | Explore: dot can freeze/jump; accuracy circle grows. Navigation: route-constrained extrapolation; optional **Bluetooth tunnel beacons** (Android). No honest DR halo. |
| **Apple Maps** | **22 pt** fill `#0A84FF` + **3 pt** white ring + 90° cone ~60 pt + 30 pt pulse. High contrast on cream `#F6F1E6` land / white roads. | Bottom sheet; **Where to?** / Search Maps. 44 pt field. | Map is the product; search is a sheet, not a required destination. | Last known + growing accuracy; along-route matching in nav. No consumer DR confidence language. |
| **Organic Maps** | Large **blue chevron/arrow**, not a tiny disc. Network fix: big accuracy circle. High outdoor visibility by design. | Top search. Map-first. | Open the app → map. Search is optional. | Tunnel: arrow **stops**; **gray arrow** = last known; locate button radar/spin. Users still confuse “last fix” with “now.” |
| **OsmAnd** | **Circle at rest**, **arrow when moving**; customizable. Stale = **gray** icon. Locate button: full blue / white / **grey = no fix**. | Drawer / search, not a Google-style explore pill. | Map + widgets (speed, GPS info / satellite count). Power-user empty state. | Gray last position; GPS widget for sat count; “GPS lost” in nav. No inertial continue by default. |
| **Magic Earth** | Follow-me control **bottom-right**: **red = location off**, **blue = on**. Heading **arrow**. | Search + navigate; locate is explicit. | No puck until follow is enabled (easy to think GPS is broken). | Support: indoor/garage needs sky; **nav uses route + sensors + road network** so the arrow is stabler in guidance than in browse. Privacy: no Wi‑Fi/cell assist. |
| **HERE WeGo** | SDK `LocationIndicator`: **pedestrian** vs **navigation** 3D assets (disc+stem or chevron). App: green when `AVAILABLE`, **grey** when `OUT_OF_SERVICE` / `TEMPORARILY_UNAVAILABLE`. Search **Where to?** + Home/Work shortcuts. | Bottom **Where to?** | Shortcuts under search; destination-oriented but browse works without a route. | Tunnel: **HERE SDK extrapolates** along tunnel geometry + estimated speed, then **gives up** when covariance is too large. Grey marker = no service, not “estimating.” |

### GNSS-denied patterns (what to steal vs reject)

| Pattern | Who | Steal for DriftZero? |
|---|---|---|
| White ring + saturated blue on light roads | Google, Apple | **Yes** (required) |
| Heading beam width = heading uncertainty | Google 2016+ | **Yes** (maps to `heading_95_rad`) |
| Accuracy circle in meters | All of the above | **Yes** (`horizontal_95_m`) |
| Gray/frozen last fix | Organic Maps, OsmAnd, HERE unavailable | **No** while DR is running |
| Route-only tunnel slide (constant speed along geometry) | HERE, Magic Earth nav, many car navs | **No** as the only estimator; optional as a **map hypothesis**, never as silent truth |
| Bluetooth / infrastructure beacons | Google Maps, Waze | **No** (offline, no extra hardware/network) |
| Growing confidence halo + keep moving | DriftZero PRD | **Yes** — this is the differentiator |
| Firmware labels (`DESTINATION`, enum names) | Typical SIH prototypes | **No** |

Official Google heading-beam note: narrower beam = better compass calibration; wider = uncalibrated ([Google Maps blog, 2016](https://blog.google/products-and-platforms/products/maps/always-know-which-way-youre-headed-with/)). Organic Maps maintainers document gray last-known arrows in tunnels ([issue #3450](https://github.com/organicmaps/organicmaps/issues/3450)). HERE SDK documents pedestrian vs navigation location indicators ([HERE map items](https://docs.here.com/here-sdk/docs/android-map-items)). MapLibre Compose defaults a **6 dp** radius puck ([LocationPuckSizes](https://maplibre.org/maplibre-compose/api/lib/maplibre-compose/org.maplibre.compose.location/-location-puck-sizes/index.html)) — too small for this basemap.

---

## 3. Per-app notes (implementer)

### 3.1 Google Maps

- **Puck:** Inner disc ~12 pt `#4285F4`, 3 pt white ring, light-blue HDOP halo, heading **beam** (not a compass needle). During driving guidance the mark becomes a **chevron/arrow** so heading is obvious at a glance.
- **Contrast:** White roads `#FFFFFF` / cream highways. The white ring is doing the contrast work; the blue alone is close to water `#AADAFF`.
- **Search:** Floating top pill, 48 pt, 24 pt corner radius, **Search here**. Tap → full search with recents. Destination is not required to show the map.
- **Empty:** Explore is a valid product state: puck + chips + map.
- **GPS lost:** No consumer “dead reckoning” chip. In guidance the camera often keeps sliding along the route. Tunnel beacons are optional infrastructure, not phone IMU. Do not copy silent route-slide as if it were an estimate.
- **Speed:** Circular **bottom-start** speed chip in nav — **padded away from the puck**.

### 3.2 Apple Maps

- **Puck:** 22 pt `#0A84FF` + 3 pt white ring (reconstructed from public design writeups, not an Apple kit). Cone ~90° / 60 pt. Pulse 30 pt @ 0.2 alpha, 2 s.
- **Contrast:** Cream land `#F6F1E6` + white roads. 22 pt is the visibility floor DriftZero should treat as **minimum fill**.
- **Search:** Bottom sheet, placeholder **Where to?** / Search Maps. Thumb-reach, 44 pt field.
- **Empty:** Generous map, few floating controls. No destination firmware banner.
- **GPS lost:** Accuracy ring grows; nav stays on the road when a route exists. No “estimating from sensors” language.

### 3.3 Organic Maps

- **Puck:** 3D-looking **blue arrow**; larger than Google’s explore dot. Accuracy circle for Wi‑Fi/cell. FAQ: large translucent circle = coarse/network position ([Organic Maps FAQ](https://organicmaps.app/faq/map/can-not-find-position/)).
- **Search:** Conventional top search; offline OSM.
- **Empty:** Map is immediately useful without a destination.
- **GPS lost:** Arrow freezes; gray last-known; locate control shows searching. Drivers report this as “still showing me in the tunnel as if current.” DriftZero must not look like that.

### 3.4 OsmAnd

- **Puck:** Resting **circle** vs moving **arrow** (profile appearance). Gray = outdated. Locate: blue (found, not synced), white (synced), **grey (not found)**, arrow (3D) ([OsmAnd interact-with-map](https://osmand.net/docs/user/map/interact-with-map/)).
- **Search:** App drawer; not the Google explore pattern.
- **Empty / HUD:** Configurable widgets, including **Current speed** and **GPS info** (sats used/seen). Speedometer can be Large — same occlusion risk as DriftZero SPEED if the map camera is unpadded.
- **GPS lost:** Gray last position; GPS widget; no default IMU continue.

### 3.5 Magic Earth

- **Puck:** Shown after **follow position**. Button **red = location services off**, **blue = on** ([Magic Earth GPS FAQ](https://support.magicearth.com/support/solutions/articles/205000058427-resolving-gps-issues-in-magic-earth)). Heading arrow; figure-8 compass calibration.
- **Search / empty:** Browse without follow shows no tracker — a trap for “where is the blue dot?”
- **GPS lost:** Browse drifts more than **navigation**, which fuses **route + sensors + road network**. Still not an honest covariance halo.

### 3.6 HERE WeGo

- **Puck:** 3D `LocationIndicator` with **stem** (pedestrian) or **nav chevron**. Color: green available, grey unavailable ([Stack Overflow / PositioningManager status](https://stackoverflow.com/questions/56291232/when-default-positionindicator-on-here-map-has-green-or-grey-color-what-does-th)).
- **Search:** **Where to?** plus Home/Work shortcuts ([HERE routing FAQ](https://help.here.com/faq/routing_and_navigation)).
- **GPS lost / tunnel:** Extrapolate along tunnel + speed, then stop guessing. Grey ≠ “we are estimating.” DriftZero should keep blue + growing halo instead of going grey.

---

## 4. Search, empty states, GPS lost — DriftZero mapping

### Search field

- Light pill over the map (Google) **or** bottom sheet (Apple). Prefer **top pill** on Android to match Google muscle memory, 48 dp height, 24 dp corner radius, white `#FFFFFF` fill, 16 dp side margin, 8 dp below status bar.
- Leading 20 dp search glyph `#5F6368`. Placeholder **Where to?** in 16 sp, `#5F6368`.
- Trailing: optional mic later; not required for SIH.
- Dark HUD panels (SPEED, mode) must not share the search row. Search stays light; telemetry stays dark — that split is fine if the **puck is not under either**.

### Empty states

| State | UI | Puck |
|---|---|---|
| Explore, no route | Map + **Where to?** + locate FAB. No `DESTINATION` banner. | Full spec |
| No location permission | Banner + CTA; map still pans | Hidden |
| Finding first fix | Chip `Finding location…` | Pulse, or hidden until first estimate |
| No offline area | Centered install CTA; do not imply live nav | Hidden |
| No search hits | Sheet: `No places for “X”. Try a road or area name.` | Unchanged |
| Ambiguous map match | Chip `Uncertain road` + wide halo; **do not snap** | Full spec, wide halo |
| DR / tunnel | Chip `No GPS · estimating` + reason | Blue disc stays; halo grows |
| Low confidence | Chip `Uncertain` + reason | Amber tick; cone may hide |

Explore-without-destination is the Google/Apple empty state. Requiring a destination to show the mark is Magic Earth follow-me, not a map app.

### GPS lost (consumer)

1. Puck **keeps moving** at 10 Hz from `NavigationState`.  
2. Halo radius tracks `horizontal_95_m`.  
3. Cone widens with `heading_95_rad`.  
4. Chip names the mode in English and one reason (`Tunnel`, `Underpass`, `Weak satellites` — only from real `gnss_health` / `health.flags`, never invented).  
5. Recenter still follows the **estimate**, not the last GNSS fix.  
6. Recovery: blend; never teleport the disc.

Judge/engineering overlay may show enums, sat counts, and covariance. Driver HUD must not.

---

## 5. Why the emulator screenshot fails

Light OpenFreeMap streets are near-white, same as Google’s road fill. Failure stack, in order:

1. **Fill too small** (MapLibre 12 dp class) — smaller than Apple’s 22 pt floor.  
2. **Missing or 1 dp white ring** — blue sits on white asphalt.  
3. **No dark stem/shadow** — no ground contact; disc looks like a map bug.  
4. **Z-order under labels** — street names paint over the disc.  
5. **Unpadded camera** — dark SPEED panel composites **on top of** the MapView, eating the puck. Recoloring the disc cannot fix (5).

`#1E6BFF` is a slightly deeper Google-like blue than `#4285F4`, chosen so it still separates from OpenFreeMap water while matching consumer “that’s me” blue. Keep the white ring; do not darken the fill to “show up on the HUD.”

---

## 6. MapLibre implementation notes (no code in this PR)

- Prefer a **custom 2D drawable** (disc + ring + stem) plus a separate halo `CircleLayer` in meters, plus a cone `SymbolLayer` or fill-extrusion-free fan. Do not use `LocationPuckColors.dotFillColorCurrentLocation = Color.Blue`.
- `RenderMode.COMPASS` or bearing from `motion.heading_rad` (vehicle course), not raw magnetometer, while speed is above a walk threshold — same lesson as Organic Maps compass-in-car issues.
- `enableStaleState`: map to “no NavigationState yet,” not to DR.
- `accuracyColor`: `#1E6BFF` with `accuracyAlpha` ≈ 0.16.
- `pulseEnabled`: only `GNSS_FUSED`.
- Interpolation of the puck between 10 Hz states is display-only and must not rewrite logged `NavigationState` (see `apps/android/README.md` and AGENTS.md).

This document does not change architecture ADRs. Puck styling is presentation of `NavigationState`, not a new estimator.

---

## 7. Sources and limits

Retrieved 2026-09-02. Third-party “DESIGN.md” reconstructions (Apple/Google) are **not** official kits; sizes are used as corroboration with SDK defaults and vendor docs. This pass did not instrument live APKs. Do not treat reconstructed pt values as licensed assets.

- [Google Maps heading beam](https://blog.google/products-and-platforms/products/maps/always-know-which-way-youre-headed-with/)
- [Google Maps tunnel Bluetooth beacons](https://www.theverge.com/2024/1/16/24039896/google-maps-android-tunnels-bluetooth-beacons)
- [Map UI Patterns: Blue dot](https://mapuipatterns.com/blue-dot/)
- [MapLibre LocationComponentOptions](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.location/-location-component-options/index.html)
- [MapLibre Compose LocationPuckSizes](https://maplibre.org/maplibre-compose/api/lib/maplibre-compose/org.maplibre.compose.location/-location-puck-sizes/index.html)
- [Organic Maps location FAQ](https://organicmaps.app/faq/map/can-not-find-position/)
- [Organic Maps location-button UX #3450](https://github.com/organicmaps/organicmaps/issues/3450)
- [OsmAnd interact with map](https://osmand.net/docs/user/map/interact-with-map/)
- [OsmAnd info widgets (speed, GPS info)](https://osmand.net/docs/user/widgets/info-widgets/)
- [Magic Earth GPS issues](https://support.magicearth.com/support/solutions/articles/205000058427-resolving-gps-issues-in-magic-earth)
- [HERE WeGo routing / Where to?](https://help.here.com/faq/routing_and_navigation)
- [HERE SDK location indicator](https://docs.here.com/here-sdk/docs/android-map-items)
- [OpenFreeMap styles](https://openfreemap.org/quick_start/)

Internal: `PRD.md` (blue marker, heading cone, confidence halo, Google Maps-like UI), `apps/android/README.md` (UI hierarchy), `contracts/navigation_state.schema.json` (modes and uncertainty).
