# ADR 015: Accuracy campaign coordination

Date: 2026-09-06. Status: accepted for this campaign. Not an accuracy result.

## Context

The locked 35-interval suite still fails both below-10% outcomes. The selected
deterministic coast is 48.83% median drift on that reused suite, with 6/35
intervals below 10%. Real-map particles improved development median and worsened
the tail. Prefix acceleration accepted zero models. Further coast-flag search is
not justified until error sources and splits are isolated.

[docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md](../15_CURSOR_ACCURACY_EXECUTION_PLAN.md)
assigns concurrent work packages. Those packages share evaluator contracts and
must not compete for the estimator or rewrite the locked benchmark.

## Decision

1. Track the campaign in a versioned manifest under `results/cursor_campaign/`
   and a human index in [docs/16_ACCURACY_CAMPAIGN.md](../16_ACCURACY_CAMPAIGN.md).
   Update the manifest at every milestone. Do not treat a plan or a CI pass as
   a target pass.

2. File ownership is exclusive until a reviewed merge:

   | Surface | Owner | Must not edit |
   |---|---|---|
   | New score-only diagnostic module, its tests, `results/cursor_diagnostics` | A | `osm_coast.py`, shared replay runner |
   | `osm_coast.py`, map tests, proposed causal map adapter | B | `eval_kotlin_replay.py`, diagnostic module |
   | New student/trainer modules | C | Shared replay runner, map module |
   | Shared experimental runner, selection matrix | D | B/C module internals |
   | Android capture, placement, device tests | E | Unselected research ports |
   | Campaign manifest, ADRs, result index, reviewed merges | F | Unfinished A-E branches |

3. Diagnostic reference substitutions stay in a score-only program. Label every
   such report `DIAGNOSTIC_ONLY`. `accuracy_gate.py` must reject that class as
   candidate evidence, even if every interval is below 0.10. A owns that gate
   rejection if it is not already present.

4. Start A and B independently. Start C only after A freezes usable data and
   splits and the diagnostic module is reviewed. That review is complete: PR 17
   is on main. D may prepare parity and leakage checks now. Candidate selection
   waits for an eligible B or C candidate. E may audit capture immediately and
   may integrate a model only after D selects one.

5. No automatic merge of an unfinished branch. Research rejections may land as
   research with their negative numbers. Failing candidates must not change live
   defaults. Report five statuses separately: code merged, experiment completed,
   median target passed, all-interval target passed, field placements tested.

6. Keep the existing 35 interval IDs, masks, endpoints, path-length denominator
   and truth gates unchanged.

## Consequences

- Overlapping runner edits are an explicit coordinator conflict, not a race.
- Previously exposed locked and development groups are not a fresh holdout.
- IO-VNBD route and vehicle identities are unavailable. Driver letter is only
  the published stem prefix (`S-S` A, `S-M` B, `S-Y` D, `S-V` E).
- This ADR does not change Android defaults or claim a 10% pass.
