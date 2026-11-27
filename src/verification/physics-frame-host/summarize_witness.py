"""Validate coupled Forge main samples and measure authored-box overlap from copied poses."""

from itertools import combinations, product
import json
import math
import struct


MODES = ("animation", "no_interpart", "full")
STEP, RESET = 2, 1


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    x, y, z = a
    i, j, k = b
    return y * k - z * j, z * i - x * k, x * j - y * i


def rotate(q, v):
    u, w = q[:3], q[3]
    uv, uuv = cross(u, v), cross(u, cross(u, v))
    return tuple(a + 2 * (w * b + c) for a, b, c in zip(v, uv, uuv))


def box_pose(box, node):
    pose = node["pose"]
    scale = node["scale"] / 16
    rotation = tuple(pose["rotation"][axis] for axis in "xyzw")
    offset = rotate(rotation, tuple(value * scale for value in box["center"]))
    center = tuple(pose["position"][axis] + offset[i] for i, axis in enumerate("xyz"))
    axes = tuple(rotate(rotation, rotate(box["rotation"], axis))
                 for axis in ((1, 0, 0), (0, 1, 0), (0, 0, 1)))
    return center, axes, tuple(value * scale / 2 for value in box["size"])


def overlap(first, second):
    a, aa, ah = first
    b, ba, bh = second
    separation = tuple(x - y for x, y in zip(a, b))
    depths = []
    for axis in (*aa, *ba, *(cross(i, j) for i, j in product(aa, ba))):
        norm = math.sqrt(dot(axis, axis))
        if norm > 1e-6:
            left = sum(edge * abs(dot(axis, basis)) for edge, basis in zip(ah, aa))
            right = sum(edge * abs(dot(axis, basis)) for edge, basis in zip(bh, ba))
            depths.append((left + right - abs(dot(axis, separation))) / norm)
    return min(depths)


def proxy_depths(nodes, poses, target_pairs, body_pairs):
    boxes = [[box_pose(box, pose) for box in node["boxes"]] if pose["active"] and pose.get("pose") else []
             for node, pose in zip(nodes, poses, strict=True)]
    result = {}
    for label, pairs in (("interpart", target_pairs), ("body", body_pairs)):
        values = [(overlap(x, y), nodes[i]["id"], nodes[j]["id"])
                  for i, j in pairs for x, y in product(boxes[i], boxes[j])]
        if values:
            metres, first, second = max(values)
            result[label] = dict(metres=metres, pair=[first, second])
    return result


def contiguous_low_steps(samples):
    streak = longest = 0
    for sample in samples:
        streak = streak + 1 if 0.075 <= sample["interval"] <= 0.125 and sample["mode"] == STEP else 0
        longest = max(longest, streak)
    return longest


def float32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def collision_steps(interval):
    # The source interval and the native division are float32 before ceil.
    quotient = float32(float32(interval) / float32(0.05))
    return min(4, max(1, math.ceil(float32(quotient - float32(1e-6)))))


def valid_timing(samples):
    previous_end = None
    for row in samples:
        timing = row.get("timing")
        if not timing:
            return False
        start = timing["frameStartNs"]
        render = timing["renderNs"]
        if (previous_end is not None and start < previous_end) or render <= 0:
            return False
        if not all(0 <= timing[field] <= render for field in ("claimedNs", "demandJoinNs", "drainJoinNs")):
            return False
        if timing["screenshotNs"] < 0 or not timing["drainJoins"] and timing["drainJoinNs"]:
            return False
        previous_end = start + render + timing["screenshotNs"]
    return bool(samples)


