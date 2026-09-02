"""Google Smartphone Decimeter Challenge. Phone GNSS plus IMU, Kaggle gated."""

from __future__ import annotations

from pathlib import Path

from driftzero_ml.datasets.errors import DatasetMissing
from driftzero_ml.datasets.lfs import FETCH_DOC, is_lfs_pointer, require_real_file
from driftzero_ml.io_vnbd.discover import inspect_delimited_table

SOURCE_URL = "https://www.kaggle.com/competitions/smartphone-decimeter-2023/data"
OVERVIEW = "https://www.ion.org/gnss/googlecompetition.cfm"
FETCH_HINT = (
    f"Accept the Kaggle rules, then download into data/raw/gsdc2023/ so "
    f"train/*/device_imu.csv exists. {SOURCE_URL} See {FETCH_DOC}."
)


def find_imu_csvs(root: Path) -> tuple[Path, ...]:
    resolved = root.expanduser().resolve()
    if not resolved.is_dir():
        raise DatasetMissing(f"GSDC root is not at {resolved}. {FETCH_HINT}")
    found = tuple(sorted(p for p in resolved.rglob("device_imu.csv") if p.is_file()))
    if not found:
        raise DatasetMissing(f"No device_imu.csv under {resolved}. {FETCH_HINT}")
    real = tuple(path for path in found if not is_lfs_pointer(path))
    if not real:
        raise DatasetMissing(
            f"GSDC IMU tables under {resolved} are still placeholders. {FETCH_HINT}"
        )
    return real


def inspect_first_imu(root: Path):
    path = require_real_file(find_imu_csvs(root)[0], dataset="GSDC", fetch_hint=FETCH_HINT)
    return inspect_delimited_table(path)
