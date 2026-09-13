# Learned-motion experiment

Date: 2026-09-06. Research preregistration. Not a 10% pass. Not a phone default.

Machine copy: [results/cursor_learned_motion/preregistration.json](../results/cursor_learned_motion/preregistration.json).
ADR: [016](adr/016-learned-motion.md). Split source:
[results/cursor_diagnostics/split_manifest.json](../results/cursor_diagnostics/split_manifest.json).

## Status

| Item | Value |
|---|---|
| Training started | No |
| Selected candidate | None |
| Holdout open | No |
| Phone path changed | No |
| Median target passed | No |
| All-interval target passed | No |

## Frozen groups

Train (24): S-M, S-S2, S-Vfa02, S-Vta3, S-Vta7, S-Vta9, S-Vta10, S-Vta16,
S-Vta21, S-Vta23, S-Vta26, S-Vta28, S-Vtb10, S-Vtb12, S-Vw2, S-Vw4, S-Vw6,
S-Vw7, S-Vw8, S-Vw9, S-Vw11, S-Vw13, S-Vw14, S-Vw15.

Validate on development only. Fresh holdout stays closed: S-Vta4, S-Vta14,
S-Vta19, S-Vta27, S-Vta30, S-Vtb4, S-Vtb5, S-Vtb11, S-Vw1, S-Vw10.

Excluded: S-Vtb3.

## Objective

Joint travelled distance and heading change, with uncertainty and
persist-speed/heading fallback. Primary score is blackout position drift.
Do not select from speed MAE or training loss alone.

## Configs

- `speed_hold`: deterministic fallback
- `linear_joint_v1`: stdlib RIDI-shaped student, CPU
- `ronin_tcn_v1`: preregistered, needs torch, not trained
- `gru_v1`: preregistered, needs torch, not trained

Seed `26168`. No GPU assumed.

## Smoke

```sh
PYTHONPATH=ml/src python3 -c "from driftzero_ml.learned_motion import cpu_smoke, write_preregistration; write_preregistration(); print(cpu_smoke())"
PYTHONPATH=ml/src python3 -m unittest ml.tests.test_learned_motion -v
```

The smoke uses synthetic odometry. It is not an IO-VNBD candidate.
