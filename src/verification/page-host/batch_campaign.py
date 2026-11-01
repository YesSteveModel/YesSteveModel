"""Execute a frozen plan within one campaign deadline, preserving incomplete runs."""
import argparse
import json
from pathlib import Path
import time

from run_host import run


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("plan",type=Path)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    plan=json.loads(args.plan.read_text())
    output=args.output.resolve()
    output.mkdir(parents=True,exist_ok=False)
    started=time.time()
    budget=plan["remaining_budget_seconds"]
    deadline=time.monotonic()+budget
    manifest=dict(plan=str(args.plan.resolve()),required_pairs_per_mode=plan["independent_pairs_per_mode"],
                  started_at=started,budget_seconds=budget,runs=[],planned_runs=len(plan["runs"]))
    def save():
        (output/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
    for index,spec in enumerate(plan["runs"]):
        remaining=deadline-time.monotonic()
        if remaining<=0:
            break
        name=f"{index:02d}-{spec['version']}"
        record={**spec,"directory":name}
        manifest["runs"].append(record)
        save()
        result=run(Path(plan[spec["version"]+"_root"]),Path(__file__).resolve().parent,
                   output/name,Path(spec["script"]),Path(plan["config"]),remaining)
        record.update(result)
        save()
        print(name,record.get("exit_code","timeout"),record["host_completed"],flush=True)
        if not record["host_completed"] or record.get("exit_code")!=0:
            break
        if spec.get("freeze_noise_after"):
            from batch_analyze import summarize
            bands=[]
            for mode in [False,True]:
                rows=[(item["directory"],row) for item in manifest["runs"] if item.get("calibration")
                      for row in summarize(output/item["directory"]) if row["eager"]==mode]
                for scene in ["cold","expired","steady"]:
                    for page in range(3):
                        values=[row["max_gap_ms"] for _,row in rows if row["scene"]==scene and row["page"]==page]
                        bands.append(dict(eager=mode,scene=scene,page=page,values_ms=values,
                                          band_ms=max(values)-min(values) if len(values)>=2 else None,
                                          independent_baseline_processes=len(set(name for name,row in rows if row["scene"]==scene and row["page"]==page)),
                                          status="observed baseline range; two processes at most, cold may have only one"))
            (output/"noise-bands.json").write_text(json.dumps(dict(frozen_at=time.time(),bands=bands,
                method="Maximum minus minimum of independent calibration process maxima; null below two processes. Small calibration remains an evidence limit."),indent=2)+"\n")
    manifest["ended_at"]=time.time()
    manifest["campaign_complete"]=len(manifest["runs"])==len(plan["runs"]) and all(item.get("host_completed") for item in manifest["runs"])
    save()


if __name__=="__main__":
    main()
