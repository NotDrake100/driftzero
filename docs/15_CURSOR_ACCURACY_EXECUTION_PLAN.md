# Cursor execution plan: work toward below 10% drift

Date: 2026-09-06. Planning baseline: main commit
`5a0893fdb321605784f1f492445ad53f2fae47fc`. This is an execution plan, not an
accuracy result or a guarantee. All new work must obey AGENTS.md and the ADRs.

## What success means

Keep the existing 35 interval IDs, masks, endpoints, path-length denominator and
truth gates unchanged. Report two distinct outcomes: full-suite median drift
below 0.10, and the stricter existing accuracy_gate.py requirement that every
interval be below 0.10. A median pass must never be described as an all-interval
pass. Missing predictions and fallback intervals remain in the denominator.
Do not replace the 35-interval benchmark with an easier field subset.

The locked suite has already been inspected repeatedly. Keep it as a regression
benchmark, but do not call another result on it fresh independent validation.
Reserve new session/route/driver/vehicle groups before training where possible;
otherwise acquire new drives. Report unknown grouping metadata honestly.

Mountless has separate levels: (1) a resting unmounted phone on the passenger
seat, console or cup holder, (2) pickup/repositioning and recovery, (3) continuous
handheld motion. A pass at one level does not certify the next. Keep all three
in the scope; stage their implementation and measurement.

## Current evidence, not historical guesses

| Experiment | Evaluation set | Median | p95 | Decision |
|---|---|---:|---:|---|
| Selected deterministic coast | Locked 35 | 48.83% | 268.24% | Target fails |
| Same coast | Development 11 | 39.84% | 54.02% | Comparison baseline |
| Real-map particle coast | Development 11 | 26.90% | 84.61% | Rejected: tail regression |
| Road plus prefix acceleration | Development 11 | 26.90% | 84.61% | Zero accepted acceleration models |

Read [v3 evidence](13_ACCURACY_V3_EXPERIMENT.md) and
[real-map evidence](14_REAL_MAP_EXPERIMENT.md). The failed acceleration experiment
was a small prefix ridge model, not evidence that all neural motion models fail.
The map result supports further investigation, not an Android rollout.

## Why the next attempt is different

First measure the remaining error sources. Then train a causal motion model on
recorded vehicle sequences and make map feedback conditional on evidence. Do not
spend another round changing coast flags or adding frameworks without an isolated
hypothesis. Speed MAE alone is insufficient: evaluate integrated distance, turns,
position drift and uncertainty over actual blackout durations.

An illustrative 1 km engineering budget is 10 m initial-position error, 30 m
speed/distance error, 30 m heading/cross-track error and 20 m remaining error,
leaving 10 m margin below the 100 m limit. This is a design allocation, not an
additive error model or a measured bound. A sustained heading error of roughly
1.7 degrees alone produces about 30 m cross-track error over a straight 1 km.
At 50 m, ordinary seed/reference uncertainty can consume much of the 5 m budget;
short-window claims require correspondingly better reference evidence.

## Launch order and ownership

Use separate branches/worktrees and draft PRs. These are suggested concurrent
work packages, not a statement of Cursor plan limits or GPU availability.
Start with two agents; more concurrency should follow independent work, not
create competing edits to the estimator.

| Task | Start condition | Owns | Deliverable |
|---|---|---|---|
| A: diagnostic audit | Now | New diagnostic module, its tests and reports | Error-source decision and data/split manifest |
| B: map reliability | Now, independent of A | osm_coast.py, map tests, proposed map adapter | Audited topology, uncertainty and map-only ablation |
| C: learned motion | After A freezes usable data and splits | New student module/trainer, model tests | Causal motion model and complete development ablation |
| D: evaluation/integration | After A, then B/C candidates | eval_osm_coast.py or replacement runner, evaluation tests | Frozen selection, full regression and fresh holdout report |
| E: phone placements | Capture audit now; model wiring after D passes | Android logger/alignment/handling and device tests | Actual placement recordings, handling behavior, on-device evidence |
| F: coordinator | Throughout | Integration branch, ADRs, result index | Reviewed milestone merges and final claim audit |

