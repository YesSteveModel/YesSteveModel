"""Independently inventory default target/set/required inputs from a built host JAR."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def inventory(jar):
    prefix = "assets/ysm/builtin/default/"
    with zipfile.ZipFile(jar) as archive:
        resources = {name[len(prefix):]: hashlib.sha256(archive.read(name)).hexdigest()
                     for name in sorted(archive.namelist()) if name.startswith(prefix) and not name.endswith("/")}
        source = json.loads(archive.read(prefix + "ysm.json"))
    if source["spec"] != 2:
        raise ValueError("inventory supports the fixed default spec 2 input")
    files = source["files"]
    player = files["player"]
    targets = [{"target": "player", "kind": "PLAYER", "matches": [],
                "textures": sorted(PurePosixPath(value if isinstance(value, str) else value["uv"]).stem
                                   for value in player["texture"]),
                "sets": ["main", "fp_arm"], "required": True,
                "animation_resources": player["animation"], "controller_resources": player.get("animation_controllers", [])}]
    for group, singular, kind in (("projectiles", "projectile", "PROJECTILE"), ("vehicles", "vehicle", "VEHICLE")):
        for index, value in enumerate(files.get(group, [])):
            if not value.get("match"):
                continue
            targets.append({"target": f"{singular}/{index:04d}", "kind": kind,
                            "matches": sorted(value["match"]), "textures": ["default"], "sets": ["main"],
                            "required": True, "animation_resources": {"main": value["animation"]} if value.get("animation") else {},
                            "controller_resources": [value["controller"]] if value.get("controller") else []})
    required = [{"target": row["target"], "texture": texture, "set": expression_set}
                for row in targets for texture in row["textures"] for expression_set in row["sets"]]
    return {"schema_version": 1, "jar": str(Path(jar).resolve()), "jar_sha256": hashlib.sha256(Path(jar).read_bytes()).hexdigest(),
            "source": "Independent ZIP resource reader and spec-2 descriptor rules; no service/candidate inventory",
            "targets": targets, "required": required, "resource_sha256": resources}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inventory(args.jar), indent=2), encoding="utf-8")
