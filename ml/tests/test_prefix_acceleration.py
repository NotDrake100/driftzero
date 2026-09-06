import math
import unittest

from driftzero_ml.prefix_acceleration import fit_prefix_acceleration


def sequence():
    frames, speed = [], 10.0
    for tick in range(601):
        stamp = tick*100_000_000
        acceleration = .4*math.sin(tick/35)
        speed += acceleration*.1
        frames.append({'kind': 'accelerometer', 'timestamp_ns': stamp,
                       'payload': {'x': acceleration+.12, 'y': .1*math.cos(tick/12)},
                       'quality': {'flags': []}})
        if tick % 20 == 0:
            frames.append({'kind': 'gnss_fix', 'timestamp_ns': stamp, 'payload': {'speed_mps': speed}})
    return frames


class PrefixAccelerationTest(unittest.TestCase):
    def test_recovers_bias_and_rejects_hidden_labels(self):
        frames = sequence()
        model = fit_prefix_acceleration(frames, 50_000_000_000)
        self.assertIsNotNone(model)
        self.assertLess(model.validation_rmse, .1*model.hold_rmse)
        self.assertAlmostEqual(model.predict(.52, 0), .4, delta=.03)
        for f in frames:
            if f['timestamp_ns'] >= 50_000_000_000:
                f['payload'] = {'speed_mps': 1000, 'x': 999, 'y': -999}
        self.assertEqual(model, fit_prefix_acceleration(frames, 50_000_000_000))

    def test_insufficient_and_constant_motion_reject(self):
        self.assertIsNone(fit_prefix_acceleration([], 10))
        frames = sequence()
        for f in frames:
            if f['kind'] == 'gnss_fix':
                f['payload']['speed_mps'] = 10
        self.assertIsNone(fit_prefix_acceleration(frames, 50_000_000_000))
