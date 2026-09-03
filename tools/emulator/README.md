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
