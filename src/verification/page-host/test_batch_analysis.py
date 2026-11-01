import csv
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from batch_analyze import interval, summarize


class BatchAnalysisTest(unittest.TestCase):
    def test_gap_crossing_window_end_is_retained(self):
        with TemporaryDirectory() as temporary:
            directory = Path(temporary)
            visit = dict(visit=0,scene="hot",page=0,eager=True,inputNanos=1_000_000,
                         window_ms=2,countsBefore={},countsAfter={},registrationsBefore=[],
                         registrationsAfter=[],endNanos=5_000_000)
            (directory / "visits.json").write_text(json.dumps([visit]))
            with (directory / "frames.csv").open("w",newline="") as target:
                writer=csv.writer(target)
                writer.writerow(["visit","start_ns","gap_ns","render_ns","cpu_ns"])
                writer.writerows([[0,1_500_000,1_000_000,100,50],[0,5_000_000,3_500_000,100,50]])
            window=summarize(directory)[0]
            self.assertEqual(window["max_gap_ms"],3.5)

            (directory/"frames.csv").write_text("visit,start_ns,gap_ns,render_ns,cpu_ns\n0,1500000,100000,100,50\n0,5000000,3500000,100,50\n")
            self.assertFalse(summarize(directory)[0]["window_start_complete"])
            self.assertTrue(window["window_complete"])
            self.assertIsNone(window["first_matching_draw_ms"])

    def test_missing_previous_frame_anchor_does_not_make_a_complete_window(self):
        with TemporaryDirectory() as temporary:
            directory=Path(temporary)
            (directory/"visits.json").write_text(json.dumps([dict(visit=0,scene="cold",page=0,eager=False,
                inputNanos=1_000_000,window_ms=2,countsBefore={},countsAfter={},registrationsBefore=[])]))
            (directory/"frames.csv").write_text("visit,start_ns,gap_ns,render_ns,cpu_ns\n0,1500000,0,100,50\n0,5000000,3500000,100,50\n")
            window=summarize(directory)[0]
            self.assertFalse(window["window_start_complete"])
            self.assertTrue(window["window_end_complete"])
            self.assertFalse(window["window_complete"])
            self.assertEqual(window["max_gap_ms"],3.5)

    def test_two_process_pairs_keep_small_sample_uncertainty(self):
        low,high=interval([1,3])
        self.assertLess(low,0)
        self.assertGreater(high,3)
        self.assertIsNone(interval([2]))


if __name__ == "__main__":
    unittest.main()
