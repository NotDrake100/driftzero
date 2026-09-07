"""Causal joint displacement inputs. GNSS after the seed never enters features."""
from __future__ import annotations

import math
from dataclasses import dataclass

from driftzero_ml.datasets.io_vnbd import trim_nondecreasing_rows
from driftzero_ml.gnss_truth import seed_heading_rad
from driftzero_ml.osm_coast import prefix_seed
from driftzero_ml.road_particle import enu_to_ll, ll_to_enu

FEATURES = ('ax_g', 'ay_g', 'az_g', 'gyro_up', 'dt_s', 'age_90s', 'seed_speed_30mps',
            'relative_heading_sin', 'relative_heading_cos', 'gyro_available', 'gyro_strong',
            'accel_available', 'raw_gyro_yaw', 'raw_gyro_pitch', 'raw_gyro_roll', 'raw_gyro_available')


@dataclass
class SequenceInput:
    stamps: list[int]
    features: list[list[float]]
    dt: list[float]
    base_velocity: list[list[float]]
    origin: tuple[float, float]
    heading: float
    seed_speed: float
    start_ns: int


def build_sequence(frames: list[dict], start: int, end: int,
                   raw_gyro: dict[int, tuple[float, float, float]] | None = None) -> SequenceInput:
    seed = prefix_seed(frames, start)
    if seed['timestamp_ns'] != start:
        raise ValueError('research sequence requires a fresh fix exactly at mask start')
    history = [dict(f['payload'], gnss_bearing_rad=f['payload'].get('bearing_rad'))
               for f in frames if f['kind'] == 'gnss_fix' and f['timestamp_ns'] <= start]
    heading = seed_heading_rad(history)
    if heading is None:
        raise ValueError('no causal seed heading')
    origin = (seed['latitude_deg'], seed['longitude_deg'])
    grouped: dict[int, dict] = {}
    for f in frames:
        if start < f['timestamp_ns'] < end and f['kind'] in ('accelerometer', 'gyroscope'):
            grouped.setdefault(f['timestamp_ns'], {})[f['kind']] = f
    features, stamps, dts, velocities = [], [], [], []
    previous, relative = start, 0.0
    for stamp, sensors in sorted(grouped.items()):
        dt = (stamp-previous)/1e9
        if not 0 < dt <= .4:
            raise ValueError('IMU gap or invalid timestep')
        accel, gyro = sensors.get('accelerometer'), sensors.get('gyroscope')
        strong = gyro is not None and 'gyro_heading_pick_weak' not in gyro['quality']['flags']
        observed_rate = gyro['payload']['z'] if gyro is not None else 0.0
        rate = observed_rate if strong else 0.0
        relative -= rate*dt
        # Zero placeholders are paired with explicit availability channels.
        axes = [accel['payload'][axis]/9.80665 if accel else 0.0 for axis in ('x', 'y', 'z')]
        raw = None if raw_gyro is None else raw_gyro.get(stamp)
        vector = [*axes, observed_rate, dt, (stamp-start)/90e9, seed['speed_mps']/30,
                  math.sin(relative), math.cos(relative), float(gyro is not None),
                  float(strong), float(accel is not None),
                  *(raw if raw is not None else (0.0, 0.0, 0.0)), float(raw is not None)]
        if not all(math.isfinite(v) for v in vector):
            raise ValueError('nonfinite input')
        features.append(vector)
        stamps.append(stamp)
        dts.append(dt)
        velocities.append([seed['speed_mps']*math.sin(relative), seed['speed_mps']*math.cos(relative)])
        previous = stamp
    if not stamps:
        raise ValueError('no IMU sequence')
    return SequenceInput(stamps, features, dts, velocities, origin, heading, seed['speed_mps'], start)


def local_target(sequence: SequenceInput, lat: float, lon: float) -> list[float]:
    east, north = ll_to_enu(sequence.origin, (lat, lon))
    s, c = math.sin(sequence.heading), math.cos(sequence.heading)
    return [east*c-north*s, east*s+north*c]


def position_states(sequence: SequenceInput, positions: list[list[float]]) -> list[dict]:
    if len(positions) != len(sequence.stamps):
        raise ValueError('prediction length differs from sequence')
    out = [{'timestamp_ns': sequence.start_ns, 'position': {'latitude_deg': sequence.origin[0],
                                                         'longitude_deg': sequence.origin[1]}}]
    s, c = math.sin(sequence.heading), math.cos(sequence.heading)
    for stamp, (right, forward) in zip(sequence.stamps, positions):
        if not math.isfinite(right) or not math.isfinite(forward):
            raise ValueError('nonfinite prediction')
        lat, lon = enu_to_ll(sequence.origin, right*c+forward*s, -right*s+forward*c)
        out.append({'timestamp_ns': stamp, 'position': {'latitude_deg': lat, 'longitude_deg': lon}})
    return out


def validate_roles(manifest: dict) -> None:
    names = ('train_session_groups', 'development_session_groups', 'fresh_holdout_session_groups',
             'locked_session_groups', 'excluded_session_groups')
    seen: set[str] = set()
    for name in names:
        values = manifest[name]
        if len(values) != len(set(values)) or seen.intersection(values):
            raise ValueError('split roles overlap or contain duplicates')
        seen.update(values)
    if len(manifest['train_session_groups']) != 24 or len(manifest['fresh_holdout_session_groups']) != 10:
        raise ValueError('frozen split cardinality changed')


def raw_gyro_samples(rows) -> dict[int, tuple[float, float, float]]:
    """Extract only timestamp and published gyro columns; no GNSS values retained."""
    out = {}
    ordered, _ = trim_nondecreasing_rows(rows)
    for row in ordered:
        values = (row.gyro_yaw, row.gyro_pitch, row.gyro_roll)
        if all(v is not None and math.isfinite(v) for v in values):
            out[row.timestamp_ns] = tuple(float(v) for v in values)
    return out
