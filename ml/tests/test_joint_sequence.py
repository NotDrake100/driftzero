import importlib.util
import json
import unittest
from pathlib import Path

from driftzero_ml.joint_sequence import (
    FEATURES,
    build_sequence,
    local_target,
    position_states,
    validate_roles,
)
from driftzero_ml.train_joint_sequence import eligible


def frames():
    seed = {'latitude_deg': 52., 'longitude_deg': -1., 'speed_mps': 10., 'bearing_rad': 0.}
    out = [{'kind': 'gnss_fix', 'timestamp_ns': 0, 'payload': seed}]
    for t in range(1, 31):
        stamp = t*100_000_000
        out.extend([{'kind': 'accelerometer', 'timestamp_ns': stamp,
                     'payload': {'x': .2, 'y': .1, 'z': 9.8}, 'quality': {'flags': []}},
                    {'kind': 'gyroscope', 'timestamp_ns': stamp,
                     'payload': {'z': .1}, 'quality': {'flags': []}}])
    return out


class JointSequenceTest(unittest.TestCase):
    def test_hidden_fix_and_future_independence(self):
        data = frames()
        first = build_sequence(data, 0, 3_000_000_001)
        data.append({'kind': 'gnss_fix', 'timestamp_ns': 1_000_000_000,
                     'payload': {'latitude_deg': -90., 'speed_mps': 1000}})
        self.assertEqual(first, build_sequence(data, 0, 3_000_000_001))
        prefix = build_sequence(data, 0, 1_000_000_001)
        self.assertEqual(first.features[:10], prefix.features)
        self.assertLess(first.base_velocity[-1][0], 0)  # Physical up yaw turns left.

    def test_coordinate_roundtrip(self):
        data = frames()
        data[0]['payload']['bearing_rad'] = 1.1
        seq = build_sequence(data, 0, 3_000_000_001)
        expected = [4., 12.]
        point = position_states(seq, [expected]*len(seq.stamps))[-1]['position']
        actual = local_target(seq, point['latitude_deg'], point['longitude_deg'])
        for a, b in zip(actual, expected):
            self.assertAlmostEqual(a, b, places=6)

    def test_gap_and_split_overlap_reject(self):
        data = frames()
        data = [f for f in data if not 500_000_000 <= f['timestamp_ns'] <= 1_000_000_000]
        with self.assertRaises(ValueError):
            build_sequence(data, 0, 3_000_000_001)
        root = Path(__file__).resolve().parents[2]
        split = json.loads((root/'results/cursor_diagnostics/split_manifest.json').read_text())
        validate_roles(split)
        split['train_session_groups'][0] = split['fresh_holdout_session_groups'][0]
        with self.assertRaises(ValueError):
            validate_roles(split)

    def test_candidate_gate_blocks_diagnostics_and_fallback(self):
        rows = [{'interval_id': 'example', 'metrics': {'drift_ratio': .2}}]
        baseline = {'failures': [], 'per_interval': rows,
                    'summary': {'drift_ratio_p50': .5, 'drift_ratio_p95': .8}}
        result = {'failures': [], 'per_interval': rows,
                  'summary': {'p50': .2, 'p95': .3, 'fail10': 1, 'fallback_count': 0}}
        self.assertTrue(eligible(result, baseline))
        result['diagnostic_only'] = True
        self.assertFalse(eligible(result, baseline))
        result.pop('diagnostic_only')
        result['summary']['fallback_count'] = 1
        self.assertFalse(eligible(result, baseline))

    @unittest.skipUnless(importlib.util.find_spec('torch'), 'optional PyTorch is unavailable')
    def test_network_prefix_invariance_and_backward(self):
        import torch

        from driftzero_ml.joint_network import JointNetwork

        torch.set_num_threads(1)
        for kind in ('mlp', 'tcn', 'gru'):
            model = JointNetwork(kind)
            x, base, dt = torch.randn(2, 35, len(FEATURES)), torch.randn(2, 35, 2), torch.full((2, 35), .1)
            # Nonzero heads ensure test covers the learned path, not only zero-init fallback.
            torch.nn.init.normal_(model.head.weight, std=.01)
            full, sigma = model(x, base, dt)
            short, _ = model(x[:, :20], base[:, :20], dt[:, :20])
            torch.testing.assert_close(full[:, :20], short, atol=1e-5, rtol=1e-5)
            (full.square().mean()+sigma.mean()).backward()
            self.assertTrue(all(p.grad is not None for p in model.parameters()))

    def test_weak_gyro_is_visible_but_does_not_drive_physics(self):
        data = frames()
        for f in data:
            if f['kind'] == 'gyroscope':
                f['quality']['flags'] = ['gyro_heading_pick_weak']
        raw = {100_000_000: (.1, -.3, .2), 9_000_000_000: (999., 999., 999.)}
        seq = build_sequence(data, 0, 3_000_000_001, raw)
        self.assertEqual(seq.features[0][3], .1)
        self.assertEqual(seq.features[0][-4:], [.1, -.3, .2, 1.])
        self.assertTrue(all(v == [0., 10.] for v in seq.base_velocity))
        raw[9_000_000_000] = (-999., 0., 0.)
        self.assertEqual(seq, build_sequence(data, 0, 3_000_000_001, raw))

    @unittest.skipUnless(importlib.util.find_spec('torch'), 'optional PyTorch is unavailable')
    def test_polar_head_can_stop_and_turn_at_high_seed_speed(self):
        import math

        import torch

        from driftzero_ml.joint_network import JointNetwork

        model = JointNetwork('mlp', head_mode='polar')
        x = torch.zeros(1, 10, len(FEATURES))
        x[..., 8] = 1.0
        base = torch.zeros(1, 10, 2)
        base[..., 1] = 25.0
        dt = torch.full((1, 10), .1)
        with torch.no_grad():
            model.head.bias[0] = math.atanh(.5)
            position, _ = model(x, base, dt)
            torch.testing.assert_close(position[0, -1], torch.tensor([25., 0.]), atol=1e-4, rtol=1e-4)
            model.head.bias[1] = math.atanh(-25/55)
            stopped, _ = model(x, base, dt)
            self.assertLess(float(stopped.abs().max()), 1e-4)
            model.head.bias[1] = 10
            capped, _ = model(x, base, dt)
            self.assertLessEqual(float(torch.linalg.vector_norm(capped[0, -1])), 55.001)
