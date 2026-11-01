import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("analysis", Path(__file__).with_name("analyze.py"))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


class AnalysisTest(unittest.TestCase):
    def test_bootstrap_constant_and_touching_zero(self):
        self.assertEqual([5, 5], analysis.interval([5] * 12))
        self.assertEqual([0, 0], analysis.interval([0] * 12))
        self.assertIsNone(analysis.interval([]))

    def test_overlapping_windows_include_later_visits_and_exclude_right_endpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "visits.json").write_text(json.dumps([
                {"visit": 0, "page": 0, "scene": "rapid", "inputNanos": 100},
                {"visit": 1, "page": 1, "scene": "rapid", "inputNanos": 200, "endNanos": 500}]))
            (root / "frames.csv").write_text("visit,start_ns,gap_ns,render_ns,cpu_ns\n"
                                             "0,100,5,3,2\n1,200,7,4,2\n1,300,99,4,2\n1,400,2,1,1\n")
            windows = analysis.windows(root, 200)
            self.assertEqual([5e-6, 7e-6], windows[0]["frame_gaps_ms"])
            self.assertEqual([7e-6, 99e-6], windows[1]["frame_gaps_ms"])
            self.assertTrue(windows[0]["window_complete"])


if __name__ == "__main__":
    unittest.main()
