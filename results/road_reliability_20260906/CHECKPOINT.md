# Task B checkpoint. Experiment completed

Date: 2026-09-06. Branch `cursor/road-reliability-8d5b`.

- Last completed step: five preregistered development ablations. All rejected.
- Commit before results: `147960d`. This file is updated with measured tables.
- Seed 26168. Maps from Actions run `34061769087`. Hashes match ADR 014 complete provenance.
- Baseline reused from that artifact (median 0.398429, p95 0.540176). Not a new Kotlin replay.
- Command: `PYTHONPATH=ml/src python -m driftzero_ml.eval_road_reliability --reuse-baseline`
- Failures: relative development gate failed for every config. No locked run.
- Next step: none for Task B selection. D should not confirm. A later versioned
  matrix could fix 25 m cell fragmentation. It is not declared or run here.
