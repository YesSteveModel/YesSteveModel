"""Runs GUI/FULL cache controls in separate JVMs and preserves their exact launch identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--budget-seconds", type=int, default=1200)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
spec = json.loads((repo / "build/benchmark/launch.json").read_text())
env = dict(os.environ, YSM_NATIVE_PATH=spec["nativeLibrary"])
started = time.monotonic()
results = []

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

metadata = dict(launch=spec,
                parent=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
                nativeSha256=sha(spec["nativeLibrary"]),
                sourceDiffSha256=hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD"], cwd=repo)).hexdigest(),
                startedUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
(output / "manifest.json").write_text(json.dumps(metadata, indent=2))

def run(model, scenario, operation):
    remaining = args.budget_seconds - (time.monotonic() - started)
    if remaining <= 0:
        raise RuntimeError("Campaign budget exhausted")
    root = output / model.replace("/", "_") / scenario
    destination = root / operation
    destination.mkdir(parents=True)
    command = [spec["java"], "-Xmx3G",
               "-javaagent:" + str(repo / "build/benchmark/native-image-access-agent.jar"),
               "-javaagent:" + str(repo / "build/benchmark/gui-purpose-input-agent.jar"),
               "-javaagent:" + str(repo / "build/benchmark/read-handle-agent.jar"),
               "-cp", os.pathsep.join(spec["classpath"]),
               "com.elfmcys.ysm.model.resource.client.render.GuiPurposeCacheProbe",
               str(root), str(destination), model, operation]
    begin = time.monotonic()
    with (destination / "console.log").open("w") as log:
        process = subprocess.run(command, cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT,
                                 timeout=min(140, remaining))
    receipt = dict(command=command, exitCode=process.returncode, wallSeconds=time.monotonic() - begin)
    (destination / "process.json").write_text(json.dumps(receipt, indent=2))
    if process.returncode:
        raise RuntimeError(f"JVM failed: {destination}")
    result = json.loads((destination / "result.json").read_text())
    result.update(modelPath=model, scenario=scenario, output=str(destination))
    results.append(result)
    print(model, scenario, operation, result.get("source"), result["compilations"], flush=True)
    return result

try:
    for model in ["wine_fox/01_taisho_maid", "misc/1_alex"]:
        fingerprints = set()
        for scenario in ["none", "gui", "full-main", "full-arm", "both", "full-ready"]:
            if scenario in ["gui", "both"]:
                run(model, scenario, "seed-gui")
            if scenario in ["full-main", "full-arm", "both"]:
                run(model, scenario, "seed-full-arm" if scenario == "full-arm" else "seed-full-main")
            if scenario != "full-ready":
                cached = run(model, scenario, "cache-only")
                assert cached["ready"] == (scenario in ["gui", "both"]), cached
                assert cached["compilations"] == 0, cached
            result = run(model, scenario, "full-ready" if scenario == "full-ready" else "gui")
            assert result["ready"], result
            warm = scenario in ["gui", "both"]
            assert result["source"] == ("DISK" if warm else "COLD"), result
            assert result["compilations"] == (0 if warm else 1), result
            fingerprints.add(tuple(entry["fingerprint"] for entry in result["inputs"]))
            assert all(entry["purpose"] == "GUI" for entry in result["inputs"]), result
        assert len(fingerprints) == 1, "FULL state changed the GUI input closure"
        full = run(model, "full-ready", "full-read")
        assert full["purpose"] == "FULL" and full["source"] == "DISK", full
        assert full["compilations"] == 0, full
        assert full["animationBatchHandles"], "FULL disk discovery did not exercise a lazy animation read"
    (output / "summary.json").write_text(json.dumps(dict(status="passed", results=results,
        wallSeconds=time.monotonic() - started), indent=2))
finally:
    metadata["endedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    (output / "manifest.json").write_text(json.dumps(metadata, indent=2))
