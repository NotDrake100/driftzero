"""Real-map research evaluation with unchanged IO-VNBD epochs and deterministic fallback."""
from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from statistics import median

from driftzero_ml.development_search import CANDIDATES
from driftzero_ml.eval_kotlin_replay import (
    SUITE_GATES,
    align_states_to_epochs,
    load_truth_jsonl,
    run,
)
from driftzero_ml.eval_navstate import score_states_against_truth
from driftzero_ml.gnss_truth import score_epochs
from driftzero_ml.osm_coast import Graph, RoadCoast, acquire_map, prefix_seed


def infer(frames: list[dict], baseline: list[dict], start: int, end: int,
          graph: Graph, sigma: float) -> tuple[list[dict], dict]:
    seed = prefix_seed(frames, start)
    coast = RoadCoast(graph, seed, heading_sigma=sigma)
    # Strip all unavailable GNSS before inference. Only IMU timestamps drive propagation.
    grouped: dict[int, dict] = {}
    for frame in frames:
        stamp = frame['timestamp_ns']
        if seed['timestamp_ns'] < stamp < end and frame['kind'] in ('accelerometer', 'gyroscope'):
            grouped.setdefault(stamp, {})[frame['kind']] = frame
    estimates, failure, previous = [], None, seed['timestamp_ns']
    for stamp, sensors in sorted(grouped.items()):
        gyro = sensors.get('gyroscope')
        rate = None if gyro is None else gyro['payload']['z']
        if gyro and 'gyro_heading_pick_weak' in gyro['quality']['flags']:
            rate = None  # Same conservative course hold as selected deterministic coast.
        try:
            coast.step((stamp-previous)/1e9, rate)
            estimates.append((stamp, coast.estimate()))
        except ValueError as error:
            failure = {'timestamp_ns': stamp, 'reason': str(error)}
            break
        previous = stamp
    out, index, latest, used = [], 0, None, 0
    for state in baseline:
        stamp = state['timestamp_ns']
        while index < len(estimates) and estimates[index][0] <= stamp:
            latest = estimates[index]
            index += 1
        copied = dict(state)
        if (start < stamp < end and latest is not None and stamp-latest[0] <= 150_000_000
                and (failure is None or stamp < failure['timestamp_ns'])):
            lat, lon, spread = latest[1]
            copied['position'] = dict(state['position'], latitude_deg=lat, longitude_deg=lon)
            copied['road_research_spread_m'] = spread
            used += 1
        out.append(copied)
    return out, {'road_states': used, 'failure': failure, 'posterior': 'mean with uncalibrated spread; no lane claim'}


def evaluate(repo: Path, directory: Path, ids: list[str] | None, sigma: float,
             *, download: bool) -> dict:
    base = directory / 'baseline'
    payload = run(repo, base, system='latch_sparse_reseed', reexport=True, rerun=True,
                  coast_mode='yaw_speed_hold', extra_replay_args=CANDIDATES['latch_sparse_reseed'],
                  skip_sensitivity=True, development_interval_ids=ids)
    scored, failures = [], list(payload['failures'])
    for row in payload['per_interval']:
        key = row['interval_id'].replace(':', '_')
        frames = [json.loads(line) for line in (base / 'frames' / f'{key}.jsonl').read_text().splitlines()]
        frames = [f for f in frames if 'kind' in f]
        states = [json.loads(line) for line in (base / 'states' / f'{key}.jsonl').read_text().splitlines()]
        start, end = row['start_ns'], row['end_ns']
        note = {}
        try:
            seed = prefix_seed(frames, start)
            osm, meta = acquire_map(seed, repo / 'results/road_coast/maps', download=download)
            graph = Graph(osm, (seed['latitude_deg'], seed['longitude_deg']))
            states, note = infer(frames, states, start, end, graph, sigma)
            note.update(map_sha256=meta['sha256'], segment_count=len(graph.edges))
        except (OSError, ValueError, RuntimeError) as error:
            note = {'road_states': 0, 'fallback_reason': str(error)}
        truth = load_truth_jsonl(base / 'truth' / f"{row['trip_id']}.jsonl")
        epochs = score_epochs(truth, start, end)
        metrics = score_states_against_truth(align_states_to_epochs(states, epochs), truth, start, end,
                                             gate=SUITE_GATES[row['interval_id'].split(':')[-1]])
        result = dict(row, metrics=metrics.to_dict(), road=note)
        scored.append(result)
        state_dir = directory / 'road_states'
        state_dir.mkdir(exist_ok=True)
        (state_dir / f'{key}.jsonl').write_text(''.join(json.dumps(s)+'\n' for s in states if start <= s['timestamp_ns'] < end))
        print(json.dumps({'interval_id': row['interval_id'], 'drift': metrics.drift_ratio, **note}), flush=True)
    ratios = [r['metrics']['drift_ratio'] for r in scored]
    summary = {'interval_count': len(scored), 'drift_ratio_p50': median(ratios) if ratios else None,
               'drift_ratio_p95': sorted(ratios)[max(0, math.ceil(.95*len(ratios))-1)] if ratios else None,
               'below_10_count': sum(r < .1 for r in ratios),
               'road_covered_intervals': sum(r['road']['road_states'] > 0 for r in scored)}
    result = {'system': 'osm_particle_research', 'heading_sigma_rad': sigma, 'seed': 26168,
              'summary': summary, 'failures': failures, 'per_interval': scored,
              'scope': 'Research only. Map failure retains deterministic baseline in denominator.'}
    (directory / 'metrics.json').write_text(json.dumps(result, indent=2)+'\n')
    with (directory / 'metrics.csv').open('w', newline='') as handle:
        writer = csv.writer(handle)
        writer.writerow(['interval_id', 'endpoint_error_m', 'truth_path_length_m', 'drift_ratio', 'road_states', 'fallback'])
        for row in scored:
            m, n = row['metrics'], row['road']
            writer.writerow([row['interval_id'], m['endpoint_error_m'], m['truth_path_length_m'], m['drift_ratio'],
                             n['road_states'], n.get('fallback_reason') or n.get('failure')])
    print(json.dumps(summary), flush=True)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download-maps', action='store_true')
    args = parser.parse_args()
    repo = Path.cwd()
    ids = json.loads((repo / 'results/accuracy_v3_20260906/development/manifest.json').read_text())['interval_ids']
    out = repo / 'results/road_coast'
    # One preregistered candidate, fixed before development outcomes are opened.
    dev = evaluate(repo, out / 'development', ids, .4, download=args.download_maps)
    base = json.loads((out / 'development/baseline/metrics_latch_sparse_reseed.json').read_text())
    eligible = (not dev['failures'] and len(dev['per_interval']) == len(ids)
                and dev['summary']['road_covered_intervals'] == len(ids)
                and dev['summary']['drift_ratio_p50'] <= .9*base['summary']['drift_ratio_p50']
                and dev['summary']['drift_ratio_p95'] <= base['summary']['drift_ratio_p95'])
    (out / 'selection.json').write_text(json.dumps({'eligible': eligible, 'heading_sigma_rad': .4,
                                                  'locked_confirmation': eligible}, indent=2)+'\n')
    if eligible:
        evaluate(repo, out / 'locked', None, .4, download=args.download_maps)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
