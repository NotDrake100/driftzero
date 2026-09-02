# IO-VNBD screening v1

Learned student versus filter-only and other causal baselines on held-out IO-VNBD trips.
GNSS is score-only after the blackout mask. No TimesFM. No vehicle ECU.

## Reproduce

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.datasets.inventory --repo .`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.eval_iovnbd_blackout --repo . --out results/io_vnbd_blackout_eval.md`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.student.train --seed 26168 --out models/motion_student_v1`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.student.train_gru --repo . --out models/motion_student_v2`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.screening --repo . --out results/io_vnbd_screening_v1`

`PYTHONPATH=ml/src python3 -m driftzero_ml.eval_kotlin_replay --repo . --out results/io_vnbd_screening_v1/kotlin_replay`

`PYTHONPATH=ml/src python3 -m driftzero_ml.eval_kotlin_replay --repo . --out results/io_vnbd_screening_v1/kotlin_replay --system kotlin_eskf_v2 --reexport --rerun`

`JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew :navigation-core:replay --args="--input results/io_vnbd_screening_v1/kotlin_replay/frames/S-Vta25.jsonl --output results/io_vnbd_screening_v1/kotlin_replay/states/S-Vta25_mid.jsonl --mask-start-ns 954514000000 --mask-end-ns 976514000000"`

`PYTHONPATH=ml/src python3 -m driftzero_ml.eval_navstate --states results/io_vnbd_screening_v1/kotlin_replay/states/S-Vta25_mid.jsonl --start-ns 954514000000 --end-ns 976514000001 --truth-jsonl results/io_vnbd_screening_v1/kotlin_replay/truth/S-Vta25.jsonl`

## Run identity

- git commit: `069e74bcb54f4b1f4b2d14b23661d11ae36e0455`
- seed: `26168`
- data manifest sha256: `e0819568b1bf5390d457bc56d817b56c37da563b22d6ee0e309ef99b3c4f0211`
- intervals: 35
- held-out trips: 23
- python: 3.12.6
- numpy: 2.5.2
- torch: 2.14.0
- matplotlib: 3.11.1
- pyyaml: 6.0.3

## Held-out table

| System | drift p50 | drift p90 | drift p95 | drift worst | endpoint p50 m | speed MAE p50 | heading MAE p50 rad |
|---|---:|---:|---:|---:|---:|---:|---:|
| freeze | 1.1200 | 1.5269 | 1.5497 | 12.9555 | 520.92 | 11.885 | 0.673 |
| persist | 0.5168 | 1.3887 | 19.8706 | 204.0427 | 234.47 | 3.439 | 0.673 |
| cv | 1.1200 | 1.5269 | 1.5497 | 12.9555 | 520.92 | 3.439 | 0.673 |
| filter_only | 0.5531 | 1.4451 | 18.0091 | 182.4295 | 193.83 | 3.439 | 0.724 |
| zupt_accel | 0.8636 | 2.1212 | 17.3780 | 180.3536 | 354.35 | 6.175 | 0.724 |
| linear | 0.7132 | 2.5454 | 9.1404 | 11.1149 | 286.98 | 5.034 | 0.724 |
| gru | 0.7550 | 3.1158 | 19.3799 | 191.8346 | 292.76 | 4.847 | 0.741 |
| gru_bump | 0.7304 | 3.3260 | 18.4571 | 183.1924 | 306.96 | 4.404 | 0.724 |
| kotlin_eskf | 6.6863 | 130.9102 | 177.8337 | 225.9308 | 2669.27 | 26.764 | 1.633 |
| kotlin_eskf_v2 | 7.5650 | 128.4485 | 452.4265 | 1075.2232 | 2996.50 | 88.600 | 1.227 |

## Plots

- `plots/S-Vta2_mid.png`
- `plots/S-S1_mid.png`
- `plots/S-S3b_mid.png`

## Worst trajectories

- `S-S3b:d1000`: GRU endpoint 19435.881051178858, linear 896.5246404147215. large heading change (2.89 rad); truth path 1002.9 m.
- `S-S3b:mid`: GRU endpoint 17653.02485118334, linear 1022.8159338892324. truth path 92.0 m.
- `S-Vta22:d1000`: GRU endpoint 1942.22866986711, linear 1334.8576769494737. late-window bump / high vibration; truth path 1089.9 m.

## SIH gate

The official aggregate gate is median drift ratio under 0.10. No system met it. Best median drift on the 35 gated intervals is persist 0.5168, then filter-only 0.5531. Linear is 0.7132. GRU is 0.7550. kotlin_eskf is 6.6863 on the 33 intervals it scored. kotlin_eskf_v2 is 7.5650 on all 35. Both are worse than persist. Stopped.

## Kotlin DeadReckoningFilter replay

Official row is 10 Hz table-rate SensorFrame JSONL. IMU is not upsampled. phone_align rotates accel. Gyro is not treated as a device XYZ vector. GNSS is unique-fix only. Replay mask is `--mask-start-ns` / `--mask-end-ns`. Scorer is `eval_navstate` at fresh-fix epochs. Artifacts live under `results/io_vnbd_screening_v1/kotlin_replay/`. v1 numbers are `metrics.json`. v2 numbers are `metrics_kotlin_eskf_v2.json`.

23 trips exported. kotlin_eskf scored 33 of 35. `S-S3b:mid` had no pose at the first unique-fix epoch (first state 4.0 s later). `S-S3b:d1000` wrote 0 NavigationState rows because the inclusive mask covered the init GNSS on this backward-jump trip.

kotlin_eskf blackout-window modes: DEAD_RECKONING 5999, LOW_CONFIDENCE 5326. `health.filter_ok` stayed true on scored windows (no numericalOk latch). LOW_CONFIDENCE is the 120 m uncertainty lamp. `imu_gap` appears on 1465 later states because that flag latches after the first predict step over 0.40 s. Regular IO-VNBD IMU dt is 0.10 s, under `maxIntegrateS`. Gaps were left as timestamp jumps.

Sensitivity only, not the official row: kotlin_eskf `S-Vta2:d50` held at 100 Hz scored drift 21.73 versus 106.84 at 10 Hz. Still worse than persist 1.19 on that interval. kotlin_eskf_v2 hold-last 100 Hz on the same interval was 656.06. Worse again. Not the official row.

## kotlin_eskf diagnosis and v2

Measured on S-Vta2 and S-S1 states plus S-S3b frames, then kept or dropped.

H1 dropped. First NavigationState heading matched the first GNSS bearing and the 10 m unique-fix course: S-Vta2 319.84 deg versus -38.82 deg (1.34 deg circular), S-S1 241.65 deg versus -121.00 deg (2.65 deg). Not north, not wrong-sign at init.

H2 held as the default NHC/ZUPT path, not as ZUPT during the blackout. Replay drives `DeadReckoningFilter` directly and did not call `setGnssHeld`. S-Vta2 blackout ZUPT 0/178, NHC 153/178. First fused second dropped speed from 7.44 m/s to 1.85 m/s with NHC on. Frame is `unspecified` after phone_align, so NHC treated an arbitrary +X as vehicle-forward. Synthetic replay tests already disable NHC (`nhcMinSpeedMps=100`).

H3 dropped on the diagnosis trips. S-Vta2 and S-S1 accelerometer dt median 0.10 s, count of dt>0.40 s was 0, blackout `imu_gap` 0. The 1465 latched flags were S-Vta17 and S-Vtb1. 10 Hz table IMU is a dataset limit. No samples were invented for the official row.

H4 dropped. Unique-fix GNSS had `speed_mps` and `bearing_rad` on 1023/1023 S-Vta2 frames and 532/532 S-S1 frames. First state speed matched the first fix (7.44 and 5.57 m/s).

H5 held. After gravity alignment S-Vta2 gravity is already +Z (mean 9.806). Course rate versus aligned gx/gy/gz was 0.435 / 0.032 / -0.001. Raw pitch versus course rate was -0.435. The mechanization applies gz as yaw, so heading rate was on the wrong axis and attitude tumbled. S-Vta2 left GNSS_FUSED at 18 s with speed 31.8 m/s, gated the rest of the trip, and was 5629 m off at mask start.

H6 held. S-S3b CSV starts at 2503320000000 ns then rewinds to 8 ms. `keep_nondecreasing_rows` keeps the 2043-row prefix. All three locked windows start on that first GNSS. Inclusive mask dropped the seed.

v2 fixes that those measurements support: heading-rate gyro on +Z from the per-trip course-correlated column (pitch on these trips), NHC off when the frame is `unspecified`, still-ZUPT skipped when GNSS is held and pre-hold speed was at least 1.5 m/s, Replay `setGnssHeld` during the mask, mask start advanced 1 ns so the interval-start fix seeds the filter. 35 of 35 intervals scored. S-S3b:mid first state is now 2503320000000. S-Vta2:d50 endpoint fell from 5676.28 m to 173.38 m. S-S1:mid rose from 21322 m to 145748 m. Aggregate drift p50 7.5650, endpoint p50 2996.50 m, still worse than persist 0.5168 / 234.47 m. Stopped.

The 5 m over 50 m example is a scenario check. Only three `d50` intervals passed the truth gate. Persist on S-Vta2 ended 2.99 m over a 53.13 m path. Filter-only on that interval was 6.48 m. Linear was 30.60 m. GRU was 32.26 m. The other two `d50` intervals (S-Vta1a, S-Vta1b) were 21 to 51 m.

The 100 m over 1 km check: ten `d1000` intervals. Persist endpoint p50 was 540 m. Filter-only 897 m. Linear 803 m. GRU 825 m. None under 100 m.

## Window-level speed (same grouped splits, corrected m/s)

From `models/motion_student_v2/train_report.json`. PICP is the uncertainty head, not a position interval.

| Split | n | freeze MAE | heuristic MAE | linear MAE | GRU MAE | GRU PICP 68 | GRU PICP 95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| validation | 23420 | 13.940 | 7.421 | 5.913 | 6.582 | 0.654 | 0.961 |
| public_test | 7696 | 8.803 | 5.236 | 4.391 | 5.164 | 0.772 | 0.999 |
| locked_test | 16241 | 10.651 | 7.263 | 5.257 | 5.208 | 0.797 | 0.979 |

GRU beat freeze and the ZUPT heuristic on every split. It beat linear only on locked_test. `beats_linear_and_heuristic` is false. `gru.json` is analysis-only.

Bump-gated R (add 1.5 to log-variance when `bump_flag` is set) left speed MAE unchanged and raised bump-window PICP 68 from 0.646 to 0.955 on validation, 0.844 to 1.0 on public_test, 0.913 to 0.998 on locked_test.

S-Vtb3 was excluded: the speed column matched neither m/s nor km/h.

`cv` matched `freeze` on this bundle because the last two GNSS rows before many blackouts share a position, so the geographic rate is zero. Persist uses the 10 m heading seed and does not.

