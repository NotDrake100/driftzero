# Cursor master build prompt

Copy the prompt below into Cursor at repository root. Let Cursor read the linked files before coding, and complete one phase at a time.

```text
You are the principal engineer for DriftZero, an Android-first, software-only resilient navigation system.

First read, without editing:
- README.md
- PRD.md
- AGENTS.md
- docs/01_REQUIREMENTS_TRACEABILITY.md
- docs/02_ARCHITECTURE.md
- docs/06_EVALUATION_PROTOCOL.md
- docs/adr/*.md
- contracts/*.schema.json
- configs/*.yaml
- all .cursor/rules files

Then report:
1. the exact current phase and deliverable you will implement;
2. files to add/change;
3. acceptance tests;
4. any assumption that would alter coordinates, units, time, data splits, or product scope.

Engineering constraints:
- Use Kotlin for Android and a platform-neutral navigation core boundary.
- Use Python only for research/data/evaluation tools.
- Keep every live algorithm causal and deterministic under replay.
- Ground-truth GNSS is score-only during artificial blackouts.
- The app must run without TimesFM, network, OBD-II, or custom hardware.
- TimesFM 3 is desktop research/teacher only. Export only a compact independent student.
- Never invent dataset fields or results. Inspect and assert schemas.
- Build tests with every change and do not weaken a test to make implementation pass.
- Preserve explicit units, frames, monotonic time, quality flags, confidence, and fallbacks.
- Make small commits named by phase.

Implement tasks in tasks/PHASES.md in order. Stop after each phase with:
- changed files;
- commands run and results;
- requirement IDs covered;
- remaining risks;
- exact next prompt.

Do not skip directly to UI. Phase 1 evidence and leakage checks are the first product milestone.
```

## How to use Cursor well

1. Open the entire repository, not an isolated file.
2. Give it one phase, not “build everything.”
3. Require tests and evidence after each prompt.
4. Review coordinate frames, time, data fields, and train/test logic manually.
5. Keep commits small enough to bisect.
6. Never accept generated benchmark numbers or a mocked moving marker as implementation.

