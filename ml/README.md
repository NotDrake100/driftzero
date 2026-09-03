# DriftZero research package

Python lives here. The phone does not import this package. Unit tests use the standard library only.

## Research versus the phone

| Here (`ml/`) | On the phone |
|---|---|
| Train linear or optional GRU students | `ZuptAccelMotionModel`, or packed `linear.json` |
| Fit `linear_dp.json` (RoNIN/TLIO-shaped Δp) | Optional χ²-gated `ingestDisplacementPseudo` |
| TimesFM 3.0 adapter (designed, not run) | Never. No TimesFM, no PyTorch, no ONNX Runtime |
| IO-VNBD / OxIOD / RoNIN loaders | Not present. Filter consumes `SensorFrame` |
| Blackout metrics and eval scripts | Score-only after a desktop run |

Export to the APK is a JSON weight file under `models/`, copied at assemble when the file exists. Missing weights leave the heuristic filter.

## Tests

Same command as the root README and `pytest.ini`:

```bash
PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v
```

`make validate` at repo root also checks `contracts/` JSON. `./ml[dev]` adds pytest and Ruff. `./ml[research]` adds numpy, pandas, torch, and TimesFM. Do not add those to the default test path. TimesFM 3.0 is designed, not run, until `results/timesfm/` exists. 3.0 weights cannot ship. Distillation from 3.0 needs a license read. 2.5 is Apache-2.0.

## IO-VNBD

Official SIH dataset. Raw trees go in gitignored `data/raw/io_vnbd/`. Git LFS is pending on a clean clone. Fetch: [scripts/fetch_datasets.md](../scripts/fetch_datasets.md). Scorecard: [docs/refs/DATASETS.md](../docs/refs/DATASETS.md).

The only CSV in Git is `ml/tests/fixtures/io_vnbd_s_vta9_head.csv` (3 rows). Loaders raise `DatasetLfsMissing` if they see a Git LFS pointer.

```bash
PYTHONPATH=ml/src python -m driftzero_ml.student.train --seed 26168
PYTHONPATH=ml/src python -m driftzero_ml.learned_imu --out models/learned_imu_v1
PYTHONPATH=ml/src python -m driftzero_ml.eval_iovnbd_blackout --out results/io_vnbd_blackout_eval.md
```

Cite [results/io_vnbd_screening_v1/summary.md](../results/io_vnbd_screening_v1/summary.md) for screening numbers. Do not cite `results/io_vnbd_blackout_eval.md`. Synthetic IMU is used when LFS is missing. Synthetic numbers are not product scores.

## Layout

```text
driftzero_ml/
  datasets/      IO-VNBD, OxIOD, RoNIN, EuRoC, TUM VI, GSDC loaders
  io_vnbd/       schema discovery, local-root gate, trip splits
  features/      causal IMU windows (no GNSS, no future samples)
  learned_imu.py RoNIN Δp / TLIO log-σ / IONet polar student (desktop)
  student/       heuristic, linear, optional GRU, train script
  baselines.py   freeze and constant-velocity screening baselines
  contracts.py   SensorFrame / NavigationState instance checks
  timesfm_adapter.py  optional zero-shot boundary (designed, not run; fails if extra missing)
  eval_iovnbd_blackout.py  desktop blackout script
```

Paper heads, dataset URLs, and TLIO gaps: [docs/refs/LEARNED_IMU.md](../docs/refs/LEARNED_IMU.md).

## Metric convention

`drift_ratio` is endpoint horizontal error divided by truth path length during the blackout. It returns `None` for a path shorter than a configurable minimum because division near zero is misleading. Absolute endpoint error remains valid for stationary cases.
