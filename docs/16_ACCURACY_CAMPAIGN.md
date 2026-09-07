# Accuracy campaign index

Date: 2026-09-07. Coordinator checkpoint v5. This is a tracking document, not
an accuracy result. Machine-readable copy:
[results/cursor_campaign/v1/manifest.json](../results/cursor_campaign/v1/manifest.json).
Plan: [docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md](15_CURSOR_ACCURACY_EXECUTION_PLAN.md).
Coordination ADR: [015](adr/015-accuracy-campaign.md).

## Five statuses

| Status | Value | Evidence |
|---|---|---|
| Code merged | Partial | Diagnostics PR 17 and capture-audit PR 18 are on main. B overlay is not. C/D are not. |
| Experiment completed | Partial | A diagnostics done. B rejected. C training opened on PR 22. No candidate. |
| Median target passed | No | Reproduced locked median 48.83% |
| All-interval target passed | No | `accuracy_gate` failed. 6/35 below 10% |
| Field placements tested | No | No owner drives |

Do not describe the 48.83% locked median as a fresh independent validation.

## Live task evidence

| Task | Status | Branch | PR | Experiment |
|---|---|---|---|---|
| A diagnostics | Merged | `cursor/accuracy-diagnostics-9aba` | [17](https://github.com/NotDrake100/driftzero/pull/17) | Score-only. Locked 35 reproduced. Split frozen. |
| B road reliability | Closed rejection | `cursor/road-reliability-8d5b` | [19](https://github.com/NotDrake100/driftzero/pull/19) | Relative gate failed. Overlay not enabled. Not merged. |
| C learned motion | Training opened | `codex/joint-motion-real-data` | [22](https://github.com/NotDrake100/driftzero/pull/22) | Real-data joint sequence. Holdout closed. No candidate yet. Preregistration remains [20](https://github.com/NotDrake100/driftzero/pull/20). |
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
(`codex/joint-motion-real-data`, ADR 018) is the real-data training pipeline.
It scores development blackout drift and keeps the holdout closed. It does not
yet pin the exact 24/10/1 group IDs in code. That is a merge blocker, not a
license to open the holdout. No measured candidate. Do not run locked
confirmation from this PR.

## D checks

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

1. Land exact frozen-group pins on PR 22, then wait for measured development
   tables. Do not open the holdout.
2. Keep D selection closed until an eligible C config exists.
3. Do not merge PR 19 as a live map default.
4. Owner field drives remain required for placement validation.

## Resume

Resume from this file and `results/cursor_campaign/v1/manifest.json`. A and the
E logger are on main. B is closed. C training is PR 22. Do not restart A's
diagnostic run.
