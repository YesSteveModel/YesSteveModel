"""Generate shared source fixtures and independent double-precision pose oracles."""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import shutil
import zlib


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def identity():
    return [[float(row == column) for column in range(4)] for row in range(4)]


def multiply(left, right):
    return [[sum(left[row][k] * right[k][column] for k in range(4))
             for column in range(4)] for row in range(4)]


def translation(values):
    result = identity()
    for axis, value in enumerate(values):
        result[axis][3] = value
    return result


def local(bone, attribute):
    pivot = bone["pivot"]
    position = attribute.get("position", [0, 0, 0])
    result = translation([(pivot[0] - position[0]) / 16,
                          (pivot[1] + position[1]) / 16,
                          (pivot[2] + position[2]) / 16])
    angles = attribute.get("rotation", bone.get("rotate", [0, 0, 0]))
    for axis in (2, 1, 0):
        rotation = identity()
        first, second = ((1, 2), (2, 0), (0, 1))[axis]
        rotation[first][first] = rotation[second][second] = math.cos(angles[axis])
        rotation[first][second] = -math.sin(angles[axis])
        rotation[second][first] = math.sin(angles[axis])
        result = multiply(result, rotation)
    scale = identity()
    for axis, value in enumerate(attribute.get("scale", [1, 1, 1])):
        scale[axis][axis] = value
    return multiply(multiply(result, scale), translation([-value / 16 for value in pivot]))


def oracle(model, root, attributes):
    # The declaration graph is traversed here; no production selector or renderer is used.
    bones = {bone["name"]: bone for bone in model["bones"]}
    if root is not None and root not in bones:
        return {"result": "rejected", "visible_cubes": [], "poses": {}}
    result = {"result": "consumed", "visible_cubes": [], "poses": {}}

    def visit(name, parent):
        bone = bones[name]
        attribute = attributes.get(name, {})
        if 0 in attribute.get("scale", [1, 1, 1]):
            return
        pose = multiply(parent, local(bone, attribute))
        result["poses"][name] = pose
        if not attribute.get("cubes_hidden", False):
            for cube in bone["cubes"]:
                result["visible_cubes"].append(cube["id"])
        if not attribute.get("children_hidden", False):
            for child in model["bones"]:
                if child.get("parent") == name:
                    visit(child["name"], pose)

    if root is None:
        for bone in model["bones"]:
            if not bone.get("parent"):
                visit(bone["name"], identity())
    else:
        visit(root, identity())
    return result


FACES = [([7, 3, 0, 4], [-1, 0, 0]), ([2, 6, 5, 1], [1, 0, 0]),
         ([3, 2, 1, 0], [0, 0, -1]), ([6, 7, 4, 5], [0, 0, 1]),
         ([7, 6, 2, 3], [0, 1, 0]), ([0, 1, 5, 4], [0, -1, 0])]


def cube(identifier, origin, size, marker, faces=6):
    return {"id": identifier, "origin": origin, "size": size,
            "marker": marker, "faces": faces}


def bone(name, parent=None, number=0):
    result = {"name": name, "pivot": [number * 2, 16, 0], "rotate": [0, 0, 0], "cubes": []}
    if parent:
        result["parent"] = parent
    return result


def parts(replacement=False):
    bones = [bone("Outside")]
    for number, name in enumerate(("LeftArm", "RightArm", "Background")):
        root = bone(name, "Outside", number)
        child = bone(name + "Child1", name, number)
        root["rotate"] = [[0, 0, 0.1], [0, 0, -0.1], [0.2, -0.1, 0.3]][number]
        child["rotate"] = [0, 0, 0.05]
        for suffix, target in (("root", root), ("child", child)):
            target["cubes"] = [cube(name + ":" + suffix,
                                   [number * 0.6, 0.8 if suffix == "root" else 1.2, 0],
                                   [0.25, 0.25, 0.25] if not replacement else [0.35, 0.4, 0.3],
                                   number * 2 + (suffix == "child"))]
        bones.extend((root, child))
    unrelated = bone("Unrelated", "Outside")
    unrelated["cubes"] = [cube("sibling", [2, 0, 0], [0.25, 0.25, 0.25], 6)]
    bones.append(unrelated)
    return {"coordinate_system": "YSM-model-blocks", "bones": bones}


def encode(protoc, schema, name, text):
    return subprocess.check_output([str(protoc), f"--proto_path={schema.parent}",
                                    f"--encode=mixel.asset.model.data.{name}", schema.name],
                                   input=text.encode("utf-8"))


def vector(name, values):
    return " ".join(f"{name}: {value:.9g}" for value in values)


