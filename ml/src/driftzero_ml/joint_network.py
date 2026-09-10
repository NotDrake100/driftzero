"""Small causal residual velocity models, optional desktop PyTorch dependency."""
from __future__ import annotations

import torch
from torch import nn
from torch.nn import functional as F

from driftzero_ml.joint_sequence import FEATURES


class JointNetwork(nn.Module):
    def __init__(self, kind: str, head_mode: str = 'cartesian'):
        super().__init__()
        self.kind = kind
        if head_mode not in ('cartesian', 'polar'):
            raise ValueError('unknown motion head')
        self.head_mode = head_mode
        width = 24
        if kind == 'gru':
            self.body = nn.GRU(len(FEATURES), width, batch_first=True)
        elif kind == 'tcn':
            self.body = nn.ModuleList([nn.Conv1d(len(FEATURES), width, 5),
                                       nn.Conv1d(width, width, 5, dilation=4),
                                       nn.Conv1d(width, width, 5, dilation=16)])
        elif kind == 'mlp':
            self.body = nn.Linear(len(FEATURES), width)
        else:
            raise ValueError('unknown architecture')
        self.head = nn.Linear(width, 3)
        nn.init.zeros_(self.head.weight)
        nn.init.zeros_(self.head.bias)
        self.register_buffer('mean', torch.zeros(len(FEATURES)))
        self.register_buffer('scale', torch.ones(len(FEATURES)))

    def forward(self, features, base, dt):
        x = (features-self.mean)/self.scale
        if self.kind == 'gru':
            x, _ = self.body(x)
        elif self.kind == 'tcn':
            x = x.transpose(1, 2)
            for layer in self.body:
                x = F.silu(layer(F.pad(x, (4*layer.dilation[0], 0))))
            x = x.transpose(1, 2)
        else:
            x = F.silu(self.body(x))
        raw = self.head(x)
        if self.head_mode == 'polar':
            angle = torch.atan2(features[..., 7], features[..., 8])+torch.pi*torch.tanh(raw[..., 0])
            speed = (torch.linalg.vector_norm(base, dim=-1)+55*torch.tanh(raw[..., 1])).clamp(0, 55)
            velocity = torch.stack((speed*torch.sin(angle), speed*torch.cos(angle)), dim=-1)
        else:
            velocity = base+10*torch.tanh(raw[..., :2])
        norm = torch.linalg.vector_norm(velocity, dim=-1, keepdim=True).clamp_min(55)/55
        velocity = velocity/norm
        position = torch.cumsum(velocity*dt.unsqueeze(-1), dim=1)
        # Conservative correlated accumulation; learned sigma is not certified coverage.
        sigma = 5+torch.cumsum((F.softplus(raw[..., 2])+0.1)*dt, dim=1)
        return position, sigma
