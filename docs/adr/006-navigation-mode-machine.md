# ADR 006: Five navigation modes are produced by the filter, not the UI

- Status: accepted
- Date: 2026-09-03

## Context

`NavigationMode` and `contracts/navigation_state.schema.json` list five modes. Until this change `DeadReckoningFilter.poseAt` emitted three (`GNSS_FUSED`, `DEAD_RECKONING`, `LOW_CONFIDENCE`). `GNSS_DEGRADED` and `REACQUIRING` existed only in the schema and the PRD. The map lamp needs all five, and PRD FR-06 requires reacquisition to need consecutive plausible fixes.

## Decision

The mode is decided in one place, `DeadReckoningFilter.poseAt`, from state the filter already owns. No UI heuristics, no map input, no second copy of the rule.

1. `LOW_CONFIDENCE` when horizontal 95 percent radius exceeds `InsConfig.lowConfidenceRadiusM` (120 m). Highest priority.
2. `DEAD_RECKONING` when GNSS is held or the last accepted fix is older than 2 s. Entering it marks `coastedSinceFix` and resets the reacquire count. A gap longer than 2 s between two accepted fixes marks the same flag even if no pose was queried in between.
3. `REACQUIRING` while `coastedSinceFix` is set and fewer than `InsConfig.reacquireFixes` (3) consecutive fixes have passed the innovation gate. A gated fix resets the count.
4. `GNSS_DEGRADED` when the last accepted fix reported accuracy above `InsConfig.degradedAccuracyM` (30 m) or a fix was rejected by the innovation gate within `InsConfig.gatedRecentS` (5 s).
5. `GNSS_FUSED` otherwise.

`gnssHealth.riskFlags` carries the reason as strings the UI can read without a schema change: `stale_gnss`, `poor_accuracy`, `gated_fix`, `reacquiring`.

The Kalman correction on the first fix after a coast is still applied in one step. That is correct for the estimate. The display puck blends through `PuckInterpolator` in the app so the eye sees a 0.5 s slide, never a jump. Blending inside the filter would corrupt covariance.

`CONFIG_ID` gains the suffix `modes_v2_degraded_30m_reacquire_3` so replays record which mode rule produced a trace.

## Consequences

- Schema unchanged. `ml/` contracts unchanged.
- Satellite status and map consistency listed in FR-06 are not yet mode inputs. `NavicMonitor` counts are display only and the matcher runs after the filter. Adding them is a later ADR.
- Tests: `DeadReckoningFilterTest` covers degraded on poor accuracy, coast to reacquiring to fused after three fixes, held release, gated fix timing, and a fix gap without pose queries.
