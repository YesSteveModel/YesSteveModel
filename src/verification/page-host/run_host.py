"""Run one immutable real-host script, retaining failures and bounded timeouts."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


def run(root, harness, output, script, config, timeout, detail=False, provider="", functional=False):
    output.mkdir(parents=True, exist_ok=False)
    (output / "game/config").mkdir(parents=True)
    if os.environ.get("YSM_PAGE_EXTRA_CONFIG"):
        shutil.copytree(os.environ["YSM_PAGE_EXTRA_CONFIG"], output / "game/config", dirs_exist_ok=True)
    shutil.copy2(config, output / "game/config/ysm-client.toml")
    shutil.copy2(script, output / "script.json")
    if os.environ.get("YSM_PAGE_OPTIONS"):
        shutil.copy2(os.environ["YSM_PAGE_OPTIONS"], output / "game/options.txt")
    env = os.environ.copy()
    env.update(YSM_PAGE_HARNESS=str(harness), YSM_PAGE_OUTPUT=str(output),
               YSM_PAGE_SCRIPT=str(output / "script.json"), YSM_PAGE_DETAIL=str(detail).lower(),
               YSM_PAGE_PROVIDER=provider, YSM_PAGE_FUNCTIONAL=str(functional).lower(),
               YSM_PAGE_DEADLINE_MILLIS=str(int((time.time() + max(0, timeout - 5)) * 1000)))
    command = ["cmd.exe", "/d", "/c", str(root / "gradlew.bat"), "-p", str(root),
               "-I", str(harness / "page-host.init.gradle"), "runClient"]
    if os.environ.get("YSM_PAGE_OFFLINE") == "true":
        command.append("--offline")
    record = dict(root=str(root), script=str(script), command=command, started_at=time.time(),
                  detail=detail, provider=provider, functional=functional, timeout_seconds=timeout)
    with (output / "launch.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=root, env=env, stdout=log, stderr=log,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            record["exit_code"] = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=log)
            record["timed_out"] = True
            process.wait()
    record["ended_at"] = time.time()
    result_file = output / "result.json"
    record["host_completed"] = result_file.exists() and json.loads(result_file.read_text())["completed"]
    (output / "run.json").write_text(json.dumps(record, indent=2) + "\n")
    return record


def main():
    parser = argparse.ArgumentParser()
    for name in ["root", "output", "script", "config"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=300)
    parser.add_argument("--detail", action="store_true")
    parser.add_argument("--provider", default="")
    parser.add_argument("--functional", action="store_true")
    args = parser.parse_args()
    record = run(args.root.resolve(), Path(__file__).resolve().parent, args.output.resolve(),
                 args.script.resolve(), args.config.resolve(), args.timeout, args.detail,
                 args.provider, args.functional)
    print(json.dumps(record, indent=2), flush=True)
    raise SystemExit(0 if record.get("exit_code") == 0 and record["host_completed"] else 1)


if __name__ == "__main__":
    main()
