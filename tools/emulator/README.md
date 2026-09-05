# Emulator GNSS drivers

Developer tools for feeding mock GNSS into an Android emulator. Not the production navigation path. `adb emu geo fix` takes longitude first, then latitude.

## `drive_route.py`

Walks a public OSRM driving line at 1 Hz and sends `adb -s <serial> emu geo fix <lon> <lat>`. Place names go through Photon once each (`StreetMapConfig.USER_AGENT`). One OSRM request, no polling.

`--gap-start-s` / `--gap-s` stop sending fixes for that window, then resume at the position the vehicle would have reached at the same speed. The app sees a real outage and a return fix far from the last one. `--gpx` writes those same emitted fixes (gap seconds omitted, timestamps still jump) so you can replay later.

Example, Camp to SB Road in Pune. Photon `limit=1` for those short names lands on the wrong shops, so the name form uses queries that resolve to the cantonment and Senapati Bapat Road:

```bash
python3 tools/emulator/drive_route.py \
  --from-name "Pune Cantonment" \
  --to-name "Senapati Bapat Road, Pune" \
  --speed-kmh 30 \
  --gap-start-s 40 \
  --gap-s 20 \
  --dry-run \
  --gpx /tmp/camp-sb.gpx
```

Those Photon hits (lat,lon) were `18.51090730,73.88510180` and `18.53386870,73.82945370`:

```bash
python3 tools/emulator/drive_route.py \
  --from 18.51090730,73.88510180 \
  --to 18.53386870,73.82945370 \
  --dry-run
```

`--serial` defaults to `emulator-5554`. `--loop` repeats until Ctrl-C.

## `tunnel_demo.sh`

Straight-line 1 Hz `geo fix` for 20 s, then 30 s with no fixes, then 20 s further along the same bearing. No city is hardcoded. Arguments are start latitude, start longitude, and bearing in degrees clockwise from north.

`adb emu geo fix` takes longitude first, then latitude.

```bash
tools/emulator/tunnel_demo.sh START_LAT START_LON BEARING_DEG [SERIAL] [SPEED_MPS]
```

SERIAL defaults to `emulator-5554`. SPEED_MPS defaults to 10.

## `score_blackout.py`

Scores an emitted-fix GPX (gaps omitted, timestamps jump) against a fused/DR pose. `path_m` is the chord across the first jump. `ratio = error_m / path_m`. Official gate is `ratio < 0.10`. Every number is an emulator fixture, not IO-VNBD and not SIH screening.

```bash
python3 tools/emulator/score_blackout.py \
  --gpx results/emulator/blackout_10pct/emitted.gpx \
  --pose-log results/emulator/blackout_10pct/logcat.txt \
  --out results/emulator/blackout_10pct/score.json
```

The JVM twin is `EmulatorRouteFixture` in `navigation-core` tests. Same polyline, leak-free mask, labeled gravity IMU. Not SwiftShader.

## `grant_runtime.sh`

Waits for a booted emulator, turns location on, grants `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, sets fine/coarse/gps/monitor_location appops, writes `first_run_done=true` in `driftzero_settings` (debug `run-as`), then restarts `MainActivity`. Agents should run this instead of tapping Allow or first-run.

```bash
tools/emulator/grant_runtime.sh [SERIAL]
```

SERIAL defaults to `emulator-5554`. Exits non-zero if `in.driftzero.app` is not installed. Safe to run again. Same command: `make emulator-grant`.
