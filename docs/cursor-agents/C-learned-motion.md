# Task C: causal learned motion with uncertainty

Work on NotDrake100/driftzero branch cursor/learned-motion. Read AGENTS.md,
docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md and A's merged diagnostic/split manifest.
Do not train until group exclusions and usable sensor semantics are explicit.
Own new student/training modules and their tests; D owns common evaluation wiring.

Implement one compact causal TCN and one GRU comparator in PyTorch, beginning with
an approximately 250k-parameter budget. Predict short-window displacement or speed
change and turn change, plus uncertainty, conditioned on trailing IMU and the last
trusted state with its age/quality. Document coordinate frames. Follow the error
source diagnosed by A; explain if it justifies a different output objective.

Training uses only approved training groups and training-fitted normalization.
Simulate blackouts, gaps, initialization errors and differing sample rates. Include
rotation augmentation, but do not claim that it validates phone handling. No
centered filters, future context or GNSS values inside candidate blackouts. Add
prefix-invariance tests and separate IO-VNBD column semantics from Android XYZ.

Preregister training configurations, seeds, early stopping and compute budget.
Run a CPU smoke test and measure throughput before full training. Do not assume
cloud-agent GPU access. If another training runner is necessary, push a resumable
job/config and report the requirement. No new paid services without authorization.

Compare speed hold and the existing linear student under identical inputs. Report
integrated-distance, turn, position and uncertainty errors across blackout lengths,
not only training loss or instantaneous speed MAE. Save every attempted model's
manifest and checkpoint hash. Provide D a frozen callable adapter and checkpoints
if eligible; otherwise provide the rejection evidence. No locked-set tuning,
TimesFM mobile dependency, or unverified Android export claim.
