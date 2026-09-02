# Learned IMU odometry (research, desktop)

Compact causal student for GNSS-denied motion. Training lives in `ml/`. The APK does not import this module, TimesFM, or a PyTorch checkpoint.

Retrieved 2026-09-02 from the paper PDFs and project pages listed below. Numbers in those papers are their sensors and splits, not DriftZero phone results.

## Which head copies which paper

| Head | Units | Paper | What the paper actually emits |
|---|---|---|---|
| `ronin_dx_m`, `ronin_dy_m` | m, HACF | RoNIN, Herath, Yan, Furukawa, ICRA 2020 ([arXiv:1905.12853](https://arxiv.org/abs/1905.12853)) | 2D velocity / strided Δp in a heading-agnostic gravity-aligned frame. ResNet loss is MSE(`P_i - P_{i-200}`). LSTM/TCN use a latent-velocity integral over the window. |
| `tlio_dz_m` plus `tlio_log_sigma_{x,y,z}` | m, ln(σ) | TLIO, Liu et al., RA-L 2020 / RSS ([arXiv:2007.01867](https://arxiv.org/abs/2007.01867)) | 3D displacement over N=200 samples at 200 Hz, diagonal covariance as log standard deviations, fused as an EKF measurement. Train MSE for ~10 epochs, then Gaussian NLL. |
| `ionet` Δl (derived as `hypot(dx,dy)`), `ionet_dpsi_rad` | m, rad | IONet, Chen, Lu, Markham, Trigoni, AAAI 2018 ([arXiv:1802.02209](https://arxiv.org/abs/1802.02209)) | Polar increment (Δl, Δψ) from a 200×6 IMU window. Paper network is bidirectional LSTM. Ours is unidirectional. |
| `forward_speed_mps`, `stop_logit`, `log_speed_variance` | m/s, ln(σ²) | Existing DriftZero ESKF `MotionPseudoMeasurement` | 1D projection used by the live filter today. Not a published IMU-odometry head. |
| Linear Δp ridge | same heads | RIDI-shaped shallow baseline, Yan, Shan, Furukawa, ECCV 2018 ([arXiv:1712.09004](https://arxiv.org/abs/1712.09004)) | RIDI regresses velocity in a gravity-stabilized frame, then double-integrates. We only keep the shallow Δp fit so training runs without Torch. |

RNIN-VIO (Chen et al., ISMAR 2021, [project](https://zju3dv.github.io/rnin-vio/), [code](https://github.com/zju3dv/rnin-vio)) adds a visual-inertial EKF around an IMU network. The IMU-only RNIN branch is the relevant piece. The visual branch is not a DriftZero phone dependency.

## What the student does

1. Take a causal 1 s IMU window (accel + gyro). No GNSS keys. No magnetometer channels.
2. Estimate gravity with the existing causal accel low-pass.
3. Rotate the window into a RoNIN HACF (Z = gravity). Level phone keeps R = I.
4. Predict 2D Δp (RoNIN), up residual + log σ (TLIO), polar heading increment (IONet), and the ESKF speed heads.
5. Train on synthetic vehicle odometry when OxIOD / RoNIN / IO-VNBD / RIDI are not on disk.

```bash
PYTHONPATH=ml/src python -m driftzero_ml.learned_imu --out models/learned_imu_v1
```

## Data URLs (fetch locally, do not commit)

| Dataset | Role | Official fetch | Local path |
|---|---|---|---|
| RoNIN | Pedestrian IMU + Tango/ARCore truth. 42.7 h, 100 subjects. Half unpublished. | Project [ronin.cs.sfu.ca](http://ronin.cs.sfu.ca/). Data DOI [10.20383/102.0543](https://doi.org/10.20383/102.0543). Code [Sachini/ronin](https://github.com/Sachini/ronin). Format [README.txt](https://ronin.cs.sfu.ca/README.txt). | `data/raw/ronin/` |
| OxIOD | Pedestrian / trolley IMU, 158 sequences, ~42 km, 100 Hz. IONet training set. | [deepio.cs.ox.ac.uk](http://deepio.cs.ox.ac.uk/). Paper [arXiv:1809.07491](https://arxiv.org/abs/1809.07491). | `data/raw/oxiod/` |
| IO-VNBD | Mandatory SIH vehicle/smartphone set. | [github.com/onyekpeu/IO-VNBD](https://github.com/onyekpeu/IO-VNBD) (Git LFS). Data in Brief [10.1016/j.dib.2021.106885](https://doi.org/10.1016/j.dib.2021.106885). | `data/raw/io_vnbd/` |
| RIDI | Earlier velocity-regression set, multiple phone placements. | Code [higerra/ridi_imu](https://github.com/higerra/ridi_imu). Project [yanhangpublic.github.io/ridi](https://yanhangpublic.github.io/ridi/). Zip linked from that README. | `data/raw/ridi/` |
| TLIO headset | 60 h Bosch BMI055, VIO labels. Repo says generate your own / retrain. | Paper + video [cathias.github.io/TLIO](https://cathias.github.io/TLIO/). Code [CathIAS/TLIO](https://github.com/CathIAS/TLIO). | not assumed |

The trainer only probes those directories. It does not invent column names for a dataset that is present. Wire a loader after a local header inspection.

## Magnetometer

The phone schema includes magnetometer (`uT`). Classical smartphone PDR uses it for heading. See Hou and Chen, IEEE Sensors 2022, [10.1109/jsen.2022.3213836](https://doi.org/10.1109/jsen.2022.3213836), and the usual step / stride / mag-heading stack.

RoNIN records mag and then disables it for orientation (Android game-rotation vector, no magnetometer). TLIO and IONet are IMU-only. Vehicle-body steel and cabin electronics make mag heading a gated filter update, not a student feature. `docs/02_ARCHITECTURE.md` already says not to trust mag heading by default.

So: mag is captured, not a blackout feature, not a network channel. That matches RoNIN/TLIO. It is a gap versus classical PDR, not versus those papers.

## What we still lack versus TLIO

Implemented, paper-shaped but compact:

- Gravity-aligned 6-axis window.
- 3D displacement + diagonal log σ (TLIO §IV-A also uses a diagonal Σ).
- MSE warmup, then Gaussian NLL.
- Causal GRU (RoNIN LSTM family) and a 4-block causal TCN (RoNIN TCN family, smaller width).
- Random-yaw HACF is available for training windows (`extra_yaw_rad`).

Not implemented, and not claimed:

1. 1D ResNet-18 at paper width (512-d FC). Ours is 1×16 GRU or 4×32 TCN.
2. N = 200 at 200 Hz. Synthetic and phone capture are ~50 to 100 Hz over 1 s.
3. Full stochastic-cloning EKF (extra 15 states for the window-start pose). Kotlin now consumes 3D Δp + log σ through `ingestDisplacementPseudo` with a cloned ENU pose treated as known, H on current position, χ² gate 11.345, and ×10 R on overlapping windows. Speed + ZUPT remain.
4. Gravity alignment from filter attitude / cloned R. HACF at clone time uses last accel (or g) rotated by current attitude.
5. IMU used twice (mechanization + network) with that correlation handled beyond the ×10 R inflation.
6. Full bias random-walk coupling to a displacement update.
7. Their headset domain, 60 h BMI055, VIO labels, and the published bias / gravity-perturbation schedule as a locked recipe.
8. Bidirectional LSTM (IONet). Forbidden on the live path.
9. Visual measurements (RNIN-VIO). Out of scope for the phone navigator.
10. ONNX export in the APK. Kotlin reads `linear.json` (speed) when assemble finds `models/motion_student_v1/linear.json`. `linear_dp.json` may also pack. Δp MAE is worse than freeze. χ² 11.345 stays. No Δp screening claim.

## Leakage rules

- GNSS fields are dropped before the window is built. Blackout truth stays in the evaluator.
- Magnetometer is not concatenated onto the 6-vector.
- Trip splits, never row splits.
- TimesFM is not imported by `learned_imu` or `student.gru`.
