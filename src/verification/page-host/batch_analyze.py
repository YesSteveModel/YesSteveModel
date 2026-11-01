"""Analyze preregistered host windows using independent process pairs."""
import argparse
import csv
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path
import statistics
import subprocess


def epoch_ns(value):
    date = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return int(date.timestamp() * 1_000_000_000)


def duration_ns(value):
    return int(Decimal(value.removeprefix("PT").removesuffix("S")) * 1_000_000_000)


def summarize(directory):
    visits = json.loads((directory / "visits.json").read_text())
    with (directory / "frames.csv").open() as source:
        frames = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(source)]
    windows = []
    for visit in visits:
        if "window_ms" not in visit:
            continue
        beginning = visit["inputNanos"]
        end = beginning + int(visit["window_ms"] * 1e6)
        # Include every complete frame interval intersecting the frozen window.
        selected = [row for row in frames if row["start_ns"] >= beginning and
                    row["start_ns"] - row["gap_ns"] < end and row["gap_ns"] > 0]
        inside = [row for row in frames if beginning <= row["start_ns"] < end]
        marker = visit.get("firstMatchingDrawNanos")
        before = visit["countsBefore"]
        after = visit.get("countsAfter", {})
        start_complete = bool(frames) and (frames[0]["start_ns"] < beginning or
            0 < frames[0]["gap_ns"] and frames[0]["start_ns"] - frames[0]["gap_ns"] <= beginning)
        windows.append(dict(visit=visit["visit"], scene=visit["scene"], page=visit["page"],
                            eager=visit["eager"], block=visit.get("block", 0),
                            start_ns=beginning, end_ns=end, window_ms=visit["window_ms"],
                            window_start_complete=start_complete,
                            window_end_complete=any(row["start_ns"] >= end for row in frames),
                            window_complete=start_complete and any(row["start_ns"] >= end for row in frames),
                            max_gap_ms=max((row["gap_ns"] for row in selected), default=0) / 1e6,
                            frame_gaps_ms=[row["gap_ns"] / 1e6 for row in selected],
                            main_render_wall_ms=sum(row["render_ns"] for row in inside) / 1e6,
                            main_render_cpu_ms=sum(row["cpu_ns"] for row in inside) / 1e6,
                            first_matching_draw_ms=(marker - beginning) / 1e6 if marker and marker < end else None,
                            card_states=visit.get("cardStatesAfter"),
                            registrations_before=visit["registrationsBefore"],
                            registrations_after=visit.get("registrationsAfter"),
                            work_delta={key: after.get(key, 0) - before.get(key, 0) for key in set(before) | set(after)},
                            first_3d_boundary="Matching draw proxy; first recognizable image latency not measured"))
    return windows


def jfr(directory, windows, executable):
    import base64
    visits = {row["visit"]: row for row in json.loads((directory / "visits.json").read_text())}
    bounds = directory / "jfr-windows.tsv"
    rows = []
    for window in windows:
        anchor = visits[window["visit"]]
        if "inputEpochNanos" not in anchor:
            window["jfr_boundary"] = "No wall-clock anchor in this probe revision"
            continue
        start = anchor["inputEpochNanos"]
        rows.append(f"{window['visit']}\t{start}\t{start + int(window['window_ms'] * 1e6)}\n")
        window["jfr_groups"] = {}
    bounds.write_text("".join(rows))
    grouped = directory / "jfr-groups.tsv"
    source = Path(__file__).parent / "jfr/JfrWindowSummary.java"
    subprocess.run([str(executable.parent / "java.exe"), "-Xmx512m", "--source", "17", str(source),
                    str(directory / "operations.jfr"), str(bounds), str(grouped)], check=True)
    by_visit = {window["visit"]: window for window in windows}
    for row in grouped.read_text().splitlines():
        visit, key, count, nanos, size, cpu, paths = row.split("\t")
        key = base64.b64decode(key).decode()
        group = dict(count=int(count), overlap_ms=int(nanos) / 1e6, bytes=int(size))
        loads = base64.b64decode(cpu).decode()
        if loads:
            group["cpu_loads"] = [list(map(float, value.split(","))) for value in loads.split(";")]
        stacks = base64.b64decode(paths).decode()
        if stacks:
            group["stack_paths"] = stacks.splitlines()
        by_visit[int(visit)]["jfr_groups"][key] = group


def interval(values):
    if len(values) < 2:
        return None
    critical = {1: 12.7062047364, 2: 4.30265272975, 3: 3.18244630528}
    df = len(values) - 1
    if df not in critical:
        raise ValueError("Unregistered process-pair count")
    margin = critical[df] * statistics.stdev(values) / len(values) ** .5
    average = statistics.mean(values)
    return [average - margin, average + margin]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jfr", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    runs = []
    for run in manifest["runs"]:
        directory = args.manifest.parent / run["directory"]
        record = {**run}
        if (directory / "result.json").exists():
            record.update(json.loads((directory / "result.json").read_text()))
            record["windows"] = summarize(directory)
            if args.jfr and (directory / "operations.jfr").exists():
                jfr(directory, record["windows"], args.jfr)
        runs.append(record)
    noise_file=args.manifest.parent/"noise-bands.json"
    bands=json.loads(noise_file.read_text()) if noise_file.exists() else None
    comparisons = []
    for eager in [False, True]:
        for scene in ["cold", "hot", "rapid", "stable", "expired", "steady"]:
            for page in range(3):
                pairs = {}
                for run in runs:
                    if run.get("calibration") or not run.get("completed"):
                        continue
                    values = [window["max_gap_ms"] for window in run["windows"]
                              if window["eager"] == eager and window["scene"] == scene and window["page"] == page and window["window_complete"]]
                    if values:
                        pairs.setdefault(run["pair"], {})[run["version"]] = statistics.mean(values)
                differences = [pair["baseline"] - pair["candidate"] for pair in pairs.values()
                               if "baseline" in pair and "candidate" in pair]
                ci = interval(differences) if len(differences) == manifest["required_pairs_per_mode"] else None
                noise=next((band["band_ms"] for band in bands["bands"] if band["eager"]==eager and band["scene"]==scene and band["page"]==page),None) if bands else None
                comparisons.append(dict(eager=eager,scene=scene,page=page,independent_pairs=len(differences),noise_band_ms=noise,
                                        baseline_minus_candidate_ms=differences,ci95_ms=ci,
                                        no_regression="not-exceeded" if scene in ["cold","expired","steady"] and noise is not None and ci and -ci[0]<=noise else "exceeded" if scene in ["cold","expired","steady"] and noise is not None and ci and -ci[1]>noise else "inconclusive",
                                        verdict="improved" if scene in ["hot", "stable"] and ci and ci[0] > 0 else "inconclusive"))
    result = dict(verdict="inconclusive",runs=runs,comparisons=comparisons,
                  limits=["Overall A9 additionally requires baseline-frozen noise bands, cold/retained/expired resource facts and A1 zero work.",
                          "Render wall minus render CPU is not attributed wait; JFR park/monitor and task-join remain separate observations.",
                          "Execution samples and host CPU load describe competition; they do not prove causal scheduler interference.",
                          "Provider lifecycle entry is not a serial/parallel numerical equivalence oracle."])
    args.output.write_text(json.dumps(result,indent=2) + "\n")


if __name__ == "__main__":
    main()
