# Phone test

Sideload the debug APK (`demo/TESTER_SIDELOAD.md`). No OBD cable. No extra antenna.

1. Open DriftZero. Allow precise location while using the app. Streets should appear. The field at the top is Where to?. Outdoors with a fix, the chip should read GPS on.
2. Type a real nearby place. Pick a result. A blue route line should draw. Distance and ETA appear only after that line exists.
3. Walk or ride for about 20 seconds so the filter has a GNSS seed and a non-zero velocity.
4. Long-press the GPS chip. LocationManager updates stop. Accel and gyro keep copying. The chip should change to No GPS, estimating. The blue puck should keep moving from our pose.
5. Long-press the chip again. GNSS ingest resumes. The marker should rejoin a live fix.

If you are parked with near-zero speed, the estimate may barely move. Try the hold while walking.
