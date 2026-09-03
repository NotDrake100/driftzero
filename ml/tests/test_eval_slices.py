import tempfile
import unittest
from pathlib import Path

from driftzero_ml.eval_iovnbd_blackout import write_blackout_interval_ids
from driftzero_ml.eval_slices import (
    SLICE_1HZ,
    SLICE_SPARSE,
    format_slices_markdown,
    slice_tables,
)


class EvalSlicesTests(unittest.TestCase):
    def test_1hz_and_sparse_tables_keep_full_denominator_separate(self) -> None:
        rows = []
        for interval_id, drift, end_m in (
            ("S-A:mid", "0.20", "10.0"),
            ("S-B:mid", "0.80", "40.0"),
            ("S-A:d50", "0.10", "5.0"),
            ("S-B:d1000", "1.20", "80.0"),
        ):
            trip = interval_id.split(":", 1)[0]
            rows.append(
                {
                    "interval_id": interval_id,
                    "trip_id": trip,
                    "split": "validation",
                    "system": "persist",
                    "endpoint_error_m": end_m,
                    "truth_path_length_m": "50.0",
                    "drift_ratio": drift,
                    "along_track_error_m": "1.0",
                    "cross_track_error_m": "1.0",
                    "speed_mae_mps": "3.0",
                    "heading_mae_rad": "0.4",
                }
            )
        spacing = {"S-A": 1.0, "S-B": 9.0}
        tables = slice_tables(rows, spacing, rewind_notes={"S-B": "timestamp rewind dropped suffix"})
        self.assertEqual(tables["n_intervals"], 4)
        self.assertEqual(tables["slices"][SLICE_1HZ]["n_intervals"], 2)
        self.assertEqual(tables["slices"][SLICE_SPARSE]["n_intervals"], 2)
        hz = tables["slices"][SLICE_1HZ]["systems"]["persist"]
        sparse = tables["slices"][SLICE_SPARSE]["systems"]["persist"]
        self.assertEqual(hz["n"], 2)
        self.assertAlmostEqual(float(hz["drift_ratio_p50"]), 0.15)
        self.assertAlmostEqual(float(hz["endpoint_p50_m"]), 7.5)
        self.assertEqual(sparse["n"], 2)
        self.assertAlmostEqual(float(sparse["drift_ratio_p50"]), 1.0)
        reasons = [row["reason"] for row in tables["slices"][SLICE_SPARSE]["members"]]
        self.assertTrue(any("timestamp rewind dropped suffix" in item for item in reasons))
        markdown = format_slices_markdown(
            tables,
            source="metrics_per_interval.csv",
            metrics_date="2026-09-03",
            written_date="2026-09-03",
        )
        self.assertIn("Source: `metrics_per_interval.csv`", markdown)
        self.assertIn("1 Hz slice", markdown)
        self.assertIn("sparse truth slice", markdown)
        self.assertIn("The official 35-interval table is unchanged", markdown)

    def test_write_blackout_interval_ids_only_when_empty(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.yaml"
            path.write_text("seed: 1\nblackout_interval_ids: []\nother: x\n")
            write_blackout_interval_ids(path, ["S-A:mid", "S-B:mid"])
            text = path.read_text()
            self.assertIn("  - S-A:mid\n", text)
            self.assertIn("  - S-B:mid\n", text)
            self.assertNotIn("blackout_interval_ids: []\n", text)
            with self.assertRaises(ValueError):
                write_blackout_interval_ids(path, ["S-C:mid"])


if __name__ == "__main__":
    unittest.main()
