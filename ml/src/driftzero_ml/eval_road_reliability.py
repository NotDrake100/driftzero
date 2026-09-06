"""Development-only road reliability ablations. Never scores locked intervals."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from driftzero_ml.eval_osm_coast import evaluate
from driftzero_ml.osm_coast import Graph, acquire_map, graph_audit, prefix_seed
from driftzero_ml.road_adapter import CONFIGS, development_gate


def development_ids(repo: Path) -> list[str]:
    manifest = json.loads((repo / 'results/accuracy_v3_20260906/development/manifest.json').read_text())
    return list(manifest['interval_ids'])


def write_activation(directory: Path, payload: dict) -> None:
    path = directory / 'activation.csv'
    with path.open('w', newline='') as handle:
        writer = csv.writer(handle)
        writer.writerow([
            'interval_id', 'drift_ratio', 'road_states', 'fallback_states',
            'overlay_reasons', 'fallback', 'map_sha256', 'segment_count',
            'raw_spread_end_m', 'dominant_mass_end',
        ])
        for row in payload['per_interval']:
            note = row['road']
            writer.writerow([
                row['interval_id'], row['metrics']['drift_ratio'], note.get('road_states'),
                note.get('fallback_states'), json.dumps(note.get('overlay_reasons') or {}, sort_keys=True),
                note.get('fallback_reason') or note.get('failure'), note.get('map_sha256'),
                note.get('segment_count'), note.get('raw_spread_end_m'), note.get('dominant_mass_end'),
            ])


def write_audits(repo: Path, directory: Path, ids: list[str], payload: dict) -> None:
    audits = []
    for row, scored in zip(ids, payload['per_interval']):
        if scored['road'].get('fallback_reason') and not scored['road'].get('map_sha256'):
            continue
        key = row.replace(':', '_')
        frames = [json.loads(line) for line in
                  (directory / 'baseline' / 'frames' / f'{key}.jsonl').read_text().splitlines()
                  if line.strip()]
        frames = [f for f in frames if 'kind' in f]
        try:
            seed = prefix_seed(frames, scored['start_ns'])
            osm, meta = acquire_map(seed, repo / 'results/road_coast/maps', download=False)
            graph = Graph(osm, (seed['latitude_deg'], seed['longitude_deg']))
            audits.append({'interval_id': row, 'map_sha256': meta['sha256'], **graph_audit(graph, osm)})
        except (OSError, ValueError, RuntimeError) as error:
            audits.append({'interval_id': row, 'error': str(error)})
    (directory / 'graph_audit.json').write_text(json.dumps(audits, indent=2) + '\n')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download-maps', action='store_true')
    parser.add_argument('--reuse-baseline', action='store_true')
    parser.add_argument('--only')
    parser.add_argument('--out', type=Path, default=Path('results/road_reliability_20260906'))
    args = parser.parse_args()
    repo = Path.cwd()
    ids = development_ids(repo)
    names = [args.only] if args.only else list(CONFIGS)
    if any(name not in CONFIGS for name in names):
        raise SystemExit('unknown config; preregistered names are ' + ','.join(CONFIGS))
    root = repo / args.out
    root.mkdir(parents=True, exist_ok=True)
    comparison = {
        'scope': 'development only. Locked confirmation was not run.',
        'interval_ids': ids,
        'configs': {},
        'eligible': [],
    }
    baseline_metrics = None
    for index, name in enumerate(names):
        cfg = CONFIGS[name]
        out = root / name
        reuse = args.reuse_baseline or index > 0
        if reuse and not (root / names[0] / 'baseline' / 'metrics_latch_sparse_reseed.json').exists():
            reuse = args.reuse_baseline
        if index > 0:
            shared = root / names[0] / 'baseline'
            out.mkdir(parents=True, exist_ok=True)
            if not (out / 'baseline').exists() and shared.exists():
                (out / 'baseline').symlink_to(shared.resolve(), target_is_directory=True)
            reuse = True
        payload = evaluate(
            repo, out, ids, cfg.heading_sigma_rad, download=args.download_maps,
            reuse_baseline=reuse, config=cfg,
        )
        write_activation(out, payload)
        if index == 0:
            try:
                write_audits(repo, out, ids, payload)
            except FileNotFoundError:
                pass
            baseline_metrics = json.loads(
                (out / 'baseline' / 'metrics_latch_sparse_reseed.json').read_text()
            )
        gate = development_gate(payload, baseline_metrics, interval_count=len(ids))
        (out / 'gate.json').write_text(json.dumps(gate, indent=2) + '\n')
        comparison['configs'][name] = {
            'summary': payload['summary'],
            'gate': gate,
            'activation': {
                'road_covered_intervals': payload['summary'].get('road_covered_intervals'),
                'overlay_reasons': _merge_reasons(payload),
            },
        }
        if gate['eligible']:
            comparison['eligible'].append(name)
        print(json.dumps({'config': name, **payload['summary'], 'eligible': gate['eligible']}), flush=True)
    comparison['decision'] = _decision(comparison)
    (root / 'comparison.json').write_text(json.dumps(comparison, indent=2) + '\n')
    (root / 'FOR_TASK_D.json').write_text(json.dumps(_for_task_d(comparison, root), indent=2) + '\n')
    return 0


def _merge_reasons(payload: dict) -> dict[str, int]:
    merged: dict[str, int] = {}
    for row in payload['per_interval']:
        for key, value in (row['road'].get('overlay_reasons') or {}).items():
            merged[key] = merged.get(key, 0) + int(value)
    return merged


def _decision(comparison: dict) -> dict:
    eligible = comparison['eligible']
    if not eligible:
        return {
            'status': 'rejected',
            'reason': 'no preregistered config met the relative development gate',
            'locked_confirmation': False,
            'android_defaults': False,
        }
    chosen = eligible[0]
    return {
        'status': 'development_eligible',
        'config': chosen,
        'locked_confirmation': False,
        'android_defaults': False,
        'absolute_median_target_passed': False,
        'note': 'Relative gate only. D must freeze and run locked confirmation.',
    }


def _for_task_d(comparison: dict, root: Path) -> dict:
    decision = comparison['decision']
    if decision['status'] != 'development_eligible':
        return {
            'status': 'rejection',
            'task_b_owns': ['osm_coast.py', 'road_adapter.py', 'eval_road_reliability.py'],
            'do_not_run_locked': True,
            'comparison': str(root / 'comparison.json'),
            'reason': decision['reason'],
            'attempted': list(comparison['configs']),
        }
    name = decision['config']
    return {
        'status': 'frozen_development_candidate',
        'config': name,
        'adapter': f'driftzero_ml.road_adapter.CONFIGS[{name!r}]',
        'seed': 26168,
        'heading_sigma_rad': CONFIGS[name].heading_sigma_rad,
        'reproduction': (
            'PYTHONPATH=ml/src python -m driftzero_ml.eval_road_reliability '
            f'--reuse-baseline --only {name}'
        ),
        'do_not_run_locked_from_task_b': True,
        'android_defaults': False,
        'relative_gate_only': True,
        'comparison': str(root / 'comparison.json'),
    }


if __name__ == '__main__':
    raise SystemExit(main())
