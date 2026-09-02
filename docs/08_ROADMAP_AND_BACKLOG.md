# Roadmap and implementation backlog

The SIH deadline shown on the official page is 20 September 2026. The schedule below is ordered by evidence risk. Complete the screening proof before spending time on decorative UI.

## Critical path

| Phase | Deliverable | Definition of done |
|---|---|---|
| P0 Repository | Contracts, configs, CI, data manifests | Clean setup and tests from README |
| P1 Evidence baseline | IO-VNBD import, locked splits, blackout evaluator, plot | Reproducible baseline result with leakage assertions |
| P2 Navigation core | Frames, calibration, strapdown, GNSS updates, replay | Synthetic tests and stable trip output |
| P3 Constraints and learned model | NHC, stop detector, causal speed model, uncertainty | Held-out ablation beats filter-only or is rejected |
| P4 Offline roads | PMTiles, graph packer, HMM matcher | Required topology fixtures pass offline |
| P5 Android product | Sensor service, engine binding, map UI, logger, status modes | 10 Hz airplane-mode run on reference phone |
| P6 TimesFM research | Zero-shot suite and teacher/student ablation | Decision report with keep/reject evidence |
| P7 India pilot | Multi-phone/route/vehicle collection and locked test | Per-slice report plus failure taxonomy |
| P8 Submission | Video, pitch, result bundle, backup | Fresh-install rehearsal and checksum archive |

## Priority backlog

### Must have

- [ ] Fetch and hash IO-VNBD subset.
- [ ] Implement schema-aware importer and unit checks.
- [ ] Assign complete-trip split groups.
- [ ] Implement deterministic blackout masking and leakage tests.
- [ ] Produce SIH screening plot and baseline table.
- [ ] Implement coordinate frames and synthetic IMU generator.
- [ ] Implement calibration and vehicle-frame alignment quality.
- [ ] Implement ESKF/InEKF propagation and GNSS updates.
- [ ] Implement GNSS health score and state machine.
- [ ] Train two small causal motion baselines.
- [ ] Implement uncertainty calibration and model fallback.
- [ ] Build one Indian offline map/road package.
- [ ] Implement rolling HMM map matcher and fixtures.
- [ ] Build Android foreground navigation service and replay source.
- [ ] Render map UI and state/confidence sheet.
- [ ] Export result bundle and demo replay.
- [ ] Benchmark latency, stability, memory, and battery.
- [ ] Record end-to-end video with verified numbers.

### Should have

- [ ] NavIC/IRNSS constellation status where Android exposes it.
- [ ] Optional raw GNSS measurement logger.
- [ ] Phone capability tiers and calibration profile manager.
- [ ] External IMU recorded-stream adapter.
- [ ] Signed/checksummed map and model package installer.
- [ ] Haptic/audio low-confidence cue.
- [ ] GeoJSON and CSV diagnostic export.
- [ ] Reference comparison against GraphHopper or Valhalla.

### Could have

- [ ] Route-conditioned map priors, separately ablated.
- [ ] Privacy-preserving fleet health aggregation.
- [ ] Automated outage-zone prediction.
- [ ] Map update delta packages.
- [ ] Operator dashboard for consented deployments.

## First 72 hours

1. Run only IO-VNBD ingestion, schema discovery, and plots.
2. Lock trip groups and a small blackout suite.
3. Implement freeze, constant-velocity, and filter skeleton baselines.
4. Generate the preliminary required position plot.
5. Create a result manifest and worst-case review.
6. In parallel only after the evidence path works, scaffold Android capture/replay and one PMTiles sample.

## Stop conditions

- If IO-VNBD units or timestamps are uncertain, stop training and resolve them.
- If a random split produces much better results, treat that as proof of leakage risk, not product performance.
- If the learned model fails held-out ablation, ship the filter fallback and redesign the target.
- If map matching improves display but worsens the unsnapped physical trace, separate visual matching from feedback.
- If TimesFM does not beat baselines or distill, report and remove it from the production story.
- If live metrics differ from replay, fix pipeline parity before recording the demo.

## Ownership suggestion for a small team

| Workstream | Primary responsibilities |
|---|---|
| Navigation | frames, filter, constraints, state machine, numerical tests |
| ML/research | data pipeline, student, TimesFM, evaluation, manifests |
| Android/maps | capture, runtime, PMTiles, graph, HMM, UI, performance |
| Product/evidence | requirements, India collection, judge mode, video, pitch |

Pair-review every cross-boundary contract. No one should be the sole reviewer of benchmark leakage or safety behavior.

## Final submission checklist

- [ ] Official requirement matrix updated.
- [ ] Mandatory dataset evidence reproducible.
- [ ] Under-10-percent target reported honestly with tail metrics.
- [ ] No external-hardware dependency in consumer demo.
- [ ] Offline app works in airplane mode.
- [ ] 10 Hz output shown and measured.
- [ ] Recovery is smooth and measured.
- [ ] TimesFM role is technically credible.
- [ ] Worst cases and limitations are included.
- [ ] Repository has clean setup, tests, and no secrets or large data.
- [ ] Video numbers match committed result bundle.

