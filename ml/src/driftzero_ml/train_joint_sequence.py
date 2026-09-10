"""Train real-data joint motion on the frozen 24 groups; select on development only."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import random
import shutil
from collections import Counter
from pathlib import Path
from statistics import median

from driftzero_ml.accuracy_gate import diagnostic_only_reasons
from driftzero_ml.datasets.io_vnbd import load_smartphone_csv
from driftzero_ml.development_search import CANDIDATES
from driftzero_ml.eval_iovnbd_blackout import screening_csv_paths
from driftzero_ml.eval_kotlin_replay import (
    SUITE_GATES,
    align_states_to_epochs,
    load_truth_jsonl,
    run,
)
from driftzero_ml.eval_navstate import score_states_against_truth
from driftzero_ml.export_sensorframe import export_sensor_frames
from driftzero_ml.gnss_truth import score_epochs
from driftzero_ml.io_vnbd.splits import session_group_id
from driftzero_ml.joint_sequence import (
    build_sequence,
    local_target,
    position_states,
    raw_gyro_samples,
    validate_roles,
)

SEED = 26168
EPOCHS = 16
START_FRACTIONS = tuple(i/14 for i in range(1, 13))
ARCHITECTURES = ('mlp', 'tcn', 'gru')


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, allow_nan=False)+'\n')


def training_samples(repo: Path, split: dict, out: Path, window_s: int = 90) -> list[dict]:
    allowed = set(split['train_session_groups'])
    samples, audit = [], []
    paths = screening_csv_paths(repo/'data/raw/io_vnbd')
    for path in paths:
        group = session_group_id(path.stem)
        if group not in allowed:
            continue  # No parsing of holdout, locked or development rows here.
        item = {'trip_id': path.stem, 'group': group, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'windows': [], 'excluded_windows': []}
        try:
            rows = load_smartphone_csv(path)
            fixes = []
            last = None
            for row in rows:
                pos = (row.latitude_deg, row.longitude_deg)
                if None not in pos and pos != last:
                    fixes.append(row.timestamp_ns)
                    last = pos
            # Twelve deterministic training starts per trip; outcomes never choose them.
            starts = sorted({fixes[min(len(fixes)-1, int(len(fixes)*f))] for f in START_FRACTIONS}) if fixes else []
            for start in starts:
                try:
                    _, frames = export_sensor_frames(rows, mask_start_ns=start)
                    seq = build_sequence(frames, start, start+window_s*1_000_000_000+1, raw_gyro_samples(rows))
                    truth = {f['timestamp_ns']: f['payload'] for f in frames if f['kind'] == 'gnss_fix'}
                    labels, mask = [], []
                    for stamp in seq.stamps:
                        fix = truth.get(stamp)
                        labels.append(local_target(seq, fix['latitude_deg'], fix['longitude_deg']) if fix else [0., 0.])
                        mask.append(float(fix is not None))
                    if sum(mask) < 3:
                        raise ValueError('fewer than three supervised fixes')
                    # Reject physically impossible training-reference jumps, never evaluation rows.
                    previous = [0., 0.]
                    previous_t = start
                    for stamp, xy, valid in zip(seq.stamps, labels, mask):
                        if valid:
                            if math.dist(xy, previous)/((stamp-previous_t)/1e9) > 55:
                                raise ValueError('training reference implies speed over 55 m/s')
                            previous, previous_t = xy, stamp
                    samples.append({'features': seq.features, 'base': seq.base_velocity, 'dt': seq.dt,
                                    'target': labels, 'mask': mask, 'trip_id': path.stem})
                    item['windows'].append({'start_ns': start, 'samples': len(seq.stamps), 'labels': int(sum(mask))})
                except (ValueError, TypeError) as error:
                    item['excluded_windows'].append({'start_ns': start, 'reason': str(error)})
        except (ValueError, OSError) as error:
            item['error'] = str(error)
        audit.append(item)
        print(json.dumps({'training_trip': path.stem, 'accepted_windows': len(item['windows'])}), flush=True)
    write_json(out/'training_manifest.json', {'split': split, 'sources': audit, 'window_count': len(samples),
                                             'holdout_read': False, 'label_scope': 'training groups only'})
    if len(samples) < 12:
        raise ValueError('insufficient usable training windows')
    return samples


def load_frames(path: Path) -> list[dict]:
    return [row for line in path.read_text().splitlines() if 'kind' in (row := json.loads(line))]


def evaluate(network, base_dir: Path, baseline: dict, out: Path, raw_by_trip: dict) -> dict:
    import torch

    scored, failures = [], list(baseline['failures'])
    network.eval() if network is not None else None
    for row in baseline['per_interval']:
        key = row['interval_id'].replace(':', '_')
        note = None
        try:
            frames = load_frames(base_dir/'frames'/f'{key}.jsonl')
            seq = build_sequence(frames, row['start_ns'], row['end_ns'], raw_by_trip[row['trip_id']])
            if network is None:
                positions, position = [], [0., 0.]
                for velocity, dt in zip(seq.base_velocity, seq.dt):
                    position = [a+b*dt for a, b in zip(position, velocity)]
                    positions.append(position)
            else:
                with torch.no_grad():
                    pred, _ = network(torch.tensor([seq.features]), torch.tensor([seq.base_velocity]),
                                      torch.tensor([seq.dt]))
                positions = pred[0].tolist()
            states = position_states(seq, positions)
            truth = load_truth_jsonl(base_dir/'truth'/f"{row['trip_id']}.jsonl")
            aligned = align_states_to_epochs(states, score_epochs(truth, row['start_ns'], row['end_ns']))
            metrics = score_states_against_truth(aligned, truth, row['start_ns'], row['end_ns'],
                                                 gate=SUITE_GATES[row['interval_id'].split(':')[-1]]).to_dict()
        except (ValueError, OSError, TypeError, RuntimeError) as error:
            metrics = row['metrics']
            note = str(error)  # Explicit entire-interval research fallback, never omit a row.
        scored.append({'interval_id': row['interval_id'], 'metrics': metrics, 'fallback_reason': note})
    ratios = [r['metrics']['drift_ratio'] for r in scored]
    result = {'system': 'joint_sequence', 'failures': failures, 'per_interval': scored,
              'summary': {'count': len(ratios), 'p50': median(ratios),
                          'p95': sorted(ratios)[math.ceil(.95*len(ratios))-1],
                          'fail10': sum(r >= .1 for r in ratios),
                          'fallback_count': sum(r['fallback_reason'] is not None for r in scored)}}
    write_json(out, result)
    return result


def eligible(result: dict, baseline: dict) -> bool:
    r, b = result['summary'], baseline['summary']
    ids = [x['interval_id'] for x in result['per_interval']]
    expected = [x['interval_id'] for x in baseline['per_interval']]
    return (not diagnostic_only_reasons(result) and not baseline['failures'] and not result['failures'] and len(ids) == len(set(ids)) and set(ids) == set(expected)
            and all(math.isfinite(x['metrics']['drift_ratio']) for x in result['per_interval'])
            and r['fallback_count'] == 0  # No retrospective gap fallback can qualify for release.
            and r['p50'] <= .9*b['drift_ratio_p50'] and r['p95'] <= b['drift_ratio_p95']
            and r['fail10'] <= sum(x['metrics']['drift_ratio'] >= .1 for x in baseline['per_interval']))


def main() -> int:
    import torch
    from torch.nn import functional as F

    from driftzero_ml.joint_network import JointNetwork, supervised_motion_loss

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--out', type=Path, default=Path('results/joint_sequence_polar'))
    parser.add_argument('--window-s', type=int, default=90)
    parser.add_argument('--epochs', type=int, default=EPOCHS)
    parser.add_argument('--checkpoint-every', type=int, default=4)
    parser.add_argument('--architectures', nargs='+', choices=(*ARCHITECTURES, 'tcn_long'), default=ARCHITECTURES)
    parser.add_argument('--increment-weight', type=float, default=0.0)
    parser.add_argument('--tail-weight', type=float, default=0.0)
    parser.add_argument('--cached-run', type=Path)
    parser.add_argument('--projected-only', action='store_true')
    parser.add_argument('--head-mode', choices=('polar', 'integrated'), default='polar')
    args = parser.parse_args()
    if args.cached_run and not args.projected_only:
        parser.error('cached replay requires explicit projected-only inputs')
    if not (1 <= args.window_s <= 300 and 1 <= args.epochs <= 128 and 1 <= args.checkpoint_every <= args.epochs
            and 0 <= args.increment_weight <= 10 and 0 <= args.tail_weight <= 1):
        parser.error('invalid bounded experiment parameters')
    repo, out = args.repo.resolve(), args.repo.resolve()/args.out
    split_path = repo/'results/cursor_diagnostics/split_manifest.json'
    split = json.loads(split_path.read_text())
    validate_roles(split)
    torch.set_num_threads(2)
    torch.manual_seed(SEED)
    random.seed(SEED)
    torch.use_deterministic_algorithms(True)
    write_json(out/'preregistration.json', {'seed': SEED, 'architectures': args.architectures, 'epochs': args.epochs,
               'batch_size': 8, 'learning_rate': .001, 'training_window_s': args.window_s, 'starts': START_FRACTIONS, 'variant': 'polar_configurable_v4', 'head_mode': args.head_mode, 'projected_only': args.projected_only, 'loss_weighting': 'equal session groups and per-window labels',
               'increment_weight': args.increment_weight, 'tail_weight': args.tail_weight, 'checkpoint_every': args.checkpoint_every, 'selection': 'complete, zero fallback, median 10% relative gain, p95 and fail10 no worse',
               'split_sha256': hashlib.sha256(split_path.read_bytes()).hexdigest(),
               'torch': torch.__version__, 'fresh_holdout_open': False, 'phone_changed': False})
    base_dir = out/'baseline'
    if args.cached_run:
        cache = args.cached_run.resolve()
        manifest = json.loads((cache/'training_manifest.json').read_text())
        prereg = json.loads((cache/'preregistration.json').read_text())
        if manifest['split'] != split or prereg['training_window_s'] != args.window_s:
            raise ValueError('cached split or training horizon differs')
        samples = torch.load(cache/'training_samples.pt', weights_only=True)
        if any(session_group_id(s['trip_id']) not in split['train_session_groups'] for s in samples):
            raise ValueError('cache contains a non-training group')
        manifest['cache_sample_sha256'] = hashlib.sha256((cache/'training_samples.pt').read_bytes()).hexdigest()
        manifest['cache_code_commit'] = (cache/'code_commit.txt').read_text().strip()
        manifest['projected_only'] = True
        write_json(out/'training_manifest.json', manifest)
        shutil.copytree(cache/'baseline', base_dir, dirs_exist_ok=True)
        baseline = json.loads((base_dir/'metrics_latch_sparse_reseed.json').read_text())
        raw_by_trip = {r['trip_id']: {} for r in baseline['per_interval']}
    else:
        samples = training_samples(repo, split, out, args.window_s)
        ids = json.loads((repo/'results/accuracy_v3_20260906/development/manifest.json').read_text())['interval_ids']
        baseline = run(repo, base_dir, system='latch_sparse_reseed', reexport=True, rerun=True,
                       coast_mode='yaw_speed_hold', extra_replay_args=CANDIDATES['latch_sparse_reseed'],
                       skip_sensitivity=True, development_interval_ids=ids)
        tables = {p.stem: p for p in screening_csv_paths(repo/'data/raw/io_vnbd')}
        raw_by_trip = {trip: raw_gyro_samples(load_smartphone_csv(tables[trip]))
                       for trip in sorted({r['trip_id'] for r in baseline['per_interval']})}
    expected_ids = json.loads((repo/'results/accuracy_v3_20260906/development/manifest.json').read_text())['interval_ids']
    actual_ids = [r['interval_id'] for r in baseline['per_interval']]
    if len(actual_ids) != len(set(actual_ids)) or set(actual_ids) != set(expected_ids):
        raise ValueError('baseline differs from frozen development intervals')
    if args.projected_only:
        for sample in samples:
            sample['features'] = [vector[:12]+[0., 0., 0., 0.] for vector in sample['features']]
        raw_by_trip = {trip: {} for trip in raw_by_trip}
    torch.save(samples, out/'training_samples.pt')
    candidates = {}
    candidates['seed_gyro'] = evaluate(None, base_dir, baseline, out/'seed_gyro.json', raw_by_trip)
    group_counts = Counter(session_group_id(s['trip_id']) for s in samples)
    all_features = torch.tensor([f for s in samples for f in s['features']])
    for kind in args.architectures:
        torch.manual_seed(SEED)
        model = JointNetwork(kind, head_mode=args.head_mode)
        model.mean.copy_(all_features.mean(0))
        model.scale.copy_(all_features.std(0).clamp_min(.1))
        optimizer = torch.optim.Adam(model.parameters(), lr=.001)
        print(json.dumps({'architecture': kind, 'parameters': sum(p.numel() for p in model.parameters())}), flush=True)
        for epoch in range(1, args.epochs+1):
            model.train()
            order = list(range(len(samples)))
            random.Random(SEED+epoch).shuffle(order)
            losses = []
            for offset in range(0, len(order), 8):
                batch = [samples[i] for i in order[offset:offset+8]]
                maximum = max(len(s['dt']) for s in batch)
                def padded(key, width=None, batch=batch, maximum=maximum):
                    tensors = []
                    for sample in batch:
                        t = torch.tensor(sample[key], dtype=torch.float32)
                        tensors.append(F.pad(t, (0, 0, 0, maximum-len(t))) if width else F.pad(t, (0, maximum-len(t))))
                    return torch.stack(tensors)
                features, base, dt = padded('features', 12), padded('base', 2), padded('dt')
                target, mask = padded('target', 2), padded('mask')
                pred, sigma = model(features, base, dt)
                per_window = supervised_motion_loss(pred, sigma, target, mask, dt, args.increment_weight)
                weights = torch.tensor([len(samples)/(len(group_counts)*group_counts[session_group_id(s['trip_id'])]) for s in batch])
                weighted = per_window*weights
                tail = weighted.topk(max(1, math.ceil(len(weighted)/4))).values.mean()
                loss = (1-args.tail_weight)*weighted.mean()+args.tail_weight*tail
                optimizer.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.)
                optimizer.step()
                losses.append(float(loss.detach()))
            print(json.dumps({'architecture': kind, 'epoch': epoch, 'loss': sum(losses)/len(losses)}), flush=True)
            if epoch % args.checkpoint_every == 0 or epoch == args.epochs:
                name = f'{kind}_{epoch}'
                torch.save({'kind': kind, 'head_mode': model.head_mode, 'state_dict': model.state_dict()}, out/f'{name}.pt')
                candidates[name] = evaluate(model, base_dir, baseline, out/f'{name}.json', raw_by_trip)
                print(json.dumps({'candidate': name, **candidates[name]['summary']}), flush=True)
    accepted = [name for name, result in candidates.items() if eligible(result, baseline)]
    selected = min(accepted, key=lambda n: (candidates[n]['summary']['fail10'], candidates[n]['summary']['p95'],
                                          candidates[n]['summary']['p50'], n)) if accepted else None
    decision = {'selected': selected, 'candidates': {n: p['summary'] for n, p in candidates.items()},
                'locked_confirmation': False, 'fresh_holdout_open': False,
                'status': 'eligible_pending_confirmation' if selected else 'rejected',
                'note': 'Selection is frozen before any locked candidate confirmation; no phone rollout.'}
    if selected and selected != 'seed_gyro':
        decision['checkpoint_sha256'] = hashlib.sha256((out/f'{selected}.pt').read_bytes()).hexdigest()
    write_json(out/'selection.json', decision)
    with (out/'metrics.csv').open('w', newline='') as handle:
        writer = csv.writer(handle)
        writer.writerow(['candidate', 'interval_id', 'endpoint_error_m', 'truth_path_length_m', 'drift_ratio', 'fallback'])
        for name, payload in candidates.items():
            for row in payload['per_interval']:
                m = row['metrics']
                writer.writerow([name, row['interval_id'], m['endpoint_error_m'], m['truth_path_length_m'],
                                 m['drift_ratio'], row['fallback_reason']])
    print(json.dumps(decision), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
