"""Paper-shaped causal IMU student. Desktop research only. Never imported by Android.

Heads follow published IMU-odometry papers, not a blog recipe:

- RoNIN (Herath, Yan, Furukawa, ICRA 2020): 2D displacement in a heading-agnostic
  gravity-aligned frame (HACF). Strided loss is MSE of Δp over the window.
- TLIO (Liu et al., RA-L / RSS 2020): the same 3D displacement as an EKF
  measurement, plus diagonal log-σ. Train MSE first, then Gaussian NLL.
- IONet (Chen et al., AAAI 2018): polar (Δl, Δψ). Δl is ||Δp_xy||. Δψ is a head.
- RIDI (Yan, Shan, Furukawa, ECCV 2018): shallow velocity/Δp baseline (ridge here).
- Sibling GRU: still emits forward speed, stop logit, log speed-variance for the
  existing ESKF MotionPseudoMeasurement.

Inputs are accelerometer + gyroscope only. Magnetometer is captured on the phone
but is not a network channel (RoNIN disables mag for orientation; TLIO/IONet do
not use it). GNSS keys never enter features, including during a blackout.

TimesFM is not imported. No artifact from this module is packaged into the APK.
"""

from __future__ import annotations

import argparse
import json
import math
import random
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from typing import Literal, Sequence

from driftzero_ml.blackout import GNSS_KEYS, assert_no_gnss_leakage
from driftzero_ml.features.causal_imu import (
    FEATURE_NAMES,
    GRAVITY_MPS2,
    MAX_SAMPLES,
    MIN_SAMPLES,
    WINDOW_NS,
    ImuSample,
    causal_gravity_vectors,
    extract_causal_imu_features,
    records_to_imu_samples,
    trim_causal_window,
)
from driftzero_ml.io_vnbd import IOVNBDMissing, assign_trip_splits, require_local_root

INPUT_SIZE = 6
HEAD_DIM = 10
ALIGN_LEN = 64
IMU_CHANNELS: tuple[str, ...] = (
    "ax_hacf",
    "ay_hacf",
    "az_hacf",
    "gx_hacf",
    "gy_hacf",
    "gz_hacf",
)
HEAD_NAMES: tuple[str, ...] = (
    "ronin_dx_m",
    "ronin_dy_m",
    "tlio_dz_m",
    "tlio_log_sigma_x",
    "tlio_log_sigma_y",
    "tlio_log_sigma_z",
    "speed_raw",
    "stop_logit",
    "log_speed_variance",
    "ionet_dpsi_rad",
)
LINEAR_FEATURE_NAMES: tuple[str, ...] = FEATURE_NAMES + (
    "ax_hacf_mean",
    "ay_hacf_mean",
    "az_hacf_mean",
)
MAG_KEYS = frozenset(
    {"mx", "my", "mz", "mag_x", "mag_y", "mag_z", "magnetometer", "magnetic_uT"}
)
DATASET_HINTS: dict[str, tuple[str, ...]] = {
    "ronin": ("data/raw/ronin", "data/raw/RoNIN"),
    "oxiod": ("data/raw/oxiod", "data/raw/OxIOD"),
    "io_vnbd": ("data/raw/io_vnbd",),
    "ridi": ("data/raw/ridi", "data/raw/RIDI"),
}
DATASET_URLS: dict[str, str] = {
    "ronin": "https://doi.org/10.20383/102.0543",
    "oxiod": "http://deepio.cs.ox.ac.uk/",
    "io_vnbd": "https://github.com/onyekpeu/IO-VNBD",
    "ridi": "https://github.com/higerra/ridi_imu",
}
IONET_HEADING_WEIGHT = 1.0
LOG_SIGMA_MIN = -8.0
LOG_SIGMA_MAX = 6.0
LINEAR_DP_SCHEMA = "driftzero.linear_dp.v1"
CHI2_GATE_3DOF = 11.345
OVERLAP_R_SCALE = 10.0
Kind = Literal["idle", "cruise", "turn", "brake"]

if set(IMU_CHANNELS) & GNSS_KEYS:
    raise RuntimeError("IMU channels must not include GNSS fields")
if set(LINEAR_FEATURE_NAMES) & GNSS_KEYS:
    raise RuntimeError("linear IMU features must not include GNSS fields")
if set(IMU_CHANNELS) & MAG_KEYS:
    raise RuntimeError("default IMU channels must not include magnetometer")


