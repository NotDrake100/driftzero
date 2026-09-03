# Fetch datasets

Raw trees belong in gitignored `data/raw/`. Do not commit TimesFM weights.

Prefer one small smartphone file first. Full LFS needs `git-lfs` and on the order of 1 to 2 GB.

## 1. IO-VNBD (official SIH reference)

Source: https://github.com/onyekpeu/IO-VNBD

Paper (CC BY 4.0): https://doi.org/10.1016/j.dib.2021.106885

All `*.csv` and `*.zip` entries are Git LFS. A clone without LFS is about 5 MB of pointers. Pointer `size` fields: synchronized zip 203606286 bytes, unsynchronized zip 214330231 bytes, all `S-*.csv` about 763 MB.

### One small smartphone file (no git-lfs)

```bash
mkdir -p "data/raw/io_vnbd/Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta09"
curl -L --fail -o "data/raw/io_vnbd/Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta09/S-Vta9.csv" \
  "https://media.githubusercontent.com/media/onyekpeu/IO-VNBD/master/Synchronised%20V%20abd%20S%20datasets/Categorised%20IOVNB%20Dataset/Vta%20(Driver%20E)/Vta09/S-Vta9.csv"
# expect about 29554 bytes of CSV, not a 130-byte pointer
```

Inspect:

```bash
PYTHONPATH=ml/src python -c "
from pathlib import Path
from driftzero_ml.datasets.io_vnbd import load_smartphone_csv, require_iovnbd_tables
print(require_iovnbd_tables(Path('data/raw/io_vnbd')))
print(len(load_smartphone_csv(Path('data/raw/io_vnbd/Synchronised V abd S datasets/Categorised IOVNB Dataset/Vta (Driver E)/Vta09/S-Vta9.csv'))))
"
```

### Full tree (needs git-lfs and about 1 to 2 GB)

```bash
brew install git-lfs   # or: https://git-lfs.com
git lfs install
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/onyekpeu/IO-VNBD.git data/raw/io_vnbd
cd data/raw/io_vnbd
# smartphone only (screening path)
git lfs pull --include="**/S-*.csv"
# or everything
# git lfs pull
```

If a file still starts with `version https://git-lfs.github.com/spec/v1`, the loader raises `DatasetLfsMissing`.

## 2. OxIOD (phone, pedestrian)

```bash
# Project page lists the zip. Confirm terms, then:
mkdir -p data/raw/oxiod
# download from http://deepio.cs.ox.ac.uk/ into data/raw/oxiod/
# expect sequence folders with syn/acc.csv and syn/gyr.csv
```

## 3. RoNIN (phone, pedestrian, research-only)

```bash
# Accept the FRDR custom NC license first.
# https://doi.org/10.20383/102.0543
# https://ronin.cs.sfu.ca/
mkdir -p data/raw/ronin
# unzip so each sequence has data.hdf5 and info.json
```

## 4. TUM VI (handheld VI rig)

```bash
# CC BY 4.0. https://cvg.cit.tum.de/data/datasets/visual-inertial-dataset
mkdir -p data/raw/tumvi
# smallest useful pack: a euroc-format room sequence (images make it large)
# wget the euroc zip, unzip so mav0/imu0/data.csv exists
```

## 5. EuRoC (MAV / robot)

```bash
# https://projects.asl.ethz.ch/datasets/doku.php?id=kmavvisualinertialdatasets
mkdir -p data/raw/euroc
# ASL zip for MH_01_easy or similar. Need mav0/imu0/data.csv
# Do not commit images.
```

## 6. Google Smartphone Decimeter Challenge 2023

```bash
# Accept Kaggle rules, then:
# kaggle competitions download -c smartphone-decimeter-2023 -p data/raw/gsdc2023
# unzip so train/*/device_imu.csv exists
```

## 7. TimesFM

Designed, not run, until `results/timesfm/` exists. Do not put weights in this repo. TimesFM 3.0 weights use `timesfm-non-commercial-license-v1.0` and cannot ship. Distillation from 3.0 needs a license read. 2.5 is Apache-2.0. If you run the optional teacher:

```bash
# desktop only, outside Git
# huggingface-cli download google/timesfm-3.0-pytorch --local-dir "$HOME/models/timesfm-3.0-pytorch"
```
