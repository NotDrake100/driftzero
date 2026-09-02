# motion_student_v2 (causal GRU)

`gru.json` exists. `train_report.json` has `beats_linear_and_heuristic: false`. Held-out speed MAE beat the ZUPT heuristic and freeze, and beat the linear student only on locked_test. It lost to the linear student on validation and public_test. Do not pack this into the APK. Do not implement a Kotlin runtime for a win. The file is for analysis and a later Kotlin port if a later seed or architecture beats linear on every held-out split.

Phone path today still uses `ZuptAccelMotionModel` plus packed `motion_student_v1/linear.json`. v2 is not a drop-in for those 12 pooled features. v2 consumes a gravity-aligned HACF sequence of length 16, channels `[ax, ay, az, gx, gy, gz]` in m/s² and rad/s.

## Forward pass

1. Take the last `align_len` (16) causal 10 Hz samples. If fewer, left-pad with zeros (past, never future).
2. Per channel: `(x - input_mean[c]) / input_std[c]`. Scaler is train-split only.
3. One unidirectional GRU, hidden 32. PyTorch gate order on `weight_ih` / `weight_hh` rows: reset, update, new.

```
ih = W_ih x + b_ih
hh = W_hh h + b_hh
r = sigmoid(ih[0:H] + hh[0:H])
z = sigmoid(ih[H:2H] + hh[H:2H])
n = tanh(ih[2H:3H] + r * hh[2H:3H])
h' = (1 - z) * n + z * h
```

4. Linear head: 8 outputs, `head_weight` is (8, 32).
5. Decode: `speed = min(50, max(0, softplus(raw[0])))`, `stop_logit = raw[1]`, `log_speed_var = clamp(raw[2], -8, 6)`, `yaw = raw[3]`, `log_yaw_var = clamp(raw[4], -8, 6)`, `dx, dy = raw[5], raw[6]`, `log_sigma_xy = clamp(raw[7], -8, 6)`.
6. If `gate_bump` and the pooled `bump_flag` is set, add 1.5 to both log-variances (still clamped). That inflates R. It does not change the speed mean.

## Budget

Multiply-adds: `16 * 3 * (6*32 + 32*32) + 32*8 = 58400`. NFR-02 is p95 under 20 ms. This is a small CPU kernel. Measure on device. Do not claim a latency number from this file.

TimesFM is not in this export.
