# Emulator screenshots

These PNGs are Android emulator captures. They use mock GPS and fake IMU. They are not phone results. They are not accuracy evidence. Do not put them in the screening deck as field traces.

| File | What it is |
|---|---|
| `emulator-screencap.png` | Early product shell. Map offline. Estimator not wired. |
| `emulator-design.png` | Later chrome mock on a grid, not a live filter. |
| `emulator-googlelike-map.png` | MapLibre on the emulator. |
| `google-simple.png` | MapLibre on the emulator, Pune tiles from the network, mock location. |
| `offline-pune.png` | Ready `pune-core` pack, mock GPS, airplane mode. Streets and labels from local PMTiles and pack glyphs. Routing still needs network. |
| `offline-route.png` | Not captured. emulator-5554 package, activity, and window services died (StorageManager NPE on install, then reboot never reached `sys.boot_completed`). `graph.bin` was copied into app files before that. Local route, banner, and voice were not verified on device. |

IO-VNBD score-only plots stay in `results/io_vnbd_screening_v1/plots/`. Those are evaluator figures, not phone screenshots.
