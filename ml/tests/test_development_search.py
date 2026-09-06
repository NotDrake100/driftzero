import tempfile
import unittest
from pathlib import Path

from driftzero_ml.development_search import choose_candidate, development_trips
from driftzero_ml.eval_kotlin_replay import run


class DevelopmentSearchTest(unittest.TestCase):
    def test_reserved_session_siblings_are_excluded(self):
        self.assertEqual(development_trips(["S-S3z", "S-S3a", "S-M1a", "S-M1b"]), ["S-M1a", "S-M1b"])

    def test_locked_interval_cannot_be_injected_into_development_run(self):
        with tempfile.TemporaryDirectory() as folder, self.assertRaisesRegex(ValueError, "locked session"):
            run(Path(folder), Path("out"), development_interval_ids=["S-S3z:mid"])

    def test_selection_requires_complete_broad_improvement(self):
        base = {"complete": True, "failed_count": 10, "p50": 0.5, "p95": 2.0}
        better = {"complete": True, "failed_count": 9, "p50": 0.4, "p95": 1.8}
        self.assertEqual(choose_candidate({"baseline": base, "better": better}), "better")
        for change in ({"complete": False}, {"failed_count": 11}, {"p95": 2.1}, {"p50": 0.49}):
            self.assertEqual(choose_candidate({"baseline": base, "bad": {**better, **change}}), "baseline")