@dataclass(frozen=True)
class LearnedImuPrediction:
    """One-window student output. Units: metres, m/s, rad, ln(sigma)."""

    dx_m: float
    dy_m: float
    dz_m: float
    log_sigma_x: float
    log_sigma_y: float
    log_sigma_z: float
    forward_speed_mps: float
    stop_logit: float
    log_speed_variance: float
    delta_heading_rad: float

    @property
    def delta_length_m(self) -> float:
        """IONet Δl. Derived from the RoNIN plane, not a free head."""

        return math.hypot(self.dx_m, self.dy_m)

    @property
    def dp_xy_m(self) -> tuple[float, float]:
        return (self.dx_m, self.dy_m)


@dataclass(frozen=True)
class OdometryWindow:
    """Causal HACF sequence plus train-only labels. GNSS is never stored."""

    features: tuple[float, ...]
    sequence: tuple[tuple[float, ...], ...]
    dx_m: float
    dy_m: float
    dz_m: float
    speed_mps: float
    stopped: float
    delta_heading_rad: float
    duration_s: float


@dataclass(frozen=True)
class LinearDisplacementStudent:
    """RIDI-shaped shallow Δp + uncertainty. Stdlib ridge, trip-split fit."""

    weights: tuple[tuple[float, ...], ...]
    feature_names: tuple[str, ...] = LINEAR_FEATURE_NAMES

    def __post_init__(self) -> None:
        dim = len(self.feature_names) + 1
        if len(self.weights) != HEAD_DIM:
            raise ValueError(f"weights must have {HEAD_DIM} targets")
        for row in self.weights:
            if len(row) != dim:
                raise ValueError(f"each target must have {dim} weights")

    def infer(self, vector: Sequence[float]) -> LearnedImuPrediction:
        if len(vector) != len(self.feature_names):
            raise ValueError("feature vector length mismatch")
        raw = [_dot(row, vector) for row in self.weights]
        return decode_raw_heads(raw)


@dataclass(frozen=True)
class DatasetProbe:
    name: str
    present: bool
    path: str | None
    url: str


def hacf_rotation(gravity: tuple[float, float, float]) -> tuple[tuple[float, float, float], ...]:
    """RoNIN HACF: any frame whose Z axis is gravity (paper §4.1).

    Device +X is flattened onto the horizontal plane when it is not parallel
    to gravity, so a level phone keeps R = I.
    """

    norm = math.sqrt(gravity[0] ** 2 + gravity[1] ** 2 + gravity[2] ** 2)
    if norm < 1e-6:
        raise ValueError("gravity vector is too small to form an HACF")
    z = (gravity[0] / norm, gravity[1] / norm, gravity[2] / norm)
    x_h = (1.0 - z[0] * z[0], -z[0] * z[1], -z[0] * z[2])
    if _norm(x_h) < 0.05:
        x_h = (-z[1] * z[0], 1.0 - z[1] * z[1], -z[1] * z[2])
    x = _normalize(x_h)
    y = _normalize(_cross(z, x))
    return (x, y, z)


def rotate_into_hacf(
    vector: tuple[float, float, float],
    rotation: Sequence[Sequence[float]],
) -> tuple[float, float, float]:
    return _matvec(rotation, vector)


def yaw_about_z(radians: float) -> tuple[tuple[float, float, float], ...]:
    c = math.cos(radians)
    s = math.sin(radians)
    return ((c, -s, 0.0), (s, c, 0.0), (0.0, 0.0, 1.0))


def hacf_sequence(
    samples: Sequence[ImuSample],
    *,
    extra_yaw_rad: float = 0.0,
) -> list[list[float]]:
    """TLIO §IV-B: rotate the window by the gravity frame at the first sample.

    Gyro gaps become explicit zeros. Magnetometer and GNSS are not channels.
    """

    if not samples:
        raise ValueError("IMU window must not be empty")
    gravity = causal_gravity_vectors(samples)
    rotation = hacf_rotation(gravity[0])
    if extra_yaw_rad != 0.0:
        rotation = _matmul(yaw_about_z(extra_yaw_rad), rotation)
    rows: list[list[float]] = []
    for sample in samples:
        accel = rotate_into_hacf((sample.ax, sample.ay, sample.az), rotation)
        if sample.gx is None or sample.gy is None or sample.gz is None:
            gyro = (0.0, 0.0, 0.0)
        else:
            gyro = rotate_into_hacf((sample.gx, sample.gy, sample.gz), rotation)
        rows.append([accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2]])
    if any(len(row) != INPUT_SIZE for row in rows):
        raise RuntimeError("HACF sequence drifted from INPUT_SIZE")
    return rows


def left_pad_sequence(sequence: Sequence[Sequence[float]], length: int = ALIGN_LEN) -> list[list[float]]:
    """Fixed-length causal window. Missing past samples are zeros, not future."""

    if length < 1:
        raise ValueError("length must be positive")
    rows = [list(row) for row in sequence]
    if len(rows) >= length:
        return rows[-length:]
    pad = [[0.0] * INPUT_SIZE for _ in range(length - len(rows))]
    return pad + rows


