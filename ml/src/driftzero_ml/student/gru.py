"""Optional causal GRU student. Torch is desktop-only. Never imported by Android.

Aligned to `learned_imu`: RoNIN 2D Δp, TLIO residual + log σ, IONet Δψ,
plus the existing speed / stop / log-var ESKF heads. Unidirectional, like
RoNIN LSTM (paper uses 3×100; this student is 1×16).
"""

from __future__ import annotations

from importlib.util import find_spec

from driftzero_ml.learned_imu import HEAD_DIM, INPUT_SIZE, apply_output_heads

HIDDEN = 16
LAYERS = 1


class TorchUnavailable(RuntimeError):
    """Raised when the optional PyTorch extra is missing."""


def torch_is_installed() -> bool:
    return find_spec("torch") is not None


def require_torch() -> None:
    if not torch_is_installed():
        raise TorchUnavailable(
            "PyTorch is optional. Install ./ml[research] on desktop. "
            "The phone path uses ZuptAccelMotionModel, not this module."
        )


def build_causal_gru():
    """Unidirectional GRU: HACF window [ax,ay,az,gx,gy,gz] -> paper heads."""

    require_torch()
    import torch
    from torch import nn

    class CausalMotionGru(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.gru = nn.GRU(
                INPUT_SIZE,
                HIDDEN,
                num_layers=LAYERS,
                batch_first=True,
                bidirectional=False,
            )
            self.head = nn.Linear(HIDDEN, HEAD_DIM)

        def forward(self, window: torch.Tensor):
            # window: [batch, time, 6], causal (no future, no GNSS, no mag).
            encoded, _ = self.gru(window)
            return apply_output_heads(self.head(encoded[:, -1, :]))

    return CausalMotionGru()
