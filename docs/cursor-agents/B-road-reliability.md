# Task B: road hypotheses and uncertainty

Work on NotDrake100/driftzero branch cursor/road-reliability. Read AGENTS.md,
docs/15_CURSOR_ACCURACY_EXECUTION_PLAN.md, ADRs 003/010/014 and the real-map report.
Own osm_coast.py or its replacement, map-specific tests and reports. D owns shared
replay wiring; agree on a small causal adapter before changing its interfaces.

Audit directed geometry, one-way roads, intersections, grade separation, turn
restrictions, dead ends and U-turn behavior. Preserve map provenance and select map
bounds only from information available before blackout. No truth-derived corridor.

Investigate why map development p95 worsens from 54.02% to 84.61%. Use development
only for changes. Preregister a small set of hypothesis/likelihood and confidence
ablations. Preserve multiple road/speed hypotheses. Calibrate uncertainty and
ambiguity rather than aggressively snapping or trusting a narrow particle spread.
Implement causal fallback and finite/bounded computation, including missing maps,
gaps, weak heading and contradictory geometry.

Deliver a road-only adapter, tests, all attempted configurations, raw interval
metrics and activation/fallback rates. Establish whether it meets the plan's
prospective development gate. Do not alter ADR 014's recorded rejection. Do not
run locked selection or enable Android defaults. Push tested milestones and give
D an exact frozen configuration and reproduction command, or a rejection report.