def linear_feature_vector(samples: Sequence[ImuSample], sequence: Sequence[Sequence[float]]) -> tuple[float, ...]:
    pooled = extract_causal_imu_features(samples).vector
    ax = _mean([row[0] for row in sequence])
    ay = _mean([row[1] for row in sequence])
    az = _mean([row[2] for row in sequence])
    vector = (*pooled, ax, ay, az)
    if len(vector) != len(LINEAR_FEATURE_NAMES):
        raise RuntimeError("linear feature length drifted from LINEAR_FEATURE_NAMES")
    return vector


def decode_raw_heads(raw: Sequence[float]) -> LearnedImuPrediction:
    if len(raw) != HEAD_DIM:
        raise ValueError(f"raw heads must have length {HEAD_DIM}")
    return LearnedImuPrediction(
        dx_m=float(raw[0]),
        dy_m=float(raw[1]),
        dz_m=float(raw[2]),
        log_sigma_x=_clamp(float(raw[3]), LOG_SIGMA_MIN, LOG_SIGMA_MAX),
        log_sigma_y=_clamp(float(raw[4]), LOG_SIGMA_MIN, LOG_SIGMA_MAX),
        log_sigma_z=_clamp(float(raw[5]), LOG_SIGMA_MIN, LOG_SIGMA_MAX),
        forward_speed_mps=min(50.0, max(0.0, _softplus(float(raw[6])))),
        stop_logit=float(raw[7]),
        log_speed_variance=_clamp(float(raw[8]), LOG_SIGMA_MIN, LOG_SIGMA_MAX),
        delta_heading_rad=float(raw[9]),
    )


def world_dp_to_hacf(
    dp_world: tuple[float, float, float],
    heading_start_rad: float,
    rotation: Sequence[Sequence[float]],
) -> tuple[float, float, float]:
    """Express a world displacement in the start-of-window HACF (TLIO §V-D)."""

    device = rotate_into_hacf(dp_world, yaw_about_z(-heading_start_rad))
    return rotate_into_hacf(device, rotation)


def odometry_windows(
    records: Sequence[dict],
    *,
    extra_yaw_rad: float = 0.0,
    stride: int = 1,
) -> list[OdometryWindow]:
    """Causal 1 s windows. Pose labels are train-only. GNSS keys are ignored."""

    if stride < 1:
        raise ValueError("stride must be at least 1")
    assert_no_gnss_leakage(records)
    samples = records_to_imu_samples(records)
    by_t = {int(row["timestamp_ns"]): row for row in records if "timestamp_ns" in row}
    labeled: list[OdometryWindow] = []
    for index in range(len(samples)):
        if index % stride != 0:
            continue
        end_ns = samples[index].timestamp_ns
        recent = samples[max(0, index + 1 - MAX_SAMPLES) : index + 1]
        window = trim_causal_window(recent, end_ns)
        if len(window) < MIN_SAMPLES:
            continue
        start = window[0]
        end = window[-1]
        start_row = by_t.get(start.timestamp_ns)
        end_row = by_t.get(end.timestamp_ns)
        if start_row is None or end_row is None:
            continue
        if not _has_pose(start_row) or not _has_pose(end_row):
            continue
        if "speed_mps" not in end_row:
            continue
        sequence = hacf_sequence(window, extra_yaw_rad=extra_yaw_rad)
        gravity = causal_gravity_vectors(window)
        rotation = hacf_rotation(gravity[0])
        if extra_yaw_rad != 0.0:
            rotation = _matmul(yaw_about_z(extra_yaw_rad), rotation)
        dp_world = (
            float(end_row["pose_x_m"]) - float(start_row["pose_x_m"]),
            float(end_row["pose_y_m"]) - float(start_row["pose_y_m"]),
            float(end_row["pose_z_m"]) - float(start_row["pose_z_m"]),
        )
        heading0 = float(start_row["heading_rad"])
        dx, dy, dz = world_dp_to_hacf(dp_world, heading0, rotation)
        dpsi = _wrap(float(end_row["heading_rad"]) - heading0)
        labeled.append(
            OdometryWindow(
                features=linear_feature_vector(window, sequence),
                sequence=tuple(tuple(row) for row in sequence),
                dx_m=dx,
                dy_m=dy,
                dz_m=dz,
                speed_mps=float(end_row["speed_mps"]),
                stopped=float(end_row.get("stopped", 0.0 if end_row["speed_mps"] >= 0.3 else 1.0)),
                delta_heading_rad=dpsi,
                duration_s=(end.timestamp_ns - start.timestamp_ns) / 1_000_000_000.0,
            )
        )
    return labeled