Prompts: [A](cursor-agents/A-diagnostics.md), [B](cursor-agents/B-road-reliability.md),
[C](cursor-agents/C-learned-motion.md), [D](cursor-agents/D-evaluation.md),
[E](cursor-agents/E-phone-validation.md), [F](cursor-agents/F-coordinator.md).
B must expose a small adapter and coordinate runner wiring with D. C and B must
not both edit the replay runner. E must not port unselected research to Android.
No automatic merge of another agent's unfinished branch.

## Phase 1: establish what could close the gap

A must reproduce the baseline before creating counterfactual diagnostics. In a
separate score-only program, reconstruct paths with reference-derived speed,
heading, or a reference-selected road hypothesis, individually and in combination.
Do not inject hidden GNSS into a candidate filter or phone API. Label every such
output `DIAGNOSTIC_ONLY`; accuracy_gate.py must never accept it as candidate evidence.
Reference-derived course/speed are noisy, especially on sparse GNSS. These are
sensitivity experiments, not perfect oracles or provable accuracy ceilings.

Compare seed offset, along/cross-track error, integrated-distance error, missed
stops, turn direction, IMU gaps and road ambiguity. Fix times and scoring epochs
must stay identical. Include reference uncertainty and timestamp alignment audit.
If fixing speed changes little, do not default to speed-network training. If
reference-informed road choice still fails, investigate distance/seed/reference
issues first. If even joint reference substitutions fail unexpectedly, audit the
coordinate/time/scoring implementation before increasing model complexity.

## Phase 2: learn the missing motion information

Proposed first model: compact causal temporal convolution network (TCN), with
one GRU comparator. Start below approximately 250k parameters as an engineering
budget, not an assumed optimal size. Use PyTorch for research; keep deterministic
runtime fallback. Do not add TimesFM to mobile inference.

Inputs: strictly trailing IMU windows, gravity/orientation quality, real sample
intervals and availability masks; last trusted velocity/heading with age and
uncertainty. Separate IO-VNBD column semantics from actual Android device XYZ.
Outputs: incremental horizontal displacement or speed change and turn change,
plus uncertainty and a stopped/moving hypothesis. Select a consistent coordinate
frame and document all transformations before training.

Train with simulated GNSS blackouts, including initialization variation, variable
sample rates, gaps and frozen pre-mask calibration. Supervised reference labels
belong only to training groups. Validation/held-out labels are score-only. Fit
normalizers on training groups only. No centered filters, future attention,
future interpolation or full-trip normalization in inference. Rotation/noise
augmentation does not substitute for real moving-phone recordings.

Use robust displacement/turn losses and a calibrated uncertainty objective.
Evaluate speed hold, existing linear student, the proposed TCN and GRU with the
same inputs and intervals. Check long rollouts, not only single-window loss.
First establish a CPU smoke run, actual throughput and memory use. GPU availability
is not assumed in cloud agents. If full training needs another runner, deliver
an exact resumable job/config and checkpoints; do not report training as finished.
Use available authorized compute, with early stopping and a predeclared run budget.

## Phase 3: couple motion and roads carefully

Improve directed road topology, turn restrictions, dead ends/U-turn policy and
curve geometry. Road choice must use causal observed motion, not the hidden route
or a future destination selected from truth. Route guidance can be an explicitly
separate ablation only when a route is known before the blackout.

Maintain competing road/speed hypotheses through intersections. Test heading and
curvature likelihoods, speed updates and gyro-bias handling separately. The
current posterior mean can fall between roads; do not display it as a lane fix.
Calibrate ambiguity against development errors. Confidence must remain broad or
fall back when the evidence cannot distinguish branches. Do not learn a selector
from locked interval IDs, durations correlated with known outcomes, or filenames.

Freeze a small experiment matrix before running it: deterministic baseline,
model-only, map-only, and combined. Any extra tuning round gets a versioned
manifest with all attempted configurations, including failures.

