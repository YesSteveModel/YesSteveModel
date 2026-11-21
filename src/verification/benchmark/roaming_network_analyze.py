"""Summarize retained roaming samples without treating short forks as steady-state proof."""
import argparse
import csv
import json
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    root = args.directory.resolve()
    manifest = json.loads((root / 'measure-manifest.json').read_text())
    if not manifest.get('complete'):
        raise ValueError('The campaign is incomplete; raw partial results remain available')
    cells = {}
    diagnostics = []
    for run in manifest['runs']:
        if run.get('exitCode') != 0:
            raise ValueError('A measurement failed: ' + run['label'])
        for value in json.loads((root / (run['label'] + '.json')).read_text()):
            method = value['benchmark'].rsplit('.', 1)[1]
            allocation = value['secondaryMetrics']['gc.alloc.rate.norm']['score']
            item = {'ns': value['primaryMetric']['score'], 'bytesPerOp': allocation,
                    'rawNs': value['primaryMetric']['rawData'],
                    'rawBytesPerOp': value['secondaryMetrics']['gc.alloc.rate.norm']['rawData'], 'source': run['label']}
            if method == 'diagnostics':
                diagnostics.append(dict(item, enabled=run['debug']))
                continue
            key = (method, int(value['params']['fields']), value['params']['opportunity'], run['version'])
            cells.setdefault(key, []).append(item)
    rows = []
    for method, fields, opportunity, version in sorted(cells):
        if version != 'legacy':
            continue
        old = cells[method, fields, opportunity, 'legacy']
        new = cells[method, fields, opportunity, 'candidate']
        if len(old) != 2 or len(new) != 2:
            raise ValueError('Each cell requires two independent forks per version')
        rows.append({'method': method, 'fields': fields, 'opportunity': opportunity,
                     'legacyNs': statistics.mean(v['ns'] for v in old),
                     'candidateNs': statistics.mean(v['ns'] for v in new),
                     'legacyBytesPerOp': statistics.mean(v['bytesPerOp'] for v in old),
                     'candidateBytesPerOp': statistics.mean(v['bytesPerOp'] for v in new),
                     'legacyForks': old, 'candidateForks': new})
    if len(rows) != 80:
        raise ValueError(f'Expected 80 cells, received {len(rows)}')
    oracle = {version: json.loads((root / f'{version}-oracle.json').read_text()) for version in ['legacy', 'candidate']}
    single = []
    fanout = []
    for before, after in zip(oracle['legacy'], oracle['candidate']):
        single.append({'fields': after['fields'], 'opportunity': after['opportunity'], 'nameBytes': after['nameBytes'],
                       'legacyProto': before['protobufBytes'], 'candidateProto': after['protobufBytes'],
                       'legacyFrame': before['frameBytes'], 'candidateFrame': after['frameBytes'],
                       'legacyRoaming': before['roamingBytes'], 'candidateRoaming': after['roamingBytes']})
    for fields in [1, 8, 32, 64]:
        byte_rows = {v['opportunity']: v for v in single if v['fields'] == fields and v['nameBytes'] == 12}
        for n in [1, 20, 100]:
            for t in [0, 1, 20]:
                fanout.append({'fields': fields, 'N': n, 'T': t,
                               'candidateSelectionChange': n * byte_rows['selection']['candidateFrame'],
                               'candidateSparseDelta': n * byte_rows['sparse']['candidateFrame'],
                               'candidateFullRoamingDelta': n * byte_rows['full']['candidateFrame'],
                               'candidateTrack': t * byte_rows['track']['candidateFrame'],
                               'legacySelectionAtEqualRecipients': n * byte_rows['selection']['legacyFrame'],
                               'legacySparseDeltaAtEqualRecipients': n * byte_rows['sparse']['legacyFrame'],
                               'legacyTrack': t * byte_rows['track']['legacyFrame'],
                               'legacyRecipientsPerCandidateRecipientBreakEvenForSparse': byte_rows['sparse']['candidateFrame'] / byte_rows['sparse']['legacyFrame']})
    calibration = [json.loads(p.read_text())[0]['primaryMetric']['score'] for p in sorted(root.glob('legacy-calibration-*.json'))]
    result = {'complete': True, 'cells': rows, 'bytes': single, 'fanout': fanout, 'diagnostics': diagnostics,
              'calibrationNs': calibration, 'samplingSecondsIncludingPilot': manifest['wallSeconds'],
              'limits': ['Short warmup and two independent forks do not prove steady-state CPU or a confidence interval.',
                         'Fanout is inferred from measured frames, not a multiplayer network measurement.',
                         'Legacy visible recipients R may differ from candidate all-ready recipients N.',
                         'Empty delta is a codec opportunity; the production planner may suppress transmission.',
                         'Native allocation and whole-game FPS/MSPT are not measured.']}
    (root / 'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    with (root / 'summary.csv').open('w', newline='', encoding='utf-8') as stream:
        columns = ['method', 'fields', 'opportunity', 'legacyNs', 'candidateNs', 'legacyBytesPerOp', 'candidateBytesPerOp']
        writer = csv.DictWriter(stream, fieldnames=columns, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({'cells': len(rows), 'byteRows': len(single), 'fanoutRows': len(fanout),
                      'samplingSecondsIncludingPilot': manifest['wallSeconds']}, indent=2))


if __name__ == '__main__':
    main()
