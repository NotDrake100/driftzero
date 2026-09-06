"""Road-constrained particle coast. Research fixture. Not SIH. Not IO-VNBD.

Estimates (edge, distance along edge, speed) while GNSS is masked. Lat/lon
is the weighted particle on the centerline. Hidden GNSS after mask start is
ignored. Persist speed is held. Curvature likelihood is omega_yaw vs v*kappa.

Do not write results/pune_v1. Do not claim the official 0.10 gate.
"""

from __future__ import annotations

import argparse
import itertools
import math
import sys
from collections.abc import Sequence
from dataclasses import dataclass, replace
from pathlib import Path

from driftzero_ml.metrics import EARTH_MEAN_RADIUS_M, LatLon, haversine_m

FIXTURE_LABEL = "fixture. Not SIH. Not IO-VNBD. Not a Pune field row."
# Labelled ENU origin for the fixture graph. Not a scored Pune trip.
FIXTURE_ORIGIN: LatLon = (18.5204, 73.8567)
SPEED_MPS = 15.0
APPROACH_M = 120.0
SEED_S_M = 80.0
ARC_RADIUS_M = 40.0
ARC_POINTS = 17
STRAIGHT_AFTER_M = 120.0
DT_S = 0.05
N_PARTICLES = 160
SIGMA_OMEGA_RADPS = 0.08
S_JITTER_M = 4.0
V_JITTER_MPS = 0.4
RESAMPLE_NEFF_FRAC = 0.5
GRAVITY_MPS2 = (0.0, 0.0, 9.80665)


class RoadParticleError(ValueError):
    """Fixture or path error. Not a silent zero."""


@dataclass(frozen=True)
class PolyPoint:
    east_m: float
    north_m: float


@dataclass(frozen=True)
class RoadEdge:
    edge_id: str
    from_node: str
    to_node: str
    points: tuple[PolyPoint, ...]
    successor_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if len(self.points) < 2:
            raise RoadParticleError(f"edge {self.edge_id} needs two points")
        if not math.isfinite(self.length_m) or self.length_m <= 0.0:
            raise RoadParticleError(f"edge {self.edge_id} length")

    @property
    def length_m(self) -> float:
        return sum(
            math.hypot(b.east_m - a.east_m, b.north_m - a.north_m)
            for a, b in zip(self.points, self.points[1:])
        )


@dataclass(frozen=True)
class RoadGraph:
    edges: dict[str, RoadEdge]

    def successors(self, edge_id: str) -> tuple[RoadEdge, ...]:
        edge = self.edges[edge_id]
        return tuple(self.edges[sid] for sid in edge.successor_ids)


@dataclass
class Particle:
    edge_id: str
    s_m: float
    speed_mps: float
    weight: float


@dataclass(frozen=True)
class CoastEstimate:
    east_m: float
    north_m: float
    latitude_deg: float
    longitude_deg: float
    dominant_edge_id: str
    dominant_weight: float
    n_eff: float
    label: str = FIXTURE_LABEL


def wrap_pi(angle_rad: float) -> float:
    return (angle_rad + math.pi) % (2.0 * math.pi) - math.pi


def yaw_about_gravity(gyro_radps: tuple[float, float, float], gravity: tuple[float, float, float]) -> float:
    gx, gy, gz = gravity
    norm = math.hypot(gx, gy, gz)
    if norm <= 1e-9:
        raise RoadParticleError("gravity vector is degenerate")
    hx, hy, hz = gx / norm, gy / norm, gz / norm
    return gyro_radps[0] * hx + gyro_radps[1] * hy + gyro_radps[2] * hz


def _seg_lengths(points: Sequence[PolyPoint]) -> list[float]:
    return [
        math.hypot(b.east_m - a.east_m, b.north_m - a.north_m)
        for a, b in itertools.pairwise(points)
    ]


def _seg_headings(points: Sequence[PolyPoint]) -> list[float]:
    headings: list[float] = []
    for a, b in itertools.pairwise(points):
        headings.append(math.atan2(b.east_m - a.east_m, b.north_m - a.north_m))
    return headings


