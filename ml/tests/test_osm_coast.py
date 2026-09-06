import math
import unittest

from driftzero_ml.osm_coast import Graph, RoadCoast, map_query, prefix_seed


def way(nodes, points, **tags):
    return {'type': 'way', 'nodes': nodes, 'geometry': [{'lat': y, 'lon': x} for x, y in points],
            'tags': {'highway': 'residential', **tags}}


class OsmCoastTest(unittest.TestCase):
    def test_direction_and_shared_topology(self):
        graph = Graph({'elements': [way([1, 2], [(0, 0), (.001, 0)], oneway='yes'),
                                    way([2, 3], [(.001, 0), (.001, .001)], oneway='-1')]}, (0, 0))
        self.assertEqual([(e.source, e.target) for e in graph.edges], [(1, 2), (3, 2)])
        self.assertFalse(graph.outgoing[2])

    def test_prefix_seed_and_query_ignore_hidden_fix(self):
        seed = {'latitude_deg': 52.4, 'longitude_deg': -1.5, 'speed_mps': 10, 'bearing_rad': 0}
        frames = [{'kind': 'gnss_fix', 'timestamp_ns': 5, 'payload': seed},
                  {'kind': 'gnss_fix', 'timestamp_ns': 11, 'payload': {'latitude_deg': -80}}]
        first = prefix_seed(frames, 10)
        frames[-1]['payload'] = {'latitude_deg': 80, 'speed_mps': 999}
        self.assertEqual(first, prefix_seed(frames, 10))
        self.assertEqual(map_query(first), map_query(prefix_seed(frames, 10)))

    def test_endpoint_crossing_bounded_population_and_sign(self):
        graph = Graph({'elements': [way([1, 2, 3], [(0, 0), (.0001, 0), (.002, 0)], oneway='yes')]}, (0, 0))
        coast = RoadCoast(graph, {'latitude_deg': 0, 'longitude_deg': .00009,
                                 'speed_mps': 15, 'bearing_rad': math.pi/2}, count=64)
        initial = coast.heading
        for _ in range(20):
            coast.step(.1, .01)
        self.assertEqual(len(coast.particles), 64)
        self.assertTrue(any(p.edge == 1 and p.s > 1 for p in coast.particles))
        self.assertAlmostEqual(coast.heading, initial-.02)
        self.assertGreater(coast.estimate()[2], 15)
        with self.assertRaises(ValueError):
            coast.step(2, 0)

    def test_no_road_does_not_invent_fix(self):
        with self.assertRaises(ValueError):
            Graph({'elements': []}, (0, 0))

    def test_inference_ignores_hidden_gnss_and_falls_back_at_gap(self):
        from driftzero_ml.eval_osm_coast import infer

        graph = Graph({'elements': [way([1, 2], [(0, 0), (.02, 0)], oneway='yes')]}, (0, 0))
        seed = {'latitude_deg': 0, 'longitude_deg': 0, 'speed_mps': 10, 'bearing_rad': math.pi/2}
        frames = [{'kind': 'gnss_fix', 'timestamp_ns': 0, 'payload': seed}]
        for stamp in (100_000_000, 200_000_000, 900_000_000):
            frames.append({'kind': 'gyroscope', 'timestamp_ns': stamp,
                           'payload': {'z': 0}, 'quality': {'flags': []}})
        frames.append({'kind': 'gnss_fix', 'timestamp_ns': 200_000_000,
                       'payload': {'latitude_deg': 45, 'speed_mps': 1000}})
        baseline = [{'timestamp_ns': stamp, 'position': {'latitude_deg': 1, 'longitude_deg': 2}}
                    for stamp in (100_000_000, 200_000_000, 900_000_000)]
        first, info = infer(frames, baseline, 0, 1_000_000_000, graph, .4)
        frames[-1]['payload'] = {'latitude_deg': -45, 'speed_mps': 0}
        self.assertEqual(first, infer(frames, baseline, 0, 1_000_000_000, graph, .4)[0])
        self.assertNotEqual(first[0]['position'], baseline[0]['position'])
        self.assertEqual(first[-1], baseline[-1])
        self.assertEqual(info['failure']['timestamp_ns'], 900_000_000)

    def test_offline_cache_rejects_tampering(self):
        import hashlib
        import json
        import tempfile
        from pathlib import Path

        from driftzero_ml.osm_coast import acquire_map

        seed = {'latitude_deg': 52.4, 'longitude_deg': -1.5}
        query = map_query(seed)
        key = hashlib.sha256(query.encode()).hexdigest()
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            with self.assertRaises(FileNotFoundError):
                acquire_map(seed, directory)
            raw = b'{"elements": []}'
            (directory / f'{key}.json').write_bytes(raw)
            (directory / f'{key}.meta.json').write_text(json.dumps({'query': query,
                                                                  'sha256': hashlib.sha256(raw).hexdigest()}))
            self.assertEqual(acquire_map(seed, directory)[0], {'elements': []})
            (directory / f'{key}.json').write_bytes(b'{}')
            with self.assertRaises(ValueError):
                acquire_map(seed, directory)
