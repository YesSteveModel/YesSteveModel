"""Summarize each interleaved block and compare same-repetition frame percentiles."""

import csv
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import median
import sys
import summarize_witness


MODES = ("animation", "no_interpart", "full")
PERCENTILES = (50, 95, 99)
TIMES = ("fullNs", "renderNs", "claimedNs", "demandJoinNs", "drainJoinNs")


def percentile(values, p):
    items = sorted(values)
    return items[math.ceil(p * len(items) / 100) - 1] / 1_000_000


def timing(rows, field):
    values = [row[field] for row in rows]
    return {f"{field}_p{p}_ms": percentile(values, p) for p in PERCENTILES}


def window_summary(rows, visit_index, visit, view, riding, paired=False):
    count = visit["test"]["entities"]
    incoming = paired or (view - 1 + visit["repeat"]) % 2 == 0
    expected = count if incoming else 0
    expected_worlds = count if visit["mode"] else 0
    report = dict(
        visit=visit_index, case=visit["test"]["name"], count=count, repeat=visit["repeat"], view=view,
        camera="in" if incoming else "out", mode=MODES[visit["mode"]], frames=len(rows),
        visible_min=min(row["visible"] for row in rows), visible_max=max(row["visible"] for row in rows),
        world_min=min(row["worlds"] for row in rows), world_max=max(row["worlds"] for row in rows),
        mismatch_frames=sum(row["visible"] != expected or row["replacements"] != expected
                            or row["worlds"] != expected_worlds or row["rejected"] != 0 for row in rows),
        steps=sum(row["steps"] for row in rows), resets=sum(row["resets"] for row in rows),
        rejected=sum(row["rejected"] for row in rows), claim_count=sum(row["claimedTasks"] for row in rows),
        demand_joins=sum(row["demandJoins"] for row in rows), drain_joins=sum(row["drainJoins"] for row in rows),
    )
    for field in TIMES:
        report.update(timing(rows, field))
    if riding:
        if not any(row["passengerSamplingNs"] for row in rows):
            raise ValueError(f"no passenger sampling for visit {visit_index}, view {view}")
        report.update(timing(rows, "passengerSamplingNs"))
    return report


