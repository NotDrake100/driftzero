# Datasets: given vs extra

Retrieved 2026-09-02. Product decisions stay in `PRD.md`. This page is the fetch and license scorecard. Loaders live in `ml/src/driftzero_ml/datasets/`. Commands are in `scripts/fetch_datasets.md`.

No SAC, NRSC, or `isro.gov.in` data pack is named in `PRD.md`, `docs/04_DATASETS_AND_DATA_GOVERNANCE.md`, or `docs/01_SIH_REQUIREMENTS_TRACEABILITY.md`. The official SIH page (`https://www.sih.gov.in/sih2026PS`, ID 26168) is cited as the source of record. In this repo that named pack is IO-VNBD.

TimesFM checkpoints are not stored here. TimesFM 3.0 is designed, not run, until `results/timesfm/` exists. 3.0 cannot ship. Distillation from 3.0 needs a license read. 2.5 is Apache-2.0.

## What Git contains versus a local checkout

Raw IO-VNBD CSVs are gitignored. A judge clone does not receive them. Hashes for a screening subset live in `data/manifests/io_vnbd_screening_v1.yaml`. Fetch commands: `scripts/fetch_datasets.md`.

| Dataset | In this Git tree? | Path |
|---|---|---|
| IO-VNBD (official) | No. Local `data/raw/io_vnbd/` is gitignored. Pending Git LFS on a clean machine. | `data/raw/io_vnbd/` after you fetch |
| IO-VNBD test excerpt | Yes | `ml/tests/fixtures/io_vnbd_s_vta9_head.csv` (header plus 3 rows) |
| OxIOD, RoNIN, TUM VI, EuRoC, GSDC | No | Expected under `data/raw/<name>/` after you fetch |

## Given (official reference)

| Dataset | Platform | URL | License / SIH training | Loader |
|---|---|---|---|---|
| **IO-VNBD** | Android phone in a car (10 Hz IMU+GNSS) plus vehicle ECU traces. UK, Nigeria, France. Not Indian roads. | Repo: https://github.com/onyekpeu/IO-VNBD Paper: https://doi.org/10.1016/j.dib.2021.106885 Speed paper: https://arxiv.org/abs/2005.01701 | Data in Brief article is **CC BY 4.0**. GitHub has no LICENSE file. SIH training and local screening: **yes**, with attribution (Onyekpe et al.). Do not re-host the full LFS tree in this Git repo. Vehicle wheel-speed columns are diagnostic only. They are not a phone product input. | `driftzero_ml.datasets.io_vnbd`: `require_iovnbd_tables`, `load_smartphone_csv`. Fails if the tree is missing or still Git LFS pointers. |

Inspected smartphone header (file `S-Vta9.csv`, latin-1): GPS lat/lon/alt/speed/accuracy/orientation/satellites, time since start (ms), date, accelerometer X/Y/Z (m/s²), gravity X/Y/Z, gyroscope yaw/pitch/roll (rad/s), magnetic field, orientation. Gyro is kept as published yaw/pitch/roll. That is not a verified Android axis map.

Preferred tree name in the upstream repo is misspelled `Synchronised V abd S datasets`.

## Extra (phone IMU/GNSS, not OBD)

