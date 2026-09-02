# Demo video script

## Recommended format

- Duration: 3 minutes 30 seconds to 4 minutes 30 seconds unless a later brief specifies another limit.
- Capture: 1080p landscape master, readable phone close-ups, separate clean voiceover.
- Evidence: one real field sequence plus one deterministic replay that exposes metrics.
- Safety: passenger operates the device or use a fixed mount. Never film a driver handling the phone.
- Contingency: record the complete replay path locally, including map assets and logs, so the demo is not network-dependent.

## Storyboard and narration

### 0:00 to 0:20: the problem

**Visual:** Indian underpass, tunnel, parking ramp, or dense flyover corridor. A normal navigation marker freezes or jumps in a clearly labeled comparison replay.

**Narration:**

“Navigation depends on GNSS, including GPS and NavIC. But tunnels, parking structures, dense roads, forests, and interference can make a phone lose trustworthy position. Existing apps may freeze or jump exactly when a driver needs continuity.”

### 0:20 to 0:40: product promise

**Visual:** DriftZero app on an ordinary Android phone. Show airplane mode and no cable to the vehicle. Offline area status is visible.

**Narration:**

“DriftZero is a software-only resilient navigation engine. It uses the phone's own inertial sensors, offline road data, and compact on-device AI. There is no OBD connection, external speedometer, custom antenna, or cloud dependency.”

### 0:40 to 1:10: live transition

**Visual:** Map with blue dot, heading cone, route, and compact `GNSS` chip. Enter a recorded or real outage. Chip changes through `Assisted` to `Dead reckoning`; confidence halo grows gradually. The dot continues.

**Narration:**

“When trusted GNSS weakens, DriftZero does not wait for a total failure. It checks signal quality and consistency, then transitions into dead reckoning. The marker continues at 10 updates per second, while the confidence halo honestly shows increasing uncertainty.”

### 1:10 to 1:40: how it works

**Visual:** Compact animated architecture. Overlay live sensor health, learned speed, filter covariance, and two road hypotheses at a junction.

**Narration:**

“A guided calibration aligns the phone with the vehicle. A lightweight causal model separates real motion from engine vibration, braking, potholes, and phone misalignment. A probabilistic navigation filter combines that estimate with vehicle constraints. An offline HMM road matcher uses OpenStreetMap topology without forcing the vehicle onto an uncertain road.”

### 1:40 to 2:15: judge replay and evidence

**Visual:** Choose a named IO-VNBD trip. Mask GNSS for a fixed interval. Show hidden truth only on the evaluation side, never in the input. Compare freeze, filter-only, and full DriftZero. Show endpoint error, blackout distance, drift ratio, and confidence coverage.

**Narration:**

“This is a deterministic replay of the mandatory IO-VNBD dataset. Ground-truth GNSS is retained only for scoring and is blocked from the navigation pipeline. We compare simple freezing, a physics-only filter, and the full system. Every result is tied to the trip, blackout manifest, model hash, and configuration.”

**Important:** replace on-screen placeholders only with measured numbers from the locked evaluator. Do not narrate an accuracy result until the file bundle proves it.

### 2:15 to 2:45: TimesFM 3 innovation

**Visual:** Desktop research view with multivariate channels, TimesFM point/quantile forecast, student model, and Android latency card.

**Narration:**

“We also evaluate TimesFM 3 as a zero-shot multivariate forecasting teacher. It forecasts short-horizon speed, yaw, and uncertainty patterns on desktop. We keep it only if it beats strong baselines on unseen trips, then distill useful behavior into a small independent model for the phone. The large foundation model never sits in the safety-critical real-time loop.”

### 2:45 to 3:10: difficult Indian cases

**Visual:** Fast montage of speed breaker, stop-go, flyover/service road, two-wheeler mount, low-cost phone, parking, remount detection. Show uncertainty or fallback when appropriate.

**Narration:**

“DriftZero is designed for Indian conditions: mixed vehicles, low-cost phones, two-wheelers, rough roads, flyovers, tunnels, parking, and weak connectivity. We test unseen phones, routes, vehicles, and mounts, including failure cases rather than only the easiest straight roads.”

### 3:10 to 3:35: recovery

**Visual:** GNSS returns. State becomes `Reacquiring`; correction blends smoothly; then `GNSS`. Show correction magnitude and no teleport.

**Narration:**

“When credible satellite positioning returns, consecutive fixes pass an innovation gate. DriftZero blends the correction instead of snapping, then returns to normal fused navigation.”

### 3:35 to 3:55: national value and close

**Visual:** Consumer phone, emergency vehicle, logistics, forest/public field team icons; engine interface accepting phone or external IMU. Finish on logo/tagline.

**Narration:**

“One private offline engine can support everyday drivers, logistics, emergency response, and government field operations. The same interface also accepts a higher-rate external IMU for future edge deployments. DriftZero: AI-Assisted Resilient Navigation Beyond GNSS.”

## On-screen evidence checklist

- Product name DriftZero. Sponsor ISRO. No contest ID on screen.
- Standalone phone, no vehicle connection.
- Airplane mode after offline area install.
- Visible transition state and confidence.
- Exact IO-VNBD trip and blackout interval.
- Truth labeled `score only, hidden from model`.
- Baseline and ablation, not only the best trace.
- Measured drift ratio with endpoint distance and blackout distance.
- 10 Hz and latency card from reference phone.
- TimesFM teacher/student boundary.
- Honest limitation and low-confidence behavior.

## Capture plan

1. Lock code, model, map, and result manifest.
2. Fresh-install the app on the reference phone.
3. Install only the demo offline area and model package.
4. Rehearse live field sequence twice with a passenger operator.
5. Capture phone screen directly plus an external camera angle.
6. Capture deterministic replay and metric export.
7. Record clean architecture visuals and voiceover.
8. Edit with labels large enough for a laptop projector.
9. Review every number against the result bundle.
10. Export a master and a compressed submission version, then watch both end to end.

## Demo contingency ladder

- Primary: safe real outage route plus local recording.
- Secondary: field log replayed through the exact production pipeline.
- Tertiary: mandatory IO-VNBD fixed replay.
- Never: a pre-animated blue dot disconnected from the estimator.

