"""OxIOD phone IMU sequences. Pedestrian, not vehicle."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

from driftzero_ml.datasets.errors import DatasetMissing
from driftzero_ml.datasets.lfs import FETCH_DOC, require_real_file

SOURCE_URL = "http://deepio.cs.ox.ac.uk/"
PAPER = "https://arxiv.org/abs/1809.07491"
FETCH_HINT = (
    f"Download OxIOD from {SOURCE_URL} into data/raw/oxiod/ so a sequence "
    f"folder contains syn/acc.csv (and syn/gyr.csv when present). See {FETCH_DOC}."
)


@dataclass(frozen=True)
class OxiodImuRow:
    trip_id: str
    timestamp_s: float
    ax: float
    ay: float
    az: float
    gx: float | None
    gy: float | None
    gz: float | None


def find_acc_csv(root: Path) -> Path:
    resolved = root.expanduser().resolve()
    if resolved.is_file() and resolved.name.lower().startswith("acc"):
        return require_real_file(resolved, dataset="OxIOD", fetch_hint=FETCH_HINT)
    matches = sorted(resolved.rglob("acc.csv"))
    if not matches:
        matches = sorted(resolved.rglob("acc*.csv"))
    if not matches:
        raise DatasetMissing(f"OxIOD acc.csv not found under {resolved}. {FETCH_HINT}")
    return require_real_file(matches[0], dataset="OxIOD", fetch_hint=FETCH_HINT)


def load_sequence(root: Path) -> list[OxiodImuRow]:
    """Load accelerometer, and gyroscope if a sibling gyr.csv exists.

    Headers are inspected. A four-column headerless table is treated as
    time, x, y, z. That is the published OxIOD dump layout, not a guess of
    IO-VNBD names.
    """

    acc_path = find_acc_csv(root)
    acc = _load_xyz(acc_path)
    gyr_path = acc_path.with_name("gyr.csv")
    gyro = _load_xyz(gyr_path) if gyr_path.is_file() else []
    gyro_by_t = {row[0]: row[1:] for row in gyro}
    trip_id = acc_path.parent.parent.name if acc_path.parent.name == "syn" else acc_path.stem
    rows: list[OxiodImuRow] = []
    for stamp, ax, ay, az in acc:
        g = gyro_by_t.get(stamp)
        rows.append(
            OxiodImuRow(
                trip_id=trip_id,
                timestamp_s=stamp,
                ax=ax,
                ay=ay,
                az=az,
                gx=None if g is None else g[0],
                gy=None if g is None else g[1],
                gz=None if g is None else g[2],
            )
        )
    if not rows:
        raise ValueError(f"{acc_path} has no IMU rows")
    return rows


def _load_xyz(path: Path) -> list[tuple[float, float, float, float]]:
    resolved = require_real_file(path, dataset="OxIOD", fetch_hint=FETCH_HINT)
    text = resolved.read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        raise ValueError(f"{resolved} is empty")
    first = next(csv.reader([lines[0]]))
    start = 0
    if any(_is_name(cell) for cell in first):
        start = 1
    rows: list[tuple[float, float, float, float]] = []
    for line in lines[start:]:
        cells = next(csv.reader([line]))
        if len(cells) < 4:
            raise ValueError(f"{resolved} row has {len(cells)} fields, expected 4")
        rows.append((float(cells[0]), float(cells[1]), float(cells[2]), float(cells[3])))
    return rows


def _is_name(cell: str) -> bool:
    stripped = cell.strip().lower()
    if not stripped:
        return False
    try:
        float(stripped)
        return False
    except ValueError:
        return True
