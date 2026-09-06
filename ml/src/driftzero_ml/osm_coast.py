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


class Graph:
    def __init__(self, osm: dict, origin: tuple[float, float]):
        self.origin = origin
        self.edges: list[Segment] = []
        self.outgoing: dict[int, list[int]] = defaultdict(list)
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
            one = tags.get('oneway', 'yes' if tags.get('junction') == 'roundabout' or tags.get('highway') == 'motorway' else 'no')
            directions = (False,) if one == '-1' else ((True,) if one in ('yes', '1', 'true') else (True, False))
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
                    self.edges.append(Segment(nodes[a], nodes[b], x, y, xx-x, yy-y, length, math.atan2(xx-x, yy-y)))
        if not self.edges:
            raise ValueError('no driveable road segments')

    def point(self, index: int, s: float) -> tuple[float, float]:
        e = self.edges[index]
        return e.x + e.dx*s/e.length, e.y + e.dy*s/e.length


@dataclass
class Hypothesis:
    edge: int
    s: float
    speed: float
    weight: float


class RoadCoast:
    def __init__(self, graph: Graph, seed: dict, *, count: int = 512, heading_sigma: float = 0.4):
        self.graph, self.count, self.heading_sigma = graph, count, heading_sigma
        self.rng = random.Random(26168)
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
            for _ in range(64):
                edge = self.graph.edges[p.edge]
                if p.s <= edge.length:
                    break
                p.s -= edge.length
                options = [i for i in self.graph.outgoing[edge.target] if self.graph.edges[i].target != edge.source]
                if not options:
                    p.s = edge.length
                    p.weight *= 0.1
                    break
                p.edge = self.rng.choice(options)
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

    def estimate(self) -> tuple[float, float, float]:
        points = [(*self.graph.point(p.edge, p.s), p.weight) for p in self.particles]
        total = sum(w for _, _, w in points)
        x, y = (sum(p[k]*p[2] for p in points)/total for k in (0, 1))
        spread = math.sqrt(sum(w*((xx-x)**2+(yy-y)**2) for xx, yy, w in points)/total)
        # Posterior mean can lie between roads. Never present it as a snapped lane fix.
        lat, lon = enu_to_ll(self.graph.origin, x, y)
        return lat, lon, max(15.0+self.elapsed, spread)