def summarize(data, output):
    visits = {(visit["test"]["name"], visit["mode"], visit["sequence"]): index
              for index, visit in enumerate(data["visits"])}
    assets = {asset["model"]: asset["nodes"] for asset in data["assets"]}
    rows = {}
    for sample in data["clockSamples"]:
        rows.setdefault(sample["visit"], []).append(sample)
    poses = {}
    for contact in data["contacts"]:
        poses.setdefault(contact["visit"], []).append(contact)
    reports, depths = [], {}
    for (case, mode, sequence), index in visits.items():
        if not mode:
            continue
        samples = rows.get(index, [])
        snapshots = poses.get(index, [])
        failures = []

        def check(condition, reason):
            if not condition:
                failures.append(reason)

        check(bool(samples) and len(samples) == len(snapshots), "missing per-main-sample snapshot")
        check(valid_timing(samples), "missing or invalid host render timing for an accepted sample")
        check(all(row["flags"] & 2 and not ((row["flags"] >> 2) & 15) for row in samples), "native failure")
        for sample, snapshot in zip(samples, snapshots):
            if sample["sample"] != snapshot["sample"] or snapshot["sampled"] != (sample["mode"] == STEP):
                failures.append("capture generation or contact listener mismatch")
                break
        visit = data["visits"][index]
        nodes = assets[visit["model"]]
        full = assets[visit["test"]["models"][2]]
        dynamic = [i for i, node in enumerate(full) if node["kind"] == "DYNAMIC"]
        targets = [(i, j) for i, j in combinations(dynamic, 2) if full[i]["collectionMask"] & full[j]["collectionMask"]]
        bodies = [(i, j) for i in dynamic for j, node in enumerate(full)
                  if node["kind"] == "PROXY" and full[i]["collectionMask"] & node["collectionMask"]]
        pose_depths = [proxy_depths(nodes, snapshot["nodes"], targets, bodies) for snapshot in snapshots]
        depths[index] = dict(case=case, mode=MODES[mode], sequence=sequence, visit=index, frames=pose_depths)
        body_peak = max((row["bodyPairs"] for row in snapshots), default=0)
        interpart_peak = max((row["interpartPairs"] for row in snapshots), default=0)
        if sequence == "near":
            check(body_peak > 0, "near replay missed body contact")
            check((interpart_peak > 0) == (mode == 2), "near replay did not distinguish piece contact")

        report = dict(case=case, mode=MODES[mode], sequence=sequence, visit=index, accepted=len(samples),
                      body_peak=body_peak, interpart_peak=interpart_peak, failures=failures)
        reports.append(report)
        if sequence == "natural_10fps":
            longest = contiguous_low_steps([row for row in samples if 2 <= row["tick"] <= 30])
            check(longest >= 10, "no continuous actual 10 FPS main-step sequence")
            check(any(row["interval"] < 0.035 and row["mode"] == STEP for row in samples if row["tick"] >= 35),
                  "no high-frequency recovery")
            report["longest_10fps"] = longest
            continue
        check(all(math.isclose(row["time"], row["actionTime"], abs_tol=3e-7) for row in samples),
              "animation and physics clocks diverged")
        check(all(math.isclose(row["yaw"], visit["test"].get("witnessAngle", 0) + 180
                               + 80 * math.sin(min(row["actionTime"], 2.4) * 2 * math.pi / 1.2), abs_tol=4e-5)
                  for row in samples), "root and action clocks diverged")
        if sequence == "near":
            check(all(0.016 <= row["interval"] <= 0.017 for row in samples[1:]),
                  "near reference lacks a regular high-frequency cadence")
            continue
        threshold = next((i for i, row in enumerate(samples) if row["interval"] > 0.075), None)
        check(threshold is not None and threshold > 0, "controlled interval missing")
        if threshold is None or threshold == 0:
            continue
        check(snapshots[threshold - 1]["bodyPairs"] > 0, "controlled sequence started outside body load")
        near_index = visits[case, 2, "near"]
        near = rows.get(near_index, [])

        def match(label, position):
            row = samples[position]
            reference = next((i for i, item in enumerate(near) if item["actionTime"] == row["actionTime"]), None)
            check(reference is not None and (output / f"visit-{index:02d}-{label}.png").is_file()
                  and (output / f"visit-{near_index:02d}-{label}.png").is_file(),
                  f"{label} is missing its exact near action or draw")
            return dict(time=row["time"], yaw=row["yaw"], body=snapshots[position]["bodyPairs"],
                        interpart=snapshots[position]["interpartPairs"], candidate=pose_depths[position],
                        sample=row["sample"], timing=row.get("timing"),
                        near_visit=near_index, near_sample=reference,
                        near_timing=near[reference].get("timing") if reference is not None else None,
                        near=depths[near_index]["frames"][reference] if reference is not None else {})

        report["pre"] = match("pre", threshold - 1)
        if sequence == "reset_025":
            reset = samples[threshold]
            check(reset["interval"] == 0.25 and reset["mode"] == RESET and not snapshots[threshold]["stepped"],
                  "0.250 s did not reset without solve")
            check(threshold + 1 < len(samples) and samples[threshold + 1]["mode"] == STEP, "reset did not resume STEP")
            report["reset_target"] = match("reset_target", threshold)
            continue
        steps = samples[threshold:threshold + 11]
        check(len(steps) == 11 and all(row["mode"] == STEP for row in steps) and all(
            math.isclose(row["interval"], target, abs_tol=3e-7) for row, target in
            zip(steps, [0.1] * 10 + [0.249])), "missing ten 100 ms and one 249 ms STEP")
        report["internal_collision_steps"] = [collision_steps(row["interval"]) for row in steps]
        report["low_frequency_samples"] = [row["sample"] for row in steps[:-1]]
        check(all(row["mode"] == STEP for row in samples[threshold:]), "unexpected reset during recovery")
        if len(samples) > threshold + 100:
            check(abs(samples[threshold + 10]["yaw"] - samples[threshold + 9]["yaw"]) > 1,
                  "249 ms sample did not cross a root turn")
            report["edge_contact"] = [snapshots[threshold + offset]["bodyPairs"] for offset in (9, 10)]
            check(max(report["edge_contact"]) > 0, "249 ms main update lacks adjacent body contact evidence")
            for label, offset in (("post", 10), ("recover", 25), ("rest", 100)):
                report[label] = match(label, threshold + offset)
        else:
            failures.append("recovery checkpoints missing")
    for filename, content in (("clock.json", reports), ("box-overlap.json", list(depths.values()))):
        (output / filename).write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(reports)} coupled witness sequences")
    return any(report["failures"] for report in reports)
