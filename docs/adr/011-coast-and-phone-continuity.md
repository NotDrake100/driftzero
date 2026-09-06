# ADR 011: Physical coast conventions and phone placement continuity

- Status: implemented; field accuracy unverified
- Date: 2026-09-06
- Paths: navigation-core, live Android PoseStore, desktop replay exporter
- Supersedes ADR 007's instruction to reset navigation on remount

Android gyroscope angular velocity is right-handed. A positive up-axis rate
turns counterclockwise; compass heading increases clockwise. Coast propagation
therefore subtracts up-axis angular rate. The exporter now emits that same
physical convention. Constant-speed turns integrate a circular arc to avoid
sample-rate-dependent endpoint error. GNSS yaw reseeding uses the same sign.
Exporter gravity calibration excludes all samples at or after blackout start.
Exports without a mask explicitly describe full-trip calibration and are not
blackout accuracy evidence. Older exported gyro files must be regenerated.

Map matching and feedback run once per timestamp through MapCoastSession. The
persist pseudo-measurement precedes matching, including engine replay. Repeated
pose reads cannot accumulate covariance reductions from one map observation.

A physical mount is optional. A resting passenger-seat, cup-holder, portrait,
landscape or tilted phone still needs an observable alignment. Android gravity
(m/s², phone axes, elapsed realtime ns, accuracy above unreliable) supplies the
up vector. PhoneMotionGuard projects calibrated phone gyro onto this vector;
it does not assume the screen faces the vehicle's forward direction. Gravity
older than 0.5 s is unavailable, rather than silently replaced by zeros.

Transverse angular speed above 0.6 rad/s or a gravity-direction jump above about
10 degrees triggers a two-second settling period. These are prototype heuristic
thresholds, not tuned field claims. During handling the filter preserves velocity
and position continuity, suppresses inertial/pseudo constraints, reports
LOW_CONFIDENCE and phone_handling, and adds position variance at 25 m²/s and yaw
variance at 0.04 rad²/s. A MountSession remount invalidates its calibration but
no longer deletes navigation state or releases a simulated GNSS blackout.
After remount, conservative propagation persists until vehicle alignment returns.

A slow phone spin around gravity cannot be uniquely separated from a vehicle
turn using these measurements alone. Handheld navigation, pocket/bag motion,
seat sliding, and long blackouts need separate recorded evaluation. Placement
labels remain heuristic; a seat cannot be identified reliably from vibration
alone. No measured less-than-10% result is claimed by these changes.

Validation: analytical left/right coast geometry at 10/100/200 Hz; attitude/course
agreement; future-gravity perturbation isolation; same-epoch map deduplication;
gravity projection across orientations; stale sensor and pickup settling tests;
blackout pose continuity and uncertainty growth; Android remount regression.
