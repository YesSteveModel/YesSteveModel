"""Run the real Forge frame comparison and retain the inputs and unaggregated observations."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time


HARNESS = Path(__file__).resolve().parent
PROJECT = HARNESS.parents[2]
MODES = ("animation", "no_interpart", "full")
TEXTURE = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def fixture(path, dense, mode):
    path.mkdir(parents=True)
    manifest = {"metadata": {"name": path.name}, "files": {"player": {
        "model": {"main": "main.json", "arm": "main.json"}, "texture": ["texture.png"],
    }}}
    bones = [{"name": "body", "pivot": [0, 12, 0], "cubes": [{
        "origin": [-3, 12, -2], "size": [6, 9, 4], "uv": [0, 0],
    }]}]
    physics = {"nodes": [], "constraints": []}
    for side, x in enumerate((-2, 2)):
        anchor = f"anchor_{side}"
        proxy = f"proxy_{side}"
        bones.append({"name": anchor, "parent": "body", "pivot": [x, 22, 0]})
        bones.append({"name": proxy, "parent": "body", "pivot": [x, 16, 0]})
        physics["nodes"].extend([
            {"id": anchor, "bone": anchor, "type": "anchor"},
            {"id": proxy, "bone": proxy, "type": "proxy", "collections": list(range(1, 17)),
             "shapes": [{"type": "box", "size": [3, 10, 3]}]},
        ])
    for column in range(5):
        parent = f"anchor_{int(column >= 3)}"
        spacing = 1.67 if dense else 2.75
        x = (column - 2) * spacing
        for segment in range(3):
            name = f"strand_{column}_{segment}"
            y = 22 - segment * 3.5
            bones.append({"name": name, "parent": parent, "pivot": [x, y, 2],
                          "cubes": [{"origin": [x - 0.75, y - 3.5, 1.25],
                                     "size": [1.5, 3.5, 1.5], "uv": [0, 0]}]})
            collections = [column * 3 + segment + 1]
            if mode == "full":
                collections.append(16)
            physics["nodes"].append({
                "id": name, "bone": name, "type": "dynamic", "collections": collections,
                "shapes": [{"type": "capsule", "point_a": [0, -0.75, 0],
                            "point_b": [0, -2.75, 0], "radius": 1.05 if dense else 0.78}],
            })
            physics["constraints"].append({
                "id": name, "node_a": parent, "node_b": name, "type": "point", "disable_collisions": True,
                "angular_y": {"spring": {"target": "animation", "frequency": 3}},
                "angular_z": {"spring": {"target": "animation", "frequency": 3}},
            })
            parent = name
    save(path / "main.json", {"minecraft:geometry": [{
        "description": {"texture_width": 16, "texture_height": 16}, "bones": bones,
    }]})
    (path / "texture.png").write_bytes(TEXTURE)
    if mode != "animation":
        manifest["files"]["player"]["physics"] = "physics.json"
        save(path / "physics.json", physics)
    save(path / "ysm.json", manifest)


def synthetic(models):
    cases = []
    for dense, counts in ((False, (1, 50, 100)), (True, (1,))):
        label = "dense" if dense else "regular"
        names = [f"{label}_{mode}" for mode in MODES]
        for name, mode in zip(names, MODES):
            fixture(models / name, dense, mode)
        for count in counts:
            cases.append(dict(name=f"{label}_{count}", entities=count, models=names,
                              texture="texture", animation=""))
    return cases


def relative_model(selector, models):
    path = Path(selector)
    if path.is_absolute() or ".." in path.parts or not (models / path).exists():
        raise ValueError(f"model selector must name a source below the model root: {selector}")


def validate_plan(plan, models):
    if not plan["cases"] or plan["repetitions"] < 1 or plan["sampleTicks"] < 10:
        raise ValueError("expected cases, positive repetitions, and at least 10 sample ticks")
    names = set()
    for case in plan["cases"]:
        if case["name"] in names or not 1 <= case["entities"] <= 100 or len(set(case["models"])) != 3:
            raise ValueError(f"invalid case {case}")
        names.add(case["name"])
        case.setdefault("texture", "texture")
        case.setdefault("animation", "")
        for model in case["models"]:
            relative_model(model, models)


def pairing_visits(plan):
    if plan["contactCheck"] or plan["riding"]:
        raise ValueError("steady pairing requires unmounted actors with contact collection disabled")
    for key in ("settleTicks", "warmupTicks"):
        if type(plan.get(key)) is not int or plan[key] < 1:
            raise ValueError(f"steady pairing requires positive {key}")

    visits = [dict(caseIndex=index, mode=mode, repeat=-1, warmup=True)
              for index in range(len(plan["cases"])) for mode in range(len(MODES))]
    for repeat in range(plan["repetitions"]):
        for index in range(len(plan["cases"])):
            case_index = (index + repeat) % len(plan["cases"])
            reverse = (case_index + repeat) % 2 != 0
            physics_modes = (2, 1) if reverse else (1, 2)
            block = 4 * repeat
            for mode in physics_modes:
                for middle in ((0, mode) if reverse else (mode, 0)):
                    flank = mode if middle == 0 else 0
                    visits.extend(dict(caseIndex=case_index, mode=entry, repeat=block, warmup=False)
                                  for entry in (flank, middle, flank))
                    block += 1
    return visits


def git(*args):
    return subprocess.check_output(["git", "-C", str(PROJECT), *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True, help="isolated Gradle output reused across runs")
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--plan", type=Path, help="JSON plan replacing the synthetic fixture")
    parser.add_argument("--models", type=Path, help="raw asset folders or MXCs referred to by --plan")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--sample-ticks", type=int, default=60)
    parser.add_argument("--case", action="append", help="select a generated case by name")
    parser.add_argument("--contact-check", action="store_true", help="capture contact witnesses instead of timings")
    parser.add_argument("--witness", action="store_true", help="capture single-actor close-ups and exact time spans")
    parser.add_argument("--riding", action="store_true", help="mount actors on installed intrinsic boat models")
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    if bool(args.models) != bool(args.plan):
        parser.error("--models and --plan must be supplied together")
    if args.witness and not args.contact_check:
        parser.error("--witness requires --contact-check")
    native = args.native.resolve(strict=True)
    build = args.build.resolve()
    output = args.output.resolve()
    if build == PROJECT / "build" or build == output or output in build.parents:
        parser.error("--build must be separate from the project build and the result directory")
    output.mkdir(parents=True, exist_ok=False)
    game = output / "game"
    models = game / "ysm/models"
    if args.plan:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        shutil.copytree(args.models, models)
    else:
        cases = synthetic(models)
        if args.case:
            cases = [case for case in cases if case["name"] in args.case]
        plan = dict(repetitions=args.repetitions, sampleTicks=args.sample_ticks, cases=cases)
    plan["contactCheck"] = args.contact_check
    plan["witness"] = args.witness
    plan["riding"] = args.riding
    validate_plan(plan, models)
    if args.witness and (args.riding or any(case["entities"] != 1 for case in plan["cases"])):
        parser.error("close-up witnesses require one unmounted actor per case")
    if plan.get("pairing"):
        plan["pairingVisits"] = pairing_visits(plan)
    else:
        plan.pop("pairingVisits", None)
    save(output / "plan.json", plan)

    (game / "config").mkdir()
    (game / "config/ysm-client.toml").write_text(
        '[general]\nDisclaimerShow = false\nDisableSelfModel = true\nDisableSelfHands = true\n'
        f'DisableVehicleModel = {str(not args.riding).lower()}\n[network]\nSessionMode = "LOCAL"\n', encoding="utf-8")
    (game / "options.txt").write_text(
        "version:3465\nrenderDistance:6\nsimulationDistance:12\nmaxFps:260\nenableVsync:false\n"
        "guiScale:2\ngraphicsMode:0\nentityShadows:false\nparticles:2\n"
        "pauseOnLostFocus:false\nbiomeBlendRadius:0\n", encoding="utf-8")
    build.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    for flag in ("YSM_DEBUG_RESOURCE", "YSM_DEBUG_NETWORK"):
        env.pop(flag, None)
    env.update(YSM_FRAME_OUTPUT=str(output), YSM_FRAME_BUILD=str(build), YSM_NATIVE_PATH=str(native),
               YSM_HOME=str(output / "global"))
    env["YSM_FRAME_RIDING"] = str(args.riding).lower()
    env["YSM_FRAME_WITNESS"] = str(args.witness).lower()
    gradle = PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(gradle), "runClient", "--no-daemon", "--console=plain",
               "--project-cache-dir", str(build / "project-cache"),
               "-I", str(HARNESS / "physics-frame-host.init.gradle")]
    if args.offline:
        command.append("--offline")
    record = dict(command=command, revision=git("rev-parse", "HEAD"), status=git("status", "--short"),
                  platform=platform.platform(), processor=platform.processor(),
                  assetHome=env["YSM_HOME"], native=str(native), nativeSha256=hashlib.sha256(native.read_bytes()).hexdigest(),
                  started=time.time())
    with (output / "launch.log").open("w", encoding="utf-8") as log:
        kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
        process = subprocess.Popen(command, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT, **kwargs)
        try:
            record["exitCode"] = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=log)
            else:
                os.killpg(process.pid, 15)
            record["exitCode"] = process.wait()
            record["timedOut"] = True
    record["ended"] = time.time()
    save(output / "run.json", record)
    if record["exitCode"] or not (output / "samples.json").exists():
        return 1
    return subprocess.call([sys.executable, str(HARNESS / "summarize.py"), str(output)])


if __name__ == "__main__":
    raise SystemExit(main())
