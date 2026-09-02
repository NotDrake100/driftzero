# Authoritative sources and reading list

Retrieved and checked on 2026-09-02 unless noted. Independent fetch log for official SIH26168 text and literature opened on 2026-09-03: [docs/refs/SIH26168_EVIDENCE.md](refs/SIH26168_EVIDENCE.md). Product decisions are interpretations by the DriftZero team; sources do not endorse the product.

## Problem statement

- [SIH 2026 official problem statements](https://www.sih.gov.in/sih2026PS), search for ID 26168. Source of record for title, sponsor, constraints, required modules, dataset, output rates, and drift target.
- [IO-VNBD repository](https://github.com/onyekpeu/IO-VNBD), official mandatory dataset link from the problem statement.
- [IO-VNBD Data in Brief paper](https://doi.org/10.1016/j.dib.2021.106885), dataset scale, geography, sensors, and collection description.
- [IO-VNBD dataset paper on arXiv](https://arxiv.org/abs/2005.01701) (Onyekpe, Palade, Kanarachos, Szkolnik). This is the dataset paper, not a separate speed-estimation paper. Related article: Onyekpe, Palade, Kanarachos, Applied Sciences 2021, 11(3), 1270.

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
- [Fused location provider](https://developers.google.com/location-context/fused-location-provider), closed GMS fusion. Not an inspectable vehicle INS.
- [CLVisit](https://developer.apple.com/documentation/corelocation/clvisit), significant-place arrive/depart. Not inertial DR.
- [Organic Maps](https://github.com/organicmaps/organicmaps), offline OSM maps using OS location providers, no IMU dead reckoning.
- [OsmAnd location provider](https://github.com/osmandapp/OsmAnd/blob/master/OsmAnd/src/net/osmand/plus/OsmAndLocationProvider.java), GNSS/fused location, not strapdown INS.
- [Google GNSSLogger and GPS measurement tools](https://github.com/google/gps-measurement-tools), reference logger and analysis tools.

## Offline maps and road matching

- [MapLibre Native releases](https://github.com/maplibre/maplibre-native/releases), Android/native mapping runtime versions.
- [MapLibre Android local PMTiles example](https://www.maplibre.org/maplibre-native/android/examples/data/PMTiles/), local archive integration.
- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/), public tile-service limits.
- [Geofabrik India extracts](https://download.geofabrik.de/asia/india.html), source option for OSM PBF.
- [Protomaps getting started](https://docs.protomaps.com/guide/getting-started) and [basemap build guide](https://docs.protomaps.com/basemaps/build), bounded PMTiles builds.
- [Newson and Krumm HMM map matching](https://www.microsoft.com/en-us/research/publication/hidden-markov-map-matching-noise-sparseness/), foundational probabilistic matcher. Implemented delta: [docs/refs/MAP_MATCHING.md](refs/MAP_MATCHING.md).
- [Quddus, Ochieng, Noland 2007 map-matching review](https://doi.org/10.1016/j.trc.2007.05.002), geometric / topological / probabilistic families and integrity.
- [GraphHopper map matching](https://github.com/graphhopper/graphhopper/blob/master/map-matching/README.md), desktop reference implementation.
- [Valhalla Meili architecture](https://valhalla.github.io/valhalla/contributing/architecture/meili/) and [map-matching API](https://valhalla.github.io/valhalla/api/map-matching/), desktop reference.

## Inertial mechanization and ESKF

- Paul D. Groves, *Principles of GNSS, Inertial, and Multisensor Integrated Navigation Systems*, 2nd ed. (2013). Local-navigation-frame equations §5.4. Companion MATLAB comments are the equation-number source used in `docs/refs/INS_ESKF.md` ([`ymjdz/MATLAB-Codes`](https://github.com/ymjdz/MATLAB-Codes)).
- D. H. Titterton and J. L. Weston, *Strapdown Inertial Navigation Technology*, 2nd ed. (2004). Local geographic mechanization §3.5.3. Quaternion algorithms §3.6.4 and §11.2.5.
- Joan Solà, [Quaternion kinematics for the error-state Kalman filter](https://arxiv.org/abs/1711.02508) (arXiv:1711.02508).
- NIMA TR8350.2 eq. (4-1) Somigliana. Constants as listed in [USGS gravity computations](https://pubs.usgs.gov/of/2006/1204/Gravity/computations.pdf) and [Theoretical gravity](https://en.wikipedia.org/wiki/Theoretical_gravity).

## Related navigation research

- [AI-IMU Dead-Reckoning](https://arxiv.org/abs/1904.06064), learned Kalman-noise adaptation. Its published vehicle-IMU results must not be presented as phone performance.
- [AVNet smartphone vehicular dead reckoning](https://link.springer.com/article/10.1186/s43020-025-00168-7), CNN/GRU pseudo-measurements and invariant filtering on a custom dataset.
- [DVSE smartphone vehicle speed estimation](https://arxiv.org/abs/2505.18490), motion transformation and cross-device research.
- [IONet](https://arxiv.org/abs/1708.03572), LSTM inertial odometry from IMU windows.
- [RIDI](https://arxiv.org/abs/1706.02575), IMU velocity regression with device placement.
- [RoNIN](https://arxiv.org/abs/1905.12853), heading-agnostic learned inertial odometry.
- [TLIO](https://arxiv.org/abs/2007.01867), learned 3D displacement plus covariance tightly coupled to an EKF.
- Student head mapping, data URLs, and TLIO gaps: [docs/refs/LEARNED_IMU.md](refs/LEARNED_IMU.md).

## Supplemental datasets

Fetch status, licenses, and loaders: [docs/refs/DATASETS.md](refs/DATASETS.md). Commands: [scripts/fetch_datasets.md](../scripts/fetch_datasets.md).

- [Google Smartphone Decimeter Challenge overview](https://www.ion.org/gnss/googlecompetition.cfm) and [2023 data](https://www.kaggle.com/competitions/smartphone-decimeter-2023/data).
- [UrbanNav paper](https://navi.ion.org/content/70/4/navi.602) and [dataset repository](https://github.com/IPNL-POLYU/UrbanNavDataset).
- [KITTI](https://www.cvlibs.net/datasets/kitti/), supporting autonomous-driving dataset.
- [OxIOD](http://deepio.cs.ox.ac.uk/), phone pedestrian IMU.
- [RoNIN](https://ronin.cs.sfu.ca/), phone pedestrian IMU, research-only license.
- [TUM VI](https://cvg.cit.tum.de/data/datasets/visual-inertial-dataset), handheld VI rig.
- [EuRoC MAV](https://projects.asl.ethz.ch/datasets/doku.php?id=kmavvisualinertialdatasets), aerial robot.
- [ISRO NavIC FAQ](https://www.isro.gov.in/FAQ_Navigation.html) and [Android CONSTELLATION_IRNSS](https://developer.android.com/reference/android/location/GnssStatus#CONSTELLATION_IRNSS). Phone logging and claim limits: [docs/refs/NAVIC.md](refs/NAVIC.md).

## Source-use cautions

- Current software versions and model terms may change. Pin and recheck them before implementation or distribution.
- Performance in a paper applies to that paper's sensors, routes, splits, and ground truth.
- OpenStreetMap data and map-rendering services are different things. An open database does not authorize bulk use of the public tile server.
- A repository without an obvious data license needs explicit review before redistribution. DriftZero should store fetch manifests rather than republishing third-party raw data.

