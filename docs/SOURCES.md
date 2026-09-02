# Authoritative sources and reading list

Retrieved and checked on 2026-09-02 unless noted. Product decisions are interpretations by the DriftZero team; sources do not endorse the product.

## Problem statement

- [SIH 2026 official problem statements](https://www.sih.gov.in/sih2026PS), search for ID 26168. Source of record for title, sponsor, constraints, required modules, dataset, output rates, and drift target.
- [IO-VNBD repository](https://github.com/onyekpeu/IO-VNBD), official mandatory dataset link from the problem statement.
- [IO-VNBD Data in Brief paper](https://doi.org/10.1016/j.dib.2021.106885), dataset scale, geography, sensors, and collection description.
- [IO-VNBD inertial speed-estimation research](https://arxiv.org/abs/2005.01701).

## Foundation model and edge inference

- [Google Research TimesFM repository](https://github.com/google-research/timesfm), TimesFM 3 release, API, multivariate/covariate behavior, and terms pointers.
- [TimesFM 3 official model card](https://huggingface.co/google/timesfm-3.0-pytorch), architecture and checkpoint details.
- [TimesFM 3 checkpoint files](https://huggingface.co/google/timesfm-3.0-pytorch/tree/main), artifact size evidence.
- [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/), Android deployment options.
- [ONNX Runtime quantization](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html), quantization tradeoffs.
- [LiteRT for Android](https://ai.google.dev/edge/litert/android), alternative mobile runtime.

## Android sensors and GNSS

- [Android sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview), sensor APIs, rates, and registration behavior.
- [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion), motion-sensor types and units.
- [Android SensorEvent](https://developer.android.com/reference/android/hardware/SensorEvent), timestamp and coordinate conventions.
- [Android GnssStatus](https://developer.android.com/reference/android/location/GnssStatus), constellation constants including IRNSS/NavIC support.
- [Android GnssMeasurement](https://developer.android.com/reference/android/location/GnssMeasurement), optional raw GNSS fields.
- [Android location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions), foreground/background permission design.
- [Google GNSSLogger and GPS measurement tools](https://github.com/google/gps-measurement-tools), reference logger and analysis tools.

## Offline maps and road matching

- [MapLibre Native releases](https://github.com/maplibre/maplibre-native/releases), Android/native mapping runtime versions.
- [MapLibre Android local PMTiles example](https://www.maplibre.org/maplibre-native/android/examples/data/PMTiles/), local archive integration.
- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/), public tile-service limits.
- [Geofabrik India extracts](https://download.geofabrik.de/asia/india.html), source option for OSM PBF.
- [Protomaps getting started](https://docs.protomaps.com/guide/getting-started) and [basemap build guide](https://docs.protomaps.com/basemaps/build), bounded PMTiles builds.
- [Newson and Krumm HMM map matching](https://www.microsoft.com/en-us/research/publication/hidden-markov-map-matching-noise-sparseness/), foundational probabilistic matcher.
- [GraphHopper map matching](https://github.com/graphhopper/graphhopper/blob/master/map-matching/README.md), desktop reference implementation.
- [Valhalla Meili architecture](https://valhalla.github.io/valhalla/contributing/architecture/meili/) and [map-matching API](https://valhalla.github.io/valhalla/api/map-matching/), desktop reference.

## Related navigation research

- [AI-IMU Dead-Reckoning](https://arxiv.org/abs/1904.06064), learned Kalman-noise adaptation. Its published vehicle-IMU results must not be presented as phone performance.
- [AVNet smartphone vehicular dead reckoning](https://link.springer.com/article/10.1186/s43020-025-00168-7), CNN/GRU pseudo-measurements and invariant filtering on a custom dataset.
- [DVSE smartphone vehicle speed estimation](https://arxiv.org/abs/2505.18490), motion transformation and cross-device research.

## Supplemental datasets

- [Google Smartphone Decimeter Challenge overview](https://www.ion.org/gnss/googlecompetition.cfm) and [2023 data](https://www.kaggle.com/competitions/smartphone-decimeter-2023/data).
- [UrbanNav paper](https://navi.ion.org/content/70/4/navi.602) and [dataset repository](https://github.com/IPNL-POLYU/UrbanNavDataset).
- [KITTI](https://www.cvlibs.net/datasets/kitti/), supporting autonomous-driving dataset.

## Source-use cautions

- Current software versions and model terms may change. Pin and recheck them before implementation or distribution.
- Performance in a paper applies to that paper's sensors, routes, splits, and ground truth.
- OpenStreetMap data and map-rendering services are different things. An open database does not authorize bulk use of the public tile server.
- A repository without an obvious data license needs explicit review before redistribution. DriftZero should store fetch manifests rather than republishing third-party raw data.

