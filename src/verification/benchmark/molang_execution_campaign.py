"""Run bounded, interleaved scalar execution samples from prepareBenchmark manifests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--launch', default='build/benchmark/launch.json')
    parser.add_argument('--legacy-launch', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--budget-seconds', type=int, default=1200)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    launches = {name: json.loads(Path(path).read_text()) for name, path in
                [('candidate', args.launch), ('legacy', args.legacy_launch)]}
    if launches['candidate']['java'] != launches['legacy']['java']:
        raise ValueError('Paired samples require the same JDK')
    (output / 'launches.json').write_text(json.dumps(launches, indent=2))
    shared = [(method, distribution, 1) for method in ['primitive', 'boxed']
              for distribution in ['finite', 'missing', 'mixed']]
    shared += [(method, 'mixed', 1) for method in ['arithmetic', 'deniedAction', 'internalFunction']]
    shared += [(method, 'mixed', count) for method in
               ['consecutiveExpressions', 'completeObservation', 'completeLogicalRound']
               for count in [1, 16, 256]]
    extra = [(method, distribution, 1) for method in ['contextFreeCall', 'transientPrimitive']
             for distribution in ['finite', 'missing', 'mixed']]
    extra += [('deniedSubject', 'mixed', 1)]
    schedule = [('pilot', name, 0, ('primitive', 'mixed', 1)) for name in ['legacy', 'candidate']]
    schedule += [('calibration-' + label, 'legacy', fork, ('primitive', 'mixed', 1))
                 for fork in range(2) for label in ['a', 'b']]
    for fork in range(2):
        for case in shared:
            for name in (['legacy', 'candidate'] if fork == 0 else ['candidate', 'legacy']):
                schedule.append(('main', name, fork, case))
        schedule += [('main', 'candidate', fork, case) for case in extra]
    started = time.monotonic()
    metadata = dict(platform=platform.platform(), processor=platform.processor(),
                    cpuCount=os.cpu_count(), budgetSeconds=args.budget_seconds,
                    plannedForks=len(schedule), independentForks=1, warmup='2 x 1 s',
                    measurement='3 x 1 s', distributions={'finite': '4/4 finite',
                    'missing': '4/4 missing', 'mixed': '2/4 finite, 2/4 missing'})
    metadata['classpathHashes'] = {name: {p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
        for p in launch['classpath'] if Path(p).is_file()} for name, launch in launches.items()}
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2))
    completed = []
    for index, (phase, name, fork, (method, distribution, count)) in enumerate(schedule):
        remaining = args.budget_seconds - (time.monotonic() - started)
        if remaining < 15:
            break
        stem = f'{index:03d}-{phase}-{name}-{method}-{distribution}-{count}-f{fork}'
        result = output / (stem + '.json')
        jmh_args = [f'.*MolangExecutionBenchmark.{method}$', '-p', f'distribution={distribution}',
                    '-p', f'expressions={count}', '-f', '1', '-wi', '1' if phase == 'pilot' else '2',
                    '-i', '1' if phase == 'pilot' else '3', '-w', '200ms' if phase == 'pilot' else '1s',
                    '-r', '200ms' if phase == 'pilot' else '1s', '-prof', 'gc',
                    '-jvmArgsAppend', '-Xms256m -Xmx256m', '-foe', 'true', '-rf', 'json', '-rff', str(result)]
        (output / (stem + '-args.json')).write_text(json.dumps(jmh_args, indent=2))
        launch = launches[name]
        command = [launch['java'], '-cp', os.pathsep.join(launch['classpath']), launch['main']] + jmh_args
        before = time.monotonic()
        with (output / (stem + '.log')).open('wb') as log:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                     timeout=max(1, remaining))
        record = dict(index=index, phase=phase, candidate=name, fork=fork, method=method,
                      distribution=distribution, expressions=count, result=result.name,
                      seconds=time.monotonic()-before, exitCode=process.returncode)
        completed.append(record)
        (output / 'samples.json').write_text(json.dumps(completed, indent=2))
        print(json.dumps(record), flush=True)
        if process.returncode:
            raise RuntimeError(f'JMH failed: {stem}')
    metadata.update(completedForks=len(completed), elapsedSeconds=time.monotonic()-started,
                    complete=len(completed) == len(schedule))
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2))
    if not metadata['complete']:
        raise RuntimeError('Sampling budget exhausted; retain the incomplete campaign')


if __name__ == '__main__':
    main()
