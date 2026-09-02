# DriftZero research package

This package begins with the components that must be correct before training: distance/drift metrics, deterministic blackout masking, and an optional dependency boundary for TimesFM 3.

## Install and test

```bash
PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v
```

The starter tests use only Python's standard library. Installing `./ml[dev]` additionally provides pytest and Ruff for the growing implementation.

Train the compact motion student (synthetic if IO-VNBD LFS is missing):

```bash
PYTHONPATH=ml/src python -m driftzero_ml.student.train --seed 26168
PYTHONPATH=ml/src python -m driftzero_ml.learned_imu --out models/learned_imu_v1
PYTHONPATH=ml/src python -m driftzero_ml.eval_iovnbd_blackout --out results/io_vnbd_blackout_eval.md
```

PyTorch is optional (`./ml[research]`). The GRU/TCN never ships in the APK. TimesFM stays a separate optional extra. Paper heads, dataset URLs, and TLIO gaps: `docs/refs/LEARNED_IMU.md`.

## Intended modules to add

```text
driftzero_ml/
  datasets/      IO-VNBD, OxIOD, RoNIN, EuRoC, TUM VI, GSDC loaders
  io_vnbd/       schema discovery, local-root gate, trip splits
  features/      causal IMU windows (no GNSS, no future samples)
  learned_imu.py RoNIN Δp / TLIO log-σ / IONet polar student (desktop)
  student/       heuristic, linear, optional RoNIN/TLIO-aligned GRU, train script
  baselines.py   freeze and constant-velocity screening baselines
  contracts.py   SensorFrame / NavigationState instance checks
  navigation/    Python reference filter and replay
  timesfm/       optional zero-shot and distillation experiments
  evaluation/    manifests, per-trip reports, plots
  export/        ONNX student, schema and parity checks
```

`io_vnbd` inspects a local table. `datasets/` loaders fail if Git LFS pointers are still on disk. Fetch commands: `scripts/fetch_datasets.md`. Scorecard: `docs/refs/DATASETS.md`.

Do not add TimesFM to the default dependencies. Research code must fail with an actionable message when the optional environment is missing, while all core tests remain usable.

## Metric convention

`drift_ratio` is endpoint horizontal error divided by truth path length during the blackout. It returns `None` for a path shorter than a configurable minimum because division near zero is misleading. Absolute endpoint error remains valid for stationary cases.
