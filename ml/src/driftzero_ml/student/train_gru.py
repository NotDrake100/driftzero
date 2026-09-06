"""Train a causal GRU student on trip-split IO-VNBD. Desktop only. No TimesFM."""

from __future__ import annotations

import argparse
import json
import random
from collections import defaultdict
from pathlib import Path
from statistics import mean

from driftzero_ml.datasets.errors import DatasetLfsMissing, DatasetMissing
from driftzero_ml.datasets.io_vnbd import load_odometry_trips
from driftzero_ml.features.causal_imu import FEATURE_NAMES, WINDOW_NS
from driftzero_ml.io_vnbd import IOVNBDMissing, assign_grouped_trip_splits
from driftzero_ml.learned_imu import odometry_windows
from driftzero_ml.metrics import gaussian_nll, picp
from driftzero_ml.student.gru import torch_is_installed
from driftzero_ml.student.gru_runtime import (
    ALIGN_LEN,
    GRU_SCHEMA,
    HEAD_DIM,
    HIDDEN,
    INPUT_SIZE,
    CausalGruStudent,
    multiply_adds_per_inference,
    save_gru_student,
)
from driftzero_ml.student.linear import fit_linear_motion_student
from driftzero_ml.student.train import mae

SEED = "26168"
EVAL_SPLITS = frozenset({"validation", "public_test", "locked_test"})
BUMP_INDEX = FEATURE_NAMES.index("bump_flag")


