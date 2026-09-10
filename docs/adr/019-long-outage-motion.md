# ADR 019: Train for long outages and penalize motion-increment errors

Date: 2026-09-10. Research only. No Android rollout.

The preceding polar GRU achieved development median 22.33%, p95 95.92%, and
2/11 below 10%. Every candidate failed the existing relative gate. Its two
worst d1000 outages last 117 and 126.1 seconds, beyond the 90-second training
horizon. This is an observed coverage mismatch, not proof of the cause.

Declare a new bounded experiment before executing it:

- Same frozen 24 allowed train groups, same 11 exposed development intervals,
  same 10 closed holdout groups, same source data and seed 26168.
- Extend training windows to at most 180 seconds with the existing twelve starts.
  Retain all training exclusions and record actual accepted group coverage.
- Compare polar GRU and a longer causal TCN, width 24. The latter uses kernel 5
  and dilations 1, 4, 16, 64, 256, giving 1365 sensor samples of receptive field.
  This is approximately 136.5 seconds at 10 Hz, not a fixed-time guarantee.
- Train 48 epochs, checkpoints every eight epochs, giving twelve checkpoints.
- Add equal-weight adjacent-fix displacement loss scaled by the actual elapsed
  time and 2 m/s. These are segment-average velocity labels from TRAINING fixes,
  never interpolated instantaneous truth or model inputs.
- Use half group-weighted mean loss and half worst-quarter batch loss, so rare
  bad training windows have stronger influence. Keep per-window label averaging.
- Keep the current causal initialization, polar head, uncertainty term, optimizer
  and no-regression gate. No locked or fresh-holdout data is used in training.

The horizon, receptive field and loss changes are combined. Any measured gain
cannot be attributed to one change alone. Runtime parameters and source hashes
are recorded. Outputs go to results/joint_sequence_long; raw results must be
retained even if rejected. The workflow never opens the fresh holdout or releases
weights automatically. At least 10% relative median improvement with no worse
p95 or failure count is only a development gate, not an absolute 10% pass.

Tests cover long-TCN and polar output prefix invariance and actual gradients,
plus loss independence from unlabelled target values and padded timesteps.


## Follow-up: explicit heading integration and smooth speed

The completed long-horizon round rejected all twelve trained checkpoints.
Best median was GRU epoch 8, 31.78% median / 90.58% p95 / 1 of 11 below 10%.
Across every tenth accepted training window, near-zero predicted speed occupies
23.46% at GRU 8 and 27.43% at GRU 48; long TCN moves from 21.41% to 35.87%.
These are model diagnostics, not true-stop rates. They do not prove collapse.
The hard clamp nonetheless has zero derivative below its speed boundary.

Declare a follow-up before measuring it: an integrated heading correction
(cumulative tanh(output) radians/second times dt) and smooth speed
55*sigmoid(logit(clamp(seed_speed/55, 1e-5, 1-1e-5))+output). This keeps speeds
strictly between 0 and 55 m/s and retains gradients at finite low outputs.
At zero output, zero/55 m/s seeds differ by at most 0.00055 m/s. Heading still
uses the causal physics prior plus the integrated correction.

Use MLP and short TCN, 32 epochs, checkpoints every eight epochs. Keep the
180-second windows and both loss weights. Use only the 12 projected sensor and
context features; zero the four raw-gyro channels in BOTH training and evaluation.
This allows exact reuse of the previous immutable training/replay artifact without
reading any new data. It is a combined head/input experiment, not an ablation.

Cache source is run 34470745169, artifact 10150025090. The workflow verifies the
training tensor SHA256 before use; manifest split, horizon and every sample's
training role are checked, and development IDs must match the frozen manifest.
Use weights_only=True when loading the cache. The cached baseline is the same
Kotlin implementation; it is not described as a new Kotlin rerun. Artifacts expire
after 30 days; the preceding source commit can regenerate the cache. No fresh
holdout is opened, and all unchanged accuracy gates still apply.