def ronin_strided_mse(dx_hat: float, dy_hat: float, dx: float, dy: float) -> float:
    """RoNIN ResNet strided velocity loss: MSE of Δp over the window (§4.3)."""

    return 0.5 * ((dx_hat - dx) ** 2 + (dy_hat - dy) ** 2)


def tlio_gaussian_nll(
    pred: LearnedImuPrediction,
    dx: float,
    dy: float,
    dz: float,
) -> float:
    """TLIO §IV-A diagonal Gaussian NLL. log σ parametrization, no 3 log(2π)."""

    terms = (
        (pred.dx_m, pred.log_sigma_x, dx),
        (pred.dy_m, pred.log_sigma_y, dy),
        (pred.dz_m, pred.log_sigma_z, dz),
    )
    acc = 0.0
    for hat, log_sigma, target in terms:
        sigma = math.exp(log_sigma)
        resid = (hat - target) / sigma
        acc += log_sigma + 0.5 * resid * resid
    return acc


def ionet_polar_loss(pred: LearnedImuPrediction, dx: float, dy: float, dpsi: float) -> float:
    """IONet polar loss: ||Δl||^2 + κ ||Δψ||^2 (AAAI 2018)."""

    length_err = pred.delta_length_m - math.hypot(dx, dy)
    heading_err = _wrap(pred.delta_heading_rad - dpsi)
    return length_err * length_err + IONET_HEADING_WEIGHT * heading_err * heading_err


def fit_linear_displacement_student(
    windows: Sequence[OdometryWindow],
    *,
    l2: float = 1e-2,
) -> LinearDisplacementStudent:
    if not windows:
        raise ValueError("windows must not be empty")
    targets = [
        [
            row.dx_m,
            row.dy_m,
            row.dz_m,
            math.log(max(1e-3, 0.15 * abs(row.dx_m) + 0.05)),
            math.log(max(1e-3, 0.15 * abs(row.dy_m) + 0.05)),
            math.log(max(1e-3, 0.15 * abs(row.dz_m) + 0.05)),
            _inv_softplus(max(0.0, row.speed_mps)),
            _logit(row.stopped),
            math.log(max(1e-4, (row.speed_mps * 0.15 + 0.2) ** 2)),
            row.delta_heading_rad,
        ]
        for row in windows
    ]
    features = [row.features for row in windows]
    weights = tuple(tuple(_ridge(features, [item[col] for item in targets], l2)) for col in range(HEAD_DIM))
    return LinearDisplacementStudent(weights=weights)


def save_linear_displacement_student(model: LinearDisplacementStudent, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": LINEAR_DP_SCHEMA,
        "feature_names": list(model.feature_names),
        "weights": [list(row) for row in model.weights],
        "heads": list(HEAD_NAMES),
        "chi2_gate": CHI2_GATE_3DOF,
        "overlap_r_scale": OVERLAP_R_SCALE,
        "log_sigma_min": LOG_SIGMA_MIN,
        "log_sigma_max": LOG_SIGMA_MAX,
        "window_ns": WINDOW_NS,
        "papers": {
            "ronin_dx_m": "Herath et al. ICRA 2020",
            "ronin_dy_m": "Herath et al. ICRA 2020",
            "tlio_dz_m": "Liu et al. RA-L 2020",
            "tlio_log_sigma": "Liu et al. RA-L 2020",
            "speed": "MotionPseudoMeasurement / sibling GRU",
            "ionet_dpsi_rad": "Chen et al. AAAI 2018",
        },
    }
    path.write_text(json.dumps(payload, indent=2) + "\n")


def load_linear_displacement_student(path: Path) -> LinearDisplacementStudent:
    """Kotlin-facing reader. Same JSON the ESKF loads from assets."""

    payload = json.loads(path.read_text())
    schema = payload.get("schema")
    if schema is not None and schema != LINEAR_DP_SCHEMA:
        raise ValueError(f"unsupported linear_dp schema: {schema}")
    names = tuple(str(item) for item in payload["feature_names"])
    if set(names) & GNSS_KEYS:
        raise ValueError("linear_dp feature_names must not include GNSS fields")
    weights = tuple(tuple(float(item) for item in row) for row in payload["weights"])
    return LinearDisplacementStudent(weights=weights, feature_names=names)


def probe_learned_imu_datasets(root: Path | None = None) -> list[DatasetProbe]:
    """Detect OxIOD / RoNIN / IO-VNBD / RIDI on disk. Never downloads."""

    base = Path.cwd() if root is None else root
    found: list[DatasetProbe] = []
    for name, relatives in DATASET_HINTS.items():
        hit: Path | None = None
        for relative in relatives:
            candidate = (base / relative).expanduser()
            if candidate.is_dir() and any(candidate.iterdir()):
                if name == "io_vnbd":
                    try:
                        require_local_root(candidate)
                    except IOVNBDMissing:
                        continue
                hit = candidate
                break
        found.append(
            DatasetProbe(
                name=name,
                present=hit is not None,
                path=None if hit is None else str(hit),
                url=DATASET_URLS[name],
            )
        )
    return found


