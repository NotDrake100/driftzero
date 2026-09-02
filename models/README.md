# Model artifacts

Model binaries are intentionally ignored by Git. Store only manifests and evaluation reports in the repository.

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