def export_model(output, name, model, protoc, schema):
    save(output / f"{name}.json", model)
    cubes = []
    raw_bones = []
    for item in model["bones"]:
        raw = {"name": item["name"], "pivot": [-item["pivot"][0], *item["pivot"][1:]], "cubes": []}
        raw["rotation"] = [-math.degrees(item["rotate"][0]), -math.degrees(item["rotate"][1]), math.degrees(item["rotate"][2])]
        if item.get("parent"):
            raw["parent"] = item["parent"]
        for primitive in item["cubes"]:
            x, y, z = primitive["origin"]
            sx, sy, sz = primitive["size"]
            positions = [(x, y, z), (x + sx, y, z), (x + sx, y + sy, z), (x, y + sy, z),
                         (x, y, z + sz), (x + sx, y, z + sz),
                         (x + sx, y + sy, z + sz), (x, y + sy, z + sz)]
            selected = FACES[:primitive["faces"]]
            marker = primitive["marker"]
            u = (marker % 8) * 2 / 16
            v = (marker // 8) * 2 / 16
            uv = [u + 1 / 16, v, u, v, u, v + 1 / 16, u + 1 / 16, v + 1 / 16]
            cubes.append("cubes_legacy { " + f"faceCount: {len(selected)} " +
                         vector("pos", [value for point in positions for value in point]) + " " +
                         vector("pos_indices", [value for face, _ in selected for value in face]) + " " +
                         vector("uv", uv) + " " + vector("uv_indices", list(range(4)) * len(selected)) + " " +
                         vector("normal", [value for _, normal in selected for value in normal]) + " }")
            raw_cube = {"origin": [-(x + sx) * 16, y * 16, z * 16],
                        "size": [sx * 16, sy * 16, sz * 16], "uv": {}}
            for face_name in ("west", "east", "north", "south", "up", "down")[:len(selected)]:
                raw_cube["uv"][face_name] = {"uv": [u * 16, v * 16], "uv_size": [1, 1]}
            raw["cubes"].append(raw_cube)
        raw_bones.append(raw)
    cube_text = "\n".join(cubes)
    cube_bytes = encode(protoc, schema, "Cubes", cube_text)
    text = []
    for item in model["bones"]:
        text.append('bones { name: ' + json.dumps(item["name"]) + " " +
                    ('parent: ' + json.dumps(item["parent"]) + " " if item.get("parent") else "") +
                    vector("pivot", item["pivot"]) + " " + vector("rotate", item["rotate"]) + " " +
                    f"cubeCount: {len(item['cubes'])} }}")
    text.append("properties { texture_height: 16 texture_width: 16 }")
    text.append('cubes: "' + "".join(f"\\{value:03o}" for value in cube_bytes) + '"')
    proto_text = "\n".join(text)
    (output / f"{name}.textproto").write_text(proto_text + "\n", encoding="utf-8")
    (output / f"{name}.pb").write_bytes(encode(protoc, schema, "GeoModel", proto_text))
    save(output / f"{name}.geo.json", {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry." + name, "texture_width": 16, "texture_height": 16},
        "bones": raw_bones}]})
    return {"bone_count": len(model["bones"]), "cube_count": len(cubes),
            "quad_count": sum(c["faces"] for b in model["bones"] for c in b["cubes"]),
            "native_group_count": None,
            "material_count": 1, "capacity": "runtime SIMD padding is not visible geometry"}


