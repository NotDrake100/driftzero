# Accuracy campaign index

Date: 2026-09-10. Coordinator checkpoint v13. This is a tracking document, not
an accuracy result. Machine-readable copy:
[results/cursor_campaign/v1/manifest.json](../results/cursor_campaign/v1/manifest.json).
Plan: [docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md](15_CURSOR_ACCURACY_EXECUTION_PLAN.md).
Coordination ADR: [015](adr/015-accuracy-campaign.md).

## Five statuses

| Status | Value | Evidence |
|---|---|---|
| Code merged | Partial | Diagnostics, capture logger, and C research trainer are on main. B overlay is not. No live candidate. |
| Experiment completed | Partial | A done. B rejected. C rounds 1-5 rejected. No candidate. |
| Median target passed | No | Reproduced locked median 48.83% |
| All-interval target passed | No | `accuracy_gate` failed. 6/35 below 10% |
| Field placements tested | No | No owner drives |

Do not describe the 48.83% locked median as a fresh independent validation.

## Live task evidence

| Task | Status | Branch | PR | Experiment |
|---|---|---|---|---|
| A diagnostics | Merged | `cursor/accuracy-diagnostics-9aba` | [17](https://github.com/NotDrake100/driftzero/pull/17) | Score-only. Locked 35 reproduced. Split frozen. |
| B road reliability | Closed rejection | `cursor/road-reliability-8d5b` | [19](https://github.com/NotDrake100/driftzero/pull/19) | Relative gate failed. Overlay not enabled. Not merged. |
| C learned motion | Rounds 1-5 rejected | `codex/long-outage-motion` | [23](https://github.com/NotDrake100/driftzero/pull/23) | Long-horizon polar and integrated head rejected. Not a candidate. Holdout closed. |
| D evaluation | Checks prepared | `cursor/accuracy-evaluation-d676` | [21](https://github.com/NotDrake100/driftzero/pull/21) | Parity and leakage only. Selection blocked. |
| E capture audit | Logger merged | `cursor/phone-validation-d676` | [18](https://github.com/NotDrake100/driftzero/pull/18) | 0 field drives. Placement validation pending. |

## A measured report, merged

Source: `results/cursor_diagnostics/` at merge `18e6772`. `accuracy_gate` exit 1.

| Result | Value |
|---|---|
| Archive match | true |
| Locked median / p95 | 48.83% / 268.24% |
| Intervals below 10% | 6/35 |
| ref_speed median | 34.08% DIAGNOSTIC_ONLY |
| ref_heading median | 16.29% DIAGNOSTIC_ONLY |
| ref_road median | 16.29% DIAGNOSTIC_ONLY |
| ref_joint median | ~0 DIAGNOSTIC_ONLY |

Oracle joint near-zero is a coordinate/time/scoring audit. It is not achieved
accuracy. Seed error is 0 m on every locked interval. Heading plus distance is
the remaining gap.

## Split freeze

Roles remain disjoint. C may now train on the 24 train groups. Fresh holdout
stays closed.

- Train (24): S-M, S-S2, S-Vfa02, S-Vta3, S-Vta7, S-Vta9, S-Vta10, S-Vta16,
  S-Vta21, S-Vta23, S-Vta26, S-Vta28, S-Vtb10, S-Vtb12, S-Vw2, S-Vw4, S-Vw6,
  S-Vw7, S-Vw8, S-Vw9, S-Vw11, S-Vw13, S-Vw14, S-Vw15
- Fresh holdout (10): S-Vta4, S-Vta14, S-Vta19, S-Vta27, S-Vta30, S-Vtb4,
  S-Vtb5, S-Vtb11, S-Vw1, S-Vw10
- Excluded: S-Vtb3 (speed column unit mismatch)

## B measured report, closed not merged

Source: `results/road_reliability_20260906/` on `e161bff`. Relative gate
failed. Locked confirmation was not run. The posterior-mean overlay is not
enabled on main. Do not start another map-only pass without a new hypothesis.

| Config | Median | p95 | Gate |
|---|---:|---:|---|
| `adr014_reproduce` | 26.90% | 84.61% | fail p95 |
| `topo_v1` | 24.25% | 68.17% | fail p95 |
| `confidence_v1` | 39.84% | 54.02% | fail median |
| `combined_v1` | 39.84% | 54.02% | fail median |
| `lateral_heal_v1` | 39.84% | 54.02% | fail median |

## C status

PR 20 remains the coordinator preregistration (ADR 016). Owner PR 22
merged to main at `b80a2aa` as research. Android defaults were not changed.
Rounds 1-5 rejected. Locked confirmation off. Holdout closed.

Development 11, physics `seed_gyro` 40.07% / 58.95% / 1/11 below 0.10.
Kotlin selected-coast comparison remains 39.84% / 54.02% / 1/11.

| Round | Best median checkpoint | Median | p95 | below 0.10 | Decision |
|---|---|---:|---:|---:|---|
| Initial Cartesian | `tcn_12` | 33.93% | 88.77% | 0/11 | reject |
| `raw_gyro_v2` | `tcn_8` | 30.06% | 120.81% | 1/11 | reject |
| `polar_balanced_v3` | `gru_16` | 22.33% | 95.92% | 2/11 | reject |
| `long_outage_v4` | `gru_8` | 31.78% | 90.58% | 1/11 | reject |
| `integrated_v5` | `mlp_16` | 38.31% | 100.78% | 0/11 | reject |

Round 4 evidence: Actions [34470745169](https://github.com/NotDrake100/driftzero/actions/runs/34470745169)
at `a1c874b`. Copied selection: `results/cursor_campaign/v1/long_outage_v4/`.
`selected` is null. `fresh_holdout_open` is false. `gru_8` beat the 10%
relative median gate versus `seed_gyro` (31.78% vs 36.06% threshold) and
failed p95 (90.58% vs 58.95%). All twelve trained checkpoints rejected.
219 train windows. `holdout_read` false. Training tensor SHA256
`89bd4da16dad62a5c6f58e9c0f97f2027888a1b9289a2891194f72bf79de2aa7`.
The 180 s horizon did not fix the tail.

Owner [PR 23](https://github.com/NotDrake100/driftzero/pull/23) HEAD
`68beb4c` ran the integrated-heading follow-up on that frozen cache.
Actions [34472740580](https://github.com/NotDrake100/driftzero/actions/runs/34472740580).
Copied selection: `results/cursor_campaign/v1/integrated_v5/`. `selected`
is null. Cache SHA matched. `holdout_read` false. `projected_only` true.
Best median `mlp_16` 38.31% failed the relative median gate (threshold
36.06%) and failed p95 (100.78% vs 58.95%). `tcn_32` reached 2/11 below
10% and still failed median and p95. Combined head and input change, not
an ablation. Do not merge PR 23 as a live default.

Do not run locked confirmation. Polar `gru_16` remains the best C median
and is not a candidate. A later C round needs an isolated tail
hypothesis, not another combined architecture, head, and input change.

## D checks

D's eval-check module remains a gate, not a selector. SELECTION_OPEN stays
false. No C config is eligible. `mlp_16` (38.31% / 100.78% / 0/11) and
`gru_8` (31.78% / 90.58% / 1/11) failed the `seed_gyro` 40.07% / 58.95%
development bar. Polar `gru_16` is also not a candidate.

ADR [017](https://github.com/NotDrake100/driftzero/blob/cursor/accuracy-evaluation-d676/docs/adr/017-evaluation-checks.md).
Locked IDs unchanged. DIAGNOSTIC_ONLY and oracle reports rejected. Matrix
unselectable. Selection and locked confirmation stay blocked until an eligible
candidate exists.

## E owner capture

Logger fields from PR 18 are on main. Physical placement validation remains
pending. Exact steps:

1. Install the APK. Complete first-run still capture, then a short straight
   drive for yaw alignment. Precise location is required.
2. Map, overflow, Settings. Turn on Record trips. Confirm REC on the bottom
   strip.
3. Keep one placement for the whole trip. Resting passenger seat, console or
   cup holder is slice 1. Pickup is a separate trip. Handheld is a later trip.
4. Passenger sidecar: start placement, every pickup, device model, Android
   version, intended Hold intervals.
5. For a scored blackout, long-press the mode lamp when speed is below 8 m/s.
   Long-press again to release.
6. Settings, turn Record trips off. Map, overflow, Trips, Export. Share the zip.
7. Cite `measured_accel_hz` and `accel_max_dt_ns` from `manifest.json`. Do not
   cite 100 Hz from the request string.

Full procedure: [docs/17_CAPTURE_AUDIT.md](17_CAPTURE_AUDIT.md).

## Previously exposed groups. Not fresh

Locked: `S-S1`, `S-S3`, `S-Vfa01`, `S-Vta1`, `S-Vta2`, `S-Vta12`, `S-Vta15`,
`S-Vta17`, `S-Vta20`, `S-Vta22`, `S-Vta24`, `S-Vta25`, `S-Vtb1`, `S-Vtb6`,
`S-Vtb8`, `S-Vtb9`, `S-Vw5`, `S-Vw16`, `S-Y1`.

Development: `S-S4`, `S-Vta5`, `S-Vta6`, `S-Vta8`, `S-Vta11`, `S-Vta13`,
`S-Vta29`, `S-Vtb2`, `S-Vtb7`, `S-Vw3`, `S-Vw12`, `S-Vw17`.

Route and vehicle identities are unavailable. Driver letter is prefix-only.

## Next justified work

1. Do not start another combined C round. A later attempt needs an isolated
   tail hypothesis. Keep the holdout closed.
2. Keep D selection closed until an eligible C config exists.
3. Do not merge PR 19 or PR 23 as a live default.
4. Owner field drives remain required for placement validation.
5. Pin exact frozen group IDs on the trainer remains a follow-up.

## Resume

Resume from this file and `results/cursor_campaign/v1/manifest.json`. A, the
E logger, and the C research trainer are on main. B is closed and not merged.
C rounds 1-5 are rejected. Do not restart A's diagnostic run. Do not treat
polar `gru_16`, long-horizon `gru_8`, or integrated `mlp_16` as a candidate.
