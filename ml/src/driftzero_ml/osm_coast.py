"""Causal offline road-particle research. Real maps, no live Android integration."""
from __future__ import annotations

import hashlib
import json
import math
import random
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from driftzero_ml.road_particle import enu_to_ll, ll_to_enu, wrap_pi

ROAD_TYPES = 'motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link'
MAX_HOPS = 64
MAX_SEED_CANDIDATES = 4096


def prefix_seed(frames: list[dict], start_ns: int) -> dict:
    fixes = [f for f in frames if f['kind'] == 'gnss_fix' and f['timestamp_ns'] <= start_ns]
    if not fixes:
        raise ValueError('no available seed fix')
    seed = dict(fixes[-1]['payload'], timestamp_ns=fixes[-1]['timestamp_ns'])
    if 'speed_mps' not in seed or 'bearing_rad' not in seed:
        raise ValueError('seed requires available speed and bearing')
    return seed


def map_query(seed: dict) -> str:
    # Fixed radius and rounded tile center depend only on the available prefix.
    lat = round(seed['latitude_deg'], 1)
    lon = round(seed['longitude_deg'], 1)
    return f'[out:json][timeout:90];way(around:16000,{lat},{lon})["highway"~"^({ROAD_TYPES})$"];out geom;'


def acquire_map(seed: dict, directory: Path, *, download: bool = False) -> tuple[dict, dict]:
    query = map_query(seed)
    key = hashlib.sha256(query.encode()).hexdigest()
    path = directory / f'{key}.json'
    meta_path = directory / f'{key}.meta.json'
    if not path.exists():
        if not download:
            raise FileNotFoundError(f'offline map missing: {key}')
        directory.mkdir(parents=True, exist_ok=True)
        errors = []
        for endpoint in ('https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'):
            try:
                request = urllib.request.Request(endpoint, data=urllib.parse.urlencode({'data': query}).encode(),
                                                 headers={'User-Agent': 'DriftZero-research/1.0'})
                with urllib.request.urlopen(request, timeout=115) as response:
                    raw = response.read()
                obj = json.loads(raw)
                if obj.get('remark') or not obj.get('elements'):
                    raise ValueError('Overpass incomplete or empty response')
                meta = {'query': query, 'endpoint': endpoint, 'sha256': hashlib.sha256(raw).hexdigest(),
                        'fetched_utc': datetime.now(timezone.utc).isoformat(),
                        'osm_timestamp': obj.get('osm3s', {}).get('timestamp_osm_base'),
                        'attribution': '© OpenStreetMap contributors, ODbL 1.0',
                        'licence': 'https://www.openstreetmap.org/copyright'}
                path.write_bytes(raw)
                meta_path.write_text(json.dumps(meta, indent=2) + '\n')
                break
            except (OSError, ValueError) as error:
                errors.append(str(error))
        else:
            raise RuntimeError('map acquisition failed: ' + '; '.join(errors))
    raw = path.read_bytes()
    meta = json.loads(meta_path.read_text())
    if hashlib.sha256(raw).hexdigest() != meta['sha256'] or meta['query'] != query:
        raise ValueError('map cache provenance mismatch')
    return json.loads(raw), meta


def _truthy(value: str | None) -> bool:
    return value not in (None, '', 'no', 'false', '0')


def _optional_int(value: str | None) -> int | None:
    if value is None or value == '':
        return None
    try:
        return int(value)
    except ValueError:
        return None


def _oneway_directions(tags: dict) -> tuple[bool, ...]:
    default = 'yes' if tags.get('junction') == 'roundabout' or tags.get('highway') == 'motorway' else 'no'
    one = tags.get('oneway', default)
    if one == '-1':
        return (False,)
    if one in ('yes', '1', 'true'):
        return (True,)
    return (True, False)


@dataclass(frozen=True)
class Segment:
    source: int
    target: int
    x: float
    y: float
    dx: float
    dy: float
    length: float
    heading: float
    way_id: int | None = None
    layer: int | None = None
    bridge: bool = False
    tunnel: bool = False
    level: str = ''
    highway: str = ''


def _grade_compatible(src: Segment, dst: Segment) -> bool:
    if src.layer is not None and dst.layer is not None and src.layer != dst.layer:
        return False
    if src.bridge and dst.tunnel:
        return False
    if src.tunnel and dst.bridge:
        return False
    return not (src.level and dst.level and src.level != dst.level)