def curvature_at_s(edge: RoadEdge, s_m: float) -> float:
    """d(heading)/ds, rad/m. Heading is atan2(east, north), 0 = north.

    A left turn from east toward north decreases that heading, so kappa is
    negative. Measured yaw must use the same sign: omega = v * kappa.
    """

    if not math.isfinite(s_m):
        raise RoadParticleError("s_m must be finite")
    s = min(max(0.0, s_m), edge.length_m)
    lengths = _seg_lengths(edge.points)
    headings = _seg_headings(edge.points)
    acc = 0.0
    idx = 0
    for i, length in enumerate(lengths):
        if s <= acc + length or i == len(lengths) - 1:
            idx = i
            break
        acc += length
    if len(headings) == 1:
        return 0.0
    if idx == 0:
        dpsi = wrap_pi(headings[1] - headings[0])
        ds = 0.5 * (lengths[0] + lengths[1])
    elif idx == len(headings) - 1:
        dpsi = wrap_pi(headings[-1] - headings[-2])
        ds = 0.5 * (lengths[-2] + lengths[-1])
    else:
        dpsi = wrap_pi(headings[idx] - headings[idx - 1])
        ds = 0.5 * (lengths[idx - 1] + lengths[idx])
    if ds <= 1e-6:
        return 0.0
    return dpsi / ds


def point_at_s(edge: RoadEdge, s_m: float) -> PolyPoint:
    s = min(max(0.0, s_m), edge.length_m)
    remaining = s
    for a, b, length in zip(edge.points, edge.points[1:], _seg_lengths(edge.points)):
        if remaining <= length or length <= 1e-12:
            if length <= 1e-12:
                return a
            t = remaining / length
            return PolyPoint(
                east_m=a.east_m + t * (b.east_m - a.east_m),
                north_m=a.north_m + t * (b.north_m - a.north_m),
            )
        remaining -= length
    return edge.points[-1]


def enu_to_ll(origin: LatLon, east_m: float, north_m: float) -> LatLon:
    lat0, lon0 = origin
    lat = lat0 + (north_m / EARTH_MEAN_RADIUS_M) * (180.0 / math.pi)
    lon = lon0 + (east_m / (EARTH_MEAN_RADIUS_M * math.cos(math.radians(lat0)))) * (180.0 / math.pi)
    return (lat, lon)


def ll_to_enu(origin: LatLon, point: LatLon) -> tuple[float, float]:
    lat0, lon0 = origin
    dlat = math.radians(point[0] - lat0)
    dlon = math.radians(point[1] - lon0)
    east = EARTH_MEAN_RADIUS_M * dlon * math.cos(math.radians(lat0))
    north = EARTH_MEAN_RADIUS_M * dlat
    return east, north


def fork_fixture_graph() -> RoadGraph:
    """East approach, then straight-east vs left quarter-circle to north."""

    approach = [
        PolyPoint(east_m=float(x), north_m=0.0)
        for x in (0.0, APPROACH_M / 2.0, APPROACH_M)
    ]
    straight = [
        PolyPoint(east_m=APPROACH_M + x, north_m=0.0)
        for x in (0.0, STRAIGHT_AFTER_M / 2.0, STRAIGHT_AFTER_M)
    ]
    arc: list[PolyPoint] = []
    for i in range(ARC_POINTS):
        theta = (math.pi / 2.0) * (i / (ARC_POINTS - 1))
        east = APPROACH_M + ARC_RADIUS_M * math.sin(theta)
        north = ARC_RADIUS_M - ARC_RADIUS_M * math.cos(theta)
        arc.append(PolyPoint(east_m=east, north_m=north))
    return RoadGraph(
        edges={
            "A": RoadEdge("A", "n0", "n1", tuple(approach), ("B", "C")),
            "B": RoadEdge("B", "n1", "n2", tuple(straight), ()),
            "C": RoadEdge("C", "n1", "n3", tuple(arc), ()),
        }
    )


def truth_state(
    graph: RoadGraph,
    t_s: float,
    speed_mps: float,
    start_s_m: float,
) -> tuple[RoadEdge, float]:
    remaining = start_s_m + speed_mps * t_s
    edge = graph.edges["A"]
    while remaining > edge.length_m:
        remaining -= edge.length_m
        succs = graph.successors(edge.edge_id)
        if not succs:
            return edge, edge.length_m
        edge = next(item for item in succs if item.edge_id == "C")
    return edge, remaining


