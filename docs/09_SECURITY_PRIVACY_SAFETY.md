# Security, privacy, and safety

## 1. Trust boundaries

| Boundary | Threat | Control |
|---|---|---|
| Sensor/GNSS provider | stale, malformed, inconsistent or manipulated measurement | monotonicity, range, freshness, innovation and quality gates |
| Replay/import file | path traversal, oversized record, invalid numbers | schema validation, bounds, streaming parser, finite checks |
| Model bundle | tampering, incompatible feature order, unsafe artifact | signed/checksummed package, fixed format, schema/version hash |
| Map package | corrupted graph, stale metadata, malicious geometry | checksum/signature, bounds, atomic install, version compatibility |
| Export | disclosure of home/work routes or identity | explicit consent, minimization, encryption, user deletion |
| UI | false precision or distracted driving | confidence state, large controls, passenger/setup guidance |

## 2. Privacy requirements

- Process sensor and map data locally by default.
- Store trips only when the user starts logging or a clearly disclosed evaluation mode requires it.
- Allow per-trip view, export, and deletion.
- Do not collect contacts, microphone, camera, advertising ID, or unrelated device identifiers.
- Minimize exact location retention. Research exports should support spatial/temporal redaction.
- Encrypt sensitive local artifacts with Android platform mechanisms.
- Use explicit, purpose-specific consent for any upload or research reuse.
- Publish a retention policy before a public pilot.

## 3. Android permission strategy

Request precise foreground location only when navigation or logging starts. Avoid background location unless a product requirement truly needs navigation with the UI hidden, and explain it in context. Sensor access and foreground service behavior must follow the targeted Android version. The [Android location permission guide](https://developer.android.com/develop/sensors-and-location/location/permissions) is the source of record.

## 4. GNSS integrity language

Allowed product terms:

- GNSS unavailable;
- GNSS degraded;
- measurement inconsistency;
- signal-integrity risk;
- dead-reckoning fallback.

Do not claim certified spoofing detection, jamming immunity, or military-grade navigation from phone-only evidence. Android exposes NavIC/IRNSS constellation identification through `GnssStatus.CONSTELLATION_IRNSS` on supported API levels, but constellation visibility is not itself proof of integrity. See [docs/refs/NAVIC.md](refs/NAVIC.md) and the [Android GnssStatus reference](https://developer.android.com/reference/android/location/GnssStatus).

## 5. Safety behavior

- Never require calibration interaction while the vehicle is moving.
- Provide audio or haptic transition cues only when useful and not alarming.
- At high uncertainty, widen the display and suppress lane-like precision.
- If the engine becomes numerically invalid or sensor time fails, emit `LOW_CONFIDENCE` with a reason and stop unsupported extrapolation.
- Route guidance must not create a dangerous last-second maneuver based solely on uncertain DR.
- A passenger or fixed mount handles demo operation.
- State clearly that DriftZero is not certified for autonomous control or safety-of-life navigation.

## 6. Secure artifact lifecycle

Each model/map package contains:

- package type and semantic version;
- compatible app/core versions;
- region or model capability metadata;
- feature/graph schema hash;
- byte size and SHA-256;
- signature when distribution infrastructure exists;
- creation time and source manifest;
- rollback compatibility.

Download to a temporary file, enforce size limits, verify before parse, then activate atomically. Keep the last known-good package for rollback.

## 7. Logging

Diagnostic logs must avoid raw precise coordinates unless trip recording is enabled. Security-relevant logs include artifact verification failure, impossible timestamps, repeated invalid measurements, model fallback, graph parse failure, and GNSS health transitions. Rate-limit logs to avoid denial of service or storage exhaustion.

## 8. Research governance

- Dataset terms and consent remain attached to derived artifacts.
- Train/validation/test provenance is recorded.
- Do not release raw participant routes publicly without explicit permission.
- Do not claim generalization to India until the locked India pilot is evaluated.
- Document any demographic or geographic sampling gaps relevant to deployment.

