"""Train and score the speed residual student. Does not write linear.json."""

from __future__ import annotations

import argparse
import json
import platform
import subprocess
import sys
from pathlib import Path
from typing import Mapping, Sequence

from driftzero_ml.student.residual import (
    CHI2_1DOF_95,
    CLIP_DELTA_MPS,
    CLEAR_MARGIN_REL,
    HORIZONS_S,
    MIN_RIDGE_TRAIN,
    RESIDUAL_FEATURE_NAMES,
    SEED_DEFAULT,
    ResidualRidgeStudent,
    ResidualWindow,
    decay_delta,
    empirical_quantile,
    evaluate_horizon,
    fit_decay_tau,
    fit_residual_ridge,
    load_residual_trips,
    maybe_load_linear,
    ridge_beats_persist,
    save_residual_ridge,
    synthetic_speed_residual_trip,
    windows_by_split,
)

EVAL_SPLITS = ("validation", "public_test", "locked_test")


def git_commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def default_synthetic_trips(seed: int = 26168) -> dict[str, list[dict]]:
    trips: dict[str, list[dict]] = {}
    for copy in range(8):
        trip_id = f"resid-{copy}"
        trips[trip_id] = synthetic_speed_residual_trip(
            trip_id=trip_id,
            duration_s=36.0,
            hz=10,
            v0=14.0 + (copy % 3),
            accel_mps2=-0.25 if copy % 2 == 0 else 0.15,
            seed=seed + copy,
        )
    return trips


def _horizon_windows(rows: Sequence[ResidualWindow], horizon: float) -> list[ResidualWindow]:
    return [row for row in rows if row.horizon_s == horizon]


def run_experiment(
    trips: Mapping[str, list[dict]],
    *,
    seed: str,
    linear_path: Path,
    source: str,
) -> dict:
    grouped, assignments, split_hash = windows_by_split(trips, seed=seed)
    train = grouped.get("train", [])
    linear = maybe_load_linear(linear_path)
    pooled_v_mean = (
        sum(row.v_gnss_mps for row in train) / len(train) if train else 0.0
    )
    by_horizon: dict[str, dict] = {}
    models: dict[float, ResidualRidgeStudent] = {}
    locked_scores: dict[float, dict] = {}
    for horizon in HORIZONS_S:
        train_h = _horizon_windows(train, horizon)
        horizon_block: dict = {
            "horizon_s": horizon,
            "train_n": len(train_h),
        }
        ridge = None
        v_mean = pooled_v_mean
        tau = 16.0
        persist_q68 = 0.0
        persist_q95 = 0.0
        decay_q68 = 0.0
        decay_q95 = 0.0
        if len(train_h) >= MIN_RIDGE_TRAIN:
            ridge = fit_residual_ridge(train_h, seed=seed, horizon_s=horizon)
            models[horizon] = ridge
            v_mean = sum(row.v_gnss_mps for row in train_h) / len(train_h)
            tau = fit_decay_tau(train_h, v_mean)
            persist_abs = [abs(row.delta_v_mps) for row in train_h]
            persist_q68 = _quantile(persist_abs, 0.68)
            persist_q95 = _quantile(persist_abs, 0.95)
            decay_resid = [
                abs(row.delta_v_mps - _decay(row, v_mean, tau)) for row in train_h
            ]
            decay_q68 = _quantile(decay_resid, 0.68)
            decay_q95 = _quantile(decay_resid, 0.95)
            horizon_block["v_mean_mps"] = v_mean
            horizon_block["decay_tau_s"] = tau
            horizon_block["train"] = {
                "n": len(train_h),
                "persist_mae_mps": sum(persist_abs) / len(persist_abs),
                "ridge_mae_mps": ridge.train_resid_rmse,
            }
        else:
            horizon_block["ridge_fit"] = f"skipped: train_n={len(train_h)} < {MIN_RIDGE_TRAIN}"
        for split in EVAL_SPLITS:
            eval_h = _horizon_windows(grouped.get(split, []), horizon)
            scored = evaluate_horizon(
                eval_h,
                ridge=ridge,
                linear=linear,
                v_mean=v_mean,
                tau_s=tau,
                persist_sigma_68=persist_q68,
                persist_sigma_95=persist_q95,
                decay_sigma_68=decay_q68,
                decay_sigma_95=decay_q95,
            )
            horizon_block[split] = scored
            if split == "locked_test":
                locked_scores[horizon] = scored
        by_horizon[f"{horizon:g}"] = horizon_block
    beats, reason = ridge_beats_persist(locked_scores)
    gru_block = {"trained": False, "reason": "ridge did not beat persist on locked trips"}
    if beats:
        gru_block = train_gru_residual(grouped, seed=seed)
        gru_block["gate_reason"] = reason
    else:
        gru_block["reason"] = reason
    locked_trips = sorted(
        trip_id for trip_id, split in assignments.items() if split == "locked_test"
    )
    return {
        "seed": seed,
        "split": "session_grouped",
        "split_hash": split_hash,
        "feature_names": list(RESIDUAL_FEATURE_NAMES),
        "source": source,
        "n_trips": len(trips),
        "train_windows": len(train),
        "windows_by_split": {name: len(grouped.get(name, [])) for name in ("train", *EVAL_SPLITS)},
        "linear_json": str(linear_path) if linear is not None else None,
        "linear_json_overwritten": False,
        "horizons_s": list(HORIZONS_S),
        "clip_delta_mps": CLIP_DELTA_MPS,
        "clear_margin_rel": CLEAR_MARGIN_REL,
        "ridge_beats_persist_locked": beats,
        "ridge_gate_reason": reason,
        "by_horizon": by_horizon,
        "gru": gru_block,
        "locked_trip_ids": locked_trips,
        "models": models,
        "assignments": assignments,
    }


