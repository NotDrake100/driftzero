"""TUM VI IMU tables. Handheld stereo+IMU rig, not a phone MEMS dataset."""

from __future__ import annotations

from pathlib import Path

from driftzero_ml.datasets.errors import DatasetMissing
from driftzero_ml.datasets.euroc import EurocImuRow, load_imu_csv
from driftzero_ml.datasets.lfs import FETCH_DOC, require_real_file

SOURCE_URL = "https://cvg.cit.tum.de/data/datasets/visual-inertial-dataset"
FETCH_HINT = (
    f"Download a euroc-format sequence from {SOURCE_URL} into "
    f"data/raw/tumvi/<sequence>/ so mav0/imu0/data.csv exists. See {FETCH_DOC}."
)


def find_imu_csv(root: Path) -> Path:
    resolved = root.expanduser().resolve()
    if resolved.is_file():
        return require_real_file(resolved, dataset="TUM VI", fetch_hint=FETCH_HINT)
    for relative in ("mav0/imu0/data.csv", "imu0/data.csv"):
        path = resolved / relative
        if path.is_file():
            return require_real_file(path, dataset="TUM VI", fetch_hint=FETCH_HINT)
    raise DatasetMissing(f"TUM VI IMU CSV not found under {resolved}. {FETCH_HINT}")


def load_tumvi_imu(path: Path) -> list[EurocImuRow]:
    """TUM VI euroc packs use the ASL IMU CSV layout."""

    return load_imu_csv(find_imu_csv(path) if path.is_dir() else path)
