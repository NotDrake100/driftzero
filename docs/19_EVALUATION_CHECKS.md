# Evaluation checks

Date: 2026-09-06. Prepared checks. Not a candidate selection. Not a 10% pass.

Machine copy: [results/cursor_eval_checks/preregistration.json](../results/cursor_eval_checks/preregistration.json).
ADR: [017](adr/017-evaluation-checks.md).

## Blocked

- Candidate selection
- Locked 35 confirmation
- Fresh holdout inspection
- B posterior-mean overlay
- DIAGNOSTIC_ONLY reports as candidates

## Allowed now

- Locked-ID parity against `GATED_INTERVAL_IDS`
- Gate rejection of oracle and reference-substitution payloads
- Holdout-closed and excluded-group assertions
- Record that B is a measured rejection

## Matrix

| Cell | Status |
|---|---|
| Deterministic baseline | Comparison only |
| Model-only | Waiting on C |
| Map-only | Rejected by B |
| Combined | Blocked |

## Commands

```sh
PYTHONPATH=ml/src python3 -m unittest ml.tests.test_eval_checks ml.tests.test_accuracy_gate -v
```