def train_gru_residual(grouped: Mapping[str, list[ResidualWindow]], *, seed: str) -> dict:
    """Small causal GRU residual. JSON weights only. Not packed unless Kotlin can load them."""

    try:
        from driftzero_ml.student.gru import torch_is_installed
    except ImportError:
        return {"trained": False, "reason": "gru module missing"}
    if not torch_is_installed():
        return {"trained": False, "reason": "torch not installed"}
    return {
        "trained": False,
        "reason": (
            "ridge beat persist, but this run does not pack a GRU. "
            "Add a Kotlin-loadable JSON weight schema before shipping a sequence model."
        ),
        "kotlin_export": False,
        "seed": seed,
        "n_train": len(grouped.get("train", [])),
    }


def _decay(row: ResidualWindow, v_mean: float, tau: float) -> float:
    return decay_delta(row.v_last_mps, v_mean, row.elapsed_s, tau)


def _quantile(values: Sequence[float], probability: float) -> float:
    if not values:
        return 0.0
    return empirical_quantile(values, probability)


def chi_square_recommendation(payload: dict) -> dict:
    locked = []
    persist_maes = []
    ridge_maes = []
    ridge_q68 = []
    for horizon in HORIZONS_S:
        block = payload["by_horizon"][f"{horizon:g}"].get("locked_test", {})
        n = int(block.get("n", 0))
        if n < 1:
            continue
        persist = block.get("persist", {})
        ridge = block.get("ridge_residual", {})
        if persist:
            persist_maes.append(persist["mae_mps"])
        if ridge:
            ridge_maes.append(ridge["mae_mps"])
        model: ResidualRidgeStudent | None = payload["models"].get(horizon)
        if model is not None:
            ridge_q68.append(model.train_resid_q68)
        locked.append(horizon)
    beats = payload["ridge_beats_persist_locked"]
    if beats and ridge_q68:
        floor = max(0.5, min(ridge_q68))
        use = "ridge residual, clip ±3 m/s, R = max(sigma_floor, sigma_pred)^2"
    else:
        floor = 2.0
        use = (
            "persist last accepted GNSS speed. Do not apply the residual or linear.json "
            "as a tight speed update"
        )
    return {
        "use": use,
        "sigma_floor_mps": floor,
        "chi2_1dof_95": CHI2_1DOF_95,
        "reject_example": (
            "innovation 20 m/s vs persist 10 m/s with sigma_floor 2 m/s: chi2=100, reject"
        ),
        "accept_example": (
            "innovation 1 m/s vs persist 10 m/s with sigma_floor 2 m/s: chi2=0.25, accept"
        ),
        "ship_residual": beats,
        "locked_horizons_scored": locked,
        "locked_persist_mae_mps": persist_maes,
        "locked_ridge_mae_mps": ridge_maes,
    }


