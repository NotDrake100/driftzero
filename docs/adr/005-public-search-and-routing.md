# ADR 005: Public place search and driving routes on the existing MapLibre map

- Status: accepted
- Date: 2026-09-02
- Scope: prototype, production-path chrome, not the live estimator

## Context

The first screen already has a MapLibre `StreetMap` (ADR 004) and a "Where to?" field. A stranger still cannot pick a destination or see a route. Offline Photon/OSRM and a local PMTiles package remain the long-term plan (ADR 003).

## Decision

1. Search with Photon (`photon.komoot.io`), biased to Pune. Fall back to Nominatim with `User-Agent: DriftZero/0.1` and India/Pune bias.
2. Route with the public OSRM driving API. Draw the polyline on the existing `StreetMap`. Do not add a second map.
3. Show distance and time as `12 km` and `28 min`, not raw metres or seconds.
4. Use GPS for the origin. If there is no fix, use a documented Koregaon Park fallback (Starbucks). Do not ship a hard-coded-only destination pair.
5. Keep this network path out of the navigation core. The estimator, when it exists, must still run offline after an area package is installed.

## Consequences

- Testers can complete a normal navigate loop on a phone with internet.
- Airplane-mode search and routing will fail until local packages exist.
- Public endpoints can rate-limit. The UI must say so in plain language.
- Sibling map work can keep extending `StreetMap` (puck, style, camera).
