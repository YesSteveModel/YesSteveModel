"""Run isolated host scripts with a fixed sampling budget and retain partial campaigns."""
import argparse
import json
import os
import shutil
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--budget-seconds", type=int, default=1200)
    parser.add_argument("--pairs", type=int, default=12)
    parser.add_argument("--detail", action="store_true")
    parser.add_argument("--provider", default="")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / "manifest.json").exists():
        parser.error("Use a new output directory; existing samples are immutable.")
    harness = Path(__file__).resolve().parent
    runs = []
    # Two complete baseline blocks precede all candidate observations.
    for block in range(2):
        for eager in [False, True]:
            runs.append({"version": "baseline", "pair": f"calibration-{block}", "eager": eager,
                         "calibration": True})
    for pair in range(args.pairs):
        for eager in [False, True]:
            for version in (["baseline", "candidate"] if pair % 2 == 0 else ["candidate", "baseline"]):
                runs.append({"version": version, "pair": str(pair), "eager": eager, "calibration": False})
    manifest = {"required_pairs": args.pairs, "runs": [], "budget_seconds": args.budget_seconds,
                "provider": args.provider, "detail": args.detail, "started_at": time.time()}
    deadline = time.monotonic() + args.budget_seconds
    for index, run in enumerate(runs):
        if time.monotonic() >= deadline:
            break
        name = f"{index:02d}-{run['version']}-eager-{str(run['eager']).lower()}"
        directory = output / name
        (directory / "game/config").mkdir(parents=True)
        shutil.copy2(args.config, directory / "game/config/ysm-client.toml")
        root = (args.baseline if run["version"] == "baseline" else args.candidate).resolve()
        environment = os.environ.copy()
        environment.update(YSM_PAGE_HARNESS=str(harness), YSM_PAGE_OUTPUT=str(directory),
                           YSM_PAGE_EAGER=str(run["eager"]).lower(), YSM_PAGE_DETAIL=str(args.detail).lower(),
                           YSM_PAGE_PROVIDER=args.provider)
        command = ["cmd.exe", "/d", "/c", str(root / "gradlew.bat"), "-p", str(root),
                   "-I", str(harness / "page-host.init.gradle"), "runClient"]
        record = {**run, "directory": name, "command": command, "started_at": time.time()}
        manifest["runs"].append(record)
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        with (directory / "launch.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=root, env=environment, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                record["exit_code"] = process.wait(timeout=max(1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=log)
                record["timed_out"] = True
                process.wait()
        record["ended_at"] = time.time()
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(name, record.get("exit_code", "timeout"), flush=True)
        if record.get("exit_code", 1) != 0:
            break
    manifest["ended_at"] = time.time()
    manifest["planned_runs"] = len(runs)
    manifest["campaign_complete"] = len(manifest["runs"]) == len(runs) and all(
        run.get("exit_code") == 0 for run in manifest["runs"])
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
