"""Run the named roaming oracle and bounded, independently forked JMH comparisons."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import time


def digest(path):
    path = Path(path)
    if path.is_file():
        return hashlib.sha256(path.read_bytes()).hexdigest()
    result = hashlib.sha256()
    if path.is_dir():
        for file in sorted(p for p in path.rglob('*') if p.is_file()):
            result.update(file.relative_to(path).as_posix().encode())
            result.update(bytes.fromhex(digest(file)))
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--legacy-launch', type=Path, required=True)
    parser.add_argument('--candidate-launch', type=Path, default=Path('build/benchmark/launch.json'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['oracle', 'pilot', 'measure'], default='oracle')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    launches = {version: json.loads(path.read_text(encoding='utf-8-sig'))
                for version, path in [('legacy', args.legacy_launch), ('candidate', args.candidate_launch)]}
    env = os.environ.copy()
    env.pop('YSM_DEBUG_NETWORK', None)
    manifest = {'phase': args.phase, 'created': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'platform': platform.platform(), 'processor': platform.processor(), 'launches': launches,
                'hashes': {version: {p: digest(p) for p in launch['classpath'] + [launch['nativeLibrary'], launch['java']]}
                           for version, launch in launches.items()}, 'runs': []}
    manifest_path = output / f'{args.phase}-manifest.json'
    start = time.monotonic()

    def save():
        manifest['wallSeconds'] = time.monotonic() - start
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

    def run(version, label, main_class, parameters, debug=False):
        launch = launches[version]
        command = ['-Dfile.encoding=UTF-8', '-Xms256m', '-Xmx512m', '-cp', ';'.join(launch['classpath']), main_class] + parameters
        argfile = output / f'{label}.args'
        argfile.write_text('\n'.join('"' + p.replace('\\', '/') + '"' for p in command), encoding='utf-8')
        local_env = env.copy()
        local_env['YSM_NATIVE_PATH'] = launch['nativeLibrary']
        if debug:
            local_env['YSM_DEBUG_NETWORK'] = '1'
        before = time.monotonic()
        row = {'version': version, 'label': label, 'arguments': command, 'debug': debug}
        manifest['runs'].append(row)
        save()
        remaining = 1200 - (time.monotonic() - start) if args.phase == 'measure' else 300
        if remaining <= 0:
            raise TimeoutError('Sampling budget exhausted; retained partial evidence')
        with (output / f'{label}.log').open('wb') as log:
            try:
                process = subprocess.run([launch['java'], '@' + str(argfile)], env=local_env, stdout=log,
                                         stderr=subprocess.STDOUT, timeout=remaining)
                row['exitCode'] = process.returncode
            finally:
                row['wallSeconds'] = time.monotonic() - before
                save()
        if process.returncode:
            raise RuntimeError(f'{label} failed; see retained log')
        print(f'{label}: passed ({row["wallSeconds"]:.2f}s)', flush=True)

    def sample(version, label, method, fields, opportunities, warm='200ms', measurement='300ms', iterations='2', debug=False):
        run(version, label, launches[version]['main'], [
            '.*RoamingNetworkBenchmark.' + method + '$', '-p', 'fields=' + str(fields), '-p', 'opportunity=' + opportunities,
            '-f', '1', '-wi', '1', '-i', iterations, '-w', warm, '-r', measurement, '-prof', 'gc',
            '-foe', 'true', '-rf', 'json', '-rff', str(output / f'{label}.json')], debug)

    if args.phase == 'oracle':
        for version in launches:
            run(version, version + '-oracle', 'com.elfmcys.ysm.benchmark.RoamingNetworkBenchmark',
                [str(output / f'{version}-oracle.json')])
        old = json.loads((output / 'legacy-oracle.json').read_text())
        new = json.loads((output / 'candidate-oracle.json').read_text())
        if len(old) != 40 or len(new) != 48:
            raise ValueError('Incomplete oracle matrix')
        candidate = {(row['fields'], row['nameBytes'], row['opportunity']): row for row in new}
        for before in old:
            after = candidate[(before['fields'], before['nameBytes'], before['opportunity'])]
            for key in ['fields', 'nameBytes', 'opportunity', 'named', 'presence']:
                if before[key] != after[key]:
                    raise ValueError(f'Named oracle differs: {key}')
            if before['opportunity'] != 'empty' and after['roamingBytes'] >= before['roamingBytes']:
                raise ValueError('Numeric roaming did not reduce the named payload')
        manifest['oracle'] = '40 matching named/write-set rows plus 8 candidate all-dirty rows; nonempty numeric roaming payloads smaller'
    elif args.phase == 'pilot':
        for version in ['legacy', 'candidate']:
            sample(version, version + '-pilot-full', 'chain', 64, 'full', iterations='1')
            sample(version, version + '-pilot-sparse', 'chain', 8, 'sparse', iterations='1')
        for repetition in range(4):
            sample('legacy', f'legacy-calibration-{repetition}', 'chain', 64, 'full')
    else:
        oracle_manifest = json.loads((output / 'oracle-manifest.json').read_text())
        pilot_manifest = json.loads((output / 'pilot-manifest.json').read_text())
        if oracle_manifest['hashes'] != manifest['hashes'] or pilot_manifest['hashes'] != manifest['hashes']:
            raise ValueError('Classpath changed after oracle or pilot')
        if not oracle_manifest.get('oracle') or any(run.get('exitCode') != 0 for run in pilot_manifest['runs']):
            raise ValueError('Oracle or pilot is incomplete')
        manifest['protocol'] = {
            'fields': [1, 8, 32, 64], 'nameBytes': 12, 'forksPerCellPerVersion': 2,
            'order': ['legacy', 'candidate', 'candidate', 'legacy'],
            'chain': {'opportunities': ['full', 'sparse', 'empty', 'selection', 'track'], 'warmup': '1x500ms', 'measurement': '2x500ms'},
            'layers': {'warmup': '1x100ms', 'measurement': '2x200ms'},
            'budgetSeconds': 1200 - pilot_manifest['wallSeconds'],
            'boundaries': 'Pilot forks cost 3.4-4.7 seconds including startup/compile. Shortened the complete protocol before comparison to fit the budget; CPU/JIT instability remains reviewable. Sparse export includes one same-value generated write. Chain includes byte-array transport copies. Diagnostics excluded from main cells.'}
        # The pilot and calibration count toward the 1200 second campaign budget.
        start -= pilot_manifest['wallSeconds']
        save()
        for method in ['chain', 'generatedExport', 'mapping', 'accumulatedApply', 'protobuf', 'relation']:
            opportunities = 'full,sparse,empty,selection,track' if method in ['chain', 'protobuf'] else 'selection' if method == 'relation' else 'full,sparse,empty'
            for fields in [1, 8, 32, 64]:
                for index, version in enumerate(['legacy', 'candidate', 'candidate', 'legacy']):
                    sample(version, f'{method}-{fields}-{index}-{version}', method, fields, opportunities,
                           warm='500ms' if method == 'chain' else '100ms', measurement='500ms' if method == 'chain' else '200ms')
        for debug in [False, True]:
            for repetition in range(2):
                sample('candidate', f'diagnostics-{debug}-{repetition}', 'diagnostics', 8, 'sparse', debug=debug)
        manifest['complete'] = True
    save()


if __name__ == '__main__':
    main()