def truth_on_bend(graph: RoadGraph, t_s: float, speed_mps: float, start_s_m: float) -> PolyPoint:
    edge, s_m = truth_state(graph, t_s, speed_mps, start_s_m)
    return point_at_s(edge, s_m)


def persist_east(start_s_m: float, t_s: float, speed_mps: float) -> PolyPoint:
    return PolyPoint(east_m=start_s_m + speed_mps * t_s, north_m=0.0)


def n_eff(particles: Sequence[Particle]) -> float:
    total = sum(p.weight for p in particles)
    if total <= 0.0:
        raise RoadParticleError("particle weights must be positive")
    return 1.0 / sum((p.weight / total) ** 2 for p in particles)


def _normalize(particles: list[Particle]) -> list[Particle]:
    total = sum(p.weight for p in particles)
    if total <= 0.0:
        raise RoadParticleError("particle weights must be positive")
    return [replace(p, weight=p.weight / total) for p in particles]


def _resample(particles: list[Particle], rng_index: int) -> list[Particle]:
    n = len(particles)
    particles = _normalize(particles)
    step = 1.0 / n
    start = ((rng_index * 17 + 3) % 1000) / 1000.0 * step
    cum = 0.0
    targets = [start + i * step for i in range(n)]
    out: list[Particle] = []
    src = 0
    cum = particles[0].weight
    for target in targets:
        while target > cum and src < n - 1:
            src += 1
            cum += particles[src].weight
        chosen = particles[src]
        out.append(Particle(chosen.edge_id, chosen.s_m, chosen.speed_mps, 1.0 / n))
    return out


def _advance_one(graph: RoadGraph, particle: Particle, ds: float) -> list[Particle]:
    if ds < 0.0:
        raise RoadParticleError("ds must be non-negative")
    remaining = ds
    edge = graph.edges[particle.edge_id]
    s = particle.s_m
    weight = particle.weight
    speed = particle.speed_mps
    while remaining > 0.0:
        left = edge.length_m - s
        if remaining <= left or left <= 1e-9:
            return [Particle(edge.edge_id, min(s + remaining, edge.length_m), speed, weight)]
        remaining -= left
        succs = graph.successors(edge.edge_id)
        if not succs:
            return [Particle(edge.edge_id, edge.length_m, speed, weight)]
        share = weight / len(succs)
        spawned: list[Particle] = []
        for succ in succs:
            child = Particle(succ.edge_id, 0.0, speed, share)
            spawned.extend(_advance_one(graph, child, remaining))
        return spawned
    return [Particle(edge.edge_id, s, speed, weight)]


def _gyro_likelihood(omega_meas: float, omega_pred: float) -> float:
    residual = omega_meas - omega_pred
    var = SIGMA_OMEGA_RADPS * SIGMA_OMEGA_RADPS
    return math.exp(-0.5 * residual * residual / var)


def seed_particles(graph: RoadGraph, start_s_m: float, speed_mps: float, n: int = N_PARTICLES) -> list[Particle]:
    if start_s_m < 0.0 or start_s_m > graph.edges["A"].length_m:
        raise RoadParticleError("seed s must lie on edge A")
    particles: list[Particle] = []
    for i in range(n):
        jitter = ((i % 11) - 5) / 5.0 * S_JITTER_M
        v_j = ((i % 7) - 3) / 3.0 * V_JITTER_MPS
        s = min(max(0.0, start_s_m + jitter), graph.edges["A"].length_m)
        particles.append(Particle("A", s, max(0.1, speed_mps + v_j), 1.0 / n))
    return particles


def step(
    graph: RoadGraph,
    particles: list[Particle],
    dt_s: float,
    omega_yaw: float,
    *,
    step_index: int,
) -> list[Particle]:
    if dt_s <= 0.0 or not math.isfinite(dt_s):
        raise RoadParticleError("dt_s must be finite and positive")
    if not math.isfinite(omega_yaw):
        raise RoadParticleError("omega_yaw must be finite")
    advanced: list[Particle] = []
    for particle in particles:
        ds = particle.speed_mps * dt_s
        for child in _advance_one(graph, particle, ds):
            edge = graph.edges[child.edge_id]
            omega_pred = child.speed_mps * curvature_at_s(edge, child.s_m)
            like = _gyro_likelihood(omega_yaw, omega_pred)
            advanced.append(replace(child, weight=child.weight * like))
    advanced = _normalize(advanced)
    if n_eff(advanced) < RESAMPLE_NEFF_FRAC * len(advanced):
        advanced = _resample(advanced, step_index)
    return advanced


