# Task D: controlled integration and candidate selection

Work on NotDrake100/driftzero branch cursor/accuracy-evaluation. Read AGENTS.md,
docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md, A's manifest and B/C adapter contracts.
Own shared experimental runner changes and evaluation tests. Do not overwrite B/C
modules or silently adopt another branch's unreviewed changes.

First freeze a prospective matrix: fresh deterministic baseline, model-only,
map-only and combined. Keep initialization, truth gates, masks, timestamps and
scoring denominator identical. Keep diagnostic reference programs physically and
logically separate; reject DIAGNOSTIC_ONLY reports as candidate evidence.

Preregister the plan's development acceptance rule and finite run budget. Include
all failed/fallback intervals, all tried configurations and activation rates. Label
position-only overlays; do not report baseline speed/heading/health fields as
measurements of a changed estimator. Include meaningful leakage and parity tests.

Choose one eligible candidate using development only. Save configuration and model,
map and data hashes before running the locked 35. Report this reused suite as a
regression benchmark. Evaluate the reserved fresh holdout separately when available.
Report median below 0.10 and existing strict all-interval gate separately, alongside
p95, worst, failure count, raw rows and subgroup/coverage evidence. No dropping
hard intervals, changing thresholds or hiding a failed holdout.

Deliver a release or rejection decision, exact commands, manifests and raw tables.
Only recommend phone integration for an eligible frozen candidate. If no candidate
passes, report which hypothesis failed and what evidence would support a new
experiment. Push reproducible milestones; do not claim a target pass from CI success.