def _device():
    import torch

    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def _build_model(hidden: int = HIDDEN):
    from driftzero_ml.student.gru import require_torch

    require_torch()
    import torch
    from torch import nn

    class ScreeningGru(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.gru = nn.GRU(INPUT_SIZE, hidden, num_layers=1, batch_first=True, bidirectional=False)
            self.head = nn.Linear(hidden, HEAD_DIM)

        def forward(self, window: torch.Tensor) -> torch.Tensor:
            encoded, _ = self.gru(window)
            return self.head(encoded[:, -1, :])

    return ScreeningGru()


def _pad(sequence: list[list[float]], length: int = ALIGN_LEN) -> list[list[float]]:
    if len(sequence) >= length:
        return sequence[-length:]
    return [[0.0] * INPUT_SIZE for _ in range(length - len(sequence))] + sequence


def _scaler(sequences: list[list[list[float]]]) -> tuple[list[float], list[float]]:
    sums = [0.0] * INPUT_SIZE
    sq = [0.0] * INPUT_SIZE
    count = 0
    for seq in sequences:
        for row in seq:
            for i, value in enumerate(row):
                sums[i] += value
                sq[i] += value * value
            count += 1
    if count < 2:
        raise ValueError("need at least two IMU samples to fit a train-only scaler")
    mean_v = [s / count for s in sums]
    std_v = []
    for i in range(INPUT_SIZE):
        var = max(1e-6, sq[i] / count - mean_v[i] * mean_v[i])
        std_v.append(var ** 0.5)
    return mean_v, std_v


def _apply_scaler(seq: list[list[float]], mean_v: list[float], std_v: list[float]) -> list[list[float]]:
    return [[(row[i] - mean_v[i]) / std_v[i] for i in range(INPUT_SIZE)] for row in seq]


def _targets(window) -> list[float]:
    duration = max(window.duration_s, 1e-3)
    yaw_rate = window.delta_heading_rad / duration
    return [
        window.speed_mps,
        window.stopped,
        yaw_rate,
        window.dx_m,
        window.dy_m,
        1.0 if window.features[BUMP_INDEX] >= 0.5 else 0.0,
    ]


def _decode_raw(raw) -> tuple:
    from torch.nn import functional as F

    speed = F.softplus(raw[:, 0]).clamp(0.0, 50.0)
    stop = raw[:, 1]
    log_speed = raw[:, 2].clamp(-8.0, 6.0)
    yaw = raw[:, 3]
    log_yaw = raw[:, 4].clamp(-8.0, 6.0)
    dx = raw[:, 5]
    dy = raw[:, 6]
    log_xy = raw[:, 7].clamp(-8.0, 6.0)
    return speed, stop, log_speed, yaw, log_yaw, dx, dy, log_xy


def _nll(residual, log_var):
    var = log_var.exp().clamp(min=1e-6)
    return 0.5 * (log_var + residual.pow(2) / var).mean()


def export_from_torch(model, mean_v: list[float], std_v: list[float], hidden: int) -> CausalGruStudent:
    state = {key: value.detach().cpu().tolist() for key, value in model.state_dict().items()}
    return CausalGruStudent(
        hidden=hidden,
        align_len=ALIGN_LEN,
        input_mean=tuple(mean_v),
        input_std=tuple(std_v),
        weight_ih=tuple(tuple(row) for row in state["gru.weight_ih_l0"]),
        weight_hh=tuple(tuple(row) for row in state["gru.weight_hh_l0"]),
        bias_ih=tuple(state["gru.bias_ih_l0"]),
        bias_hh=tuple(state["gru.bias_hh_l0"]),
        head_weight=tuple(tuple(row) for row in state["head.weight"]),
        head_bias=tuple(state["head.bias"]),
    )


def train_gru(
    trips: dict[str, list[dict]],
    *,
    seed: str = SEED,
    stride: int = 10,
    epochs: int = 12,
    batch_size: int = 64,
    hidden: int = HIDDEN,
    log_path: Path | None = None,
) -> dict:
    if not torch_is_installed():
        raise RuntimeError("torch is required for train_gru")
    import torch

    torch.manual_seed(int(seed) if str(seed).isdigit() else 26168)
    random.seed(int(seed) if str(seed).isdigit() else 26168)
    assignments = {row.trip_id: row.split for row in assign_grouped_trip_splits(sorted(trips), seed=seed)}
    by_split: dict[str, list] = defaultdict(list)
    for trip_id, records in trips.items():
        split = assignments[trip_id]
        for window in odometry_windows(records, stride=stride):
            by_split[split].append(window)
    train_windows = by_split.get("train") or []
    if len(train_windows) < 32:
        raise ValueError(f"not enough train windows: {len(train_windows)}")
    train_seq = [_pad([list(row) for row in w.sequence]) for w in train_windows]
    mean_v, std_v = _scaler(train_seq)
    device = _device()

    def pack(windows: list) -> tuple:
        seqs = torch.tensor(
            [_apply_scaler(_pad([list(row) for row in w.sequence]), mean_v, std_v) for w in windows],
            dtype=torch.float32,
            device=device,
        )
        tgt = torch.tensor([_targets(w) for w in windows], dtype=torch.float32, device=device)
        return seqs, tgt

    train_x, train_y = pack(train_windows)
    val_windows = by_split.get("validation") or []
    val_pack = pack(val_windows) if val_windows else None
    model = _build_model(hidden).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    logs: list[str] = []
    best_state = None
    best_val = float("inf")
    bad = 0
    n_train = train_x.shape[0]
    for epoch in range(1, epochs + 1):
        model.train()
        order = torch.randperm(n_train, device=device)
        losses = []
        for start in range(0, n_train, batch_size):
            idx = order[start : start + batch_size]
            raw = model(train_x[idx])
            speed, stop, log_speed, yaw, log_yaw, dx, dy, log_xy = _decode_raw(raw)
            y = train_y[idx]
            loss = _nll(speed - y[:, 0], log_speed)
            loss = loss + 0.35 * torch.nn.functional.binary_cross_entropy_with_logits(stop, y[:, 1].clamp(0.0, 1.0))
            loss = loss + 0.25 * _nll(yaw - y[:, 2], log_yaw)
            if epoch >= 4:
                resid = torch.hypot(dx - y[:, 3], dy - y[:, 4])
                loss = loss + 0.15 * _nll(resid, log_xy)
            opt.zero_grad()
            loss.backward()
            opt.step()
            losses.append(float(loss.detach().cpu()))
        val_nll = mean(losses)
        if val_pack is not None:
            model.eval()
            with torch.no_grad():
                raw = model(val_pack[0])
                speed, _stop, log_speed, _yaw, _ly, _dx, _dy, _ls = _decode_raw(raw)
                val_nll = float(_nll(speed - val_pack[1][:, 0], log_speed).cpu())
        line = f"epoch {epoch} train_loss={mean(losses):.5f} val_speed_nll={val_nll:.5f}"
        logs.append(line)
        if log_path is not None:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            with log_path.open("a") as handle:
                handle.write(line + "\n")
        if val_nll < best_val - 1e-4:
            best_val = val_nll
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            bad = 0
        else:
            bad += 1
            if bad >= 3:
                logs.append(f"early_stop at epoch {epoch}")
                break
    if best_state is not None:
        model.load_state_dict(best_state)
    student = export_from_torch(model, mean_v, std_v, hidden)
    report = _held_out_report_from_windows(student, by_split)
    report["seed"] = seed
    report["epochs_ran"] = len([x for x in logs if x.startswith("epoch")])
    report["best_val_speed_nll"] = best_val
    report["device"] = str(device)
    report["train_windows"] = len(train_windows)
    report["window_stride"] = stride
    report["hidden"] = hidden
    report["align_len"] = ALIGN_LEN
    report["window_ns"] = WINDOW_NS
    report["multiply_adds"] = multiply_adds_per_inference(hidden, ALIGN_LEN)
    report["speed_unit"] = "m/s"
    report["split"] = "session_grouped"
    report["logs"] = logs
    report["schema"] = GRU_SCHEMA
    return {"student": student, "report": report}


def _held_out_report_from_windows(student: CausalGruStudent, by_split: dict[str, list]) -> dict:
    from driftzero_ml.features.causal_imu import MAX_SPEED_MPS, SPEED_VIB_GAIN

    idle_i = FEATURE_NAMES.index("idle_flag")
    bump_i = FEATURE_NAMES.index("bump_flag")
    vib_i = FEATURE_NAMES.index("vibration_energy")
    train_windows = by_split.get("train") or []
    linear = None
    if train_windows:
        linear = fit_linear_motion_student(
            [w.features[: len(FEATURE_NAMES)] for w in train_windows],
            [w.speed_mps for w in train_windows],
            [w.stopped for w in train_windows],
        )
    splits: dict[str, dict] = {}
    for split in EVAL_SPLITS:
        windows = by_split.get(split) or []
        if not windows:
            continue
        gru_pred: list[float] = []
        lin_pred: list[float] = []
        heur: list[float] = []
        freeze: list[float] = []
        targets: list[float] = []
        resid: list[float] = []
        log_vars: list[float] = []
        sigmas: list[float] = []
        bump_resid: list[float] = []
        bump_sigma: list[float] = []
        gated_resid: list[float] = []
        gated_sigma: list[float] = []
        for window in windows:
            target = float(window.speed_mps)
            pred = student.infer(window.sequence, bump=False, gate_bump=False)
            bump = window.features[bump_i] >= 0.5
            gated = student.infer(window.sequence, bump=bump, gate_bump=True)
            if window.features[idle_i] >= 0.5:
                heur_speed = 0.0
            else:
                heur_speed = min(MAX_SPEED_MPS, SPEED_VIB_GAIN * (max(0.0, window.features[vib_i]) ** 0.5))
            gru_pred.append(pred.forward_speed_mps)
            heur.append(heur_speed)
            freeze.append(0.0)
            targets.append(target)
            resid.append(pred.forward_speed_mps - target)
            log_vars.append(pred.log_speed_variance)
            sigmas.append(math_exp_half(pred.log_speed_variance))
            if bump:
                bump_resid.append(pred.forward_speed_mps - target)
                bump_sigma.append(math_exp_half(pred.log_speed_variance))
                gated_resid.append(gated.forward_speed_mps - target)
                gated_sigma.append(math_exp_half(gated.log_speed_variance))
            if linear is not None:
                lin_pred.append(linear.infer(window.features[: len(FEATURE_NAMES)])[0])
        row = {
            "count": len(targets),
            "freeze_speed_mae": mae(freeze, targets),
            "heuristic_speed_mae": mae(heur, targets),
            "gru_speed_mae": mae(gru_pred, targets),
            "gru_speed_nll": gaussian_nll(resid, log_vars),
            "gru_picp_68": picp(resid, sigmas, 1.0),
            "gru_picp_95": picp(resid, sigmas, 1.959964),
        }
        if lin_pred:
            row["linear_speed_mae"] = mae(lin_pred, targets)
        if bump_resid:
            row["bump_count"] = len(bump_resid)
            row["bump_picp_68"] = picp(bump_resid, bump_sigma, 1.0)
            row["bump_gated_picp_68"] = picp(gated_resid, gated_sigma, 1.0)
            row["bump_abs_mae"] = mean(abs(r) for r in bump_resid)
            row["bump_gated_abs_mae"] = mean(abs(r) for r in gated_resid)
        splits[split] = row
    beats = True
    for row in splits.values():
        gru = row["gru_speed_mae"]
        if gru >= row["heuristic_speed_mae"]:
            beats = False
        if "linear_speed_mae" in row and gru >= row["linear_speed_mae"]:
            beats = False
    return {"splits": splits, "beats_linear_and_heuristic": beats}


def math_exp_half(log_var: float) -> float:
    import math

    return math.exp(0.5 * log_var)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train causal GRU motion student on IO-VNBD")
    parser.add_argument("--seed", default=SEED)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("models/motion_student_v2"))
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--stride", type=int, default=10)
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    log_path = repo / "results" / "io_vnbd_screening_v1" / "logs" / "train_gru.log"
    try:
        trips = load_odometry_trips(repo / "data" / "raw" / "io_vnbd")
    except (IOVNBDMissing, DatasetLfsMissing, DatasetMissing) as error:
        print(json.dumps({"skipped": True, "reason": str(error)}))
        return 0
    result = train_gru(
        trips,
        seed=str(args.seed),
        stride=args.stride,
        epochs=args.epochs,
        log_path=log_path,
    )
    student: CausalGruStudent = result["student"]
    report = result["report"]
    report["source"] = f"io_vnbd:{len(trips)} smartphone tables"
    report["excluded_trips"] = ["S-Vtb3: GPS speed column unit neither m/s nor km/h"]
    try:
        import subprocess

        report["git_commit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repo, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        report["git_commit"] = "unknown"
    args.out.mkdir(parents=True, exist_ok=True)
    extra = {
        "seed": report["seed"],
        "git_commit": report.get("git_commit"),
        "speed_unit": "m/s",
        "split": "session_grouped",
    }
    if report["beats_linear_and_heuristic"]:
        save_gru_student(
            student,
            args.out / "gru.json",
            extra=extra,
        )
        report["exported"] = True
    else:
        # Keep weights for screening even if the gate fails. Do not claim a win.
        extra["gate_failed"] = True
        save_gru_student(student, args.out / "gru.json", extra=extra)
        report["exported"] = False
        report["export_note"] = (
            "Held-out speed MAE did not beat both the linear student and the "
            "ZUPT-accel heuristic. Weights are written for analysis, not as a win."
        )
    (args.out / "train_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "logs"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
