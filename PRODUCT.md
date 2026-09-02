# DriftZero

DriftZero is a phone navigator. You type a destination, see a driving route, and follow the blue dot. The long-term product keeps that dot moving when GPS dies. That motion estimator is not in this build.

## How a tester uses it

1. Open DriftZero on a phone (or an emulator) with internet.
2. Allow location if asked. If there is no GPS, the start point is Starbucks, Koregaon Park, Pune.
3. Type a place in the search bar. For the Pune demo, type `PES Modern College of Engineering, Pune`, or tap the suggestion chip.
4. Tap a result. The app looks up a driving route and draws it on the map.
5. Read distance and time in plain language, such as `5.3 km` and `6 min`. Follow the blue dot toward the red destination mark.

Search uses Photon (Komoot), then Nominatim if Photon has nothing usable. Routes use the public OSRM driving service. Both are biased toward India and Pune. The demo path is not a hard-coded pair of pins. The destination always comes from search. The only hard-coded location is the Koregaon Park start when GPS is missing.

## When GPS is lost

If the phone had a fix and then GPS goes quiet, the blue dot stays at the last known place. The card says GPS is weak and that motion without GPS is not ready. The app does not invent a coasting path.

## What is not done yet

- Dead-reckoning filter, IMU alignment, and a 10 Hz estimate while GPS is gone
- Outage states (fused, degraded, dead reckoning, reacquiring)
- Confidence halo that grows with uncertainty
- Offline map package and road matching
- Offline search and routing
- Turn-by-turn voice instructions
- A learned motion model

This is a product-path prototype: search, route, and map chrome. It is not a claim that the estimator works.

## Build

From the repository root:

```bash
./gradlew :android-app:assembleDebug
```

No Google Maps SDK. The map is MapLibre with OpenFreeMap tiles. Search and tiles need a network in this prototype.
