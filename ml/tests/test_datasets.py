import tempfile
import unittest
from pathlib import Path

from driftzero_ml.datasets import (
    DatasetLfsMissing,
    DatasetMissing,
    is_lfs_pointer,
    load_euroc_imu,
    load_oxiod_sequence,
    load_smartphone_csv,
    load_tumvi_imu,
    require_iovnbd_tables,
    require_real_file,
    require_ronin_sequence,
)
from driftzero_ml.datasets.io_vnbd import (
    latlon_to_enu_m,
    screening_smartphone_tables,
    smartphone_to_odometry,
    to_imu_records,
)
from driftzero_ml.io_vnbd.discover import inspect_delimited_table

FIXTURES = Path(__file__).resolve().parent / "fixtures"
IO_VNBD_HEAD = FIXTURES / "io_vnbd_s_vta9_head.csv"
LFS_POINTER = FIXTURES / "git-lfs-pointer.sample"
EUROC = FIXTURES / "euroc_imu0_format.csv"
REPO_RAW = Path(__file__).resolve().parents[2] / "data" / "raw" / "io_vnbd"


class LfsTests(unittest.TestCase):
    def test_pointer_is_detected(self) -> None:
        self.assertTrue(is_lfs_pointer(LFS_POINTER))

    def test_real_csv_is_not_a_pointer(self) -> None:
        self.assertFalse(is_lfs_pointer(IO_VNBD_HEAD))

    def test_pointer_raises_with_fetch_hint(self) -> None:
        with self.assertRaisesRegex(DatasetLfsMissing, "Git LFS pointer"):
            require_real_file(LFS_POINTER, dataset="IO-VNBD", fetch_hint="pull LFS")


