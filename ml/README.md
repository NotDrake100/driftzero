# DriftZero research package

This package begins with the components that must be correct before training: distance/drift metrics, deterministic blackout masking, and an optional dependency boundary for TimesFM 3.

## Install and test

```bash
PYTHONPATH=ml/src python -m unittest discover -s ml/tests -v
```

The starter tests use only Python's standard library. Installing `./ml[dev]` additionally provides pytest and Ruff for the growing implementation.

## Intended modules to add

```text
driftzero_ml/
  io_vnbd/       schema discovery, ingestion, QA
  splits/        complete-trip grouped partitions
  features/      causal transforms and train-only scalers
  baselines/     freeze, CTRA, filter, TCN, GRU
  navigation/    Python reference filter and replay
  timesfm/       optional zero-shot and distillation experiments
  evaluation/    manifests, per-trip reports, plots
  export/        ONNX student, schema and parity checks
```

Do not add TimesFM to the default dependencies. Research code must fail with an actionable message when the optional environment is missing, while all core tests remain usable.

## Metric convention

`drift_ratio` is endpoint horizontal error divided by truth path length during the blackout. It returns `None` for a path shorter than a configurable minimum because division near zero is misleading. Absolute endpoint error remains valid for stationary cases.
