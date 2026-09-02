"""Local dataset loaders. Fail clearly when LFS or downloads are missing."""

from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.datasets.euroc import load_imu_csv as load_euroc_imu
from driftzero_ml.datasets.io_vnbd import load_smartphone_csv, require_iovnbd_tables
from driftzero_ml.datasets.lfs import is_lfs_pointer, require_real_file
from driftzero_ml.datasets.oxiod import load_sequence as load_oxiod_sequence
from driftzero_ml.datasets.ronin import require_sequence as require_ronin_sequence
from driftzero_ml.datasets.tumvi import load_tumvi_imu

__all__ = [
    "DatasetLfsMissing",
    "DatasetMissing",
    "is_lfs_pointer",
    "load_euroc_imu",
    "load_oxiod_sequence",
    "load_smartphone_csv",
    "load_tumvi_imu",
    "require_iovnbd_tables",
    "require_real_file",
    "require_ronin_sequence",
]
