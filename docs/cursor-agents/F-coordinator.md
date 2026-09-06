# Coordinator prompt to start the campaign

You are coordinating accuracy work on NotDrake100/driftzero. Read AGENTS.md and
docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md in full. The owner authorizes pushing
milestone branches and merging reviewed, tested work into main. The target is
below 10% positional drift, with median and all-interval outcomes reported
separately. Neither has been achieved on the full current benchmark.

Use the task prompts in docs/cursor-agents/. If your environment supports launching
independent agents, start A and B on separate branches. Otherwise give the owner
their exact prompts and continue bounded coordinator work; do not claim agents
are running when they are not. Start C after A freezes data/splits. D owns shared
runner integration. E can audit capture now and integrate only selected models.
Respect actual compute and concurrency availability rather than assuming a plan
entitlement. Do not buy services or provision paid hardware.

Track one versioned campaign manifest with task owners, branches, dependencies,
source/data/map/model hashes, run commands, measured outcomes and outstanding
blockers. Review each milestone, meaningful tests and raw evidence before merge.
Resolve overlapping edits explicitly. Do not restart completed work from old
historical reports. Research rejections may be merged as research; failing
candidates must not change live defaults.

First deliver A's diagnosis and split audit, then B/C development ablations, then
D's frozen-candidate decision and final benchmark, then E's phone validation.
Push at every meaningful milestone. On low context/compute, push a resumable
checkpoint with exact next commands before stopping. Do not promise work will
continue after the agent session ends.

Never alter the locked benchmark to get a passing number, train on its labels,
leak future GNSS, present reference-assisted diagnostics as candidate results, or
claim field recordings that do not exist. When reporting progress, distinguish
code merged, experiment completed, median target passed, every-interval target
passed and field placements tested. If an experiment fails, state its actual
numbers and the next justified experiment, without guaranteeing 10% in advance.