def render_summary(payload: dict, rec: dict) -> str:
    lines = [
        "# Speed residual v1",
        "",
        "Target is `delta_v = v_gnss(t) - v_last_accepted_gnss(t0)` in m/s. "
        "Features are the causal 12 IMU stats plus hold-interval forward accel mean, "
        "|omega_z|, IMU stop flag, elapsed since t0, and the single allowed scalar "
        "`v_last_accepted_mps`. Splits are `session_group_id`, never row split.",
        "",
        "## Run identity",
        "",
        f"- git commit: `{payload['git_commit']}`",
        f"- seed: `{payload['seed']}`",
        f"- split hash: `{payload['split_hash']}`",
        f"- source: `{payload['source']}`",
        f"- trips: {payload['n_trips']}",
        f"- python: {payload['python']}",
        f"- platform: {payload['platform']}",
        f"- linear.json: `{payload['linear_json']}` (not overwritten)",
        "",
        "## Locked-test table",
        "",
        "| horizon s | model | MAE m/s | PICP 68 | PICP 95 | n windows | seed | split hash |",
        "|---|---|---:|---:|---:|---:|---|---|",
    ]
    hash8 = payload["split_hash"]
    seed = payload["seed"]
    for horizon in HORIZONS_S:
        block = payload["by_horizon"][f"{horizon:g}"]
        locked = block.get("locked_test", {})
        n = int(locked.get("n", 0))
        models = []
        if n:
            models.append(("persist", locked["persist"]))
            if "linear_absolute" in locked:
                models.append(("linear_absolute", locked["linear_absolute"]))
            models.append(("decay_to_mean", locked["decay_to_mean"]))
            if "ridge_residual" in locked:
                models.append(("ridge_residual", locked["ridge_residual"]))
            if "ridge_clipped" in locked:
                models.append(("ridge_clipped", locked["ridge_clipped"]))
        if not models:
            lines.append(
                f"| {horizon:g} | (no windows) |  |  |  | 0 | {seed} | `{hash8}` |"
            )
            continue
        for name, scores in models:
            mae = scores.get("mae_mps")
            picp68 = scores.get("picp68")
            picp95 = scores.get("picp95")
            mae_s = "" if mae is None else f"{mae:.4f}"
            p68_s = "" if picp68 is None else f"{picp68:.3f}"
            p95_s = "" if picp95 is None else f"{picp95:.3f}"
            lines.append(
                f"| {horizon:g} | {name} | {mae_s} | {p68_s} | {p95_s} | {n} | {seed} | `{hash8}` |"
            )
    lines.extend(["", "## Verdict", ""])
    if payload["ridge_beats_persist_locked"]:
        lines.append(
            "Ridge residual beat persist by the predeclared 10 percent relative margin "
            "on locked 5 s and 10 s windows."
        )
    else:
        lines.append(
            "Ridge residual did not beat persist by a clear margin on locked trips. "
            f"{payload['ridge_gate_reason']}. Keep `models/motion_student_v1/linear.json` as is. "
            "Do not pack a residual or GRU student."
        )
    gru = payload.get("gru") or {}
    if gru.get("trained"):
        lines.append(f"GRU residual was trained. Kotlin export: {gru.get('kotlin_export')}.")
    else:
        lines.append(f"GRU residual was not packed. {gru.get('reason')}")
    locked30 = payload["by_horizon"]["30"].get("locked_test", {})
    ridge30 = locked30.get("ridge_residual")
    persist30 = locked30.get("persist")
    if ridge30 and persist30 and persist30["mae_mps"] > 1e-9:
        gain30 = (persist30["mae_mps"] - ridge30["mae_mps"]) / persist30["mae_mps"]
        lines.append(
            f"At 30 s, ridge MAE {ridge30['mae_mps']:.4f} vs persist {persist30['mae_mps']:.4f} "
            f"(rel {gain30:.3f}). That is under the 10 percent locked gate."
        )
    lines.extend(
        [
            "",
            "## Fit notes",
            "",
        ]
    )
    for horizon in HORIZONS_S:
        block = payload["by_horizon"][f"{horizon:g}"]
        train_n = int(block.get("train_n", 0))
        skip = block.get("ridge_fit")
        if skip:
            lines.append(f"- {horizon:g} s: train windows {train_n}. {skip}.")
        else:
            lines.append(f"- {horizon:g} s: train windows {train_n}. Ridge fit.")
    lines.append(
        "Analysis JSON under `models/speed_residual_v1/` is marked `ship: false`. "
        "`models/motion_student_v1/linear.json` was not overwritten."
    )
    skipped = payload.get("skipped_trips") or []
    if skipped:
        lines.append("")
        lines.append("Skipped tables: " + "; ".join(_short_skip(item) for item in skipped) + ".")
    lines.extend(
        [
            "",
            "## Uncertainty and speed chi-square",
            "",
            f"- Recommended blackout speed aid: {rec['use']}.",
            f"- Sigma floor: {rec['sigma_floor_mps']:.2f} m/s.",
            f"- 1-dof chi-square 95 percent threshold: {rec['chi2_1dof_95']:.3f}.",
            f"- {rec['reject_example']}.",
            f"- {rec['accept_example']}.",
            "",
            "Persist and decay PICP use training |residual| quantiles as a constant radius "
            "(68th and 95th). Ridge PICP uses predicted log-variance with z=1 and z=1.96. "
            "Linear absolute PICP uses packed `linear.json` log-variance.",
            "",
            "## Leakage",
            "",
            "Feature names were asserted leak-free except `v_last_accepted_mps` at t0. "
            "Labels use `infer_speed_unit` on unique-fix finite difference versus the speed column "
            "(header says km/h, values are m/s).",
            "",
        ]
    )
    return "\n".join(lines) + "\n"


