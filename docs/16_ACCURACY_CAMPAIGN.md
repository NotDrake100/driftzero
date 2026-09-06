# Accuracy campaign index

Date: 2026-09-06. Coordinator checkpoint v1. This is a tracking document, not
an accuracy result. Machine-readable copy:
[results/cursor_campaign/v1/manifest.json](../results/cursor_campaign/v1/manifest.json).
Plan: [docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md](15_CURSOR_ACCURACY_EXECUTION_PLAN.md).
Coordination ADR: [015](adr/015-accuracy-campaign.md).

## Five statuses

| Status | Value | Evidence |
|---|---|---|
| Code merged | No new campaign code on main | Main is `c7a1ab9`. This branch is tracking only. |
| Experiment completed | No | A and B not finished. C/D/E not started. |
| Median target passed | No | Locked selected coast median 48.83% |
| All-interval target passed | No | `accuracy_gate` failed. 6/35 intervals below 10% |
| Field placements tested | No | No new owner drives in this campaign |

Do not describe the 48.83% locked median as a fresh independent validation. The
suite has already been inspected. Missing predictions and fallbacks stay in the
denominator.

## Current measured evidence, already on main

| Experiment | Set | Median | p95 | Decision |
|---|---|---:|---:|---|
| Selected deterministic coast | Locked 35 | 48.83% | 268.24% | Target fails |
| Same coast | Development 11 | 39.84% | 54.02% | Comparison baseline |
| Real-map particle coast | Development 11 | 26.90% | 84.61% | Rejected: tail regression |
| Road plus prefix acceleration | Development 11 | 26.90% | 84.61% | Zero accepted acceleration models |

Sources: [v3](13_ACCURACY_V3_EXPERIMENT.md), [real-map](14_REAL_MAP_EXPERIMENT.md),
[locked gate](../results/accuracy_v3_20260906/development/locked_gate.json),
[ADR 014 decision](../results/road_research_20260906/road_complete/selection.json).

## Pins

| Item | Value |
|---|---|
| Campaign base (this branch) | `c7a1ab9ccf1c156c48da1cac7b6e10ef384f66d4` |
| Plan baseline named in docs/15 | `5a0893fdb321605784f1f492445ad53f2fae47fc` |
| Selected coast implementation | `1cd92fb3d5dc60fea57e944a4862991c714d484d` |
| IO-VNBD pin | `118939602e3422d47b8ab0807b623751c3ac135b` |
| Split/search seed | `26168` |
| Learned checkpoint | None selected. Keep deterministic fallback. |
| Map model | ADR 014 research only. Not live Android. |

## Task board

| Task | Start condition | Branch intent | Status | Blockers |
|---|---|---|---|---|
| F coordinator | Throughout | `cursor/accuracy-campaign-d676` | This checkpoint | Review A/B before merge |
| A diagnostics | Now | `cursor/accuracy-diagnostics` | Launched independently | Needs pinned IO-VNBD and JDK 17 |
| B road reliability | Now, independent of A | `cursor/road-reliability` | Launched independently | Development only. Cached maps may be absent |
| C learned motion | After A freezes data/splits | `cursor/learned-motion` | Blocked on A | Do not train yet |
| D evaluation | After A, then B/C candidates | `cursor/accuracy-evaluation` | Blocked on A then B/C | Owns shared runner |
| E phone validation | Capture audit now. Model wiring after D | `cursor/phone-validation` | Not started | Cloud agents cannot collect drives |

C is blocked until A writes an explicit train/development/fresh-holdout
manifest that excludes locked session siblings and previously exposed
development groups. D must not treat `DIAGNOSTIC_ONLY` reports as candidates.

## Previously exposed groups. Not fresh

Locked session groups derived from `GATED_INTERVAL_IDS` via `session_group_id`:

`S-S1`, `S-S3`, `S-Vfa01`, `S-Vta1`, `S-Vta2`, `S-Vta12`, `S-Vta15`, `S-Vta17`,
`S-Vta20`, `S-Vta22`, `S-Vta24`, `S-Vta25`, `S-Vtb1`, `S-Vtb6`, `S-Vtb8`,
`S-Vtb9`, `S-Vw5`, `S-Vw16`, `S-Y1`.

Development groups already used for ADR 013/014 selection
([manifest](../results/accuracy_v3_20260906/development/manifest.json)):

`S-S4`, `S-Vta5`, `S-Vta6`, `S-Vta8`, `S-Vta11`, `S-Vta13`, `S-Vta29`,
`S-Vtb2`, `S-Vtb7`, `S-Vw3`, `S-Vw12`, `S-Vw17`.

Route and vehicle identities are unavailable in the published IO-VNBD stems.
Driver letter is only the prefix mapping in `ml/src/driftzero_ml/io_vnbd/splits.py`.

## Reproduction of the current locked baseline

Requires the pinned raw dataset and JDK 17. Follow
[.github/workflows/accuracy.yml](../.github/workflows/accuracy.yml) for
materialization. The target gate is expected to fail. Do not ignore that exit
code in a release gate.

```sh
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
```

## Next justified work

1. A reproduces the baseline, freezes splits, and reports which isolated
   reference substitutions move seed, distance, heading, stop, gap, or road
   error. Recommendation for B/C must follow those measurements.
2. B audits topology and uncertainty on development only. Investigate the
   measured p95 move from 54.02% to 84.61%. Deliver a causal adapter or a
   rejection. Do not rewrite ADR 014's recorded rejection.
3. C starts only after A's split freeze. No TimesFM on the phone path.
4. D freezes the matrix after A, then after B/C candidates. One locked-35 run
   after one frozen selection.
5. E audits capture when scheduled. No manufactured drives.

## Resume

If this session ends, resume from `results/cursor_campaign/v1/manifest.json`.
Do not restart completed ADR 013/014 work from old reports. Do not promise
background work after the session ends.
