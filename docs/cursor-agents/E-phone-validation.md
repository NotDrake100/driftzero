# Task E: mountless phone evidence and deployment

Work on NotDrake100/driftzero branch cursor/phone-validation. Read AGENTS.md,
docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md, ADR 011, SensorFrame/NavigationState
contracts and the Android logger/alignment code. Own Android-specific recording,
placement, handling and device tests; coordinate estimator interfaces with D.

Start by auditing capture completeness, actual sampling intervals, monotonic clocks,
GNSS speed/bearing/accuracy, gravity and raw sensor semantics. Provide the owner
an exact capture/export procedure using existing app capabilities where possible.
Cloud agents cannot collect physical drives. Never replace that evidence with
synthetic fixtures or generated trajectories. See the plan for the initial pilot.

Treat resting passenger seat/console/cup holder, pickup/repositioning and continuous
handheld as separate validation slices. Preserve navigation state while handling
invalidates alignment; suppress unsupported constraints, increase uncertainty and
measure recovery. Do not require a physical mount for resting-phone operation.

Only after D selects a candidate: port/integrate it behind a reversible gate,
verify Python/Android output parity, validate exported model operators and runtime,
and retain deterministic offline fallback. Measure real-device inference latency,
10 Hz output gaps, thermal, battery and memory behavior. Sensor replay/unit tests
do not certify field accuracy. No OBD/cloud/camera requirement silently added.

Deliver tested code, owner-facing capture instructions, actual results when drives
are provided, and explicit remaining untested placements/devices. Push checkpoints
and PRs. Do not claim the 10% target or universal mountless operation without the
corresponding complete measurements.