def _short_skip(item: str) -> str:
    stem, _, rest = item.partition(":")
    if not rest:
        return item
    if "/" in rest:
        reason = rest.rsplit(":", 1)[-1].strip()
        return f"{stem}: {reason}"
    return f"{stem}:{rest}" if rest.startswith("too_few") else f"{stem}: {rest.lstrip(': ')}"


def json_ready(payload: dict) -> dict:
    out = dict(payload)
    models = out.pop("models")
    out["ridge_train"] = {
        f"{horizon:g}": {
            "n_train": model.n_train,
            "train_resid_rmse": model.train_resid_rmse,
            "train_resid_q68": model.train_resid_q68,
            "train_resid_q95": model.train_resid_q95,
        }
        for horizon, model in models.items()
    }
    out.pop("assignments", None)
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train DriftZero speed residual student")
    parser.add_argument("--seed", default=SEED_DEFAULT)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("results/speed_residual_v1"))
    parser.add_argument("--model-out", type=Path, default=Path("models/speed_residual_v1"))
    parser.add_argument(
        "--linear",
        type=Path,
        default=Path("models/motion_student_v1/linear.json"),
    )
    parser.add_argument("--synthetic", action="store_true")
    args = parser.parse_args(argv)
    source = "synthetic"
    trips: dict[str, list[dict]]
    skipped: tuple[str, ...] = ()
    if args.synthetic:
        trips = default_synthetic_trips(seed=int(args.seed) if str(args.seed).isdigit() else 26168)
    else:
        root = args.repo / "data" / "raw" / "io_vnbd"
        try:
            trips, skipped = load_residual_trips(root)
        except Exception as error:
            trips = default_synthetic_trips(
                seed=int(args.seed) if str(args.seed).isdigit() else 26168
            )
            source = f"synthetic; IO-VNBD unavailable ({error})"
        else:
            if len(trips) < 4:
                n_loaded = len(trips)
                trips = default_synthetic_trips(
                    seed=int(args.seed) if str(args.seed).isdigit() else 26168
                )
                source = f"synthetic; IO-VNBD subset too small ({n_loaded} tables)"
            else:
                source = f"io_vnbd:{len(trips)} smartphone tables"
            print(f"loaded {len(trips)} trips ({source}); skipped {len(skipped)}", flush=True)
    payload = run_experiment(trips, seed=str(args.seed), linear_path=args.linear, source=source)
    print(
        "windows "
        f"train={payload['windows_by_split']['train']} "
        f"val={payload['windows_by_split']['validation']} "
        f"public={payload['windows_by_split']['public_test']} "
        f"locked={payload['windows_by_split']['locked_test']}",
        flush=True,
    )
    payload["skipped_trips"] = list(skipped)
    payload["git_commit"] = git_commit()
    payload["python"] = sys.version.split()[0]
    payload["platform"] = platform.platform()
    rec = chi_square_recommendation(payload)
    payload["chi_square"] = rec
    args.out.mkdir(parents=True, exist_ok=True)
    args.model_out.mkdir(parents=True, exist_ok=True)
    ship = bool(payload["ridge_beats_persist_locked"])
    for horizon, model in payload["models"].items():
        save_residual_ridge(
            model,
            args.model_out / f"ridge_{horizon:g}s.json",
            ship=ship,
        )
    summary = render_summary(payload, rec)
    (args.out / "summary.md").write_text(summary)
    (args.out / "metrics.json").write_text(json.dumps(json_ready(payload), indent=2) + "\n")
    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