| Dataset | Phone vs robot | URL | License / SIH training | Loader | Use here |
|---|---|---|---|---|---|
| **OxIOD** | **Phone**, pedestrian (handheld, pocket, bag, trolley). iPhone-class MEMS. Not vehicle. | http://deepio.cs.ox.ac.uk/ Paper: https://arxiv.org/abs/1809.07491 | No explicit data license on the project page. Cite Chen et al. Local research: **caution, probably OK**. Do not redistribute the zip. | `driftzero_ml.datasets.oxiod.load_sequence` (`syn/acc.csv`, optional `gyr.csv`) | Domain-shift / pedestrian IMU only |
| **RoNIN** | **Phone**, pedestrian. Android IMU phone plus a Tango harness for truth. | https://ronin.cs.sfu.ca/ Data: https://doi.org/10.20383/102.0543 Format: https://ronin.cs.sfu.ca/README.txt Code GPL: https://github.com/Sachini/ronin | **Custom non-commercial research.** Cite Herath, Yan, Furukawa, ICRA 2020. SIH student research: **likely OK**. No commercial reuse. Do not ship their pretrained models. About half the original set was withheld. | `driftzero_ml.datasets.ronin.require_sequence` (`data.hdf5` + `info.json`). Needs `h5py` to read groups. | Pedestrian only |
| **TUM VI** | **Handheld VI rig** (stereo fisheye + IMU at 200 Hz). Not a phone. | https://cvg.cit.tum.de/data/datasets/visual-inertial-dataset Paper: https://arxiv.org/abs/1804.06120 | **CC BY 4.0**. Cite Schubert et al. SIH: **yes** for filter sanity, with attribution. | `driftzero_ml.datasets.tumvi.load_tumvi_imu` (ASL / euroc `imu0/data.csv`) | Robot/handheld IMU, not phone scores |
| **EuRoC MAV** | **Robot.** Asctec Firefly micro aerial vehicle. Stereo + IMU 200 Hz. | https://projects.asl.ethz.ch/datasets/doku.php?id=kmavvisualinertialdatasets ETH collection: https://www.research-collection.ethz.ch/handle/20.500.11850/117682 | Cite Burri et al., IJRR 2016. Confirm ETH collection terms before any redistribute. SIH: **yes** for filter tests, **no** as a phone-MEMS trainer. | `driftzero_ml.datasets.euroc.load_imu_csv` | MAV IMU only |
| **GSDC 2023** | **Phone** GNSS + IMU, several Android models. Mostly US routes. | https://www.kaggle.com/competitions/smartphone-decimeter-2023/data Overview: https://www.ion.org/gnss/googlecompetition.cfm | Kaggle / Google competition rules. Accept before download. SIH local training: **usually OK** after you accept. Do not republish the Kaggle dump. | `driftzero_ml.datasets.gsdc.find_imu_csvs` | Phone GNSS diversity |
| **UrbanNav** | Vehicle multi-sensor rig (Hong Kong, Tokyo, others). Not India. | https://github.com/IPNL-POLYU/UrbanNavDataset Paper: https://navi.ion.org/content/70/4/navi.602 | Check the repo license before training. Urban canyon diagnostics, not a phone-only trainer. | No loader (bags / custom layout, large) | Supporting |
| **KITTI** | Vehicle IMU/GNSS/cameras | https://www.cvlibs.net/datasets/kitti/ | CC BY-NC-SA. Not phone MEMS. | No loader | Filter sanity only |
| **TIAND** (IIT Hyderabad) | **India roads**, vehicle cameras/radar/LiDAR/survey GNSS. Not a phone. | https://tihan.iith.ac.in/TiAND.html | Research dataset. **Do not** treat as phone IMU+GNSS. | None | Not a DriftZero trainer |

No public India **phone** vehicle IMU+GNSS pack was found. India generalization stays a team-collected, consented pilot (`docs/04_DATASETS_AND_DATA_GOVERNANCE.md`).

## NavIC / IRNSS notes (phone app)

Logging, chip, ISRO FAQ limits, and judge language: [docs/refs/NAVIC.md](NAVIC.md). Short version: log `CONSTELLATION_IRNSS` when Android reports it. Do not describe NavIC visibility as certified integrity or anti-jam. GAGAN is the safety-of-life GPS-augmentation path, not NavIC.

## What this Mac can train

| Job | Can we run it now? |
|---|---|
| Linear motion student on synthetic IMU | Yes. `PYTHONPATH=ml/src python -m driftzero_ml.student.train` |
| Linear student / learned_imu on categorised synchronised IO-VNBD S- tables | After a local LFS pull. Checked-in weights in `models/motion_student_v1/` and `models/learned_imu_v1/` came from one such checkout. Not a screening plot until a locked blackout suite exists in `results/`. |
| Full IO-VNBD screening plot | **Pending.** Raw CSVs are not in Git. Blackout interval IDs are not locked in `results/`. |
| Optional GRU | Only if `torch` is installed (`./ml[research]`). Research artifact, not APK. |
| OxIOD / RoNIN / TUM VI / EuRoC / GSDC | Loaders exist. No local files yet. Pedestrian and robot sets must not be reported as IO-VNBD or India-phone scores. |
| TimesFM teacher | Optional desktop extra. Weights stay out of Git. |

## Attribution (minimum)

If you train on IO-VNBD, cite: Onyekpe, Palade, Kanarachos, et al. IO-VNBD: Inertial and Odometry benchmark dataset for ground vehicle positioning. *Data in Brief* 35 (2021) 106885. https://doi.org/10.1016/j.dib.2021.106885
