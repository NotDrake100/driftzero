# Model artifacts

Git tracks JSON weights and train reports in this directory. Git ignores binary checkpoints (`*.onnx`, `*.pt`, `*.pth`, `*.tflite`). That ignore list is in the repo `.gitignore`. JSON is not ignored.

Tracked today:

- `motion_student_v1/linear.json` (speed student). Assemble copies it into APK assets when present. Screening source: `models/motion_student_v1/train_report.json` and `results/io_vnbd_screening_v1/summary.md`. Linear speed MAE beats freeze and the ZUPT heuristic on every held-out split. Position-drift gate 0.10 is not met.
- `learned_imu_v1/linear_dp.json` (optional Δp). Not packed unless you pass `-Pdriftzero.packLearnedImu=true`. Train report says Δp MAE is worse than freeze. Not a screening claim.
- `motion_student_v2/gru.json`. Not packed. No Kotlin GRU runtime. Analysis-only. See `motion_student_v2/README.md`.

`motion_student_v1/train_report_invalid_kmh_labels.json` is the 2026-09-02 report. Those speed MAE numbers divided `GPS SPEED (Kmh)` by 3.6. The column is metres per second. Do not cite that file.

TimesFM checkpoints never enter this directory. The desktop experiment is designed, not run, until `results/timesfm/` exists. TimesFM 3.0 weights use `timesfm-non-commercial-license-v1.0` (non-commercial, non-production) and cannot ship. Distillation from 3.0 needs a license read. 2.5 weights remain Apache-2.0.

A later mobile package should include, when those artifacts exist:

- model ID and semantic version;
- training code commit and data/split manifest hashes;
- input feature order, units, frames, sample rate, window length, and missing-value policy;
- train-only scaler parameters and hash;
- output semantics and uncertainty transform;
- held-out metrics and confidence calibration;
- SHA-256 when available;
- fallback compatibility and rollback version.

ONNX opset, ONNX Runtime, float/quantized parity, and signatures are planned. Nothing in this repo exports ONNX. Gradle has no ONNX runtime.
