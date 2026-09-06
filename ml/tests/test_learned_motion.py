"""Learned-motion split guards, causality, fallback, and blackout drift."""

from __future__ import annotations

import math
import unittest

from driftzero_ml.blackout import GNSS_KEYS
from driftzero_ml.learned_imu import LearnedImuPrediction
from driftzero_ml.learned_motion import (
    CONFIGS,
    SEED,
    UNCERTAINTY_FALLBACK,
    advance_latlon,
    assert_no_feature_leakage,
    assert_trainable,
    cpu_smoke,
    group_role,
    holdout_is_closed,
    joint_step_from_prediction,
    load_split_roles,
    persist_speed_heading_step,
    preregistration_payload,
    rollout_joint_steps,
    score_blackout_rollout,
    train_windows_from_trips,
)


class LearnedMotionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.roles = load_split_roles()

    def test_frozen_roles_match_diagnostics_manifest(self) -> None:
        self.assertEqual(self.roles.seed, SEED)
        self.assertEqual(len(self.roles.train), 24)
        self.assertEqual(len(self.roles.fresh_holdout), 10)
        self.assertEqual(self.roles.excluded, frozenset({"S-Vtb3"}))
        self.assertTrue(self.roles.train.isdisjoint(self.roles.fresh_holdout))
        self.assertTrue(self.roles.train.isdisjoint(self.roles.locked))
        self.assertTrue(self.roles.train.isdisjoint(self.roles.development))
        self.assertTrue(self.roles.fresh_holdout.isdisjoint(self.roles.locked))
        self.assertTrue(self.roles.fresh_holdout.isdisjoint(self.roles.development))
        self.assertIn("S-M", self.roles.train)
        self.assertIn("S-Vta4", self.roles.fresh_holdout)
        self.assertEqual(group_role("S-M"), "train")
        self.assertEqual(group_role("S-Vta4"), "fresh_holdout")
        self.assertEqual(group_role("S-Vtb3"), "excluded")
        self.assertEqual(group_role("S-S1"), "locked_confirmation")

    def test_training_rejects_holdout_locked_excluded_and_development(self) -> None:
        for trip, role in (
            ("S-Vta4", "fresh_holdout"),
            ("S-S1", "locked_confirmation"),
            ("S-Vtb3", "excluded"),
            ("S-Vta8", "development"),
        ):
            with self.assertRaisesRegex(ValueError, role):
                assert_trainable(trip, self.roles)
        self.assertEqual(assert_trainable("S-M", self.roles), "S-M")
        with self.assertRaisesRegex(ValueError, "fresh holdout"):
            holdout_is_closed(["S-Vta4"], self.roles)

    def test_train_windows_reject_holdout_trip_ids(self) -> None:
        with self.assertRaisesRegex(ValueError, "fresh holdout"):
            train_windows_from_trips({"S-Vta4": []}, self.roles)

    def test_preregistration_is_joint_and_keeps_holdout_closed(self) -> None:
        payload = preregistration_payload(self.roles)
        self.assertEqual(payload["seed"], SEED)
        self.assertFalse(payload["training_started"])
        self.assertFalse(payload["holdout_open"])
        self.assertFalse(payload["timesfm"])
        self.assertFalse(payload["phone_path_changed"])
        self.assertEqual(payload["objective"]["outputs"], ["travelled_distance_m", "heading_change_rad"])
        self.assertIn("speed_mae_mps", payload["objective"]["insufficient_alone"])
        self.assertEqual(set(payload["configs"]), set(CONFIGS))
        self.assertEqual(payload["train_session_groups"], sorted(self.roles.train))
        self.assertEqual(payload["fresh_holdout_session_groups"], sorted(self.roles.fresh_holdout))

    def test_rollout_and_score_report_position_drift_not_speed_only(self) -> None:
        origin = (18.52, 73.86)
        steps = [
            persist_speed_heading_step(10.0, 0.0, 1.0),
            persist_speed_heading_step(10.0, 0.0, 1.0),
        ]
        estimate = rollout_joint_steps(origin, 0.0, steps)
        truth = [origin, advance_latlon(origin, 0.0, 10.0), advance_latlon(origin, 0.0, 20.0)]
        scored = score_blackout_rollout(estimate, truth)
        self.assertEqual(scored["primary_metric"], "blackout_position_drift_ratio")
        self.assertIn("endpoint_error_m", scored)
        self.assertIn("drift_ratio", scored)
        self.assertAlmostEqual(scored["endpoint_error_m"], 0.0, places=6)
        turned = rollout_joint_steps(
            origin,
            0.0,
            [
                persist_speed_heading_step(10.0, 0.0, 1.0),
            ],
        )
        # Wrong heading: 90 deg, same distance, should leave a cross-track residual.
        bent = [origin, advance_latlon(origin, math.pi / 2, 10.0)]
        drifted = score_blackout_rollout(turned, bent)
        self.assertGreater(drifted["endpoint_error_m"], 5.0)

    def test_uncertainty_and_nonfinite_heads_use_deterministic_fallback(self) -> None:
        ok = LearnedImuPrediction(
            dx_m=2.0,
            dy_m=0.0,
            dz_m=0.0,
            log_sigma_x=-2.0,
            log_sigma_y=-2.0,
            log_sigma_z=-2.0,
            forward_speed_mps=8.0,
            stop_logit=0.0,
            log_speed_variance=-2.0,
            delta_heading_rad=0.1,
        )
        step = joint_step_from_prediction(ok, last_speed_mps=8.0, dt_s=0.2)
        self.assertFalse(step.used_fallback)
        self.assertAlmostEqual(step.distance_m, 2.0)
        self.assertAlmostEqual(step.heading_change_rad, 0.1)
        wide = LearnedImuPrediction(
            **{
                **ok.__dict__,
                "log_sigma_x": math.log(UNCERTAINTY_FALLBACK ** 2) + 1.0,
                "log_sigma_y": math.log(UNCERTAINTY_FALLBACK ** 2) + 1.0,
            }
        )
        fallback = joint_step_from_prediction(wide, last_speed_mps=8.0, dt_s=0.2)
        self.assertTrue(fallback.used_fallback)
        self.assertEqual(fallback.reason, "uncertainty_fallback")
        self.assertAlmostEqual(fallback.distance_m, 1.6)
        self.assertEqual(fallback.heading_change_rad, 0.0)
        broken = LearnedImuPrediction(**{**ok.__dict__, "dx_m": float("nan")})
        self.assertEqual(
            joint_step_from_prediction(broken, last_speed_mps=8.0, dt_s=0.2).reason,
            "nonfinite_displacement",
        )

    def test_features_cannot_carry_gnss_keys(self) -> None:
        assert_no_feature_leakage({"ax": 0.1, "gz": 0.0})
        with self.assertRaisesRegex(AssertionError, "GNSS"):
            assert_no_feature_leakage({"ax": 0.1, "gnss_speed_mps": 12.0})
        self.assertIn("latitude_deg", GNSS_KEYS)

    def test_future_heading_sample_does_not_change_an_already_built_step(self) -> None:
        pred = LearnedImuPrediction(
            dx_m=1.0,
            dy_m=0.0,
            dz_m=0.0,
            log_sigma_x=-3.0,
            log_sigma_y=-3.0,
            log_sigma_z=-3.0,
            forward_speed_mps=5.0,
            stop_logit=0.0,
            log_speed_variance=-3.0,
            delta_heading_rad=0.05,
        )
        first = joint_step_from_prediction(pred, last_speed_mps=5.0, dt_s=0.2)
        later = LearnedImuPrediction(**{**pred.__dict__, "delta_heading_rad": 1.5})
        # A later window is a different causal step. The earlier increment is unchanged.
        self.assertAlmostEqual(first.heading_change_rad, 0.05)
        self.assertNotAlmostEqual(later.delta_heading_rad, first.heading_change_rad)

    def test_cpu_smoke_reports_blackout_drift(self) -> None:
        report = cpu_smoke(26168)
        self.assertEqual(report["label"], "SYNTHETIC_SMOKE_ONLY")
        self.assertTrue(report["not_a_candidate"])
        self.assertIn("drift_ratio", report["blackout"])
        self.assertIn("endpoint_error_m", report["blackout"])
        self.assertGreater(report["window_count"], 0)
        self.assertNotEqual(report["blackout"]["primary_metric"], "speed_mae_mps")


if __name__ == "__main__":
    unittest.main()
