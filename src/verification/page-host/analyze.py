"""Recompute fixed-window observations without treating frames as independent runs."""
import argparse
import csv
import hashlib
import json
import random
from pathlib import Path
from statistics import mean


def interval(values, repetitions=10000, seed=20261030):
    if not values:
        return None
    generator = random.Random(seed)
    sampled = sorted(mean(generator.choices(values, k=len(values))) for _ in range(repetitions))
    return [sampled[int(repetitions * .025)], sampled[int(repetitions * .975)]]


def windows(directory, window_ns=2_000_000_000):
    visits = json.loads((directory / "visits.json").read_text())
    with (directory / "frames.csv").open() as source:
        frames = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(source)]
    result = []
    for visit in visits:
        if visit["scene"] in {"warmup", "expired-wait"}:
            continue
        beginning = visit["inputNanos"]
        end = beginning + window_ns
        selected = [frame for frame in frames if beginning <= frame["start_ns"] < end]
        result.append({"visit": visit["visit"], "page": visit["page"], "scene": visit["scene"],
                       "start_ns": beginning, "end_ns": end,
                       "window_complete": bool(visits) and visits[-1].get("endNanos", 0) >= end,
                       "max_gap_ms": max((row["gap_ns"] for row in selected), default=0) / 1e6,
                       "frame_gaps_ms": [row["gap_ns"] / 1e6 for row in selected],
                       "render_wall_ms": sum(row["render_ns"] for row in selected) / 1e6,
                       "main_cpu_ms": sum(row["cpu_ns"] for row in selected) / 1e6})
    return result


def analyze(manifest_path):
    manifest = json.loads(manifest_path.read_text())
    runs = []
    for run in manifest["runs"]:
        directory = manifest_path.parent / run["directory"]
        if not (directory / "result.json").exists():
            runs.append({**run, "completed": False})
            continue
        result = json.loads((directory / "result.json").read_text())
        runs.append({**run, **result, "windows": windows(directory), "files": {
            file.name: hashlib.sha256(file.read_bytes()).hexdigest()
            for file in directory.iterdir() if file.is_file() and file.suffix in {".json", ".csv", ".jfr"}}})
    comparisons = []
    for eager in [False, True]:
        for page in range(3):
            for scene in ["hot", "stable", "cold", "expired", "rapid", "steady"]:
                pairs = {}
                for run in runs:
                    if not run["completed"] or run.get("pilot") or run["eager"] != eager:
                        continue
                    for window in run["windows"]:
                        if window["scene"] == scene and window["page"] == page and window["window_complete"]:
                            pairs.setdefault(run["pair"], {}).setdefault(run["version"], []).append(window["max_gap_ms"])
                differences = [mean(pair["baseline"]) - mean(pair["candidate"]) for pair in pairs.values()
                               if "baseline" in pair and "candidate" in pair]
                ci = interval(differences) if len(differences) >= manifest["required_pairs"] else None
                comparisons.append({"eager": eager, "page": page, "scene": scene,
                                    "pairs": len(differences), "baseline_minus_candidate_ms": differences,
                                    "mean_difference_ci95_ms": ci,
                                    "verdict": "improved" if scene in {"hot", "stable"} and ci and ci[0] > 0
                                    else "inconclusive"})
    return {"verdict": "inconclusive", "reason": "A9 also requires resource-state proof, A1, baseline noise bands,"
            " first recognizable 3D, and all required page/mode/scene groups.",
            "seed": 20261030, "bootstrap_repetitions": 10000, "runs": runs, "comparisons": comparisons,
            "boundaries": ["Render wall minus main CPU does not identify wait or background competition.",
                           "Rapid windows intentionally overlap; use the complete script as the resampling unit.",
                           "Entry counts do not prove successful uploads or physical releases.",
                           "First access is not automatically a proven cold resource state."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    arguments.output.write_text(json.dumps(analyze(arguments.manifest), indent=2) + "\n")