class Graph:
    def __init__(self, osm: dict, origin: tuple[float, float], *,
                 grade_separation: bool = False, parse_turn_restrictions: bool = False):
        self.origin = origin
        self.grade_separation = grade_separation
        self.edges: list[Segment] = []
        self.outgoing: dict[int, list[int]] = defaultdict(list)
        self.forbidden_turns: set[tuple[int, int, int]] = set()
        self.only_turns: dict[tuple[int, int], set[int]] = {}
        seen = set()
        for way in osm.get('elements', []):
            tags = way.get('tags', {})
            if way.get('type') != 'way' or tags.get('highway') not in ROAD_TYPES.split('|'):
                continue
            if tags.get('access') == 'no' or tags.get('motor_vehicle') == 'no':
                continue
            nodes, geom = way.get('nodes', []), way.get('geometry', [])
            if len(nodes) != len(geom):
                raise ValueError('incomplete OSM way geometry')
            directions = _oneway_directions(tags)
            layer = _optional_int(tags.get('layer'))
            bridge = _truthy(tags.get('bridge'))
            tunnel = _truthy(tags.get('tunnel'))
            level = str(tags.get('level') or '')
            way_id = way.get('id')
            for i in range(len(nodes)-1):
                for forward in directions:
                    a, b = (i, i+1) if forward else (i+1, i)
                    pair = (nodes[a], nodes[b])
                    if pair in seen:
                        continue
                    seen.add(pair)
                    x, y = ll_to_enu(origin, (geom[a]['lat'], geom[a]['lon']))
                    xx, yy = ll_to_enu(origin, (geom[b]['lat'], geom[b]['lon']))
                    length = math.hypot(xx-x, yy-y)
                    if length < 0.01:
                        continue
                    self.outgoing[nodes[a]].append(len(self.edges))
                    self.edges.append(Segment(
                        nodes[a], nodes[b], x, y, xx-x, yy-y, length, math.atan2(xx-x, yy-y),
                        way_id=way_id, layer=layer, bridge=bridge, tunnel=tunnel,
                        level=level, highway=str(tags.get('highway') or ''),
                    ))
        if not self.edges:
            raise ValueError('no driveable road segments')
        if parse_turn_restrictions:
            self._load_restrictions(osm)

    def _load_restrictions(self, osm: dict) -> None:
        for rel in osm.get('elements', []):
            tags = rel.get('tags', {})
            if rel.get('type') != 'relation' or tags.get('type') != 'restriction':
                continue
            excepted = str(tags.get('except') or '')
            if 'motorcar' in excepted.split(';') or 'motor_vehicle' in excepted.split(';'):
                continue
            restriction = (tags.get('restriction:motorcar') or tags.get('restriction:motor_vehicle')
                           or tags.get('restriction'))
            if not restriction:
                continue
            members = rel.get('members') or []
            from_ways = [m['ref'] for m in members if m.get('role') == 'from' and m.get('type') == 'way']
            to_ways = [m['ref'] for m in members if m.get('role') == 'to' and m.get('type') == 'way']
            via_nodes = [m['ref'] for m in members if m.get('role') == 'via' and m.get('type') == 'node']
            if not from_ways or not via_nodes:
                continue
            for from_way in from_ways:
                for via in via_nodes:
                    if restriction.startswith('only_') and to_ways:
                        self.only_turns.setdefault((from_way, via), set()).update(to_ways)
                    elif restriction.startswith('no_') and to_ways:
                        for to_way in to_ways:
                            self.forbidden_turns.add((from_way, via, to_way))

    def successors(self, edge_index: int, *, allow_deadend_uturn: bool = False) -> list[int]:
        edge = self.edges[edge_index]
        options, uturns = [], []
        for index in self.outgoing[edge.target]:
            nxt = self.edges[index]
            if nxt.target == edge.source:
                uturns.append(index)
                continue
            if self._legal_transition(edge, nxt):
                options.append(index)
        if options:
            return options
        if not allow_deadend_uturn:
            return []
        return [index for index in uturns if self._legal_transition(edge, self.edges[index])]

    def _legal_transition(self, src: Segment, dst: Segment) -> bool:
        if self.grade_separation and not _grade_compatible(src, dst):
            return False
        if src.way_id is None or dst.way_id is None:
            return True
        if (src.way_id, src.target, dst.way_id) in self.forbidden_turns:
            return False
        allowed = self.only_turns.get((src.way_id, src.target))
        return allowed is None or dst.way_id in allowed

    def point(self, index: int, s: float) -> tuple[float, float]:
        e = self.edges[index]
        return e.x + e.dx*s/e.length, e.y + e.dy*s/e.length


