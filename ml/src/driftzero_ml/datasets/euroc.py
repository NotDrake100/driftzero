"""EuRoC MAV ASL IMU tables. Robot / MAV, not phone MEMS."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

from driftzero_ml.datasets.errors import DatasetMissing
from driftzero_ml.datasets.lfs import FETCH_DOC, require_real_file

SOURCE_URL = "https://projects.asl.ethz.ch/datasets/doku.php?id=kmavvisualinertialdatasets"
ETH_COLLECTION = "https://www.research-collection.ethz.ch/handle/20.500.11850/117682"
FETCH_HINT = (
    f"Download an ASL zip from {SOURCE_URL} (ETH Research Collection: "
    f"{ETH_COLLECTION}) into data/raw/euroc/<sequence>/ so that "
    f"mav0/imu0/data.csv exists. See {FETCH_DOC}."
)

IMU_HEADER_TOKENS = (
    "timestamp",
    "w_rs_s_x",
    "w_rs_s_y",
    "w_rs_s_z",
    "a_rs_s_x",
    "a_rs_s_y",
    "a_rs_s_z",
)


@dataclass(frozen=True)
class EurocImuRow:
    timestamp_ns: int
    wx: float
    wy: float
    wz: float
    ax: float
    ay: float
    az: float


def find_imu_csv(root: Path) -> Path:
    resolved = root.expanduser().resolve()
    candidates = (
        resolved / "mav0" / "imu0" / "data.csv",
        resolved / "imu0" / "data.csv",
        resolved if resolved.is_file() else resolved / "data.csv",
    )
    for path in candidates:
        if path.is_file():
            return require_real_file(path, dataset="EuRoC", fetch_hint=FETCH_HINT)
    raise DatasetMissing(f"EuRoC IMU CSV not found under {resolved}. {FETCH_HINT}")


def load_imu_csv(path: Path) -> list[EurocImuRow]:
    """Parse the documented ASL IMU header. Synthetic format fixtures are allowed."""

    resolved = require_real_file(path, dataset="EuRoC", fetch_hint=FETCH_HINT)
    text = resolved.read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        raise ValueError(f"EuRoC IMU table is empty: {resolved}")
    header = [part.strip().lstrip("#").strip() for part in next(csv.reader([lines[0]]))]
    compact = [_compact(name) for name in header]
    if len(compact) < 7 or not compact[0].startswith("timestamp"):
        raise ValueError(
            f"{resolved} is not an ASL IMU table. Expected timestamp plus "
            f"w_RS_S_* and a_RS_S_*. Observed: {header}"
        )
    for token in IMU_HEADER_TOKENS[1:]:
        if token not in compact:
            raise ValueError(
                f"{resolved} missing {token} after inspection. Observed: {header}"
            )
    index = {name: i for i, name in enumerate(compact)}
    rows: list[EurocImuRow] = []
    for line in lines[1:]:
        cells = next(csv.reader([line]))
        if len(cells) < 7:
            raise ValueError(f"{resolved} row has {len(cells)} fields, expected 7")
        rows.append(
            EurocImuRow(
                timestamp_ns=int(cells[index["timestamp"] if "timestamp" in index else 0]),
                wx=float(cells[index["w_rs_s_x"]]),
                wy=float(cells[index["w_rs_s_y"]]),
                wz=float(cells[index["w_rs_s_z"]]),
                ax=float(cells[index["a_rs_s_x"]]),
                ay=float(cells[index["a_rs_s_y"]]),
                az=float(cells[index["a_rs_s_z"]]),
            )
        )
    if not rows:
        raise ValueError(f"{resolved} has no IMU rows")
    return rows


def _compact(name: str) -> str:
    return name.lower().split("[", 1)[0].strip().replace(" ", "_")
