"""Run an immutable, interleaved ownership campaign from exported Java launch manifests."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--launch', default='build/benchmark/launch.json')
    parser.add_argument('--legacy-launch', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--budget-seconds', type=int, default=1200)
    args = parser.parse_args()
    root = Path(args.output).resolve()
    root.mkdir(parents=True, exist_ok=False)
    launches = {name: json.loads(Path(path).read_text()) for name, path in
                [('candidate', args.launch), ('legacy', args.legacy_launch)]}
    if launches['candidate']['java'] != launches['legacy']['java']:
        raise ValueError('Different JDK executables')
    hashes = {name: {path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
                     for path in launch['classpath'] if Path(path).is_file()}
              for name, launch in launches.items()}
    native = {hashlib.sha256(Path(launch['nativeLibrary']).read_bytes()).hexdigest()
              for launch in launches.values()}
    if len(native) != 1:
        raise ValueError('Different native libraries')
    cases = [('frame', owners, query) for owners in [1, 64]
             for query in ['same', 'switch', 'replay', 'absent', 'dynamic']]
    cases.append(('earlyConsumption', 64, 'switch'))
    config = dict(launches=launches, classpathHashes=hashes, nativeSha256=next(iter(native)),
                  platform=platform.platform(), cpuCount=os.cpu_count(), cases=cases, forks=3,
                  warmup='2 x 1 s', measurement='3 x 1 s', vm=['-Xms256m', '-Xmx512m'],
                  gcProfiler=True, bootstrapSeed=43137, bootstrapIterations=10000,
                  minimumNoiseThreshold=0.05, budgetSeconds=args.budget_seconds,
                  dynamicTrace='10000 preallocated names, circular; retained growth measured separately')
    (root / 'config.json').write_text(json.dumps(config, indent=2))
    completed = []

    def run(name, phase, fork, case, short=False):
        index = len(completed)
        method, owners, query = case
        stem = f'{index:03d}-{phase}-{name}-{method}-{owners}-{query}-f{fork}'
        result = root / (stem + '.json')
        launch = launches[name]
        jmh = [f'.*EntityOwnershipBenchmark.{method}$', '-p', f'owners={owners}', '-p', f'query={query}',
               '-f', '1', '-wi', '1' if short else '2', '-i', '2' if short else '3',
               '-w', '500ms' if short else '1s', '-r', '500ms' if short else '1s',
               '-prof', 'gc', '-jvmArgsAppend', '-Xms256m -Xmx512m', '-foe', 'true',
               '-rf', 'json', '-rff', str(result)]
        (root / (stem + '-args.json')).write_text(json.dumps(jmh, indent=2))
        command = [launch['java'], '-cp', os.pathsep.join(launch['classpath']), launch['main']] + jmh
        env = os.environ.copy()
        env['YSM_NATIVE_PATH'] = launch['nativeLibrary']
        before = time.monotonic()
        with (root / (stem + '.log')).open('wb') as log:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=env,
                                     timeout=max(1, args.budget_seconds - (before - started)))
        record = dict(phase=phase, variant=name, fork=fork, case=case, result=result.name,
                      exitCode=process.returncode, seconds=time.monotonic() - before)
        completed.append(record)
        (root / 'samples.json').write_text(json.dumps(completed, indent=2))
        print(json.dumps(record), flush=True)
        if process.returncode:
            raise RuntimeError(stem)
        return json.loads(result.read_text())[0]['primaryMetric']['score']

    # All truth processes run before the sampling budget starts.
    for name, launch in launches.items():
        env = os.environ.copy()
        env['YSM_NATIVE_PATH'] = launch['nativeLibrary']
        for _, owners, query in cases[:10]:
            path = root / f'truth-{name}-{owners}-{query}.log'
            with path.open('wb') as log:
                subprocess.run([launch['java'], '-cp', os.pathsep.join(launch['classpath']),
                                'com.elfmcys.ysm.client.entity.EntityOwnershipBenchmark', str(owners), query],
                               env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
    started = time.monotonic()
    calibration = []
    for fork in range(3):
        a = run('legacy', 'calibration-a', fork, cases[1], True)
        b = run('legacy', 'calibration-b', fork, cases[1], True)
        calibration.append(abs(b / a - 1))
    threshold = max(0.05, max(calibration))
    decision = dict(noiseThreshold=threshold, calibrationDeviations=calibration,
                    rule='Overall 95% interval upper < 1-threshold and same-direction repeat reopens D05',
                    weights='Equal geometric weights for ten main cases; early consumption separate')
    (root / 'decision.json').write_text(json.dumps(decision, indent=2))
    values = {str(case): {name: [] for name in launches} for case in cases}
    for fork in range(3):
        for case in cases:
            for name in (['legacy', 'candidate'] if fork % 2 == 0 else ['candidate', 'legacy']):
                if time.monotonic() - started > args.budget_seconds - 15:
                    raise RuntimeError('Sampling budget exhausted; matrix incomplete')
                values[str(case)][name].append(run(name, 'main', fork, case))
    rng = random.Random(43137)
    def ratio(case):
        data = values[str(case)]
        return statistics.mean(rng.choices(data['candidate'], k=3)) / statistics.mean(rng.choices(data['legacy'], k=3))
    report = {}
    for case in cases:
        samples = sorted(ratio(case) for _ in range(10000))
        data = values[str(case)]
        report[str(case)] = dict(ratio=statistics.mean(data['candidate']) / statistics.mean(data['legacy']),
                                ci95=[samples[250], samples[9749]], throughput=data)
    overall = sorted(math.exp(statistics.mean(math.log(ratio(case)) for case in cases[:10]))
                     for _ in range(10000))
    report['overall'] = dict(ratio=math.exp(statistics.mean(math.log(report[str(case)]['ratio']) for case in cases[:10])),
                             ci95=[overall[250], overall[9749]], noiseThreshold=threshold)
    report['elapsedSeconds'] = time.monotonic() - started
    report['matrixComplete'] = True
    (root / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report['overall']), flush=True)


if __name__ == '__main__':
    main()
