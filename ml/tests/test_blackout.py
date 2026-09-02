import unittest

from driftzero_ml.blackout import (
    BlackoutInterval,
    assert_no_gnss_leakage,
    mask_gnss_records,
    validate_blackout_intervals,
)


class BlackoutTests(unittest.TestCase):
    def test_half_open_mask_removes_all_known_gnss_fields(self) -> None:
        rows = [
            {"timestamp_ns": 0, "ax": 1.0, "latitude_deg": 10.0},
            {
                "timestamp_ns": 10,
                "ax": 2.0,
                "latitude_deg": 10.1,
                "gnss_speed_mps": 3.0,
                "satellites_used": 8,
            },
            {"timestamp_ns": 20, "ax": 3.0, "latitude_deg": 10.2},
        ]
        masked = mask_gnss_records(rows, [BlackoutInterval(10, 20, "gap-1")])
        self.assertEqual(masked[0]["latitude_deg"], 10.0)
        self.assertIs(masked[1]["gnss_masked"], True)
        self.assertEqual(masked[1]["blackout_interval_id"], "gap-1")
        self.assertNotIn("latitude_deg", masked[1])
        self.assertNotIn("gnss_speed_mps", masked[1])
        self.assertNotIn("satellites_used", masked[1])
        self.assertEqual(masked[1]["ax"], 2.0)
        self.assertEqual(masked[2]["latitude_deg"], 10.2)
        assert_no_gnss_leakage(masked)

    def test_source_records_are_not_mutated(self) -> None:
        row = {"timestamp_ns": 10, "latitude_deg": 10.1}
        mask_gnss_records([row], [BlackoutInterval(0, 20, "gap")])
        self.assertEqual(row, {"timestamp_ns": 10, "latitude_deg": 10.1})

    def test_overlapping_intervals_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "overlapping"):
            validate_blackout_intervals(
                [BlackoutInterval(0, 20, "a"), BlackoutInterval(10, 30, "b")]
            )

    def test_timestamp_order_is_enforced(self) -> None:
        with self.assertRaisesRegex(ValueError, "ordered"):
            mask_gnss_records(
                [{"timestamp_ns": 20}, {"timestamp_ns": 10}],
                [BlackoutInterval(0, 30, "gap")],
            )

    def test_leakage_assertion_detects_manual_mistake(self) -> None:
        with self.assertRaisesRegex(AssertionError, "latitude_deg"):
            assert_no_gnss_leakage(
                [{"timestamp_ns": 10, "gnss_masked": True, "latitude_deg": 12.0}]
            )


if __name__ == "__main__":
    unittest.main()
