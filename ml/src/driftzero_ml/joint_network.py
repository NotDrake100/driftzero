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
        elif kind in ('tcn', 'tcn_long'):
            dilations = (1, 4, 16) if kind == 'tcn' else (1, 4, 16, 64, 256)
            self.body = nn.ModuleList([nn.Conv1d(len(FEATURES) if i == 0 else width, width, 5, dilation=d)
                                       for i, d in enumerate(dilations)])
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
        elif self.kind in ('tcn', 'tcn_long'):
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


def supervised_motion_loss(pred, sigma, target, mask, dt, increment_weight=0.0):
    """Per-window position and adjacent-fix displacement losses, training labels only."""
    error = torch.linalg.vector_norm(pred-target, dim=-1)
    scale = (torch.linalg.vector_norm(target, dim=-1)*.1).clamp_min(10)
    robust = F.smooth_l1_loss(pred/scale.unsqueeze(-1), target/scale.unsqueeze(-1), reduction='none').sum(-1)
    nll = .5*(error/sigma).square()+2*torch.log(sigma)
    loss = ((robust+.01*nll)*mask).sum(-1)/mask.sum(-1).clamp_min(1)
    if increment_weight:
        elapsed = dt.cumsum(-1)
        increments = []
        for i in range(len(pred)):
            indices = torch.nonzero(mask[i] > 0, as_tuple=True)[0]
            if not len(indices):
                increments.append(pred[i].sum()*0)
                continue
            origin = pred.new_zeros((1, 2))
            predicted = torch.cat((origin, pred[i, indices]), dim=0).diff(dim=0)
            observed = torch.cat((origin, target[i, indices]), dim=0).diff(dim=0)
            seconds = torch.cat((elapsed.new_zeros(1), elapsed[i, indices])).diff().clamp_min(.001)
            # Segment-average velocity labels, not interpolated instantaneous truth.
            residual = (predicted-observed)/(2*seconds[:, None])
            increments.append(F.smooth_l1_loss(residual, torch.zeros_like(residual), reduction='none').sum(-1).mean())
        loss = loss+increment_weight*torch.stack(increments)
    return loss
