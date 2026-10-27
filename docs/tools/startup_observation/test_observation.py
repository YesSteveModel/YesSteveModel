import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from install_observation import HELPER, OBSERVED_STARTUP_CALL, STARTUP_PATH, install
import run_functional


class ObservationTest(unittest.TestCase):
    @staticmethod
    def copy_current_sources(root):
        project = Path(__file__).resolve().parents[3]
        java = Path("src/main/java/com/elfmcys/ysm")
        for source in ("event/CommonEvent.java", STARTUP_PATH, "model/service/ClientModelService.java",
                       "model/resource/client/render/ModelRenderTargetLoader.java",
                       "expression/aot/ExpressionEngineMetrics.java"):
            target = root / java / source
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(project / java / source, target)
        return root / java

    @staticmethod
    def source_bytes(root):
        return {str(path.relative_to(root)): path.read_bytes() for path in root.rglob("*.java")}

    def test_current_sources_install_and_repeated_install_preserves_every_byte(self):
        for numeric in (True, False):
            with self.subTest(numeric=numeric), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                java = self.copy_current_sources(root)
                service_path = java / "model/service/ClientModelService.java"
                service_before = service_path.read_text(encoding="utf-8")
                install(root, numeric)
                self.assertIn(OBSERVED_STARTUP_CALL, (java / STARTUP_PATH).read_text(encoding="utf-8"))
                self.assertEqual(service_before.replace("new ExpressionEngineMetrics()", f"{HELPER}.metrics()"),
                                 service_path.read_text(encoding="utf-8"))
                self.assertEqual(Path(__file__).with_name("StartupObservation.java").read_text(encoding="utf-8"),
                                 (java / "verification/StartupObservation.java").read_text(encoding="utf-8"))
                metrics = (java / "expression/aot/ExpressionEngineMetrics.java").read_text(encoding="utf-8")
                self.assertEqual("StartupObservation.duration(" in metrics, numeric)
                installed = self.source_bytes(root)
                with self.assertRaisesRegex(ValueError, "already installed"):
                    install(root, numeric)
                self.assertEqual(installed, self.source_bytes(root))

    def test_changed_final_anchor_leaves_no_partial_overlay(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = self.copy_current_sources(root)
            metrics = java / "expression/aot/ExpressionEngineMetrics.java"
            metrics.write_text(metrics.read_text(encoding="utf-8").replace(
                "portableProgramDecodes.incrementAndGet();", "changed();"), encoding="utf-8")
            before = self.source_bytes(root)
            with self.assertRaisesRegex(ValueError, "overlay anchor must occur once"):
                install(root)
            self.assertEqual(before, self.source_bytes(root))
            self.assertFalse((java / "verification").exists())

    def test_functional_modes_install_current_startup_and_restore_after_launch_failure(self):
        for mode in ("consume", "required-failure"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                project = root / "project"
                self.copy_current_sources(project)
                install(project)
                before = self.source_bytes(project)
                def manifest(path):
                    return {name: hashlib.sha256(content).hexdigest()
                            for name, content in self.source_bytes(path).items()}
                native = root / "native.dll"
                native.write_bytes(b"fixture")
                protocol = root / "protocol.json"
                protocol.write_text(json.dumps({"jdk": os.environ["JAVA_HOME"], "jfc": "fixture.jfc"}))
                candidate = root / "candidate.json"
                candidate.write_text(json.dumps({
                    "protocol_sha256": run_functional.sha(protocol), "engine_source_commit": "fixture",
                    "configuration": {"project": str(project), "native": str(native),
                        "native_sha256": run_functional.sha(native), "repository": "fixture", "engine_jars": {},
                        "source_manifest_sha256": hashlib.sha256(
                            json.dumps(manifest(project), sort_keys=True).encode()).hexdigest()}}))
                output = root / "output"
                arguments = ["run_functional.py", "--protocol", str(protocol), "--candidate-receipt", str(candidate),
                             "--output", str(output), "--mode", mode]
                with patch.object(sys, "argv", arguments), patch.object(run_functional, "manifest", manifest), \
                        patch.object(run_functional.subprocess, "Popen", side_effect=OSError("fixture launch failure")):
                    with self.assertRaisesRegex(OSError, "fixture launch failure"):
                        run_functional.main()
                overlay = output / "overlay/src/main/java/com/elfmcys/ysm"
                startup = (overlay / STARTUP_PATH).read_text(encoding="utf-8")
                if mode == "required-failure":
                    self.assertIn("StartupConsumption.requiredFailure(system, builtinCapabilities.availability());",
                                  startup)
                    self.assertIn("ClientModelService.start(system, availability);",
                                  (overlay / "verification/StartupConsumption.java").read_text(encoding="utf-8"))
                else:
                    self.assertIn(OBSERVED_STARTUP_CALL, startup)
                    self.assertIn("StartupConsumption.begin(service)",
                                  (overlay / "verification/StartupObservation.java").read_text(encoding="utf-8"))
                self.assertEqual(before, self.source_bytes(project))
                self.assertTrue(json.loads((output / "receipt.json").read_text())["source_restored"])

    def test_jfr_preserves_duration_value_scope_thread_and_numeric_delta(self):
        jdk = Path(os.environ["JAVA_HOME"])
        source = r'''
import jdk.jfr.*;
import java.nio.file.*;
public class NumericFixture {
 @Name("ysm.StartupBoundary") @StackTrace(false)
 public static class Boundary extends Event { public String label; }
 @Name("ysm.ExpressionPreparation") @StackTrace(false)
 public static class Scope extends Event { public String label; }
 @Name("ysm.ExpressionDuration") @StackTrace(false)
 public static class Duration extends Event {
  public String label, scope, compilationKind; public long nanoseconds;
 }
 @Name("ysm.AnalysisWork") @StackTrace(false)
 public static class Analysis extends Event {
  public String counter, scope, compilationKind; public long delta;
 }
 static void boundary(String label) { var b=new Boundary(); b.label=label; b.commit(); }
 public static void main(String[] args) throws Exception {
  try(var r=new Recording()) {
   r.enable(Boundary.class); r.enable(Scope.class).withThreshold(java.time.Duration.ZERO);
   r.enable(Duration.class); r.enable(Analysis.class); r.start();
   boundary("COMMON_SETUP");
   var s=new Scope(); s.label="player/main"; s.begin();
   var d=new Duration(); d.label="SEMANTIC_ANALYSIS"; d.scope=s.label;
   d.compilationKind="PERSISTENT"; d.nanoseconds=123456789L; d.commit();
   Thread.sleep(5);
   var a=new Analysis(); a.counter="EXPECTED_EVALUATIONS"; a.scope=s.label;
   a.compilationKind="PERSISTENT"; a.delta=17; a.commit();
   s.end(); s.commit(); boundary("REQUIRED_READY");
   boundary("INVENTORY_COMPLETE"); boundary("NORMAL_STOP_REQUESTED");
   r.stop(); r.dump(Path.of(args[0]));
  }
 }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "NumericFixture.java"
            fixture.write_text(source)
            subprocess.run([str(jdk / "bin/javac.exe"), "-d", str(root), str(fixture),
                            str(Path(__file__).with_name("JfrStartupExtract.java"))], check=True, capture_output=True)
            recording = root / "numeric.jfr"
            subprocess.run([str(jdk / "bin/java.exe"), "-cp", str(root), "NumericFixture", str(recording)], check=True, capture_output=True)
            output = subprocess.check_output([str(jdk / "bin/java.exe"), "-cp", str(root), "JfrStartupExtract", str(recording)])
            facts = json.loads(output)
        self.assertEqual(facts["stage_nanos"]["SEMANTIC_ANALYSIS"], 123456789)
        self.assertEqual(facts["analysis_work"]["PERSISTENT.EXPECTED_EVALUATIONS"], 17)
        self.assertEqual(facts["work"]["SEMANTIC_ANALYSIS"], 1)
        self.assertEqual({row["scope"] for row in facts["numeric_events"]}, {"player/main"})
        self.assertEqual({row["thread"] for row in facts["numeric_events"]},
                         {row["thread"] for row in facts["preparation_spans"]})



if __name__ == "__main__": unittest.main()