class IoVnbdLoaderTests(unittest.TestCase):
    def test_inspects_published_smartphone_headers(self) -> None:
        report = inspect_delimited_table(IO_VNBD_HEAD)
        names = " ".join(name.lower() for name in report.column_names)
        self.assertIn("gps latitude", names)
        self.assertIn("accelerometer x", names)
        self.assertIn("gyroscope yaw", names)
        self.assertEqual(report.row_count, 3)

    def test_loads_head_fixture(self) -> None:
        rows = load_smartphone_csv(IO_VNBD_HEAD)
        self.assertEqual(len(rows), 3)
        self.assertAlmostEqual(rows[0].ax, -0.3318)
        self.assertIsNotNone(rows[0].latitude_deg)
        records = to_imu_records(rows)
        self.assertEqual(records[0]["ax"], rows[0].ax)
        self.assertNotIn("latitude_deg", records[0])
        odom = smartphone_to_odometry(rows)
        self.assertEqual(len(odom), 3)
        self.assertIn("pose_x_m", odom[0])
        self.assertNotIn("latitude_deg", odom[0])
        east, north = latlon_to_enu_m((0.0, 0.0), 0.0, 0.001)
        self.assertGreater(east, 100.0)
        self.assertLess(abs(north), 1.0)

    def test_backward_timestamp_is_dropped_not_reordered(self) -> None:
        from driftzero_ml.datasets.io_vnbd import SmartphoneRow, keep_nondecreasing_rows

        rows = [
            SmartphoneRow("t", 1_000_000_000, 0.0, 0.0, 9.8, None, None, None, 0.0, 0.0, 0.0),
            SmartphoneRow("t", 500_000_000, 0.0, 0.0, 9.8, None, None, None, 0.0, 0.0, 0.0),
            SmartphoneRow("t", 2_000_000_000, 0.0, 0.0, 9.8, None, None, None, 0.0, 0.0, 0.0),
        ]
        kept = keep_nondecreasing_rows(rows)
        self.assertEqual([row.timestamp_ns for row in kept], [1_000_000_000, 2_000_000_000])

    def test_rewind_suffix_is_reported(self) -> None:
        from driftzero_ml.datasets.io_vnbd import SmartphoneRow, trim_nondecreasing_rows

        prefix = [
            SmartphoneRow("t", 2_000_000_000 + index * 100_000_000, 0.0, 0.0, 9.8, None, None, None, 0.0, 0.0, 0.0)
            for index in range(5)
        ]
        suffix = [
            SmartphoneRow("t", 8_000_000 + index * 100_000_000, 0.0, 0.0, 9.8, None, None, None, 0.0, 0.0, 0.0)
            for index in range(7)
        ]
        kept, rewind = trim_nondecreasing_rows(prefix + suffix)
        self.assertEqual(len(kept), 5)
        self.assertIsNotNone(rewind)
        assert rewind is not None
        self.assertEqual(rewind.dropped_rows, 7)
        self.assertTrue(rewind.suffix)
        self.assertEqual(rewind.first_rewind_ns, 8_000_000)

    def test_missing_root_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(FileNotFoundError, "IO-VNBD"):
                require_iovnbd_tables(Path(folder) / "missing")

    def test_screening_prefers_categorised_unique_stems(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            preferred = (
                root
                / "Synchronised V abd S datasets"
                / "Categorised IOVNB Dataset"
                / "Vta"
                / "Vta09"
            )
            dup = (
                root
                / "Unsynchronised V and S Dataset"
                / "S-Dataset"
            )
            preferred.mkdir(parents=True)
            dup.mkdir(parents=True)
            payload = IO_VNBD_HEAD.read_text(encoding="latin-1")
            (preferred / "S-Vta9.csv").write_text(payload)
            (preferred / "S-Vta3.csv").write_text(payload)
            (preferred / "S-Vta4.csv").write_text(payload)
            (preferred / "S-Vta5.csv").write_text(payload)
            (dup / "S-Vta9.csv").write_text(payload)
            (dup / "S-extra.csv").write_text(payload)
            chosen = screening_smartphone_tables(root)
            stems = [path.stem for path in chosen]
            self.assertEqual(sorted(stems), ["S-Vta3", "S-Vta4", "S-Vta5", "S-Vta9"])
            self.assertTrue(all("Categorised IOVNB Dataset" in path.parts for path in chosen))

    def test_lfs_only_tree_fails(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "S-fake.csv"
            path.write_text(LFS_POINTER.read_text())
            with self.assertRaisesRegex(DatasetLfsMissing, "pointer"):
                require_iovnbd_tables(Path(folder))

    def test_local_raw_trip_loads_if_present(self) -> None:
        trips = list(REPO_RAW.rglob("S-Vta9.csv")) if REPO_RAW.is_dir() else []
        real = [p for p in trips if not is_lfs_pointer(p)]
        if not real:
            self.skipTest("full S-Vta9.csv not in data/raw/io_vnbd")
        rows = load_smartphone_csv(real[0])
        self.assertGreater(len(rows), 3)


class EurocTumviTests(unittest.TestCase):
    def test_euroc_format_fixture(self) -> None:
        rows = load_euroc_imu(EUROC)
        self.assertEqual(len(rows), 3)
        self.assertEqual(rows[0].timestamp_ns, 1403636579763555584)
        self.assertAlmostEqual(rows[0].az, 9.81)

    def test_tumvi_reuses_asl_imu(self) -> None:
        rows = load_tumvi_imu(EUROC)
        self.assertEqual(len(rows), 3)

    def test_missing_euroc_root(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(DatasetMissing, "EuRoC"):
                from driftzero_ml.datasets.euroc import find_imu_csv

                find_imu_csv(Path(folder))


class OxiodRoninTests(unittest.TestCase):
    def test_oxiod_headerless_acc(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            syn = Path(folder) / "handheld" / "data1" / "syn"
            syn.mkdir(parents=True)
            (syn / "acc.csv").write_text("0.0,0.1,0.2,9.8\n0.01,0.1,0.2,9.7\n")
            (syn / "gyr.csv").write_text("0.0,0.01,0.0,0.0\n0.01,0.01,0.0,0.01\n")
            rows = load_oxiod_sequence(Path(folder))
            self.assertEqual(len(rows), 2)
            self.assertAlmostEqual(rows[0].az, 9.8)
            self.assertAlmostEqual(rows[0].gx, 0.01)

    def test_ronin_missing_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(DatasetMissing, "RoNIN"):
                require_ronin_sequence(Path(folder))

    def test_gsdc_missing_root_is_explicit(self) -> None:
        from driftzero_ml.datasets.gsdc import find_imu_csvs

        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(DatasetMissing, "GSDC"):
                find_imu_csvs(Path(folder) / "missing")


if __name__ == "__main__":
    unittest.main()
