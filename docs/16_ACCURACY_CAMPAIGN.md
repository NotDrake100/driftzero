# Accuracy campaign index

Date: 2026-09-06. Coordinator checkpoint v2. This is a tracking document, not
an accuracy result. Machine-readable copy:
[results/cursor_campaign/v1/manifest.json](../results/cursor_campaign/v1/manifest.json).
Plan: [docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md](15_CURSOR_ACCURACY_EXECUTION_PLAN.md).
Coordination ADR: [015](adr/015-accuracy-campaign.md).

## Five statuses

| Status | Value | Evidence |
|---|---|---|
| Code merged | No | Main is still `c7a1ab9`. No campaign merge. |
| Experiment completed | Partial | A diagnostic run completed. B has no new scores. |
| Median target passed | No | Reproduced locked median 48.83% |
| All-interval target passed | No | `accuracy_gate` failed. 6/35 below 10% |
| Field placements tested | No | No owner drives |

Do not describe the 48.83% locked median as a fresh independent validation.

## Live task evidence

| Task | Agent lifecycle | Branch | Latest commit | PR | Experiment |
|---|---|---|---|---|---|
| A diagnostics | IDLE | `cursor/accuracy-diagnostics-9aba` | `8913eb08beb466f706a6b872892eb10905f37c05` | [17](https://github.com/NotDrake100/driftzero/pull/17) | Reproduced locked 35. Matches archive. DIAGNOSTIC_ONLY substitutions scored. Not merged. |
| B road reliability | RUNNING | `cursor/road-reliability-8d5b` | `147960d1a21e1a891701feacf5612ed2d6f3d9f9` | [19](https://github.com/NotDrake100/driftzero/pull/19) | Adapter and preregistration only. No new interval scores. |
| E capture audit | Coordinator | `cursor/phone-validation-d676` | see PR 18 | [18](https://github.com/NotDrake100/driftzero/pull/18) | Logger audit. 0 field drives. Model wiring blocked. |
| C learned motion | Not started | | | | Blocked. Split is frozen. Do not train until A PR 17 is reviewed for merge. |
| D evaluation | Not started | | | | Blocked on B/C candidates. |

First check found A and B as lifecycle RUNNING with no branch, no PR, no diff.
That is not experiment evidence. Git branches appeared later.

## A measured report, reviewed not merged

Source: `results/cursor_diagnostics/` on `8913eb0`. `accuracy_gate` exit 1.

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
accuracy. Seed error is 0 m on every locked interval.

## Split freeze

Coordinator reviewed `split_manifest.json`. Roles are disjoint. Fresh holdout
is not previously exposed. C must not train on locked, development, or
fresh-holdout groups.

- Train (24): S-M, S-S2, S-Vfa02, S-Vta3, S-Vta7, S-Vta9, S-Vta10, S-Vta16,
  S-Vta21, S-Vta23, S-Vta26, S-Vta28, S-Vtb10, S-Vtb12, S-Vw2, S-Vw4, S-Vw6,
  S-Vw7, S-Vw8, S-Vw9, S-Vw11, S-Vw13, S-Vw14, S-Vw15
- Fresh holdout (10): S-Vta4, S-Vta14, S-Vta19, S-Vta27, S-Vta30, S-Vtb4,
  S-Vtb5, S-Vtb11, S-Vw1, S-Vw10
- Excluded: S-Vtb3 (speed column unit mismatch)

C stays blocked until PR 17 is reviewed for merge of the diagnostic module.

## B status

Preregistered five configs. No `comparison.json`. Do not merge as a default.
ADR 014 rejection stands. The published p95 move 54.02% to 84.61% is
along-track shortfall on S-Vta8:d1000 and a wrong-road row on S-Vta29:d1000,
from existing CSVs, not a new run.

## E status

Capture audit in [docs/17_CAPTURE_AUDIT.md](https://github.com/NotDrake100/driftzero/blob/cursor/phone-validation-d676/docs/17_CAPTURE_AUDIT.md)
on PR 18. IMU accuracy codes, GNSS mock/vertical accuracy, and ring-drop
counts. `ANDROID_HOME` missing in this workspace, so Android app tests were
not run here. No field zip.

## Previously exposed groups. Not fresh

Locked: `S-S1`, `S-S3`, `S-Vfa01`, `S-Vta1`, `S-Vta2`, `S-Vta12`, `S-Vta15`,
`S-Vta17`, `S-Vta20`, `S-Vta22`, `S-Vta24`, `S-Vta25`, `S-Vtb1`, `S-Vtb6`,
`S-Vtb8`, `S-Vtb9`, `S-Vw5`, `S-Vw16`, `S-Y1`.

Development: `S-S4`, `S-Vta5`, `S-Vta6`, `S-Vta8`, `S-Vta11`, `S-Vta13`,
`S-Vta29`, `S-Vtb2`, `S-Vtb7`, `S-Vw3`, `S-Vw12`, `S-Vw17`.

Route and vehicle identities are unavailable. Driver letter is prefix-only.

## Next justified work

1. Review A PR 17 tests before any merge. Keep DIAGNOSTIC_ONLY out of
   `accuracy_gate` candidates.
2. Wait for B to write scored development tables. Do not merge PR 19 without
   them.
3. Keep C blocked until that A review. Then train only on the frozen train
   groups.
4. Keep D blocked. Keep E model wiring blocked.

## Resume

Resume from this file and `results/cursor_campaign/v1/manifest.json`. Do not
restart A's completed diagnostic run. Do not promise work after this session.
