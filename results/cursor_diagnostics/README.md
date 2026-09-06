# Cursor diagnostics (Task A)

Date: 2026-09-06. Scope: score-only. Label: DIAGNOSTIC_ONLY.
This folder is not candidate accuracy evidence. `accuracy_gate.py` rejects
these reports even when every interval would numerically pass.

Read `FINDINGS.md` for the measured error budget and B/C recommendations.

## Status

| Claim | Status |
|---|---|
| Diagnostic module and tests | Present |
| Frozen train / development / fresh-holdout manifest | `split_manifest.json` |
| Selected deterministic baseline reproduced | Yes. Matches archive |
| Target gate | Failed. Exit code 1 |
| Median target passed | Failed. 48.83% |
| All-interval target passed | Failed |
| Field placements tested | Failed |

The archived locked result remains median 48.83%, p95 268.24%, 6/35 below 10%.
This machine matched that archive. The archive was not replaced.

## Pins

- Launch main: `c7a1ab9ccf1c156c48da1cac7b6e10ef384f66d4`
- IO-VNBD: `118939602e3422d47b8ab0807b623751c3ac135b`
- Seed: `26168`
- Coast flags: `--weak-heading-policy=hold_course --gnss-reseed-after-s=3 --gnss-reseed-min-median-unique-s=2 --gnss-reseed-while-fused --coast-latch-gnss-speed --coast-mode=yaw_speed_hold`

## Split honesty

Route and vehicle identities are unavailable in published IO-VNBD stems.
Driver letter is only the prefix mapping (S-S=A, S-M=B, S-Y=D, S-V=E).

Locked session groups and ADR 013/014 development groups are previously
exposed. They are not fresh. Locked siblings are excluded from train.
S-Vtb3 is excluded (speed column matches neither m/s nor km/h).
Fresh holdout groups were reserved by SHA-256 order with seed 26168 before
reading outcomes.

## Reproduce

```sh
PYTHONPATH=ml/src python -m unittest ml.tests.test_diagnostics ml.tests.test_accuracy_gate -v
PYTHONPATH=ml/src python -m driftzero_ml.diagnostics --out results/cursor_diagnostics --manifest-only

git clone https://github.com/onyekpeu/IO-VNBD.git data/raw/io_vnbd
git -C data/raw/io_vnbd checkout 118939602e3422d47b8ab0807b623751c3ac135b
git -C data/raw/io_vnbd lfs pull --include='**/Categorised IOVNB Dataset/**/S-*.csv'

export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64
PYTHONPATH=ml/src python -m driftzero_ml.eval_kotlin_replay \
  --out results/cursor_baseline --system latch_sparse_reseed \
  --coast-mode=yaw_speed_hold --reexport --rerun --skip-sensitivity \
  --replay-arg=--weak-heading-policy=hold_course \
  --replay-arg=--gnss-reseed-after-s=3 \
  --replay-arg=--gnss-reseed-min-median-unique-s=2 \
  --replay-arg=--gnss-reseed-while-fused \
  --replay-arg=--coast-latch-gnss-speed
PYTHONPATH=ml/src python -m driftzero_ml.accuracy_gate \
  results/cursor_baseline/metrics_latch_sparse_reseed.json \
  --out results/cursor_baseline/target_gate.json
# Expect exit code 1. Do not ignore it.

PYTHONPATH=ml/src python -m driftzero_ml.diagnostics \
  --out results/cursor_diagnostics \
  --states-dir results/cursor_baseline/states \
  --reproduced-metrics results/cursor_baseline/metrics_latch_sparse_reseed.json
```
