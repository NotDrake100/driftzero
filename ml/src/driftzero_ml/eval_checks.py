"""Parity, leakage, and selection guards for Task D.

Candidate selection and locked confirmation stay closed until an eligible
development candidate exists. Task B's map overlay is a recorded rejection.
DIAGNOSTIC_ONLY reports cannot enter the candidate matrix.
"""

from __future__ import annotations

import json
from pathlib import Path

from driftzero_ml.accuracy_gate import diagnostic_only_reasons
from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.screening import GATED_INTERVAL_IDS

ROOT = Path(__file__).resolve().parents[3]
SPLIT_MANIFEST = ROOT / "results" / "cursor_diagnostics" / "split_manifest.json"
PREREGISTRATION = ROOT / "results" / "cursor_eval_checks" / "preregistration.json"
B_DECISION = ROOT / "results" / "road_reliability_20260906" / "FOR_TASK_D.json"
SEED = "26168"
SELECTION_OPEN = False
LOCKED_CONFIRMATION_OPEN = False
HOLDOUT_OPEN = False

MATRIX = {
    "deterministic_baseline": {
        "role": "comparison",
        "system": "latch_sparse_reseed",
        "selectable": False,
        "reason": "existing selected coast. comparison only",
    },
    "model_only": {
        "role": "pending_c",
        "selectable": False,
        "reason": "no eligible C candidate yet",
    },
    "map_only": {
        "role": "rejected_b",
        "selectable": False,
        "reason": "Task B failed the relative development gate. Overlay stays off.",
    },
    "combined": {
        "role": "blocked",
        "selectable": False,
        "reason": "needs an eligible motion or map candidate first",
    },
}

DEVELOPMENT_GATE = {
    "name": "relative_development_gate_v1",
    "not_the_absolute_0_10_target": True,
    "requires_complete_finite_metrics": True,
    "relative_median_improvement": 0.10,
    "p95_not_worse": True,
    "fail10_count_not_worse": True,
}


def load_split() -> dict:
    return json.loads(SPLIT_MANIFEST.read_text())


def locked_ids_unchanged() -> bool:
    split = load_split()
    return set(split["locked_interval_ids"]) == GATED_INTERVAL_IDS


def reject_as_candidate(payload: dict) -> list[str]:
    return diagnostic_only_reasons(payload)


def b_map_overlay_rejected() -> dict:
    if B_DECISION.is_file():
        payload = json.loads(B_DECISION.read_text())
        return {
            "present": True,
            "status": payload.get("status"),
            "locked_confirmation": payload.get("locked_confirmation", False),
            "android_defaults": payload.get("android_defaults", False),
        }
    return {
        "present": False,
        "status": "rejection",
        "locked_confirmation": False,
        "android_defaults": False,
        "source": "campaign recorded B rejection. Decision file lives on cursor/road-reliability-8d5b",
    }


def assert_holdout_closed(used_groups: list[str], split: dict | None = None) -> None:
    split = split or load_split()
    holdout = set(split["fresh_holdout_session_groups"])
    leaked = sorted({session_group_id(item) for item in used_groups} & holdout)
    if leaked:
        raise ValueError(f"fresh holdout is closed: {', '.join(leaked)}")
    if HOLDOUT_OPEN:
        raise AssertionError("HOLDOUT_OPEN must stay false until D opens it after selection")


def assert_selection_blocked() -> None:
    if SELECTION_OPEN or LOCKED_CONFIRMATION_OPEN:
        raise AssertionError("selection and locked confirmation stay blocked")
    if any(row["selectable"] for row in MATRIX.values()):
        raise AssertionError("no matrix cell is selectable until an eligible candidate exists")


def scoring_ratio(endpoint_error_m: float, truth_path_length_m: float) -> float:
    """Same ratio the accuracy gate recomputes. Never trust a cached value."""

    if truth_path_length_m <= 0:
        raise ValueError("truth_path_length_m must be positive")
    if endpoint_error_m < 0:
        raise ValueError("endpoint_error_m must be non-negative")
    return endpoint_error_m / truth_path_length_m


def preregistration_payload() -> dict:
    split = load_split()
    return {
        "experiment": "accuracy_evaluation_checks_v1",
        "status": "checks_prepared",
        "selection_open": SELECTION_OPEN,
        "locked_confirmation_open": LOCKED_CONFIRMATION_OPEN,
        "holdout_open": HOLDOUT_OPEN,
        "seed": SEED,
        "locked_interval_count": len(GATED_INTERVAL_IDS),
        "locked_ids_match_split": locked_ids_unchanged(),
        "matrix": MATRIX,
        "development_gate": DEVELOPMENT_GATE,
        "b_map_overlay": b_map_overlay_rejected(),
        "diagnostic_only_rejected": True,
        "train_session_groups": split["train_session_groups"],
        "fresh_holdout_session_groups": split["fresh_holdout_session_groups"],
        "excluded_session_groups": split["excluded_session_groups"],
        "notes": [
            "Parity and leakage checks may run now.",
            "Do not run locked confirmation.",
            "Do not open the fresh holdout for tuning.",
            "Do not enable the B posterior-mean overlay.",
        ],
    }


def write_preregistration(path: Path | None = None) -> dict:
    payload = preregistration_payload()
    destination = path or PREREGISTRATION
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(payload, indent=2) + "\n")
    return payload
