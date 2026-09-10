# Long-outage motion experiment

Date: 2026-09-10. Research only. The below-10% objective remains open.

[ADR 019](adr/019-long-outage-motion.md) declares this experiment. It addresses
three limitations of the previous neural round: training ended before the longest
development outages, the TCN had a short receptive field, and the loss allowed
poor motion between labelled positions.

## Implemented changes

Training windows extend from 90 to at most 180 seconds. The new causal TCN uses
five dilations with a 1365-sample receptive field. Both it and the GRU retain the
polar speed/heading output, use adjacent-fix displacement supervision and a loss
that weights the worst training windows more strongly. Training labels never
enter prediction inputs. The ten fresh holdout groups remain closed.

The experiment combines these changes; it cannot isolate their individual effects.
No claim about passenger-seat, pickup or handheld accuracy follows from IO-VNBD.

## Reproduce

Use the pinned dataset and CPU PyTorch runtime in the workflow, then:

```sh
PYTHONPATH=ml/src python -m driftzero_ml.train_joint_sequence \
  --out results/joint_sequence_long --window-s 180 --epochs 48 \
  --checkpoint-every 8 --architectures gru tcn_long \
  --increment-weight 1 --tail-weight 0.5
```

Source: `a1c874b17734d05acaef7dbb239daf702c7cf57b`.
[Measured run](https://github.com/NotDrake100/driftzero/actions/runs/34470745169).
[PR 23](https://github.com/NotDrake100/driftzero/pull/23).

The existing eleven development intervals, baseline flags, truth gates, seed,
split roles and release criteria are unchanged. Complete finite predictions,
zero fallbacks, at least 10% relative median improvement, and no worse p95 or
below-10% failure count are required before locked confirmation. Passing that
relative gate would not establish an absolute below-10% result.

## Verification

Actual CPU PyTorch tests cover polar GRU and long-TCN prefix invariance,
backpropagation, high-speed stops/turns, and loss independence from unlabelled
positions and padded timesteps. Standard masking, coordinates and split tests
remain in place. Ruff and local documentation links pass.


## Completed results

Both experiments completed on all eleven development intervals, with zero omitted
intervals and zero fallbacks. The horizon run scored twelve trained checkpoints;
the integrated run scored eight. Each also evaluated the same physics baseline.
Both used 219 windows from 23 trips in 21 of the 24 approved training groups.

| System or round | Lowest-median checkpoint | Median | p95 | Below 10% | Decision |
|---|---|---|---|---|---|
| Kotlin development baseline | Fixed configuration | 39.84% | 54.02% | 1/11 | Comparator |
| Earlier polar round | GRU 16 | 22.33% | 95.92% | 2/11 | Rejected |
| 180-second horizon and tail loss | GRU 8 | 31.78% | 90.58% | 1/11 | Rejected |
| Smooth speed, integrated heading, projected inputs | MLP 16 | 38.31% | 100.78% | 0/11 | Rejected |

The minimum-median rows are diagnostic summaries, not selected release models.
All twenty trained checkpoints failed the unchanged relative gate. More training
and the new model structures did not solve the accuracy problem. Neither median
below 10% nor all-interval below 10% is achieved. Locked confirmation was not run;
the locked baseline remains 48.83% median, 268.24% p95, 6/35 below 10%.
Android estimation and field-placement validation remain unchanged.

Raw reports and training provenance:

- [Horizon selection and all checkpoint scores](../results/long_motion_20260910/horizon/selection.json).
- [Integrated selection and all checkpoint scores](../results/long_motion_20260910/integrated/selection.json).

## Integrated follow-up reproduction

The smooth-speed and integrated-heading rationale is in ADR 019. Its input set
also changes: the four raw gyro channels are zeroed in training and evaluation.
It cannot isolate the effect of the output head from the input change.

Source: `68beb4c542f5899fdddf9818f6807fa2c73422b5`.
[Integrated run](https://github.com/NotDrake100/driftzero/actions/runs/34472740580).
Extract the horizon run's joint-motion-evidence artifact into research-cache/long:

```sh
PYTHONPATH=ml/src python -m driftzero_ml.train_joint_sequence \
  --out results/joint_sequence_integrated --window-s 180 --epochs 32 \
  --checkpoint-every 8 --architectures mlp tcn \
  --increment-weight 1 --tail-weight 0.5 \
  --cached-run research-cache/long --projected-only --head-mode integrated
```

The cached baseline is unchanged Kotlin replay evidence, not a fresh rerun.
The workflow verifies the training tensor hash. The runner checks split equality,
horizon, sample group membership and exact development interval membership.
A regression test injects a holdout-labelled sample and verifies rejection before
training. Model tests verify finite low-speed gradients and heading causality.

Artifact ZIP SHA256:

- Horizon: `4c3473183e552df501190d0582eb10894f4b8ab34fde38777ec7bf88860db393`.
- Integrated: `11314ae54d11572099c65b2cf4a9af95daedecbecdf869f913fdf854a653920b`.

Artifacts retain weights, tensors and full baseline replay for 30 days. Compact
reports are committed permanently. To regenerate an expired horizon artifact,
use the first source commit and pinned raw-data workflow. Training cache SHA256:
`89bd4da16dad62a5c6f58e9c0f97f2027888a1b9289a2891194f72bf79de2aa7`.
