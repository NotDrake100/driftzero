# Datasets and data governance

## 1. Dataset portfolio

| Priority | Dataset | Use in DriftZero | Important caveat |
|---|---|---|---|
| Mandatory | [IO-VNBD](https://github.com/onyekpeu/IO-VNBD) | SIH screening, smartphone speed/DR research, vehicle reference comparisons | Non-Indian routes; inspect exact files and metadata before assuming a field |
| High | [Google Smartphone Decimeter Challenge 2023](https://www.kaggle.com/competitions/smartphone-decimeter-2023/data) | Phone diversity, raw GNSS plus inertial behavior, precise ground truth | Competition collection conditions differ from target roads |
| High | [UrbanNav](https://github.com/IPNL-POLYU/UrbanNavDataset) | Urban-canyon GNSS and multisensor/reference diagnostics | Sensor rigs and cities differ from standalone phone deployment |
| Supporting | [KITTI](https://www.cvlibs.net/datasets/kitti/) | Filter sanity and reproduction of vehicle-IMU methods | Not representative of consumer smartphone MEMS |
| Decisive | DriftZero India collection (planned) | Generalization across Indian roads, phones, mounts, and vehicles | No dataset exists yet. Pune drives are P2 in `docs/07_RESEARCH_AND_ROADMAP.md`. Requires consent, safety protocol, metadata quality, and careful ground truth |

The [IO-VNBD data paper](https://doi.org/10.1016/j.dib.2021.106885) reports about 58 hours and 4,400 km of smartphone data, with additional vehicle data, across the UK, Nigeria, and France. The repository contains synchronized and unsynchronized collections. Freeze the exact subset and commit its manifest because repository structure and links can change.

## 2. Data directory contract

Large or redistributable datasets are not committed to Git.

```text
data/
  manifests/
    sources.yaml
    files.sha256
    trips.parquet
    splits.yaml
    blackouts.yaml
  raw/<dataset>/<version>/
  interim/<dataset>/<pipeline_hash>/
  processed/<feature_schema>/<split>/
```

Each trip record includes dataset, trip ID, driver group if known, route group, vehicle group, phone model/IMU vendor if known, country, timestamp range, sampling rates, mount metadata, ground-truth type, consent/status, and file hashes.

## 3. Ingestion gates

Before training:

1. verify file hashes and parse counts;
2. preserve original units and column names in a raw manifest;
3. plot timestamp deltas, missingness, sensor norms, GNSS path, and speed;
4. verify coordinate frames against dataset documentation;
5. quantify sensor/GNSS clock offset and mark uncertain synchronization;
6. detect duplicate or overlapping trips;
7. assign groups before window generation;
8. create processed features from train-fitted transformations only.

Reject or quarantine corrupted intervals rather than silently interpolating long gaps.

## 4. Leakage-proof splitting

Random row or random-window splits are forbidden. Windows from the same trip are highly correlated.

Use nested groups:

- primary holdout: complete trip;
- stronger holdout: route or geographic corridor;
- available metadata: driver and vehicle;
- India generalization: phone model and IMU vendor;
- final demonstration set: locked before model selection.

Maintain at least train, validation, public-test/rehearsal, and private locked-test partitions. All windows from a trip inherit the trip partition.

## 5. Artificial blackout protocol

Artificial outages allow scoring against retained ground truth while mimicking missing GNSS.

### Selection

- Define duration and distance bands before examining model error.
- Sample complete intervals within valid sensor and ground-truth coverage.
- Stratify by speed, turns, stops, road type, and duration.
- Include fixed official examples near 50 m and 1 km when data permits.
- Include scenario-driven intervals, not only random straight roads.
- Store intervals in a versioned manifest.

### Execution

At the blackout start, record the last accepted GNSS update. During the interval:

- remove position, speed, bearing, satellite-derived features, and raw GNSS from runtime input unless the experiment explicitly studies degraded rather than absent GNSS;
- prevent imputation from future fixes;
- prevent global smoothing across the interval;
- prevent scaler or alignment fitting on score-only truth;
- allow ground truth only in the evaluator after inference;
- restore GNSS through the same reacquisition gate used live.

### Blackout suites

| Suite | Duration/distance | Purpose |
|---|---|---|
| Short | 5 to 30 s, roughly 20 to 300 m | Underpass, short tunnel, flyover shadow |
| Medium | 30 to 120 s, roughly 100 m to 2 km | Tunnel, parking exit, dense canyon |
| Long stress | 2 to 5 min where available | Uncertainty/failure behavior, not universal accuracy claim |
| Transition | degraded fixes before/after missing block | State machine and reacquisition |
| Adversarial road | junction, parallel road, flyover, U-turn | Map-matching ambiguity |

## 6. Ground truth hierarchy

1. Survey/RTK or high-grade reference with synchronization documentation.
2. Dataset-provided fused reference whose failure behavior is understood.
3. Clear-sky phone GNSS retained for score-only artificial blackout, with acknowledged error floor.
4. Map projection is never treated as independent truth.

Do not report centimetre-scale conclusions against metre-scale phone GNSS labels.

## 7. India collection (planned)

No India trip files exist in this repository. Owner records eight Pune drives in docs/07 P2.

### Minimum matrix

- At least 3 phone tiers and 2 sensor vendors.
- At least 3 vehicle classes, including a two-wheeler if safely mountable.
- At least 2 cities or strongly different road environments.
- Repeated route in both directions and on different days.
- Stable mount plus deliberate safe remount test.
- Straight, turn, U-turn, stop-go, rough road, speed breaker, flyover/service road, parking, and underpass/tunnel events.

### Safety and consent

- A passenger or fixed mount operates the phone. The driver does not touch it while moving.
- Record informed consent and intended use.
- Minimize personally identifying information.
- Avoid collecting unnecessary audio, contacts, or camera data.
- Define retention and deletion dates.
- Encrypt transferred logs and restrict access.

### Metadata that makes the data useful

Record phone model, Android version, sensor names/vendor/range/resolution, mount type and orientation, vehicle class, approximate load, tyres only if relevant and volunteered, weather, road condition, route tags, reference system, calibration status, and incidents such as phone movement.

## 8. Augmentation

Allowed training augmentation must remain physically plausible:

- phone-frame rotations consistent with mount variation;
- bias random walk, scale perturbation, timestamp jitter, and dropped samples;
- vibration/noise drawn from real stationary and rough-road segments;
- GNSS outages and accuracy degradation;
- mild speed/time warping with corresponding label transformation.

Do not rotate axes without transforming gravity, attitude, and labels consistently. Keep an unaugmented locked test set.

## 9. Dataset scorecard

For every dataset, document:

- provenance and retrieval date;
- terms and allowed handling;
- sensor fields, rates, units, and coordinate frame;
- reference truth and synchronization;
- geography and scenario coverage;
- missingness and known defects;
- split groups and overlap risk;
- approved uses in training, validation, or visualization.

Even when a prototype can technically download data, the product should maintain this scorecard. Data governance is part of reproducibility and future deployment.

