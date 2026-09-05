"""Fixture road particle coast. Labeled. Not SIH. Not IO-VNBD."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from driftzero_ml.road_particle import (
    ARC_RADIUS_M,
    FIXTURE_LABEL,
    GRAVITY_MPS2,
    RoadParticleError,
    SEED_S_M,
    SPEED_MPS,
    coast_fixture,
    curvature_at_s,
    fork_fixture_graph,
    main,
    refuse_official_pune,
    yaw_about_gravity,
)


class RoadParticleTests(unittest.TestCase):
    def test_gravity_axis_yaw_is_dot_product(self) -> None:
        yaw = yaw_about_gravity((0.1, 0.2, 0.3), GRAVITY_MPS2)
        self.assertAlmostEqual(yaw, 0.3, places=9)
        tilted = yaw_about_gravity((0.0, 0.4, 0.0), (0.0, 9.8, 0.0))
        self.assertAlmostEqual(tilted, 0.4, places=9)
        with self.assertRaises(RoadParticleError):
            yaw_about_gravity((0.0, 0.0, 0.1), (0.0, 0.0, 0.0))

    def test_straight_edge_curvature_near_zero(self) -> None:
        graph = fork_fixture_graph()
        kappa = curvature_at_s(graph.edges["B"], 40.0)
        self.assertLess(abs(kappa), 0.002)

    def test_arc_curvature_near_one_over_radius(self) -> None:
        graph = fork_fixture_graph()
        edge = graph.edges["C"]
        kappa = curvature_at_s(edge, edge.length_m * 0.5)
        self.assertAlmostEqual(abs(kappa), 1.0 / ARC_RADIUS_M, delta=0.008)
        self.assertLess(kappa, 0.0)

    def test_fork_fixture_beats_persist_and_picks_bend(self) -> None:
        row = coast_fixture(duration_s=6.9)
        self.assertEqual(row["label"], FIXTURE_LABEL)
        self.assertTrue(row["not_sih"])
        self.assertTrue(row["not_io_vnbd"])
        self.assertEqual(row["dominant_edge"], "C")
        self.assertGreater(float(row["dominant_weight"]), 0.55)
        self.assertLess(float(row["pf_error_m"]), float(row["persist_error_m"]))
        self.assertLess(float(row["pf_drift"]), float(row["persist_drift"]))
        # Fixture proof only. Not an official 0.10 claim.
        self.assertLess(float(row["pf_error_m"]), 12.0)

    def test_hidden_gnss_in_mask_does_not_change_estimate(self) -> None:
        leak = [(18.53, 73.86), (18.54, 73.87)]
        plain = coast_fixture(duration_s=6.9)
        with_leak = coast_fixture(duration_s=6.9, gnss_after_mask=leak)
        self.assertAlmostEqual(float(plain["pf_error_m"]), float(with_leak["pf_error_m"]))
        self.assertEqual(plain["dominant_edge"], with_leak["dominant_edge"])

    def test_refuses_results_pune_v1(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            official = repo / "results" / "pune_v1"
            official.mkdir(parents=True)
            with self.assertRaises(RoadParticleError):
                refuse_official_pune(official, repo)
            code = main(["--out", str(official), "--repo", str(repo)])
            self.assertEqual(code, 2)

    def test_path_length_matches_speed_times_time(self) -> None:
        row = coast_fixture(duration_s=6.9)
        self.assertAlmostEqual(float(row["path_m"]), SPEED_MPS * 6.9, places=6)
        self.assertGreater(SEED_S_M, 0.0)


if __name__ == "__main__":
    unittest.main()