def texture(path, variant=0):
    palette = [(255, 32, 32), (255, 160, 32), (32, 255, 32), (32, 255, 160),
               (32, 32, 255), (160, 32, 255), (255, 255, 32), (255, 32, 255)]
    scan = b"".join(b"\0" + bytes(value for x in range(16)
                                  for value in (*palette[(x // 2 + variant) % 8], 255 if y < 8 else 128))
                     for y in range(16))
    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) +
                     chunk(b"IDAT", zlib.compress(scan)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--protoc", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    base = parts()
    models = {"parts-a": base, "parts-b": parts(True)}
    alias = copy.deepcopy(base)
    for item in alias["bones"]:
        for field in ("name", "parent"):
            if field in item:
                item[field] = {"LeftArm": "LeftArm1", "RightArm": "rightarm", "Background": "background"}.get(item[field], item[field])
    models["parts-aliases"] = alias
    for name in ("LeftArm", "Background"):
        missing = copy.deepcopy(base)
        missing["bones"] = [b for b in missing["bones"] if b["name"] not in (name, name + "Child1")]
        models["missing-" + name.lower()] = missing
    surface = {"coordinate_system": "YSM-model-blocks", "bones": [bone("LeftArm")]}
    for number, size in enumerate(([0.25] * 3, [-0.25, 0.25, 0.25], [0.25, -0.25, 0.25],
                                   [0.25, 0.25, -0.25], [0.25] * 3, [0.25] * 3, [0.25] * 3)):
        surface["bones"][0]["cubes"].append(cube("surface:" + str(number),
            [number * 0.4, 0, 0], size, number if number < 5 else number + 32,
            faces=1 if number == 4 else 6))
    models["surfaces"] = surface
    supported_surface = copy.deepcopy(surface)
    # Use outward shells to isolate output-path equivalence from reversed-shell culling.
    for primitive in supported_surface["bones"][0]["cubes"]:
        primitive["size"] = [abs(value) for value in primitive["size"]]
    models["surfaces-supported"] = supported_surface
    workloads = {}
    for count in (32, 96):
        model = {"coordinate_system": "YSM-model-blocks", "bones": [bone("Body")]}
        model["bones"][0]["cubes"] = [cube("body:0", [0, 0, 0], [0.25] * 3, 0)]
        for index in range(1, count):
            item = bone(f"Body{index}", "Body", index % 8)
            item["cubes"] = [cube(f"body:{index}", [index % 8 * 0.3, index // 8 * 0.3, 0], [0.25] * 3, index % 8)]
            model["bones"].append(item)
        name = f"body-{count}"
        models[name] = model
        workloads[name] = {"frames": [{"logical_tick": frame, "attributes": {
            b["name"]: {"rotation": [0, 0, frame * 0.02], "cubes_hidden": index % 2 == frame % 2}
            for index, b in enumerate(model["bones"])}} for frame in range(8)],
            "stable_visible_set": "all bones visible; rotations follow the same eight frames",
            "changing_visible_set": "alternating even/odd cube visibility; same draw count"}
    stats = {name: export_model(output, name, model, args.protoc, args.schema) for name, model in models.items()}
    scenarios = {"initial": {}}
    for name in ("Outside", "LeftArm", "LeftArmChild1"):
        for kind, values in (("translation", {"position": [5, 3, 2]}),
                             ("rotation", {"rotation": [0.2, 0.3, 0.4]}),
                             ("nonuniform", {"scale": [2, 0.5, 1.5]}),
                             ("zero", {"scale": [0, 1, 1]}),
                             ("hide-self", {"cubes_hidden": True}),
                             ("hide-children", {"children_hidden": True})):
            scenarios[name + ":" + kind] = {name: values}
    scenarios["all-hidden"] = {b["name"]: {"cubes_hidden": True} for b in base["bones"]}
    animation = [{"logical_tick": tick, "attributes": {
        "LeftArm": {"position": [tick * 2, 0, 0], "rotation": [0, 0, 0.1 + tick * 0.3]},
        "RightArm": {"position": [-tick * 2, 0, 0], "rotation": [0, 0, -0.1 - tick * 0.3]},
        "Background": {"position": [0, tick * 10, 0], "rotation": [0.2 + tick * 0.5, -0.1, 0.3]}}} for tick in range(4)]
    save(output / "pose-oracles.json", {"matrix_layout": "row-major; column vectors",
        "local_formula": "T((pivot + (-position.x,position.y,position.z))/16) * Rz * Ry * Rx * S * T(-pivot/16)",
        "scenarios": {name: {"attributes": attributes, "expected": {
            root: oracle(base, None if root == "body" else root, attributes)
            for root in ("body", "LeftArm", "RightArm", "Background")}} for name, attributes in scenarios.items()},
        "animation": [{**frame, "hands": {root: oracle(base, root, frame["attributes"])
            for root in ("LeftArm", "RightArm")}, "background": oracle(base, "Background", {})} for frame in animation]})
    save(output / "workloads.json", workloads)
    save(output / "actions-and-controls.json", {
        "action_events": [{"logical_tick": tick, "kind": kind, "id": f"{kind}-{tick}"}
                          for tick in (1, 3) for kind in ("sound", "script", "particle", "sync")],
        "observations": [{"logical_tick": tick, "sequence": ["background", "left", "right", "left", "body", "background", "right"]}
                         for tick in range(4)],
        "expected_cumulative_actions": {"0": [0, 0, 0, 0], "1": [1, 1, 1, 1], "2": [1, 1, 1, 1], "3": [2, 2, 2, 2]},
        "controls": ["self-model-disabled", "self-arms-disabled", "reenabled-after-replacement",
                     "hook-rejected", "hook-overridden-texture", "next-draw-no-override", "legal-empty", "missing-root"],
        "production_bindings": "T2 must bind event IDs to each real action submission seam; mock counts do not prove those seams."})
    save(output / "failure-and-host-inputs.json", {
        "controlled_failures": ["local-extract", "jni-output-writeback", "capacity-reservation", "native-generation-midway"],
        "consumer_exception": {"identity": "fixture-known-consumer-exception", "boundary": "real fallback replay through outer draw", "recovery_required": False},
        "lifetime": ["model-a-to-b", "texture-variant", "reload", "player-replacement", "logout"],
        "host_scenarios": ["standard-left", "standard-right", "background-first", "background-last", "background-only",
                           "swarfare-left-old", "swarfare-right-old", "swarfare-left-new", "swarfare-right-new"],
        "host_reference_status": "not captured; F6 requires real host matrices/weapon state before visual comparison",
        "camera": {"fov": 70, "view_bob": [False, True], "light": ["day", "night"], "shader": "off baseline; Oculus/PBR separate unrun combination"}})
    texture(output / "markers-a.png")
    texture(output / "markers-b.png", 3)
    for name in ("parts-a", "parts-b", "parts-aliases", "missing-leftarm", "missing-background", "surfaces"):
        pack = output / "raw-packs" / name
        pack.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output / f"{name}.geo.json", pack / "model.geo.json")
        shutil.copyfile(output / ("markers-b.png" if name == "parts-b" else "markers-a.png"), pack / "markers.png")
        save(pack / "ysm.json", {"spec": 2, "metadata": {"name": "First Person Fixture " + name},
            "properties": {"default_texture": "markers", "free": True},
            "files": {"player": {"model": {"main": "model.geo.json", "arm": "model.geo.json"},
                "animation": {"main": "pose.animation.json", "arm": "pose.animation.json", "fp_arm": "pose.animation.json"},
                "texture": ["markers.png"]}}})
        animated_bones = {}
        declaration = {b["name"]: b for b in base["bones"]}
        available = {b["name"] for b in models[name]["bones"]}
        for root in ("LeftArm", "RightArm", "Background"):
            actual_root = {"LeftArm": "LeftArm1", "RightArm": "rightarm", "Background": "background"}.get(root, root) if name == "parts-aliases" else root
            if actual_root not in available:
                continue
            animated_bones[actual_root] = {"position": {}, "rotation": {}}
            for frame in animation:
                key = str(frame["logical_tick"] / 20)
                attributes = frame["attributes"][root]
                animated_bones[actual_root]["position"][key] = attributes["position"]
                rotation = [value - initial for value, initial in zip(attributes["rotation"], declaration[root]["rotate"])]
                animated_bones[actual_root]["rotation"][key] = [-math.degrees(rotation[0]), -math.degrees(rotation[1]), math.degrees(rotation[2])]
        save(pack / "pose.animation.json", {"format_version": "1.8.0", "animations": {
            "idle": {"loop": True, "animation_length": 0.2, "bones": animated_bones}}})
    manifest = {"schema_version": 1, "fixture_revision": 1, "models": stats,
                "fixtures": {"F1": ["parts-a", "parts-aliases", "missing-leftarm", "pose-oracles.json"],
                             "F2": ["parts-a", "parts-b", "markers-a.png", "markers-b.png", "pose-oracles.json"],
                             "F3": ["actions-and-controls.json"], "F4": ["failure-and-host-inputs.json"],
                             "F5": ["surfaces", "surfaces-supported"], "F6": ["failure-and-host-inputs.json"],
                             "F7": ["body-32", "body-96", "workloads.json"]},
                "tolerance": {"position_absolute_blocks": 0.00002, "matrix_absolute": 0.00001,
                              "normal_tangent_absolute": 0.00002, "integer_indices_and_counts": "exact",
                              "basis": "independent Python double math; bounded coordinates and shallow hierarchy; fixed before candidate output"},
                "scope": "Source inputs and numerical expectations; no game, action submission or performance pass. Raw/protobuf equivalence must pass FirstPersonRawFixtureTest.",
                "protoc_sha256": hashlib.sha256(args.protoc.read_bytes()).hexdigest(),
                "generator_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "schema_sha256": hashlib.sha256(args.schema.read_bytes()).hexdigest(),
                "files": {str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in sorted(output.rglob("*")) if path.is_file() and path.name != "manifest.json"}}
    save(output / "manifest.json", manifest)
    print(json.dumps({"models": len(models), "files": len(manifest["files"]), "output": str(output)}))


if __name__ == "__main__":
    main()