def weighted_estimate(graph: RoadGraph, particles: Sequence[Particle], origin: LatLon = FIXTURE_ORIGIN) -> CoastEstimate:
    particles = _normalize(list(particles))
    east = sum(point_at_s(graph.edges[p.edge_id], p.s_m).east_m * p.weight for p in particles)
    north = sum(point_at_s(graph.edges[p.edge_id], p.s_m).north_m * p.weight for p in particles)
    masses: dict[str, float] = {}
    for particle in particles:
        masses[particle.edge_id] = masses.get(particle.edge_id, 0.0) + particle.weight
    dominant = max(masses, key=lambda key: masses[key])
    lat, lon = enu_to_ll(origin, east, north)
    return CoastEstimate(
        east_m=east,
        north_m=north,
        latitude_deg=lat,
        longitude_deg=lon,
        dominant_edge_id=dominant,
        dominant_weight=masses[dominant],
        n_eff=n_eff(particles),
    )


def coast_fixture(
    *,
    duration_s: float,
    gnss_after_mask: Sequence[LatLon] | None = None,
) -> dict[str, float | str | bool]:
    """Run the labeled fork fixture. gnss_after_mask must not change the estimate."""

    if gnss_after_mask:
        # Score-only. Using it here would leak. Ignore by construction.
        _ = tuple(gnss_after_mask)
    graph = fork_fixture_graph()
    particles = seed_particles(graph, SEED_S_M, SPEED_MPS)
    t = 0.0
    step_i = 0
    while t < duration_s - 1e-12:
        edge, s_m = truth_state(graph, t, SPEED_MPS, SEED_S_M)
        omega = SPEED_MPS * curvature_at_s(edge, s_m)
        gyro = (0.0, 0.0, omega)
        yaw = yaw_about_gravity(gyro, GRAVITY_MPS2)
        particles = step(graph, particles, DT_S, yaw, step_index=step_i)
        t += DT_S
        step_i += 1
    est = weighted_estimate(graph, particles)
    truth_end = truth_on_bend(graph, duration_s, SPEED_MPS, SEED_S_M)
    persist_end = persist_east(SEED_S_M, duration_s, SPEED_MPS)
    origin = FIXTURE_ORIGIN
    truth_ll = enu_to_ll(origin, truth_end.east_m, truth_end.north_m)
    persist_ll = enu_to_ll(origin, persist_end.east_m, persist_end.north_m)
    est_ll = (est.latitude_deg, est.longitude_deg)
    start_ll = enu_to_ll(origin, SEED_S_M, 0.0)
    path_m = SPEED_MPS * duration_s
    pf_err = haversine_m(est_ll, truth_ll)
    persist_err = haversine_m(persist_ll, truth_ll)
    return {
        "label": FIXTURE_LABEL,
        "not_sih": True,
        "not_io_vnbd": True,
        "path_m": path_m,
        "pf_error_m": pf_err,
        "persist_error_m": persist_err,
        "pf_drift": pf_err / path_m,
        "persist_drift": persist_err / path_m,
        "dominant_edge": est.dominant_edge_id,
        "dominant_weight": est.dominant_weight,
        "start_lat": start_ll[0],
        "start_lon": start_ll[1],
    }


def refuse_official_pune(out: Path, repo: Path) -> None:
    official = (repo / "results" / "pune_v1").resolve()
    if out.resolve() == official:
        raise RoadParticleError("fixture must not write results/pune_v1")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=FIXTURE_LABEL)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--repo", type=Path, default=Path("."))
    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        if args.out is not None:
            refuse_official_pune(args.out, args.repo)
            raise RoadParticleError("fixture writes no metrics file. Tests print. Not pune_v1.")
        row = coast_fixture(duration_s=6.9)
        print(row)
        return 0
    except RoadParticleError as error:
        print(error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

