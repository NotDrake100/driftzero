# Emulator screenshots

These PNGs are Android emulator captures. They use mock GPS and fake IMU. They are not phone results. They are not accuracy evidence. Do not put them in the screening deck as field traces.

| File | What it is |
|---|---|
| `emulator-screencap.png` | Early product shell. Map offline. Estimator not wired. |
| `emulator-design.png` | Later chrome mock on a grid, not a live filter. |
| `emulator-googlelike-map.png` | MapLibre on the emulator. |
| `google-simple.png` | MapLibre on the emulator, Pune tiles from the network, mock location. |
| `offline-pune.png` | Ready `pune-core` pack, mock GPS, airplane mode. Streets and labels from local PMTiles and pack glyphs. Routing still needs network. |
| `offline-route.png` | Not captured. emulator-5554 is healthy (`sys.boot_completed=1`, APK installed). Mock GPS `adb emu geo fix 73.8851 18.51091`, airplane on, `graph.bin` still at `files/area-packs/installed/pune-core/` (4.5 MB). Pack style loaded first (`pmtiles=true`). LocalRouter loaded that `graph.bin` (51230 edges, not OSRM). Dest extra `18.51808,73.8677` returned `no route` on the guest (puck origin). The same window routes Camp to that dest on the host. Banner, TTS, and polyline were not on screen. |

IO-VNBD score-only plots stay in `results/io_vnbd_screening_v1/plots/`. Those are evaluator figures, not phone screenshots.

## `blackout_10pct/`

Known-length emulator GNSS gap. `adb emu geo fix` along an OSRM line, 9 s emit gap, `path_m` 49.79 m from the emitted GPX jump. Live APK coast stayed at an old last-known fix (`error_m` 3110 m, ratio 62.5). That is SwiftShader IMU and speed-0 mock GNSS, not a 10 percent claim. The 10 percent check on this fixture is `EmulatorRouteFixture` on the same GPX (`error_m` 1.59 m, ratio 0.0319). Details in `blackout_10pct/README.md`.
