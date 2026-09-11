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

    def test_grade_separation_blocks_bridge_to_tunnel(self):
        elements = [
            {'type': 'way', 'id': 10, 'nodes': [1, 2],
             'geometry': [{'lat': 0, 'lon': 0}, {'lat': 0, 'lon': .001}],
             'tags': {'highway': 'residential', 'oneway': 'yes', 'bridge': 'yes', 'layer': '1'}},
            {'type': 'way', 'id': 11, 'nodes': [2, 3],
             'geometry': [{'lat': 0, 'lon': .001}, {'lat': .001, 'lon': .001}],
             'tags': {'highway': 'residential', 'oneway': 'yes', 'tunnel': 'yes', 'layer': '-1'}},
        ]
        connected = Graph({'elements': elements}, (0, 0))
        self.assertEqual(connected.successors(0), [1])
        separated = Graph({'elements': elements}, (0, 0), grade_separation=True)
        self.assertEqual(separated.successors(0), [])

    def test_turn_restriction_is_noop_without_relations(self):
        osm = {'elements': [way([1, 2, 3], [(0, 0), (.001, 0), (.002, 0)], oneway='yes')]}
        osm['elements'][0]['id'] = 7
        plain = Graph(osm, (0, 0))
        parsed = Graph(osm, (0, 0), parse_turn_restrictions=True)
        self.assertEqual([e.target for e in plain.edges], [e.target for e in parsed.edges])
        self.assertEqual(parsed.successors(0), plain.successors(0))
        self.assertEqual(parsed.forbidden_turns, set())

    def test_only_straight_restriction_keeps_the_named_way(self):
        elements = [
            {'type': 'way', 'id': 1, 'nodes': [1, 2],
             'geometry': [{'lat': 0, 'lon': 0}, {'lat': 0, 'lon': .001}],
             'tags': {'highway': 'residential', 'oneway': 'yes'}},
            {'type': 'way', 'id': 2, 'nodes': [2, 3],
             'geometry': [{'lat': 0, 'lon': .001}, {'lat': 0, 'lon': .002}],
             'tags': {'highway': 'residential', 'oneway': 'yes'}},
            {'type': 'way', 'id': 3, 'nodes': [2, 4],
             'geometry': [{'lat': 0, 'lon': .001}, {'lat': .001, 'lon': .001}],
             'tags': {'highway': 'residential', 'oneway': 'yes'}},
            {'type': 'relation', 'tags': {'type': 'restriction', 'restriction': 'only_straight_on'},
             'members': [{'type': 'way', 'ref': 1, 'role': 'from'},
                         {'type': 'node', 'ref': 2, 'role': 'via'},
                         {'type': 'way', 'ref': 2, 'role': 'to'}]},
        ]
        graph = Graph({'elements': elements}, (0, 0), parse_turn_restrictions=True)
        targets = [graph.edges[i].target for i in graph.successors(0)]
        self.assertEqual(targets, [3])

    def test_deadend_uturn_only_when_enabled(self):
        graph = Graph({'elements': [way([1, 2], [(0, 0), (.001, 0)])]}, (0, 0))
        self.assertEqual(graph.successors(0), [])
        self.assertEqual(graph.successors(0, allow_deadend_uturn=True), [1])
        self.assertEqual(graph.edges[1].target, 1)

    def test_heading_aware_junction_prefers_matching_branch(self):
        elements = [
            way([1, 2], [(0, 0), (.0002, 0)], oneway='yes'),
            way([2, 3], [(.0002, 0), (.003, 0)], oneway='yes'),
            way([2, 4], [(.0002, 0), (.0002, .002)], oneway='yes'),
        ]
        graph = Graph({'elements': elements}, (0, 0))
        seed = {'latitude_deg': 0, 'longitude_deg': 0, 'speed_mps': 12, 'bearing_rad': math.pi/2}
        coast = RoadCoast(graph, seed, count=64, heading_aware_junction=True)
        for _ in range(30):
            coast.step(.1, 0.0)
        east = sum(1 for p in coast.particles if graph.edges[p.edge].target == 3)
        north = sum(1 for p in coast.particles if graph.edges[p.edge].target == 4)
        self.assertGreater(east, north)

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
