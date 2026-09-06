# ADR 015: Causal road overlay adapter (research)

- Status: proposed, research only. Not live Android.
- Date: 2026-09-06
- Product path: Python `ml/` until Task D freezes a passing development
  candidate. Live estimator stays ADR 001. Map display rules stay ADR 003.
- Requirements: SIH-10, SIH-19, SIH-23. Does not change ADR 014's recorded
  rejection.

## Context

ADR 010/014 put a 512-particle road coast on real OSM during IO-VNBD
blackouts. Development median improved and p95 worsened. The overlay always
wrote the posterior mean, including when particles split or left the
deterministic coast. Junctions were uniform random. Grade tags and turn
restrictions were unused. Task B needs a D-facing causal interface that can
fall back without editing the shared replay runner.

## Decision

1. Keep ADR 014 map acquisition: 16 km around the last available GNSS rounded
   to 0.1 degree. Do not change the way query. Preserve cache hashes.
2. Put overlay, confidence, and topology flags in `driftzero_ml.road_adapter`.
   `eval_osm_coast.infer` may call that adapter with ADR 014 defaults.
3. New topology (heading-weighted successors, dead-end U-turn, grade-tag veto,
   restriction parse when relations exist) is opt-in.
4. Confidence-gated overlay is opt-in. Ambiguous split, large raw spread, or
   >150 m disagreement with the causal deterministic coast keeps the baseline
   position. The mean between roads is not a lane fix.
5. Task B never runs locked confirmation and never enables Android defaults.

## Consequences

- D can freeze one named `RoadAdapterConfig` without importing research
  diagnostics from Task A.
- Missing maps, IMU gaps, weak heading, collapsed posteriors, and gated
  epochs remain in the denominator as fallback coverage.
- Restriction relations are absent from current way-only snapshots. Enabling
  the parser does not invent restrictions.
- A passing relative development gate is not an absolute 0.10 pass and is not
  a field-placement result.
