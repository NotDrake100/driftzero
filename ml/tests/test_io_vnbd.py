import tempfile
import unittest
from pathlib import Path

from driftzero_ml.baselines import (
    constant_velocity_baseline,
    freeze_baseline,
    persist_course_baseline,
)
from driftzero_ml.io_vnbd import (
    IOVNBDMissing,
    assign_grouped_trip_splits,
    assign_trip_splits,
    inspect_delimited_table,
    require_local_root,
    session_group_id,
)


class DiscoverTests(unittest.TestCase):
    def test_inspects_headers_without_renaming(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fixture.csv"
            path.write_text("alpha,beta\n1,2.5\n3,4.0\n")
            report = inspect_delimited_table(path)
            self.assertEqual(report.column_names, ("alpha", "beta"))
            self.assertEqual(report.row_count, 2)
            self.assertEqual(report.columns[0].inferred_kind, "int")
            self.assertEqual(report.columns[1].inferred_kind, "float")
            self.assertEqual(len(report.sha256), 64)

    def test_rejects_empty_header_name(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "bad.csv"
            path.write_text(",beta\n1,2\n")
            with self.assertRaisesRegex(ValueError, "empty column name"):
                inspect_delimited_table(path)

    def test_missing_local_root_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            missing = Path(folder) / "io_vnbd"
            with self.assertRaisesRegex(IOVNBDMissing, "Clone"):
                require_local_root(missing)


class SplitTests(unittest.TestCase):
    def test_each_trip_has_one_split(self) -> None:
        trips = [f"trip-{index}" for index in range(20)]
        rows = assign_trip_splits(trips, seed="screening-v1")
        self.assertEqual(len(rows), 20)
        self.assertEqual(len({row.trip_id for row in rows}), 20)
        self.assertTrue({row.split for row in rows} <= {"train", "validation", "public_test", "locked_test"})

    def test_assignment_is_deterministic(self) -> None:
        trips = ["a", "b", "c", "d"]
        first = assign_trip_splits(trips, seed="screening-v1")
        second = assign_trip_splits(trips, seed="screening-v1")
        self.assertEqual(first, second)

    def test_duplicate_trip_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "duplicate"):
            assign_trip_splits(["same", "same"], seed="x")

    def test_session_siblings_share_one_split(self) -> None:
        self.assertEqual(session_group_id("S-S3a"), "S-S3")
        self.assertEqual(session_group_id("S-Vta1b"), "S-Vta1")
        self.assertEqual(session_group_id("S-Vta12"), "S-Vta12")
        trips = ["S-S3a", "S-S3b", "S-S3c", "S-Vta1a", "S-Vta1b", "S-Vta2"]
        rows = assign_grouped_trip_splits(trips, seed="26168")
        by_id = {row.trip_id: row.split for row in rows}
        self.assertEqual(by_id["S-S3a"], by_id["S-S3b"])
        self.assertEqual(by_id["S-S3b"], by_id["S-S3c"])
        self.assertEqual(by_id["S-Vta1a"], by_id["S-Vta1b"])


class BaselineTests(unittest.TestCase):
    def test_freeze_repeats_last_fix(self) -> None:
        points = freeze_baseline((12.0, 77.0), 3)
        self.assertEqual(points, [(12.0, 77.0), (12.0, 77.0), (12.0, 77.0)])

    def test_constant_velocity_is_causal(self) -> None:
        history = ((0.0, 0.0, 0), (0.0, 0.001, 1_000_000_000))
        horizon = (2_000_000_000,)
        points = constant_velocity_baseline(history, horizon)
        self.assertEqual(len(points), 1)
        self.assertAlmostEqual(points[0][0], 0.0)
        self.assertAlmostEqual(points[0][1], 0.002)

    def test_constant_velocity_rejects_future_history(self) -> None:
        history = ((0.0, 0.0, 0), (0.0, 0.001, 1_000))
        with self.assertRaisesRegex(ValueError, "at or after"):
            constant_velocity_baseline(history, (500,))

    def test_persist_course_holds_heading(self) -> None:
        points = persist_course_baseline((0.0, 0.0), 0.0, 10.0, [1.0, 1.0])
        self.assertEqual(len(points), 2)
        self.assertGreater(points[1][0], points[0][0])


if __name__ == "__main__":
    unittest.main()
