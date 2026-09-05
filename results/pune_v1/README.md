# Pune v1

This folder is **not IO-VNBD**. Do not mix it with
`results/io_vnbd_screening_v1/summary.md`.

No real Pune drive has been scored in this tree. There is no p50, no
endpoint table, and no invented metre. The harness
(`python3 -m driftzero_ml.eval_pune`) refuses to write
`metrics_summary.json` when `--logs` has no `sensors.jsonl`.
The Python road particle fixture (`driftzero_ml.road_particle`) must not
write this folder. It is labeled fixture, not a Pune field row. ADR 010.

## How to run

Record a trip in the Android app (Settings, trip record). Hold GNSS
during the scored segments so 1 Hz GNSS is logged with `gnss_held` and
stays out of the filter.

```text
PYTHONPATH=ml/src python3 -m driftzero_ml.eval_pune \
  --logs /path/to/exported/trip \
  --out results/pune_v1
```

`--logs` may be one trip directory or a parent of several trip folders.
Each trip needs `sensors.jsonl`, `states.jsonl`, and `manifest.json` with
`hold_intervals`.

Do not pass `--synthetic` into `results/pune_v1`. That flag is for the
labeled fixture in `ml/tests/test_eval_pune.py` only.

## Units in the trip log

- IMU timestamps: integer nanoseconds, `android_elapsed_realtime`
- Accel: m/s^2, `android_device`
- Gyro: rad/s, `android_device`
- GNSS: latitude_deg, longitude_deg, speed_mps (m/s), bearing_rad
  (radians, clockwise from north)
- Hold flag: quality.flags `gnss_held`
- `declared_rate_hz` is the preferred request (100). Measured Hz is
  median 1/dt from IMU timestamps. Never cite 100 Hz without that
  measurement.

## Driver protocol

1. Mount the phone. First-run still until the still step passes.
2. Drive straight for about 30 s with GNSS accepted. Do not Hold yet.
3. Arm Hold GNSS.
4. Drive a straight 50 m. Release Hold.
5. Later, arm Hold GNSS again and drive about 1 km. Release Hold.
6. Keep trip record on for the whole run. Export the trip zip.

The eval scores each Hold window. If a Hold contains 50 m or 1000 m of
GNSS truth path, it also writes `d50` and `d1000` rows. Those are
scenario checks, not a substitute for the official IO-VNBD median gate.
