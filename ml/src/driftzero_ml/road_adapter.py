"""Causal road overlay adapter for Task D.

Contract (research only, not live Android):

- `RoadAdapterConfig` is the frozen configuration D should pin. Named members
  live in `CONFIGS`. ADR 014 defaults are `CONFIGS['adr014_reproduce']`.
- `apply_causal_overlay` is the only inference entry point. It reads prefix GNSS
  for the seed, IMU inside the open-closed blackout, and deterministic baseline
  states. It never reads GNSS after `start_ns`.
- Outputs copy baseline states. Only position lat/lon and `road_*` research
  fields may change. Velocity, heading, mode, and health stay baseline.
- The posterior mean can lie between roads. With `confidence_gate`, that mean is
  not written. Ambiguity, large raw spread, or contradiction versus the
  deterministic coast keeps the baseline position.
- Finite/bounded: nonfinite dt/gyro/accel raise; dt > 0.4 s is an IMU gap;
  at most 64 hops per particle step; collapsed weights become fallback.

Do not import this module from the Android live path. Do not pass locked
interval IDs into `eval_road_reliability`.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace
from typing import Any

from driftzero_ml.metrics import haversine_m
from driftzero_ml.osm_coast import Graph, RoadCoast, prefix_seed
from driftzero_ml.prefix_acceleration import fit_prefix_acceleration
from driftzero_ml.road_particle import enu_to_ll, ll_to_enu


@dataclass(frozen=True)
class RoadAdapterConfig:
    name: str
    heading_sigma_rad: float = 0.4
    particle_count: int = 512
    rng_seed: int = 26168
    heading_aware_junction: bool = False
    deadend_uturn: bool = False
    grade_separation: bool = False
    parse_turn_restrictions: bool = False
    confidence_gate: bool = False
    min_dominant_mass: float = 0.50
    spread_floor_m: float = 30.0
    spread_rate_mps: float = 3.0
    max_disagreement_m: float = 150.0
    lateral_heal: bool = False
    use_acceleration: bool = False
    max_imu_gap_s: float = 0.4

    def graph_kwargs(self) -> dict[str, bool]:
        return {
            'grade_separation': self.grade_separation,
            'parse_turn_restrictions': self.parse_turn_restrictions,
        }


# Preregistered before new development scores. Do not add a sixth config after
# opening those scores without a new versioned manifest.
CONFIGS: dict[str, RoadAdapterConfig] = {
    'adr014_reproduce': RoadAdapterConfig(name='adr014_reproduce'),
    'topo_v1': RoadAdapterConfig(
        name='topo_v1', heading_aware_junction=True, deadend_uturn=True,
        grade_separation=True, parse_turn_restrictions=True,
    ),
    'confidence_v1': RoadAdapterConfig(name='confidence_v1', confidence_gate=True),
    'combined_v1': RoadAdapterConfig(
        name='combined_v1', heading_aware_junction=True, deadend_uturn=True,
        grade_separation=True, parse_turn_restrictions=True, confidence_gate=True,
    ),
    'lateral_heal_v1': RoadAdapterConfig(
        name='lateral_heal_v1', heading_aware_junction=True, deadend_uturn=True,
        grade_separation=True, parse_turn_restrictions=True, confidence_gate=True,
        lateral_heal=True,
    ),
}


def decide_overlay(raw_spread_m: float, elapsed_s: float, dominant_mass: float,
                   disagreement_m: float | None, config: RoadAdapterConfig) -> tuple[bool, str]:
    if not config.confidence_gate:
        return True, 'road'
    if dominant_mass < config.min_dominant_mass:
        return False, 'ambiguous_split'
    if raw_spread_m > config.spread_floor_m + config.spread_rate_mps * elapsed_s:
        return False, 'spread'
    if disagreement_m is not None and disagreement_m > config.max_disagreement_m:
        return False, 'contradictory_geometry'
    return True, 'road'


def _baseline_ll(state: dict) -> tuple[float, float]:
    pos = state['position']
    return pos['latitude_deg'], pos['longitude_deg']


def _lateral_heal(origin: tuple[float, float], road_lat: float, road_lon: float,
                  heading_rad: float, baseline: tuple[float, float]) -> tuple[float, float]:
    rx, ry = ll_to_enu(origin, (road_lat, road_lon))
    bx, by = ll_to_enu(origin, baseline)
    along_e, along_n = math.sin(heading_rad), math.cos(heading_rad)
    along = (bx - rx) * along_e + (by - ry) * along_n
    return enu_to_ll(origin, rx + along * along_e, ry + along * along_n)


def apply_causal_overlay(frames: list[dict], baseline: list[dict], start: int, end: int,
                         graph: Graph, config: RoadAdapterConfig | None = None) -> tuple[list[dict], dict]:
    """Overlay road lat/lon onto deterministic states. GNSS after start is ignored."""
    cfg = config or CONFIGS['adr014_reproduce']
    seed = prefix_seed(frames, start)
    coast = RoadCoast(
        graph, seed, count=cfg.particle_count, heading_sigma=cfg.heading_sigma_rad,
        heading_aware_junction=cfg.heading_aware_junction, deadend_uturn=cfg.deadend_uturn,
        rng_seed=cfg.rng_seed,
    )
    model = fit_prefix_acceleration(frames, start) if cfg.use_acceleration else None
    grouped: dict[int, dict] = {}
    for frame in frames:
        stamp = frame['timestamp_ns']
        if seed['timestamp_ns'] < stamp < end and frame['kind'] in ('accelerometer', 'gyroscope'):
            grouped.setdefault(stamp, {})[frame['kind']] = frame
    estimates: list[tuple[int, Any, Any]] = []
    failure, previous = None, seed['timestamp_ns']
    for stamp, sensors in sorted(grouped.items()):
        gyro = sensors.get('gyroscope')
        rate = None if gyro is None else gyro['payload']['z']
        if gyro and 'gyro_heading_pick_weak' in gyro['quality']['flags']:
            rate = None
        try:
            accel = sensors.get('accelerometer')
            prediction = model.predict(accel['payload']['x'], accel['payload']['y']) if model and accel else 0.0
            coast.step((stamp-previous)/1e9, rate, prediction)
            estimates.append((stamp, coast.estimate(), coast.diagnostics()))
        except ValueError as error:
            failure = {'timestamp_ns': stamp, 'reason': str(error)}
            break
        previous = stamp
    out, index, latest, used = [], 0, None, 0
    reasons: dict[str, int] = {}
    last_diag = None
    for state in baseline:
        stamp = state['timestamp_ns']
        while index < len(estimates) and estimates[index][0] <= stamp:
            latest = estimates[index]
            index += 1
        copied = dict(state)
        if (start < stamp < end and latest is not None and stamp-latest[0] <= 150_000_000
                and (failure is None or stamp < failure['timestamp_ns'])):
            lat, lon, spread = latest[1]
            diag = latest[2]
            last_diag = diag
            base_ll = _baseline_ll(state)
            disagreement = haversine_m((lat, lon), base_ll)
            use_road, reason = decide_overlay(
                diag.raw_spread_m, diag.elapsed_s, diag.dominant_mass, disagreement, cfg,
            )
            reasons[reason] = reasons.get(reason, 0) + 1
            if use_road:
                if cfg.lateral_heal:
                    lat, lon = _lateral_heal(graph.origin, lat, lon, diag.heading_rad, base_ll)
                    reason = 'lateral_heal'
                    reasons[reason] = reasons.get(reason, 0) + 1
                copied['position'] = dict(state['position'], latitude_deg=lat, longitude_deg=lon)
                copied['road_research_spread_m'] = spread
                copied['road_raw_spread_m'] = diag.raw_spread_m
                copied['road_dominant_mass'] = diag.dominant_mass
                copied['road_overlay_reason'] = reason
                used += 1
            else:
                copied['road_overlay_reason'] = reason
        out.append(copied)
    note = {
        'road_states': used,
        'failure': failure,
        'acceleration_model_accepted': model is not None,
        'posterior': 'mean with uncalibrated spread; no lane claim',
        'config': cfg.name,
        'overlay_reasons': reasons,
        'fallback_states': sum(v for k, v in reasons.items() if k != 'road' and k != 'lateral_heal'),
    }
    if last_diag is not None:
        note.update(
            raw_spread_end_m=last_diag.raw_spread_m,
            dominant_mass_end=last_diag.dominant_mass,
            cluster_count_end=last_diag.cluster_count,
            unique_edges_end=last_diag.unique_edges,
        )
    return out, note


def _below_10_count(payload: dict) -> int:
    summary = payload.get('summary') or {}
    if summary.get('below_10_count') is not None:
        return int(summary['below_10_count'])
    return sum(
        (row.get('metrics') or {}).get('drift_ratio', 1.0) < 0.10
        for row in payload.get('per_interval') or []
    )


def development_gate(candidate: dict, baseline: dict, *, interval_count: int) -> dict:
    """Relative development gate recorded before runs. Not the absolute 0.10 target."""
    c, b = candidate['summary'], baseline['summary']
    ratios_ok = (
        c.get('drift_ratio_p50') is not None and b.get('drift_ratio_p50') is not None
        and math.isfinite(c['drift_ratio_p50']) and math.isfinite(b['drift_ratio_p50'])
        and c.get('drift_ratio_p95') is not None and b.get('drift_ratio_p95') is not None
        and math.isfinite(c['drift_ratio_p95']) and math.isfinite(b['drift_ratio_p95'])
    )
    fail_c = interval_count - _below_10_count(candidate)
    fail_b = interval_count - _below_10_count(baseline)
    complete = (not candidate.get('failures') and len(candidate.get('per_interval') or []) == interval_count
                and ratios_ok)
    median_ok = bool(ratios_ok and c['drift_ratio_p50'] <= 0.90 * b['drift_ratio_p50'])
    p95_ok = bool(ratios_ok and c['drift_ratio_p95'] <= b['drift_ratio_p95'])
    fail10_ok = fail_c <= fail_b
    return {
        'rule': 'relative_development_gate_v1',
        'not_the_absolute_0_10_target': True,
        'complete': complete,
        'relative_median_ok': median_ok,
        'p95_ok': p95_ok,
        'fail10_ok': fail10_ok,
        'eligible': bool(complete and median_ok and p95_ok and fail10_ok),
        'candidate_p50': c.get('drift_ratio_p50'),
        'candidate_p95': c.get('drift_ratio_p95'),
        'baseline_p50': b.get('drift_ratio_p50'),
        'baseline_p95': b.get('drift_ratio_p95'),
        'candidate_fail10': fail_c,
        'baseline_fail10': fail_b,
        'locked_confirmation': False,
        'absolute_median_target_passed': False,
        'all_interval_target_passed': False,
    }


def with_heading_sigma(name: str, sigma: float) -> RoadAdapterConfig:
    return replace(CONFIGS[name], heading_sigma_rad=sigma)
