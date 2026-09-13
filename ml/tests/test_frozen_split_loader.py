"""Frozen IO-VNBD group policy tests. No raw dataset rows are opened."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from driftzero_ml.datasets.io_vnbd import (
    load_frozen_train_smartphone_csv,
    load_odometry_trips,
)
from driftzero_ml.io_vnbd import (
    FROZEN_DEVELOPMENT_SESSION_GROUPS,
    FROZEN_EXCLUDED_SESSION_GROUPS,
    FROZEN_FRESH_HOLDOUT_SESSION_GROUPS,
    FROZEN_LOCKED_SESSION_GROUPS,
    FROZEN_SPLIT_SEED,
    FROZEN_TRAIN_SESSION_GROUPS,
    frozen_group_role,
    load_frozen_split_manifest,
    require_frozen_train_group,
)


class FrozenSplitTests(unittest.TestCase):
    def test_manifest_matches_pinned_24_10_1_groups(self) -> None:
        split = load_frozen_split_manifest()
        self.assertEqual(split.seed, FROZEN_SPLIT_SEED)
        self.assertEqual(split.train, FROZEN_TRAIN_SESSION_GROUPS)
        self.assertEqual(split.fresh_holdout, FROZEN_FRESH_HOLDOUT_SESSION_GROUPS)
        self.assertEqual(split.excluded, FROZEN_EXCLUDED_SESSION_GROUPS)
        self.assertEqual(len(split.train), 24)
        self.assertEqual(len(split.fresh_holdout), 10)
        self.assertEqual(len(split.excluded), 1)
        self.assertEqual(len(split.development), len(FROZEN_DEVELOPMENT_SESSION_GROUPS))
        self.assertEqual(len(split.locked), len(FROZEN_LOCKED_SESSION_GROUPS))

    def test_loader_accepts_only_frozen_train_groups(self) -> None:
        self.assertEqual(require_frozen_train_group("S-Vta9"), "S-Vta9")
        self.assertEqual(require_frozen_train_group("S-Vw14c"), "S-Vw14")
        cases = {
            "S-Vta4": "fresh_holdout",
            "S-S1": "locked",
            "S-S4": "development",
            "S-Vtb3": "excluded",
        }
        for trip_id, role in cases.items():
            with self.subTest(trip_id=trip_id):
                self.assertEqual(frozen_group_role(trip_id), role)
                with self.assertRaisesRegex(ValueError, role):
                    require_frozen_train_group(trip_id)
                with self.assertRaisesRegex(ValueError, role):
                    load_frozen_train_smartphone_csv(
                        Path("/does/not/exist") / f"{trip_id}.csv"
                    )

    def test_odometry_loader_preflights_all_paths(self) -> None:
        with tempfile.TemporaryDirectory() as folder, patch(
            "driftzero_ml.datasets.io_vnbd.screening_smartphone_tables",
            return_value=(Path(folder) / "S-Vta4.csv",),
        ), self.assertRaisesRegex(ValueError, "fresh_holdout"):
            load_odometry_trips(Path(folder))

    def test_unregistered_group_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "unregistered"):
            require_frozen_train_group("S-synthetic")


if __name__ == "__main__":
    unittest.main()
