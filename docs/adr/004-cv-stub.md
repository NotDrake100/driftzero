# ADR 004: Pose store and GNSS-off coast

- Status: accepted
- Date: 2026-09-02
- Filename: `004-cv-stub.md` is historical. The decision is the pose store, not a computer-vision stub.

## Context

The live map puck froze when LocationManager stopped. The product needs the own-vehicle mark to keep moving through a GNSS gap.

## Decision

`PoseStore` is the UI-readable pose at 10 Hz. MapLibre draws the puck from that pose, not from the default location engine.

When GNSS age is over 2 s, or ingest is held (Simulate GPS off), the estimator coasts on last velocity. The chip reads "No GPS, estimating". Long-press the GPS chip to stop consuming LocationManager while the 10 Hz pose loop keeps running.

This coast is last course and speed (plus IMU if present). It is not a claim of a finished ESKF product, even when `DeadReckoningFilter` is the live engine.

## Consequences

- GPS-off demos no longer look like a frozen Maps puck, if last speed is non-zero.
- Drift grows. There is no map constraint on this path.
- UI siblings read `PoseStore.state` and must not invent a second GNSS feed for the puck.
