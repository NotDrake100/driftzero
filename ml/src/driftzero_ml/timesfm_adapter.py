"""Optional and isolated TimesFM 3 research adapter.

This module intentionally does not import TimesFM at module import time. The
official API may evolve, so repository code should pin a checkpoint revision and
inject a thin predictor callable after validating it against the current model
card. No Android or navigation-core package may import this module.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from importlib.util import find_spec
from math import isfinite

Matrix = Sequence[Sequence[float]]


class TimesFMUnavailable(RuntimeError):
    """Raised when the optional research environment is unavailable."""


@dataclass(frozen=True)
class ForecastResult:
    point: tuple[tuple[float, ...], ...]
    quantiles: tuple[tuple[tuple[float, ...], ...], ...] | None


def timesfm_is_installed() -> bool:
    return find_spec("timesfm") is not None


def require_timesfm() -> None:
    if not timesfm_is_installed():
        raise TimesFMUnavailable(
            'TimesFM is optional. Install the pinned research environment with '
            'python -m pip install -e "./ml[research]", then verify the official TimesFM 3 API.'
        )


def validate_context(context: Matrix, *, expected_context_length: int | None = None) -> None:
    """Validate a variate-first matrix: [num_variates][context_length]."""

    if not context:
        raise ValueError("context must contain at least one variate")
    lengths = {len(variate) for variate in context}
    if len(lengths) != 1 or 0 in lengths:
        raise ValueError("all variates must have the same non-zero context length")
    actual_length = next(iter(lengths))
    if expected_context_length is not None and actual_length != expected_context_length:
        raise ValueError(
            f"expected context length {expected_context_length}, received {actual_length}"
        )
    for variate_index, variate in enumerate(context):
        for time_index, value in enumerate(variate):
            if not isfinite(float(value)):
                raise ValueError(
                    f"non-finite context at variate {variate_index}, time {time_index}"
                )


def run_injected_predictor(
    context: Matrix,
    predictor: Callable[[Matrix], ForecastResult],
    *,
    expected_context_length: int | None = None,
    expected_horizon_length: int | None = None,
) -> ForecastResult:
    """Validate the TimesFM boundary before and after an injected prediction.

    Injection makes the moving third-party API explicit and straightforward to
    fake in unit tests. The implementation wired to the official checkpoint must
    live in a research command, never in product runtime.
    """

    validate_context(context, expected_context_length=expected_context_length)
    result = predictor(context)
    if len(result.point) != len(context):
        raise ValueError("point forecast must preserve the number of variates")
    horizon_lengths = {len(variate) for variate in result.point}
    if len(horizon_lengths) != 1 or 0 in horizon_lengths:
        raise ValueError("point forecast variates must have one non-zero horizon length")
    actual_horizon = next(iter(horizon_lengths))
    if expected_horizon_length is not None and actual_horizon != expected_horizon_length:
        raise ValueError(
            f"expected horizon length {expected_horizon_length}, received {actual_horizon}"
        )
    validate_context(result.point, expected_context_length=actual_horizon)
    return result

