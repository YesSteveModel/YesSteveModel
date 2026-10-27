"""Run nine isolated JMH comparisons and retain raw producer-entry/receiver observations."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import time


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def percentile(values, quantile):
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("build/benchmark/launch.json"))
    parser.add_argument("--output", type=Path, default=Path("build/reports/benchmark/resource-priority"))
    parser.add_argument("--iterations", type=int, default=2)
    parser.add_argument("--budget-seconds", type=int, default=480)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    launch = json.loads(args.manifest.read_text())
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    agent = args.manifest.resolve().parent / "native-image-access-agent.jar"
    native = Path(launch["nativeLibrary"])
    classpath = os.pathsep.join(launch["classpath"])
    modes = ["single_fifo_control", "split_equal", "split_normal"]
    scenarios = ["saturated", "sameFlight", "sharedPreparation"]
    order = [(scenario, mode) for index, scenario in enumerate(scenarios)
             for mode in modes[index:] + modes[:index]]
    metadata = {
        "platform": platform.platform(), "processor": platform.processor(), "logicalCpus": os.cpu_count(),
        "launch": launch, "order": order, "iterations": args.iterations, "budgetSeconds": args.budget_seconds,
        "nativeSha256": digest(native), "agentSha256": digest(agent),
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "worktree": subprocess.check_output(["git", "status", "--porcelain=v1"], text=True),
        "limitations": ["single_fifo_control is a current-code queue control, not a historical HEAD baseline",
                        "single_fifo_control has a third physical worker that never executes benchmark work",
                        "producer entry follows atomic scheduler claim; requestToProducerStart is not exact claim time",
                        "aggregate compute sums all real producer and consumer stage times, including both shared consumers",
                        "Ready and receiver are headless observations, excluding GPU, actual entity installation and frame time",
                        "small-sample P95/P99 are descriptive order statistics without tail confidence"],
    }
    if os.name == "nt":
        metadata["powerScheme"] = subprocess.check_output(["powercfg", "/getactivescheme"], text=True).strip()
    metadata["classpathJarSha256"] = {path: digest(Path(path)) for path in launch["classpath"]
                                      if Path(path).is_file()}
    source_paths = subprocess.check_output(["rg", "--files", "-g", "*.java", "src/verification/benchmark"],
                                           text=True).splitlines()
    metadata["benchmarkSourceSha256"] = {path: digest(Path(path)) for path in source_paths}
    compiled = []
    symbols = {"client/work": ["ClientResourceScheduler", "WorkPriority", "ResourcePrioritySchedulers"],
               "client/render": ["ModelRenderTargetCache", "ModelRenderTargetLoader", "PreparedTargetCache",
                                 "BakedModelCache", "ResourcePriorityBenchmark", "ResourcePriorityProbe",
                                 "DemandPreparationBenchmark"]}
    for root in map(Path, launch["classpath"]):
        if not root.is_dir():
            continue
        for package, names in symbols.items():
            for name in names:
                compiled.extend((root / "com/elfmcys/ysm/model/resource" / package).glob(name + "*.class"))
    metadata["compiledSha256"] = {str(path): digest(path) for path in compiled}
    metadata_path = output / "metadata.json"
    if args.resume:
        retained = json.loads(metadata_path.read_text(encoding="utf-8"))
        for key in ["launch", "order", "iterations", "nativeSha256", "agentSha256", "classpathJarSha256",
                    "benchmarkSourceSha256", "compiledSha256"]:
            current = json.loads(json.dumps(metadata[key]))
            if retained[key] != current:
                raise RuntimeError(f"Cannot resume a campaign after its {key} changed")
        metadata = retained
        metadata.setdefault("startedAtUnix", metadata_path.stat().st_birthtime)
    else:
        metadata["startedAtUnix"] = time.time()
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    environment = dict(os.environ, YSM_NATIVE_PATH=str(native))
    observations = []
    began = time.monotonic() - (time.time() - metadata["startedAtUnix"])
    for scenario, mode in order:
        label = f"{scenario}-{mode}"
        command = [launch["java"], "-cp", classpath, launch["main"], ".*ResourcePriorityBenchmark.prepare",
                   "-p", f"mode={mode}", "-p", f"scenario={scenario}", "-p", "temperature=derivedHot",
                   "-f", "1", "-wi", "1", "-i", str(args.iterations), "-w", "200ms", "-r", "300ms",
                   "-jvmArgsAppend", f'-Xmx3G -javaagent:"{agent}"=priority',
                   "-foe", "true", "-rf", "json", "-rff", str(output / f"{label}.jmh.json")]
        remaining = args.budget_seconds - (time.monotonic() - began)
        if remaining <= 0:
            raise RuntimeError("Campaign budget exhausted; retain partial raw evidence")
        retained_result = output / f"{label}.jmh.json"
        if args.resume and retained_result.exists() and json.loads(retained_result.read_text(encoding="utf-8")):
            print(f"Retaining completed {label}; budget remaining {remaining:.0f}s", flush=True)
            code = 0
        else:
            print(f"Running {label}; budget remaining {remaining:.0f}s", flush=True)
            with (output / f"{label}.log").open("w", encoding="utf-8") as log:
                process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                           start_new_session=os.name != "nt")
                try:
                    code = process.wait(timeout=remaining)
                except subprocess.TimeoutExpired:
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log,
                                       stderr=subprocess.STDOUT, check=False)
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                    raise RuntimeError("Campaign budget exhausted; stopped this campaign's JMH process tree")
        if code:
            raise RuntimeError(f"JMH failed for {label}; inspect its retained log")
        phase = "unknown"
        measured = 0
        for line in (output / f"{label}.log").read_text(encoding="utf-8", errors="replace").splitlines():
            if "# Warmup Iteration" in line:
                phase = "warmup"
            elif re.search(r"^Iteration\s+\d+:", line):
                phase = "measurement"
            marker = "RESOURCE_PRIORITY_SAMPLE "
            if marker not in line:
                continue
            payload = line.split(marker, 1)[1]
            if "\ufffd" in payload:
                raise RuntimeError(f"A sample JSON contains invalid UTF-8 in {label}")
            sample = json.loads(payload)
            sample.update(phase=phase, log=f"{label}.log")
            observations.append(sample)
            measured += phase == "measurement"
        if measured != args.iterations:
            raise RuntimeError(f"Expected {args.iterations} measured samples for {label}, got {measured}")
        (output / "samples.jsonl").write_text("".join(json.dumps(row) + "\n" for row in observations), encoding="utf-8")
    summary = []
    metrics = ["requestToProducerStartMs", "aggregateComputeMs", "completionToReadyObservationMs",
               "requestToReceiverMs", "backgroundElapsedMs", "backgroundJobsPerSecond"]
    for scenario, mode in order:
        selected = [row for row in observations if row["phase"] == "measurement"
                    and row["mode"] == mode and row["scenario"] == scenario]
        summary.append({"scenario": scenario, "mode": mode, "samples": len(selected),
                        "metrics": {metric: {"p50": percentile([row[metric] for row in selected], .5),
                                             "p95": percentile([row[metric] for row in selected], .95),
                                             "p99": percentile([row[metric] for row in selected], .99)}
                                    for metric in metrics},
                        "counts": [{key: row[key] for key in ["loaderStarts", "producers", "consumerBuilds",
                                                              "nativeBakes", "peakCompute", "expressionStages"]}
                                   for row in selected]})
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Completed in {time.monotonic() - began:.1f}s; raw evidence and summary: {output}", flush=True)


if __name__ == "__main__":
    main()
