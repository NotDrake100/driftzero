# IO-VNBD v8 error budget

Written 2026-09-03. Method only until `results/io_vnbd_screening_v1/kotlin_replay/metrics_kotlin_eskf_v8.json` exists. No invented metres.

Official SIH gate: median position drift < 0.10 of distance during GNSS outage. Locked 35 intervals, seed 26168. Score only `frames_premask`.

Published rows used here, and no others:

- persist 0.5168 / 234 m
- kotlin v1 6.69, v2 7.56
- v3 leaky frames 0.541 / 194 m (not a headline)
- v3 premask 0.703 / 201 m
- v5 HOLD_COURSE leak-free 0.611 / 187.5 m, 1 Hz 0.257/52 (beats persist 0.319/72), sparse 0.841
- v6a reseed 0.538 / 163 m, sparse 0.572 (beats persist sparse), 1 Hz lost
- v7 no single winner. Official rows stay v5_1hz and v6_sparse unless a later row beats both.

v7 mechanism, already measured: T=6 s or 8 s unique-gap reseed snaps S-S1:mid (last unique hop 9 s) to about 163 m versus v5 55 m, and snaps an earlier 9 s hop on 1 Hz S-Vta2 (12.46 m to 34.25 m) even though the hop at mask start is 1 s.

v8 change: reseed only when last unique-fix gap >= 8 s **and** the median of the last 3 unique intervals, including the candidate hop, is also >= 8 s. A 1 Hz stream with one historical 9 s hop is not sparse. Live phone keeps reseed off. Hidden GNSS inside the mask is not used. TimesFM is not on the phone. Road heading is not scored on IO-VNBD (no APK graph for those roads).

Along-track ~ dv * T. Cross-track ~ v * dtheta * T. Persist speed MAE p50 3.439 m/s and heading MAE p50 0.673 rad are not a 60 s bias budget; the heading figure is scored against 1 to 9 s GNSS course. The 0.10 gate is not met on this suite today. Persist 0.5168 remains the held-out coast.

This file is rewritten by `eval_kotlin_eskf_v8` after a scored run. Until then there is no v8 p50 to cite.
