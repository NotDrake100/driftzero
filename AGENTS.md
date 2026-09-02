# DriftZero agent instructions

These rules apply to every coding agent working in this repository.

## Mission

Build a reproducible Android-first resilient navigation product named DriftZero. Preserve the product constraints in `PRD.md` and the requirement traceability in `docs/01_REQUIREMENTS_TRACEABILITY.md`.

## Non-negotiable engineering rules

1. Do not place TimesFM 3 in the mobile real-time loop. It is an optional desktop research adapter and teacher only.
2. Do not use GNSS labels, future samples, full-trajectory normalization, or map-derived truth inside a masked blackout.
3. Split datasets by complete trip and grouped route/driver/vehicle. Never use a random row split.
4. Keep the deterministic filter and conservative fallback usable when the learned model is absent.
5. Every sensor value must have documented units, axes, timestamp domain, availability, and quality.
6. Keep algorithms causal. Any smoother using future observations is evaluation-only and must be labeled.
7. Never manufacture metrics, traces, screenshots, satellite counts, or benchmark results.
8. Do not aggressively snap to a road when hypotheses are ambiguous. Emit uncertainty.
9. The consumer flow must not depend on OBD-II, vehicle speedometer, network, LiDAR, or custom hardware.
10. Treat privacy, confidence, and failure behavior as core product features.
11. Follow `.cursor/rules/design-anti-vibecode.mdc`. The UI is a field instrument, not a SaaS landing page.
12. Follow `.cursor/rules/code-quality.mdc`. Remove dead code as you go. Do not add demo-only junk.

## Change protocol

- Read the relevant ADR and contract before modifying a boundary.
- Add tests before or with algorithm changes.
- Record experiment configuration and seed.
- Add a short ADR for a new architectural decision.
- Update requirement traceability when behavior changes.
- For performance claims, attach the evaluation manifest and raw per-trip table.

## Definition of done for a code task

- Tests pass locally.
- Formatting and static checks pass.
- No hard-coded secrets or machine-specific paths.
- Offline behavior is preserved.
- Edge cases and fallbacks are tested.
- Documentation states whether the change is prototype, research-only, or production-path.

