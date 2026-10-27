"""Run the real Forge consumer or required I/O failure through an isolated overlay."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from install_observation import OBSERVED_STARTUP_CALL, STARTUP_PATH, replace


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, value): path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def manifest(root):
    paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root).decode().split("\0")
    return {path: sha(root / path) for path in paths if path and (root / path).is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--candidate-receipt", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=("consume", "required-failure"), required=True)
    args = parser.parse_args()
    protocol = json.loads(args.protocol.read_text(encoding="utf-8"))
    candidate = json.loads(args.candidate_receipt.read_text(encoding="utf-8"))
    if sha(args.protocol) != candidate["protocol_sha256"]:
        raise ValueError("candidate/protocol mismatch")
    config = candidate["configuration"]
    project = Path(config["project"])
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    java = project / "src/main/java/com/elfmcys/ysm"
    helper = java / "verification/StartupObservation.java"
    setup = java / STARTUP_PATH
    loader = java / "model/resource/client/render/ModelRenderTargetLoader.java"
    destination = java / "verification/StartupConsumption.java"
    if destination.exists():
        raise ValueError("functional overlay already installed")
    originals = {path: path.read_bytes() for path in (helper, setup, loader)}
    source_before = manifest(project)
    if hashlib.sha256(json.dumps(source_before, sort_keys=True).encode()).hexdigest() != config["source_manifest_sha256"]:
        raise ValueError("functional source does not match the measured candidate")
    if sha(config["native"]) != config["native_sha256"]:
        raise ValueError("native candidate changed")
    receipt = {"schema_version": 1, "mode": args.mode, "status": "executing",
               "protocol_sha256": sha(args.protocol), "candidate_receipt_sha256": sha(args.candidate_receipt),
               "engine_source_commit": candidate["engine_source_commit"],
               "engine_jars": config["engine_jars"], "native_sha256": sha(config["native"]),
               "source_before": source_before}
    try:
        shutil.copyfile(Path(__file__).with_name("StartupConsumption.java"), destination)
        if args.mode == "consume":
            replace(helper, 'Minecraft.getInstance().tell(() -> {\n            boundary("NORMAL_STOP_REQUESTED");\n            Minecraft.getInstance().stop();\n        });',
                    'Minecraft.getInstance().tell(() -> StartupConsumption.begin(service));')
            replace(helper, 'var value = ready.target();',
                    'var value = ready.target();\n'
                    '                    YesSteveModel.LOGGER.info("[startup-consumption] required-set target={} texture={} set={} generation={} ready=true",\n'
                    '                            target.id(), texture, value.expressions().identity().set().id(), value.expressions().generationId());\n'
                    '                    if (value.playerResources() != null) YesSteveModel.LOGGER.info("[startup-consumption] required-set target={} texture={} set={} generation={} ready=true",\n'
                    '                            target.id(), texture, value.armExpressions().identity().set().id(), value.armExpressions().generationId());')
        else:
            replace(setup, OBSERVED_STARTUP_CALL,
                    'com.elfmcys.ysm.verification.StartupConsumption.requiredFailure(system, builtinCapabilities.availability());')
            replace(loader, 'var local = set.select(definition);',
                    'if (request.defaultModel() && request.targetId().equals("player") && set.id().equals("main")) {\n'
                    '                    com.elfmcys.ysm.YesSteveModel.LOGGER.info("[startup-consumption] required-failure-entry target={} set={}", request.targetId(), set.id());\n'
                    '                    java.nio.file.Files.readAllBytes(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("required-input-intentionally-absent.bin"));\n'
                    '                }\n'
                    '                var local = set.select(definition);')
        receipt["overlay_source"] = {str(path.relative_to(project)): sha(path) for path in (*originals, destination)}
        for path in (*originals, destination):
            target = args.output / "overlay" / path.relative_to(project)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
        game = args.output / "game"
        (game / "config").mkdir(parents=True)
        (game / "config/ysm-client.toml").write_text('[general]\nDisclaimerShow = false\n\n[network]\nSessionMode = "LOCAL"\n', encoding="utf-8")
        env = os.environ.copy()
        env.update(JAVA_HOME=protocol["jdk"], YSM_NATIVE_PATH=config["native"], YSM_MEASUREMENT_GAME=str(game),
                   YSM_MEASUREMENT_JFR=str(args.output / "startup.jfr"), YSM_MEASUREMENT_JFC=protocol["jfc"])
        command = [str(project / "gradlew.bat"), "runClient", "--no-daemon", "--console=plain", "--offline",
                   "--init-script", str(Path(__file__).with_name("startup.init.gradle").resolve()),
                   f"-Psemaweave.repository={config['repository']}"]
        receipt.update(command=command, cwd=str(project), environment_overrides={k: v for k, v in env.items() if k.startswith("YSM_")})
        save(args.output / "receipt.json", receipt)
        started = time.monotonic()
        with (args.output / "client.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
            try: code = process.wait(timeout=420)
            except subprocess.TimeoutExpired:
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
                raise TimeoutError("owned functional process exceeded fixed timeout")
        receipt.update(exit_code=code, elapsed_seconds=time.monotonic() - started)
        text = (args.output / "client.log").read_text(encoding="utf-8", errors="replace")
        if code: raise ValueError("runClient failed; inspect preserved client.log")
        launch = game / "startup-launch-inputs.json"
        shutil.copyfile(launch, args.output / "launch-inputs.json")
        files = json.loads(launch.read_text(encoding="utf-8"))["files"]
        for name, digest in config["engine_jars"].items():
            if [v for p, v in files.items() if Path(p).name == name] != [digest]:
                raise ValueError("functional run loaded another engine candidate")
        receipt["observations"] = [line.split("[startup-consumption] ", 1)[1] for line in text.splitlines() if "[startup-consumption] " in line]
        if args.mode == "consume":
            if "[startup-consumption] status=passed" not in text or "[startup-consumption] status=failed" in text:
                raise ValueError("real consumption failed or missing")
            actual = sorted({(a, b, c) for a, b, c in re.findall(r"required-set target=(\S+) texture=(\S+) set=(\S+) generation=\d+ ready=true", text)})
            inventory = json.loads(Path(protocol["independent_inventory"]["path"]).read_text(encoding="utf-8"))
            expected = sorted((r["target"], r["texture"], r["set"]) for r in inventory["required"])
            receipt["required_inventory"] = {"actual": actual, "expected": expected, "differences": [] if actual == expected else ["required tuple mismatch"]}
            if actual != expected: raise ValueError("independent required inventory differs")
        else:
            if "requiredFailure=passed category=java.nio.file.NoSuchFileException" not in text or "partialService=false" not in text:
                raise ValueError("required failure provenance/publication not proved")
        receipt.update(status="produced", raw={name: sha(args.output / name) for name in ("client.log", "startup.jfr", "launch-inputs.json")})
    except BaseException as failure:
        receipt.update(status="failed", failure=str(failure))
        raise
    finally:
        for path, content in originals.items(): path.write_bytes(content)
        if destination.exists(): destination.unlink()
        receipt["source_restored"] = manifest(project) == source_before
        save(args.output / "receipt.json", receipt)
    print(json.dumps({k: receipt[k] for k in ("mode", "status", "exit_code", "elapsed_seconds", "source_restored")}))


if __name__ == "__main__": main()