def synthetic_vehicle_odometry(
    kind: Kind,
    *,
    duration_s: float = 4.0,
    hz: int = 50,
    seed: int = 0,
    trip_id: str = "synthetic-0",
) -> list[dict]:
    """Vehicle IMU plus pose labels. No GNSS fields. Mag is omitted on purpose."""

    if hz <= 0 or duration_s <= 0:
        raise ValueError("hz and duration_s must be positive")
    rng = random.Random(seed)
    n = int(duration_s * hz)
    dt = 1.0 / hz
    dt_ns = 1_000_000_000 // hz
    rows: list[dict] = []
    speed = 0.0
    heading = 0.0
    x = 0.0
    y = 0.0
    z = 0.0
    for index in range(n):
        t = index * dt
        yaw_rate = 0.0
        ax_body = 0.0
        if kind == "idle":
            speed = 0.0
            vib = 0.02
        elif kind == "cruise":
            speed = 12.0
            vib = 1.6
        elif kind == "turn":
            speed = 8.0
            yaw_rate = 0.35
            vib = 1.2
        else:
            speed = max(0.0, 12.0 * (1.0 - t / max(duration_s, 1e-6)))
            ax_body = -3.0 if speed > 0.4 else 0.0
            vib = 1.0 if speed > 0.4 else 0.02
            if speed < 0.4:
                speed = 0.0
        heading = _wrap(heading + yaw_rate * dt)
        x += speed * math.cos(heading) * dt
        y += speed * math.sin(heading) * dt
        ax, ay, az = _vehicle_accel(rng, ax_body, vib)
        gx = rng.gauss(0.0, 0.01 if speed < 0.3 else 0.04)
        gy = rng.gauss(0.0, 0.01 if speed < 0.3 else 0.03)
        gz = yaw_rate + rng.gauss(0.0, 0.01 if speed < 0.3 else 0.03)
        rows.append(
            {
                "trip_id": trip_id,
                "timestamp_ns": index * dt_ns,
                "ax": ax,
                "ay": ay,
                "az": az,
                "gx": gx,
                "gy": gy,
                "gz": gz,
                "pose_x_m": x,
                "pose_y_m": y,
                "pose_z_m": z,
                "heading_rad": heading,
                "speed_mps": speed,
                "stopped": 1.0 if speed < 0.3 else 0.0,
            }
        )
    return rows


def default_synthetic_odometry(seed: int = 26168) -> dict[str, list[dict]]:
    trips: dict[str, list[dict]] = {}
    index = 0
    for kind in ("idle", "cruise", "turn", "brake"):
        for copy in range(3):
            trip_id = f"{kind}-{copy}"
            trips[trip_id] = synthetic_vehicle_odometry(
                kind,
                duration_s=3.0,
                hz=50,
                seed=seed + index,
                trip_id=trip_id,
            )
            index += 1
    return trips


def train_from_odometry(
    trips: dict[str, list[dict]],
    *,
    seed: str = "26168",
    stride: int = 1,
) -> dict:
    assignments = {row.trip_id: row.split for row in assign_trip_splits(sorted(trips), seed=seed)}
    train_windows: list[OdometryWindow] = []
    eval_rows: dict[str, list[OdometryWindow]] = defaultdict(list)
    for trip_id, records in trips.items():
        split = assignments[trip_id]
        for window in odometry_windows(records, stride=stride):
            if split == "train":
                train_windows.append(window)
            else:
                eval_rows[split].append(window)
    if not train_windows:
        raise ValueError("no labeled IMU odometry windows in the training split")
    student = fit_linear_displacement_student(train_windows)
    report: dict = {
        "seed": seed,
        "train_windows": len(train_windows),
        "heads": {
            "ronin_dp_xy": "Herath et al. ICRA 2020 (RoNIN HACF Δp)",
            "tlio_dz_log_sigma": "Liu et al. RA-L 2020 (TLIO displacement + log σ)",
            "ionet_polar": "Chen et al. AAAI 2018 (Δl derived, Δψ head)",
            "speed_stop_logvar": "existing ESKF MotionPseudoMeasurement",
        },
        "splits": {},
    }
    for split, rows in eval_rows.items():
        if not rows:
            continue
        predicted = [student.infer(row.features) for row in rows]
        report["splits"][split] = {
            "count": len(rows),
            "ronin_dp_xy_mae_m": _mae_xy(predicted, rows),
            "tlio_nll": mean(
                tlio_gaussian_nll(pred, row.dx_m, row.dy_m, row.dz_m)
                for pred, row in zip(predicted, rows)
            ),
            "ionet_polar_loss": mean(
                ionet_polar_loss(pred, row.dx_m, row.dy_m, row.delta_heading_rad)
                for pred, row in zip(predicted, rows)
            ),
            "speed_mae_mps": mean(
                abs(pred.forward_speed_mps - row.speed_mps) for pred, row in zip(predicted, rows)
            ),
            "freeze_dp_xy_mae_m": mean(math.hypot(row.dx_m, row.dy_m) for row in rows),
        }
    return {"student": student, "report": report}


