# Model artifacts

Model binaries are intentionally ignored by Git. Store only manifests and evaluation reports in the repository.

`python -m driftzero_ml.student.train --out models/motion_student_v1` writes `linear.json` (speed student). Assemble copies it into APK assets when present. `python -m driftzero_ml.learned_imu --out models/learned_imu_v1` writes `linear_dp.json`. Assemble copies that file too. Linear Δp MAE is worse than freeze. Keep the χ² gate. Do not treat Δp as a screening claim. TimesFM checkpoints never enter this directory's production package.

Every mobile model package must include:

- model ID and semantic version;
- training code commit and data/split manifest hashes;
- input feature order, units, frames, sample rate, window length, and missing-value policy;
- train-only scaler parameters and hash;
- output semantics and uncertainty transform;
- ONNX opset and runtime compatibility;
- float/quantized parity results;
- held-out metrics and confidence calibration;
- SHA-256 and signature when available;
- fallback compatibility and rollback version.

TimesFM checkpoints never enter this directory's production package. Research configuration records an exact checkpoint revision separately.