def write_windows(output, windows):
    with (output / "windows.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, windows[0].keys())
        writer.writeheader()
        writer.writerows(windows)


def distribution(samples):
    return dict(min=min(samples), median=median(samples), max=max(samples))


def paired_delta(first, center, last, field):
    elapsed = last["midpointNs"] - first["midpointNs"]
    fraction = (center["midpointNs"] - first["midpointNs"]) / elapsed
    a, b, c = (row[field] for row in (first, center, last))
    interpolated = a + fraction * (c - a)
    # The bracket endpoint differences expose drift without assuming it was linear.
    sign = 1 if center["mode"] != "animation" else -1
    endpoints = sorted((sign * (b - a), sign * (b - c)))
    return dict(estimate=sign * (b - interpolated), min=endpoints[0], max=endpoints[1])


def summarize_pairing(data, plan, frames, output):
    scheduled = plan["pairingVisits"]
    if len(data["visits"]) != len(scheduled):
        raise ValueError("runtime visit count differs from the pairing plan")
    windows, failures = [], []
    blocks, noise = defaultdict(list), defaultdict(list)
    for index, (visit, entry) in enumerate(zip(data["visits"], scheduled)):
        case = plan["cases"][entry["caseIndex"]]
        if (visit["test"]["name"], visit["test"]["entities"], visit["mode"], visit["repeat"], visit["model"]) != (
            case["name"], case["entities"], entry["mode"], entry["repeat"], case["models"][entry["mode"]]
        ):
            raise ValueError(f"runtime visit {index} differs from the pairing plan")
        rows = frames.pop((index, 1), [])
        if entry["warmup"]:
            if rows:
                raise ValueError(f"warmup visit {index} contributed measured frames")
            continue
        if not rows:
            raise ValueError(f"no measured frames for visit {index}")
        window = window_summary(rows, index, visit, 1, False, paired=True)
        window["midpointNs"] = (rows[0]["frameStartNs"] + rows[-1]["frameStartNs"] + rows[-1]["fullNs"]) // 2
        windows.append(window)
        if (window["mismatch_frames"] or window["resets"]
                or bool(window["steps"]) != (window["mode"] != "animation")):
            failures.append(window)
        blocks[window["case"], window["repeat"]].append(window)
        if window["mode"] == "animation":
            noise[window["case"]].append(window)
    if frames:
        raise ValueError(f"unexpected measured visit/view keys: {list(frames)}")
    write_windows(output, windows)

    triplets, comparisons = [], defaultdict(list)
    invalid = {row["visit"] for row in failures}
    for (case, repeat), rows in blocks.items():
        modes = [row["mode"] for row in rows]
        if len(rows) != 3 or modes[0] != modes[2] or modes[0] == modes[1] or "animation" not in modes:
            raise ValueError(f"case {case} block {repeat} is not a symmetric triplet: {modes}")
        if any(row["visit"] in invalid for row in rows):
            continue
        if not rows[0]["midpointNs"] < rows[1]["midpointNs"] < rows[2]["midpointNs"]:
            raise ValueError(f"case {case} block {repeat} has unordered sampling times")
        mode = next(mode for mode in modes if mode != "animation")
        comparison = dict(case=case, repeat=repeat, mode=mode, order=modes,
                          visits=[row["visit"] for row in rows], frames=[row["frames"] for row in rows])
        for p in PERCENTILES:
            comparison[f"delta_p{p}_ms"] = paired_delta(*rows, f"fullNs_p{p}_ms")
        triplets.append(comparison)
        comparisons[case, mode].append(comparison)

    deltas = []
    for (case, mode), rows in comparisons.items():
        comparison = dict(case=case, mode=mode, blocks=len(rows))
        for p in PERCENTILES:
            metric = f"delta_p{p}_ms"
            values = [row[metric] for row in rows]
            lower, upper = min(value["min"] for value in values), max(value["max"] for value in values)
            direction = "positive" if lower > 0 else "negative" if upper < 0 else "unresolved"
            comparison[metric] = dict(estimate=distribution([value["estimate"] for value in values]),
                                      endpointRange=[lower, upper], direction=direction)
        deltas.append(comparison)
    animation_noise = []
    for case, rows in noise.items():
        valid = [row for row in rows if row["visit"] not in invalid]
        if valid:
            animation_noise.append(dict(case=case, windows=len(valid), **{
                f"p{p}_ms": distribution([row[f"fullNs_p{p}_ms"] for row in valid]) for p in PERCENTILES
            }))
    report = dict(width=data["width"], height=data["height"], displayRefreshHz=data["refreshRate"],
                  warmup_visits=sum(entry["warmup"] for entry in scheduled), triplets=triplets,
                  deltas=deltas, animation_noise=animation_noise, invalid_windows=failures)
    (output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(windows)} windows, {len(triplets)} paired triplets, {len(failures)} invalid windows")
    return bool(failures)


def main(output):
    data = json.loads((output / "samples.json").read_text(encoding="utf-8"))
    if not data["completed"]:
        raise ValueError(data["failure"])
    (output / "assets.json").write_text(json.dumps(data["assets"], indent=2) + "\n", encoding="utf-8")
    plan = json.loads((output / "plan.json").read_text(encoding="utf-8"))
    if plan.get("witness"):
        if not data["visits"] or not all(visit.get("sequence") for visit in data["visits"]):
            raise ValueError("Witness visits require a replay sequence tied to the animation and root clock")
        return summarize_witness.summarize(data, output)
    if plan.get("contactCheck"):
        rows = []
        for index, visit in enumerate(data["visits"]):
            if visit["mode"] == 0:
                continue
            samples = [item for item in data["contacts"] if item["visit"] == index]
            rows.append(dict(case=visit["test"]["name"], mode=MODES[visit["mode"]], samples=len(samples),
                             interpart_max=max((item["interpartPairs"] for item in samples), default=0),
                             body_max=max((item["bodyPairs"] for item in samples), default=0)))
        (output / "contacts.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote contact witnesses with {len(data['contacts'])} samples")
        return any(not row["samples"] or not row["body_max"]
                   or bool(row["interpart_max"]) != (row["mode"] == "full") for row in rows)
    frames = defaultdict(list)
    for row in data["frames"]:
        frames[row["visit"], row["view"]].append(row)
    if plan.get("pairing"):
        return summarize_pairing(data, plan, frames, output)
    windows = []
    for index, visit in enumerate(data["visits"]):
        for view in (1, 2, 3):
            rows = frames[index, view]
            if not rows:
                raise ValueError(f"no frames for visit {index}, view {view}")
            windows.append(window_summary(rows, index, visit, view, plan.get("riding")))
    write_windows(output, windows)
    baselines = {(row["case"], row["repeat"], row["view"]): row for row in windows if row["mode"] == "animation"}
    comparisons = defaultdict(list)
    noise = defaultdict(list)
    failures = []
    for row in windows:
        key = row["case"], row["repeat"], row["view"]
        baseline = baselines[key]
        if row["mismatch_frames"] or (row["mode"] != "animation" and not row["steps"]):
            failures.append({key: row[key] for key in ("case", "repeat", "view", "mode", "mismatch_frames", "steps")})
            continue
        if baseline["mismatch_frames"]:
            continue
        if row["mode"] == "animation":
            noise[row["case"], row["camera"]].append(row)
        else:
            comparisons[row["case"], row["camera"], row["mode"]].append({
                f"delta_p{p}_ms": row[f"fullNs_p{p}_ms"] - baseline[f"fullNs_p{p}_ms"] for p in PERCENTILES
            })
    report = dict(width=data["width"], height=data["height"], displayRefreshHz=data["refreshRate"], deltas=[],
                  animation_noise=[], invalid_windows=failures)
    for (case, camera, mode), rows in comparisons.items():
        values = {}
        for key in rows[0]:
            samples = [row[key] for row in rows]
            values[key] = distribution(samples)
        report["deltas"].append(dict(case=case, camera=camera, mode=mode, windows=len(rows), **values))
    for (case, camera), rows in noise.items():
        spread = {}
        for p in PERCENTILES:
            samples = [row[f"fullNs_p{p}_ms"] for row in rows]
            spread[f"p{p}_range_ms"] = [min(samples), max(samples)]
        report["animation_noise"].append(dict(case=case, camera=camera, windows=len(rows), **spread))
    (output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(windows)} windows, {len(data['frames'])} frames, {len(failures)} invalid windows")
    return bool(failures)


if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1])))