def train_torch_student(trips: dict[str, list[dict]], out_dir: Path) -> str:
    """Compact GRU, then compact TCN. RoNIN MSE warmup, then TLIO NLL."""

    from driftzero_ml.student.gru import torch_is_installed

    if not torch_is_installed():
        return "skipped: torch not installed"
    import torch

    windows = [window for records in trips.values() for window in odometry_windows(records)]
    if len(windows) < 8:
        return "skipped: not enough odometry windows"
    windows = windows[:96]
    batched = torch.tensor(
        [left_pad_sequence(window.sequence) for window in windows],
        dtype=torch.float32,
    )
    target = torch.tensor(
        [
            [
                window.dx_m,
                window.dy_m,
                window.dz_m,
                window.speed_mps,
                window.stopped,
                window.delta_heading_rad,
            ]
            for window in windows
        ],
        dtype=torch.float32,
    )
    notes: list[str] = []
    for name, builder in (
        ("gru.pt", _build_aligned_gru),
        ("tcn.pt", build_ronin_tcn),
    ):
        model = builder()
        opt = torch.optim.Adam(model.parameters(), lr=1e-2)
        model.train()
        for epoch in range(12):
            opt.zero_grad()
            pred = model(batched)
            if epoch < 6:
                loss = _torch_ronin_mse(pred, target)
            else:
                loss = _torch_tlio_nll(pred, target) + 0.25 * _torch_ionet(pred, target)
            loss = loss + 0.25 * _torch_speed(pred, target)
            loss.backward()
            opt.step()
        out_dir.mkdir(parents=True, exist_ok=True)
        path = out_dir / name
        torch.save(model.state_dict(), path)
        notes.append(f"wrote {path} (research only, not for APK)")
    return "; ".join(notes)


