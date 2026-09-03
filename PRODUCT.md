# Phone test

Sideload the debug APK (`demo/TESTER_SIDELOAD.md`). No OBD cable. No extra antenna. Streets and routes use the network until a Ready area pack is installed. This is not an accuracy test. Emulator screenshots under `results/emulator/` are not phone results.

1. Open DriftZero. Allow the Android precise-location permission while using the app. Streets should appear if the network can reach OpenFreeMap. The field at the top is Where to?. Outdoors with a fix, the lamp should read GNSS (or Assisted, Dead reckoning, Reacquiring, or Low confidence).
2. Type a real nearby place. Pick a result. A blue route line should draw. Distance and ETA appear only after that line exists.
3. Walk or ride for about 20 seconds so the filter has a GNSS seed and a non-zero velocity.
4. Long-press the mode lamp (under 8 m/s) or use Hold GNSS in Judge. LocationManager updates stop. Accel and gyro keep copying. The lamp should change to Dead reckoning. The blue puck should keep moving from our pose.
5. Long-press the lamp again, or Resume GNSS. GNSS ingest resumes. The marker should rejoin a live fix.

If you are parked with near-zero speed, the estimate may barely move. Try the hold while walking.
