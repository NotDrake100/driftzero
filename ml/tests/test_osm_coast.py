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