def graph_audit(graph: Graph, osm: dict | None = None) -> dict:
    dead_ends = 0
    oneway = 0
    grade_tagged = 0
    reverse = {(e.target, e.source) for e in graph.edges}
    for index, edge in enumerate(graph.edges):
        if (edge.source, edge.target) not in reverse:
            oneway += 1
        if not graph.successors(index):
            dead_ends += 1
        if edge.layer is not None or edge.bridge or edge.tunnel or edge.level:
            grade_tagged += 1
    relations = 0
    if osm is not None:
        relations = sum(1 for el in osm.get('elements', [])
                        if el.get('type') == 'relation' and el.get('tags', {}).get('type') == 'restriction')
    return {
        'segment_count': len(graph.edges),
        'oneway_segments': oneway,
        'deadend_segments': dead_ends,
        'grade_tagged_segments': grade_tagged,
        'restriction_relations_present': relations,
        'forbidden_turn_count': len(graph.forbidden_turns),
        'only_turn_count': len(graph.only_turns),
        'grade_separation': graph.grade_separation,
        'note': 'tags from the cached OSM extract. No truth-derived corridor.',
    }


@dataclass
class Hypothesis:
    edge: int
    s: float
    speed: float
    weight: float


@dataclass(frozen=True)
class CoastDiagnostics:
    latitude_deg: float
    longitude_deg: float
    reported_spread_m: float
    raw_spread_m: float
    n_eff: float
    unique_edges: int
    cluster_count: int
    dominant_mass: float
    elapsed_s: float
    heading_rad: float