## Phase 4: selection and proof

Development eligibility: complete predictions with finite metrics; at least 10%
relative median improvement over the fresh deterministic baseline; no worse p95
or count of intervals failing 0.10. This relative gate is NOT the absolute target.
Report map/model activation and fallback rates. A valid causal fallback may count
as coverage, but it must never be hidden or excluded. Record this proposed new
matrix rule before runs; do not retroactively reinterpret ADR 014 results.

Select one candidate, save config/checkpoint hashes, then run the locked 35 once
for that frozen candidate. Also run the reserved fresh holdout if available.
If either fails, report the failure and resume development on development data.
Do not inspect individual fresh holdout failures and continue calling it untouched.
Report median/worst/percentiles, all interval rows, subgroups, coverage and
trip-group uncertainty intervals. A small reused benchmark cannot certify generality.

Only a selected candidate gets mobile integration. Export the model in a verified
format supported by the chosen Android runtime, with deterministic Python versus
Android parity tests. ONNX is an option to evaluate, not an already shipped model.
Measure actual on-device inference latency, 10 Hz output gaps, memory, battery and
thermal behavior. A passing desktop replay is not a phone deployment result.

## Physical evidence the owner must arrange

Cloud agents can implement and analyze the logger; they cannot manufacture drives.
Use the Android app or borrow an Android device for this Android-specific pipeline.
Start with a manageable pilot of roughly 12 separate outdoor drives, spreading
resting placements and including stops, turns, bends and longer straights. This
is an initial coverage target, not sufficient statistical certification. Add
phones, vehicles, drivers and routes as resources allow; keep some drives unopened.

A passenger operates logging and records placement changes. Capture actual IMU
sampling, gravity, gyro, accelerometer, GNSS quality/speed/bearing and monotonic
timestamps. Audit achieved rates rather than claiming requested 100 Hz was attained.
Use good-GNSS outdoor drives with artificial masking for initial truth. Sparse or
poor-quality reference cannot certify 5 m errors. Better reference equipment may
be used for evaluation only, clearly separated from the phone-only product inputs.

For pickup/repositioning, score detection delay, false alarms, uncertainty growth,
recovery and navigation error separately. Add continuous handheld tests after the
resting baseline works, without promising its accuracy from orientation augmentation.

## Reproduction and checkpoint protocol

Existing checks and baseline commands, not commands for unimplemented new modules:

```sh
make validate
make lint
make links
./gradlew :navigation-core:test --no-daemon
./gradlew :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --no-daemon
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

The baseline command requires the pinned raw dataset and JDK 17; follow
.github/workflows/accuracy.yml for data materialization. The current target gate
is expected to fail. Never turn that failure into success by ignoring its exit code
in a release gate. Experiment completion and target attainment are separate statuses.

At every meaningful milestone push the branch: manifest before experiments,
working implementation with tests, then measured evidence. If context or compute
runs low, push a resumable checkpoint immediately. Include exact command, commit,
config/seed, data/map/model hashes, run IDs, last completed step, failures and next
step. No promise of background work after a session ends.

Keep compact raw per-interval tables and manifests in Git, force-adding intended
results when ignored. Store large checkpoints/snapshots in durable owner-accessible
storage with hashes. Actions artifacts expire; a run URL alone is not permanent
reproducibility. Record storage expiry and recovery commands explicitly.

The coordinator merges reviewed, tested milestones into main. Rejected experiments
may be merged as clearly separated research with their negative results, but cannot
change live defaults. Every final status states separately: code merged, experiment
completed, median target passed, all-interval target passed, and field scope tested.

Live campaign tracking (not an accuracy claim):
[docs/16_ACCURACY_CAMPAIGN.md](16_ACCURACY_CAMPAIGN.md) and
[results/cursor_campaign/v1/manifest.json](../results/cursor_campaign/v1/manifest.json).
ADR: [015](adr/015-accuracy-campaign.md).
