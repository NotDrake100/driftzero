import math
import unittest

from driftzero_ml.eval_osm_coast import infer
from driftzero_ml.osm_coast import Graph, map_query
from driftzero_ml.road_adapter import (
    CONFIGS,
    apply_causal_overlay,
    decide_overlay,
    development_gate,
)
from driftzero_ml.road_particle import ll_to_enu


def way(nodes, points, **tags):
    return {'type': 'way', 'nodes': nodes, 'geometry': [{'lat': y, 'lon': x} for x, y in points],
            'tags': {'highway': 'residential', **tags}}


def frames_and_baseline(seed, stamps, hidden=None):
    frames = [{'kind': 'gnss_fix', 'timestamp_ns': 0, 'payload': seed}]
    for stamp in stamps:
        frames.append({'kind': 'gyroscope', 'timestamp_ns': stamp,
                       'payload': {'z': 0}, 'quality': {'flags': []}})
    if hidden is not None:
        frames.append(hidden)
    baseline = [{'timestamp_ns': stamp,
                 'position': {'latitude_deg': 0.0004, 'longitude_deg': stamp / 1e13}}
                for stamp in stamps]
    return frames, baseline


class RoadAdapterTest(unittest.TestCase):
    def test_map_query_matches_adr014_provenance(self):
        seed = {'latitude_deg': 52.94, 'longitude_deg': -1.76}
        self.assertEqual(
            map_query(seed),
            '[out:json][timeout:90];way(around:16000,52.9,-1.8)'
            '["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|'
            'residential|service|living_street|motorway_link|trunk_link|primary_link|'
            'secondary_link|tertiary_link)$"];out geom;',
        )

    def test_decide_overlay_preregistered_thresholds(self):
        cfg = CONFIGS['confidence_v1']
        self.assertTrue(decide_overlay(20, 10, 0.8, 40, cfg)[0])
        self.assertEqual(decide_overlay(20, 10, 0.2, 40, cfg)[1], 'ambiguous_split')
        self.assertEqual(decide_overlay(200, 10, 0.9, 40, cfg)[1], 'spread')
        self.assertEqual(decide_overlay(20, 10, 0.9, 200, cfg)[1], 'contradictory_geometry')
        self.assertTrue(decide_overlay(200, 10, 0.2, 400, CONFIGS['adr014_reproduce'])[0])

    def test_confidence_gate_keeps_baseline_when_mean_is_between_roads(self):
        north = 80.0 / 111_320.0
        elements = [
            way([1, 2], [(0, 0), (.01, 0)], oneway='yes'),
            way([3, 4], [(0, north), (.01, north)], oneway='yes'),
        ]
        graph = Graph({'elements': elements}, (0, 0))
        seed = {'latitude_deg': north / 2, 'longitude_deg': 0.001,
                'speed_mps': 10, 'bearing_rad': math.pi / 2, 'horizontal_accuracy_m': 40}
        stamps = [100_000_000 * i for i in range(1, 16)]
        frames, baseline = frames_and_baseline(seed, stamps)
        always, _ = apply_causal_overlay(frames, baseline, 0, 2_000_000_000, graph,
                                         CONFIGS['adr014_reproduce'])
        gated, note = apply_causal_overlay(frames, baseline, 0, 2_000_000_000, graph,
                                           CONFIGS['confidence_v1'])
        road_ll = always[5]['position']
        base_ll = baseline[5]['position']
        mid_north = ll_to_enu(graph.origin, (road_ll['latitude_deg'], road_ll['longitude_deg']))[1]
        self.assertGreater(mid_north, 5)
        self.assertLess(mid_north, 75)
        self.assertEqual(gated[5]['position'], base_ll)
        self.assertGreater(note['fallback_states'], 0)

    def test_hidden_gnss_does_not_change_overlay(self):
        graph = Graph({'elements': [way([1, 2], [(0, 0), (.02, 0)], oneway='yes')]}, (0, 0))
        seed = {'latitude_deg': 0, 'longitude_deg': 0, 'speed_mps': 10, 'bearing_rad': math.pi / 2}
        stamps = (100_000_000, 200_000_000, 300_000_000)
        hidden = {'kind': 'gnss_fix', 'timestamp_ns': 200_000_000,
                  'payload': {'latitude_deg': 45, 'speed_mps': 1000}}
        frames, baseline = frames_and_baseline(seed, stamps, hidden)
        first, _ = infer(frames, baseline, 0, 1_000_000_000, graph, .4)
        frames[-1]['payload'] = {'latitude_deg': -45, 'speed_mps': 0}
        self.assertEqual(first, infer(frames, baseline, 0, 1_000_000_000, graph, .4)[0])

    def test_development_gate_is_relative_and_keeps_failures(self):
        baseline = {'summary': {'drift_ratio_p50': 0.40, 'drift_ratio_p95': 0.54, 'below_10_count': 1},
                    'failures': [], 'per_interval': [{}] * 11}
        better = {'summary': {'drift_ratio_p50': 0.30, 'drift_ratio_p95': 0.50, 'below_10_count': 2},
                  'failures': [], 'per_interval': [{}] * 11}
        tail = {'summary': {'drift_ratio_p50': 0.27, 'drift_ratio_p95': 0.85, 'below_10_count': 2},
                'failures': [], 'per_interval': [{}] * 11}
        self.assertTrue(development_gate(better, baseline, interval_count=11)['eligible'])
        rejected = development_gate(tail, baseline, interval_count=11)
        self.assertFalse(rejected['eligible'])
        self.assertFalse(rejected['p95_ok'])
        self.assertTrue(rejected['not_the_absolute_0_10_target'])
        self.assertFalse(rejected['locked_confirmation'])
        replay_baseline = {
            'summary': {'drift_ratio_p50': 0.40, 'drift_ratio_p95': 0.54},
            'failures': [],
            'per_interval': [{'metrics': {'drift_ratio': r}} for r in
                             (0.04, 0.18, 0.21, 0.22, 0.24, 0.40, 0.43, 0.46, 0.51, 0.53, 0.54)],
        }
        counted = development_gate(better, replay_baseline, interval_count=11)
        self.assertEqual(counted['baseline_fail10'], 10)

    def test_preregistered_config_names_are_frozen(self):
        self.assertEqual(list(CONFIGS), [
            'adr014_reproduce', 'topo_v1', 'confidence_v1', 'combined_v1', 'lateral_heal_v1',
        ])