class RoadCoast:
    def __init__(self, graph: Graph, seed: dict, *, count: int = 512, heading_sigma: float = 0.4,
                 heading_aware_junction: bool = False, deadend_uturn: bool = False, rng_seed: int = 26168):
        self.graph, self.count, self.heading_sigma = graph, count, heading_sigma
        self.heading_aware_junction = heading_aware_junction
        self.deadend_uturn = deadend_uturn
        self.rng = random.Random(rng_seed)
        self.heading = seed['bearing_rad']
        self.elapsed = 0.0
        candidates = []
        x, y = ll_to_enu(graph.origin, (seed['latitude_deg'], seed['longitude_deg']))
        sigma = max(8.0, min(40.0, seed.get('horizontal_accuracy_m', 15.0)))
        for index, edge in enumerate(graph.edges):
            s = max(0.0, min(edge.length, ((x-edge.x)*edge.dx+(y-edge.y)*edge.dy)/edge.length))
            xx, yy = graph.point(index, s)
            distance = math.hypot(xx-x, yy-y)
            angle = wrap_pi(edge.heading-self.heading)
            if distance <= 3*sigma and abs(angle) < math.pi/2:
                weight = math.exp(-0.5*(distance/sigma)**2-0.5*(angle/0.5)**2)
                candidates.append(Hypothesis(index, s, seed['speed_mps'], weight))
        if not candidates:
            raise ValueError('no compatible road seed, use deterministic fallback')
        if len(candidates) > MAX_SEED_CANDIDATES:
            candidates.sort(key=lambda item: item.weight, reverse=True)
            candidates = candidates[:MAX_SEED_CANDIDATES]
        self.particles = self.resample(candidates)
        for p in self.particles:
            p.speed = min(55.0, max(0.0, p.speed+self.rng.gauss(0, 3)))
        self.last_weight_time = 0.0

    def resample(self, particles: list[Hypothesis]) -> list[Hypothesis]:
        total = sum(p.weight for p in particles)
        if not math.isfinite(total) or total <= 0:
            raise ValueError('road posterior collapsed')
        step = total/self.count
        target = self.rng.random()*step
        result, index, cumulative = [], 0, particles[0].weight
        for _ in range(self.count):
            while cumulative < target and index < len(particles)-1:
                index += 1
                cumulative += particles[index].weight
            p = particles[index]
            result.append(Hypothesis(p.edge, p.s, p.speed, 1/self.count))
            target += step
        return result

    def _pick_successor(self, options: list[int]) -> int:
        if not self.heading_aware_junction:
            return self.rng.choice(options)
        sigma = self.heading_sigma + 0.005*self.elapsed
        weights = []
        for index in options:
            angle = wrap_pi(self.graph.edges[index].heading-self.heading)
            weights.append(max(1e-8, math.exp(-0.5*(angle/sigma)**2)))
        pick = self.rng.random()*sum(weights)
        cumulative = 0.0
        chosen = options[-1]
        for index, weight in zip(options, weights):
            cumulative += weight
            if pick <= cumulative:
                return index
        return chosen

    def step(self, dt: float, physical_up_rate: float | None, acceleration: float = 0.0) -> None:
        if not math.isfinite(acceleration) or abs(acceleration) > 3:
            raise ValueError('invalid acceleration prediction')
        if dt <= 0 or not math.isfinite(dt):
            raise ValueError('invalid timestep')
        if dt > 0.4:
            raise ValueError('IMU gap, road coast unavailable')
        self.elapsed += dt
        if physical_up_rate is not None:
            if not math.isfinite(physical_up_rate):
                raise ValueError('nonfinite gyro')
            self.heading = wrap_pi(self.heading-physical_up_rate*dt)
        for p in self.particles:
            p.speed = max(0.0, min(55.0, p.speed+acceleration*dt+self.rng.gauss(0, 0.35*math.sqrt(dt))))
            p.s += p.speed*dt
            for _ in range(MAX_HOPS):
                edge = self.graph.edges[p.edge]
                if p.s <= edge.length:
                    break
                p.s -= edge.length
                options = self.graph.successors(p.edge, allow_deadend_uturn=self.deadend_uturn)
                if not options:
                    p.s = edge.length
                    p.weight *= 0.1
                    break
                if self.deadend_uturn and len(options) == 1 and self.graph.edges[options[0]].target == edge.source:
                    p.weight *= 0.3
                p.edge = self._pick_successor(options)
            else:
                raise ValueError('excessive road transitions')
        if self.elapsed-self.last_weight_time >= 1.0:
            # One likelihood per second avoids treating 10 Hz correlated IMU as independent fixes.
            sigma = self.heading_sigma + 0.005*self.elapsed
            for p in self.particles:
                angle = wrap_pi(self.graph.edges[p.edge].heading-self.heading)
                p.weight *= max(1e-8, math.exp(-0.5*(angle/sigma)**2))
            self.particles = self.resample(self.particles)
            self.last_weight_time = self.elapsed

    def diagnostics(self) -> CoastDiagnostics:
        points = [(*self.graph.point(p.edge, p.s), p.weight, p.edge) for p in self.particles]
        total = sum(item[2] for item in points)
        if not math.isfinite(total) or total <= 0:
            raise ValueError('road posterior collapsed')
        x = sum(item[0]*item[2] for item in points)/total
        y = sum(item[1]*item[2] for item in points)/total
        raw = math.sqrt(sum(item[2]*((item[0]-x)**2+(item[1]-y)**2) for item in points)/total)
        n_eff = 1.0 / sum((item[2]/total)**2 for item in points)
        cells: dict[tuple[int, int], float] = defaultdict(float)
        for xx, yy, weight, _ in points:
            cells[(math.floor(xx/25.0), math.floor(yy/25.0))] += weight
        dominant = max(cells.values())/total if cells else 0.0
        lat, lon = enu_to_ll(self.graph.origin, x, y)
        reported = max(15.0+self.elapsed, raw)
        return CoastDiagnostics(
            latitude_deg=lat, longitude_deg=lon, reported_spread_m=reported, raw_spread_m=raw,
            n_eff=n_eff, unique_edges=len({item[3] for item in points}), cluster_count=len(cells),
            dominant_mass=dominant, elapsed_s=self.elapsed, heading_rad=self.heading,
        )

    def estimate(self) -> tuple[float, float, float]:
        diag = self.diagnostics()
        # Posterior mean can lie between roads. Never present it as a snapped lane fix.
        return diag.latitude_deg, diag.longitude_deg, diag.reported_spread_m
