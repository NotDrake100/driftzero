# ADR 008: MATCHED road heading, never a position snap

- Status: accepted
- Date: 2026-09-03
- Product path: production (navigation-core + live PoseStore). Not research-only.
- Requirements: SIH-10, SIH-19 in `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`; `docs/05_MAPS_AND_MAP_MATCHING.md` section 5; gate plan 3d

## Context

`HmmRoadMatcher` already returns MATCHED / AMBIGUOUS / UNMATCHED plus a display centreline. ADR 001 and ADR 003 forbid replacing ESKF lat/lon with that centreline. Heading error is the remaining cross-track term after a persist-quality coast (gate plan §2). `RoadHeadingAid` and `DeadReckoningFilter.applyRoadHeading` already existed on this lineage. Soft feedback was not on Replay, so an OSM extract could not be scored.

## Decision

1. **Heading only.** When MATCHED, not near a junction, speed ≥ 1 m/s, posterior / second-best gates pass, and the 1-dof chi-square accepts, apply edge bearing as a Joseph yaw update. Restore east/north/up after inject. Do not write matcher lat/lon into the filter.
2. **One helper.** `RoadHeadingFeedback.apply` is the live and replay owner. `PoseStore.publish` and `Replay.runFilter` / `DeadReckoningEngine` call it. Gates stay in `RoadHeadingAid.decide`.
3. **Replay is opt-in.** `--road-graph=` loads OSM XML, OSM PBF, or `graph.bin`. Default remains no graph so official IO-VNBD hashes stay map-free. This repo has no pinned extract for those UK/Nigeria/France roads.
4. **TimesFM stays off the phone.** This aid is OSM geometry plus the ESKF. No teacher, no checkpoint.

## Consequences

- Cross-track can drop on a single carriageway when the matcher is actually MATCHED. Parallel roads and flyovers stay AMBIGUOUS and apply nothing.
- A wrong MATCHED edge is a parallel-road failure, not a silent snap. Show AMBIGUOUS in the deck.
- IO-VNBD median 0.10 is still not a map claim. Score Pune (or a named extract) separately.
