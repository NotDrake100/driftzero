# Task A: establish the error budget and data integrity

Paste this task into a cloud agent working on NotDrake100/driftzero:

Read AGENTS.md, docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md, docs/13_ACCURACY_V3_EXPERIMENT.md,
docs/14_REAL_MAP_EXPERIMENT.md and the relevant ADRs. Work on branch
cursor/accuracy-diagnostics. Own a new score-only diagnostic module, its tests,
and results/cursor_diagnostics; coordinate any common evaluator edits with D.

Reproduce the selected deterministic baseline with pinned data and the exact
existing 35 masks and scoring rules. Audit timestamps, sensor units/axis semantics,
unique-fix cadence, seed errors, reference uncertainty and per-trip grouping. Freeze
an explicit train/development/fresh-holdout manifest, excluding locked session
siblings from training and selection. Identify previously exposed groups; do not
call them fresh. Report unavailable route/vehicle/driver identities.

Implement isolated reference-substitution diagnostics for speed, heading and road
choice, singly and jointly. Hidden reference stays in a score-only program, never
the candidate inference API. Label outputs DIAGNOSTIC_ONLY and prevent candidate
accuracy gates from accepting them. Sparse reference derivatives are approximate.
Keep masks, epochs and denominators identical; label unsupported diagnostics.

Deliver per-interval error attribution with seed, distance, heading, stop, gap and
road-ambiguity evidence. State which improvement could plausibly close the gap and
which would not. Add leakage/coordinate/time regressions where necessary. Do not
retune the benchmark or claim oracle-assisted performance as achieved accuracy.

Push the manifest checkpoint, tested implementation, and raw findings. Finish with
exact reproduction commands and a concrete recommendation for B/C. If blocked,
push the diagnostic evidence and exact missing prerequisite. No fabricated results.
