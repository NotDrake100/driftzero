# IO-VNBD screening v1

Learned student versus filter-only and other causal baselines on held-out IO-VNBD trips.
GNSS is score-only after the blackout mask. No TimesFM. No vehicle ECU.

## Reproduce

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.datasets.inventory --repo .`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.eval_iovnbd_blackout --repo . --out results/io_vnbd_blackout_eval.md`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.student.train --seed 26168 --out models/motion_student_v1`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.student.train_gru --repo . --out models/motion_student_v2`

`PYTHONPATH=ml/src ~/.venvs/driftzero-ml/bin/python -m driftzero_ml.screening --repo . --out results/io_vnbd_screening_v1`

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

## Plots

- `plots/S-Vta2_mid.png`
- `plots/S-S1_mid.png`
- `plots/S-S3b_mid.png`

## Worst trajectories

- `S-S3b:d1000`: GRU endpoint 19435.881051178858, linear 896.5246404147215. large heading change (2.89 rad); truth path 1002.9 m.
- `S-S3b:mid`: GRU endpoint 17653.02485118334, linear 1022.8159338892324. truth path 92.0 m.
- `S-Vta22:d1000`: GRU endpoint 1942.22866986711, linear 1334.8576769494737. late-window bump / high vibration; truth path 1089.9 m.

## SIH gate

The official aggregate gate is median drift ratio under 0.10. No system met it. Best median drift on the 35 gated intervals is persist 0.5168, then filter-only 0.5531. Linear is 0.7132. GRU is 0.7550.

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

