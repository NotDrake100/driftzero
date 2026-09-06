"""Frozen prefix-only acceleration regression, with chronological validation.

Fits change in GNSS speed to integrated gravity-aligned horizontal acceleration.
It learns forward-axis projection and residual bias, not absolute speed. Model
rejection leaves speed hold available. No third-party runtime is needed.
"""
from __future__ import annotations

import itertools
import math
from dataclasses import dataclass

from driftzero_ml.student.linear import _ridge


@dataclass(frozen=True)
class PrefixAcceleration:
    weights: tuple[float, ...]
    fit_hops: int
    validation_hops: int
    validation_rmse: float
    hold_rmse: float

    def predict(self, ax: float, ay: float) -> float:
        if not math.isfinite(ax) or not math.isfinite(ay):
            raise ValueError('nonfinite acceleration')
        value = self.weights[0] + self.weights[1]*ax + self.weights[2]*ay
        return max(-3.0, min(3.0, value))


def fit_prefix_acceleration(frames: list[dict], start_ns: int) -> PrefixAcceleration | None:
    # Filtering before any fit or statistics is the leakage boundary.
    prefix = sorted((f for f in frames if f['timestamp_ns'] < start_ns), key=lambda f: f['timestamp_ns'])
    fixes = [f for f in prefix if f['kind'] == 'gnss_fix' and 'speed_mps' in f['payload']]
    accel = [f for f in prefix if f['kind'] == 'accelerometer']
    xs, ys = [], []
    index = 0
    for before, after in itertools.pairwise(fixes):
        start, end = before['timestamp_ns'], after['timestamp_ns']
        span = (end-start)/1e9
        target = (after['payload']['speed_mps']-before['payload']['speed_mps'])/span if span > 0 else math.inf
        while index < len(accel) and accel[index]['timestamp_ns'] <= start:
            index += 1
        integral = [0.0, 0.0]
        covered, previous, valid = 0.0, start, True
        while index < len(accel) and accel[index]['timestamp_ns'] <= end:
            sample = accel[index]
            dt = (sample['timestamp_ns']-previous)/1e9
            if dt > .4 or 'phone_align_unavailable' in sample['quality']['flags']:
                valid = False
            if dt > 0:
                for j, axis in enumerate(('x', 'y')):
                    integral[j] += sample['payload'][axis]*dt
                covered += dt
            previous = sample['timestamp_ns']
            index += 1
        if not valid or not 1 <= span <= 15 or covered < .9*span or abs(target) > 3:
            continue
        vector = tuple(v/covered for v in integral)
        if all(math.isfinite(v) for v in vector) and math.isfinite(target):
            xs.append(vector)
            ys.append(target)
    if len(xs) < 12:
        return None
    split = max(8, int(.7*len(xs)))
    weights = tuple(_ridge(xs[:split], ys[:split], .1))
    predictions = [weights[0]+weights[1]*x+weights[2]*y for x, y in xs[split:]]
    truth = ys[split:]
    rmse = math.sqrt(sum((p-y)**2 for p, y in zip(predictions, truth))/len(truth))
    hold = math.sqrt(sum(y*y for y in truth)/len(truth))
    # Reject unconstrained or physically implausible projections. No refit after validation.
    if not .2 <= math.hypot(*weights[1:]) <= 2 or abs(weights[0]) > 1 or rmse >= .9*hold:
        return None
    return PrefixAcceleration(weights, split, len(truth), rmse, hold)