def build_ronin_tcn():
    """Compact causal TCN. RoNIN TCN uses 6 blocks / up to 128 ch. We keep 4×32."""

    from driftzero_ml.student.gru import require_torch

    require_torch()
    import torch
    from torch import nn

    class CausalConv(nn.Module):
        def __init__(self, channels_in: int, channels_out: int, dilation: int) -> None:
            super().__init__()
            self.pad = dilation * 2
            self.conv = nn.Conv1d(
                channels_in,
                channels_out,
                kernel_size=3,
                dilation=dilation,
                padding=0,
            )

        def forward(self, series: torch.Tensor) -> torch.Tensor:
            padded = nn.functional.pad(series, (self.pad, 0))
            return self.conv(padded)

    class ResidualBlock(nn.Module):
        def __init__(self, channels_in: int, channels_out: int, dilation: int) -> None:
            super().__init__()
            self.body = nn.Sequential(
                CausalConv(channels_in, channels_out, dilation),
                nn.ReLU(),
                CausalConv(channels_out, channels_out, dilation),
            )
            self.skip = (
                nn.Identity()
                if channels_in == channels_out
                else nn.Conv1d(channels_in, channels_out, kernel_size=1)
            )

        def forward(self, series: torch.Tensor) -> torch.Tensor:
            return nn.functional.relu(self.body(series) + self.skip(series))

    class CompactRoninTcn(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.blocks = nn.Sequential(
                ResidualBlock(INPUT_SIZE, 16, 1),
                ResidualBlock(16, 16, 2),
                ResidualBlock(16, 32, 4),
                ResidualBlock(32, 32, 8),
            )
            self.head = nn.Linear(32, HEAD_DIM)

        def forward(self, window: torch.Tensor):
            # window: [batch, time, 6], already HACF, causal left-pad.
            series = window.transpose(1, 2)
            encoded = self.blocks(series)
            raw = self.head(encoded[:, :, -1])
            return decode_torch_heads(raw)

    return CompactRoninTcn()


def decode_torch_heads(raw):
    from driftzero_ml.student.gru import require_torch

    require_torch()
    import torch
    from torch.nn import functional as F

    class TorchHeads:
        def __init__(self, tensor: torch.Tensor) -> None:
            self.dx = tensor[:, 0]
            self.dy = tensor[:, 1]
            self.dz = tensor[:, 2]
            self.log_sigma_x = tensor[:, 3].clamp(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
            self.log_sigma_y = tensor[:, 4].clamp(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
            self.log_sigma_z = tensor[:, 5].clamp(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
            self.speed = F.softplus(tensor[:, 6])
            self.stop_logit = tensor[:, 7]
            self.log_speed_var = tensor[:, 8].clamp(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
            self.dpsi = tensor[:, 9]

        @property
        def delta_length(self):
            return torch.hypot(self.dx, self.dy)

    return TorchHeads(raw)


def apply_output_heads(raw):
    """Shared GRU/TCN head decode. Used by student.gru."""

    return decode_torch_heads(raw)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train DriftZero paper-shaped IMU student")
    parser.add_argument("--seed", default="26168")
    parser.add_argument("--out", type=Path, default=Path("models/learned_imu_v1"))
    parser.add_argument("--data-root", type=Path, default=None)
    args = parser.parse_args(argv)
    probes = probe_learned_imu_datasets(args.data_root)
    present = [row for row in probes if row.present]
    trips = default_synthetic_odometry(seed=int(args.seed) if str(args.seed).isdigit() else 26168)
    source = "synthetic"
    iovnbd_root = Path("data/raw/io_vnbd") if args.data_root is None else args.data_root / "data" / "raw" / "io_vnbd"
    if args.data_root is not None and (args.data_root / "io_vnbd").is_dir():
        iovnbd_root = args.data_root / "io_vnbd"
    try:
        from driftzero_ml.datasets.errors import DatasetMissing
        from driftzero_ml.datasets.io_vnbd import load_odometry_trips
        from driftzero_ml.io_vnbd import IOVNBDMissing

        loaded = load_odometry_trips(iovnbd_root)
        if len(loaded) >= 4:
            trips = loaded
            source = f"io_vnbd:{len(loaded)} smartphone tables"
        elif present:
            names = ", ".join(f"{row.name}@{row.path}" for row in present)
            source = f"synthetic; IO-VNBD subset too small ({len(loaded)} tables). also on disk: {names}"
    except (OSError, ValueError, DatasetMissing, IOVNBDMissing):
        if present:
            names = ", ".join(f"{row.name}@{row.path}" for row in present)
            source = f"synthetic; present but unused: {names}"
    stride = 10 if source.startswith("io_vnbd:") else 1
    result = train_from_odometry(trips, seed=str(args.seed), stride=stride)
    student: LinearDisplacementStudent = result["student"]
    report = result["report"]
    report["source"] = source
    report["window_stride"] = stride
    report["datasets"] = [
        {"name": row.name, "present": row.present, "path": row.path, "url": row.url} for row in probes
    ]
    report["imu_channels"] = list(IMU_CHANNELS)
    report["window_ns"] = WINDOW_NS
    report["magnetometer_in_features"] = False
    report["timesfm"] = False
    report["apk"] = False
    args.out.mkdir(parents=True, exist_ok=True)
    save_linear_displacement_student(student, args.out / "linear_dp.json")
    report["torch"] = train_torch_student(trips, args.out)
    (args.out / "train_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0


def _build_aligned_gru():
    from driftzero_ml.student.gru import build_causal_gru

    return build_causal_gru()


def _torch_ronin_mse(pred, target):
    err_x = pred.dx - target[:, 0]
    err_y = pred.dy - target[:, 1]
    return 0.5 * (err_x.pow(2) + err_y.pow(2)).mean()


def _torch_tlio_nll(pred, target):
    terms = (
        (pred.dx, pred.log_sigma_x, target[:, 0]),
        (pred.dy, pred.log_sigma_y, target[:, 1]),
        (pred.dz, pred.log_sigma_z, target[:, 2]),
    )
    acc = pred.dx.new_zeros(())
    for hat, log_sigma, truth in terms:
        resid = (hat - truth) * (-log_sigma).exp()
        acc = acc + (log_sigma + 0.5 * resid.pow(2)).mean()
    return acc


def _torch_ionet(pred, target):
    length = pred.delta_length
    truth_l = (target[:, 0].pow(2) + target[:, 1].pow(2)).sqrt()
    heading = pred.dpsi - target[:, 5]
    heading = (heading + math.pi) % (2.0 * math.pi) - math.pi
    return (length - truth_l).pow(2).mean() + IONET_HEADING_WEIGHT * heading.pow(2).mean()


def _torch_speed(pred, target):
    return (pred.speed - target[:, 3]).pow(2).mean()


def _has_pose(row: dict) -> bool:
    return all(key in row for key in ("pose_x_m", "pose_y_m", "pose_z_m", "heading_rad"))


def _vehicle_accel(rng: random.Random, ax_body: float, vib: float) -> tuple[float, float, float]:
    phase = rng.random() * 2.0 * math.pi
    return (
        ax_body + vib * math.sin(phase) + rng.gauss(0.0, 0.08),
        vib * 0.25 * math.cos(phase) + rng.gauss(0.0, 0.08),
        GRAVITY_MPS2 + vib * 0.15 * math.sin(phase * 2.0) + rng.gauss(0.0, 0.08),
    )


def _mae_xy(predicted: Sequence[LearnedImuPrediction], rows: Sequence[OdometryWindow]) -> float:
    return mean(
        math.hypot(pred.dx_m - row.dx_m, pred.dy_m - row.dy_m) for pred, row in zip(predicted, rows)
    )


def _cross(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def _norm(vector: tuple[float, float, float]) -> float:
    return math.sqrt(vector[0] ** 2 + vector[1] ** 2 + vector[2] ** 2)


def _normalize(vector: tuple[float, float, float]) -> tuple[float, float, float]:
    norm = _norm(vector)
    if norm < 1e-9:
        raise ValueError("cannot normalize a near-zero vector")
    return (vector[0] / norm, vector[1] / norm, vector[2] / norm)


def _matvec(
    matrix: Sequence[Sequence[float]],
    vector: tuple[float, float, float],
) -> tuple[float, float, float]:
    return (
        matrix[0][0] * vector[0] + matrix[0][1] * vector[1] + matrix[0][2] * vector[2],
        matrix[1][0] * vector[0] + matrix[1][1] * vector[1] + matrix[1][2] * vector[2],
        matrix[2][0] * vector[0] + matrix[2][1] * vector[1] + matrix[2][2] * vector[2],
    )


def _matmul(
    left: Sequence[Sequence[float]],
    right: Sequence[Sequence[float]],
) -> tuple[tuple[float, float, float], ...]:
    rows = []
    for i in range(3):
        rows.append(
            (
                left[i][0] * right[0][0] + left[i][1] * right[1][0] + left[i][2] * right[2][0],
                left[i][0] * right[0][1] + left[i][1] * right[1][1] + left[i][2] * right[2][1],
                left[i][0] * right[0][2] + left[i][1] * right[1][2] + left[i][2] * right[2][2],
            )
        )
    return (rows[0], rows[1], rows[2])


def _mean(values: Sequence[float]) -> float:
    if not values:
        return 0.0
    return sum(values) / len(values)


def _clamp(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))


def _softplus(value: float) -> float:
    if value > 40.0:
        return value
    return math.log(1.0 + math.exp(value))


def _inv_softplus(value: float) -> float:
    if value <= 1e-8:
        return -20.0
    if value > 40.0:
        return value
    return math.log(math.exp(value) - 1.0)


def _logit(probability: float) -> float:
    clipped = min(0.999, max(0.001, probability))
    return math.log(clipped / (1.0 - clipped))


def _wrap(radians: float) -> float:
    return (radians + math.pi) % (2.0 * math.pi) - math.pi


def _dot(weights: Sequence[float], vector: Sequence[float]) -> float:
    acc = weights[0]
    for weight, value in zip(weights[1:], vector):
        acc += weight * value
    return acc


def _ridge(windows: Sequence[Sequence[float]], targets: Sequence[float], l2: float) -> list[float]:
    rows = [[1.0, *row] for row in windows]
    width = len(rows[0])
    gram = [[0.0] * width for _ in range(width)]
    rhs = [0.0] * width
    for row, target in zip(rows, targets):
        for i in range(width):
            rhs[i] += row[i] * target
            for j in range(width):
                gram[i][j] += row[i] * row[j]
    for i in range(width):
        gram[i][i] += l2
    return _solve(gram, rhs)


def _solve(matrix: list[list[float]], rhs: list[float]) -> list[float]:
    n = len(rhs)
    aug = [matrix[i][:] + [rhs[i]] for i in range(n)]
    for i in range(n):
        pivot = i
        for row in range(i + 1, n):
            if abs(aug[row][i]) > abs(aug[pivot][i]):
                pivot = row
        aug[i], aug[pivot] = aug[pivot], aug[i]
        diag = aug[i][i]
        if abs(diag) < 1e-12:
            raise ValueError("singular linear system")
        scale = 1.0 / diag
        for col in range(i, n + 1):
            aug[i][col] *= scale
        for row in range(n):
            if row == i:
                continue
            factor = aug[row][i]
            for col in range(i, n + 1):
                aug[row][col] -= factor * aug[i][col]
    return [aug[i][n] for i in range(n)]


if __name__ == "__main__":
    raise SystemExit(main())
