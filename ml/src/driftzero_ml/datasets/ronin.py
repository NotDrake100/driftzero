"""RoNIN phone IMU sequences. Pedestrian, custom research license."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from driftzero_ml.datasets.errors import DatasetMissing
from driftzero_ml.datasets.lfs import FETCH_DOC, require_real_file

SOURCE_URL = "https://ronin.cs.sfu.ca/"
FRDR = "https://doi.org/10.20383/102.0543"
README = "https://ronin.cs.sfu.ca/README.txt"
FETCH_HINT = (
    f"Download RoNIN from {FRDR} (project page {SOURCE_URL}) into "
    f"data/raw/ronin/<sequence>/ with data.hdf5 and info.json. See {FETCH_DOC}."
)


@dataclass(frozen=True)
class RoninSequence:
    root: Path
    hdf5: Path
    info_json: Path


def require_sequence(root: Path) -> RoninSequence:
    resolved = root.expanduser().resolve()
    hdf5 = resolved / "data.hdf5" if resolved.is_dir() else resolved
    if hdf5.name != "data.hdf5":
        found = sorted(resolved.rglob("data.hdf5")) if resolved.is_dir() else []
        if not found:
            raise DatasetMissing(f"RoNIN data.hdf5 not found under {resolved}. {FETCH_HINT}")
        hdf5 = found[0]
    payload = require_real_file(hdf5, dataset="RoNIN", fetch_hint=FETCH_HINT)
    info = payload.with_name("info.json")
    if not info.is_file():
        raise DatasetMissing(f"RoNIN info.json missing next to {payload}. {FETCH_HINT}")
    require_real_file(info, dataset="RoNIN", fetch_hint=FETCH_HINT)
    return RoninSequence(root=payload.parent, hdf5=payload, info_json=info)


def describe_sequence(root: Path) -> dict:
    """Open hdf5 only when h5py is installed. Otherwise report paths."""

    seq = require_sequence(root)
    report = {
        "root": str(seq.root),
        "hdf5": str(seq.hdf5),
        "info_json": str(seq.info_json),
        "source": SOURCE_URL,
        "readme": README,
    }
    try:
        import h5py  # type: ignore
    except ImportError:
        report["h5py"] = "missing"
        report["note"] = "Install h5py to read synced IMU groups. Paths are valid."
        return report
    with h5py.File(seq.hdf5, "r") as handle:
        report["h5py"] = "ok"
        report["keys"] = sorted(handle.keys())
    return report
