# ADR 014: Real-map road-particle evaluation

Date: 2026-09-06. Status: research only, not live Android.

Extend ADR 010's fixture with an IO-VNBD experiment. Keep the deterministic v3
coast as the baseline and causal fallback. Do not port to the phone until real
recordings show a gain. No Pune or mountless accuracy claim follows from this test.

Use directed OSM segments and a fixed 512-particle speed/road posterior. Seed
from available GNSS at or before mask start, matching existing replay semantics.
Calibration remains strictly pre-mask. No masked GNSS values reach inference.
Physical up gyro decreases compass heading. Weak gyro holds course. Evaluate
heading likelihood once per second with sigma 0.4 rad plus 0.005 rad per elapsed
second. Speed prior sigma is 3 m/s; random walk is 0.35 m/s/sqrt(s). Seed 26168.
Road junction hypotheses remain multiple; report posterior spread and never
claim lane identity. Spread is heuristic, not calibrated coverage. Unsupported
turn restrictions and current-map versus historical-data mismatch are limitations.

Download maps as an explicit desktop preparation step. Queries use a fixed 16 km
radius around the last available GNSS coordinate rounded to 0.1 degree. No hidden
route, endpoint or blackout distance chooses map bounds. Cache response bytes,
SHA256, query, source endpoint, fetch time and OSM timestamp. Inference is offline.
Missing maps, missing seed, or IMU gaps keep the existing deterministic coast;
these rows remain in the denominator. Archive all map snapshots with evidence.

Preregister one candidate on the existing 11 development intervals. Confirm on
all locked 35 only if development map coverage is complete, no evaluation fails,
median improves at least 10% relative to the freshly rerun v3 baseline, and p95
is no worse. Save the decision before accessing locked results. Report both the
suite median target below 0.10 and the stricter all-interval target separately.
Neither threshold nor truth gates change.

Map data: © OpenStreetMap contributors, ODbL 1.0.
[Attribution and licence](https://www.openstreetmap.org/copyright).
[Overpass geometry query documentation](https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL).

Reproduce: `PYTHONPATH=ml/src python -m driftzero_ml.eval_osm_coast --download-maps`.
Without `--download-maps`, only cached maps are used. This is a research command,
not a network dependency of the app. Metrics and per-interval CSV are written to
`results/road_coast`; the workflow archives maps, traces and baseline evidence.

## Acceleration ablation, preregistered before real-map outcomes

A second run enables `--acceleration-model`. Fit a three-coefficient ridge model
(bias, horizontal x projection, horizontal y projection; L2 0.1) from integrated
pre-mask acceleration to GNSS speed changes. Require at least 12 usable GNSS hops,
train on the first 70% (at least eight), and validate on the remaining later hops.
Accept only a 10% validation RMSE gain over zero acceleration, projection norm
0.2 to 2, and bias magnitude at most 1 m/s². Do not refit after validation. Clip
inference to ±3 m/s². Missing or rejected models retain the speed random walk.
Do not infer arbitrary phone handling support from this stationary-placement fit.

Use the same fixed development intervals and acceptance rule as the road-only
candidate, additionally rejecting any partial-interval IMU failure. Run both
ablations with the same map cache. This is a distinct declared model experiment;
its result must be reported beside the road-only outcome, including regressions.
