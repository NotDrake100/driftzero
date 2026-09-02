import unittest

from driftzero_ml.timesfm_adapter import ForecastResult, run_injected_predictor, validate_context


class TimesFMAdapterTests(unittest.TestCase):
    def test_validate_context_rejects_ragged_variates(self) -> None:
        with self.assertRaisesRegex(ValueError, "same non-zero"):
            validate_context([[1.0, 2.0], [3.0]])

    def test_injected_predictor_preserves_contract(self) -> None:
        context = [[1.0, 2.0], [10.0, 20.0]]

        def predictor(values):
            self.assertIs(values, context)
            return ForecastResult(point=((3.0,), (30.0,)), quantiles=None)

        result = run_injected_predictor(
            context,
            predictor,
            expected_context_length=2,
            expected_horizon_length=1,
        )
        self.assertEqual(result.point, ((3.0,), (30.0,)))

    def test_injected_predictor_rejects_wrong_variate_count(self) -> None:
        def predictor(_):
            return ForecastResult(point=((1.0,),), quantiles=None)

        with self.assertRaisesRegex(ValueError, "number of variates"):
            run_injected_predictor([[1.0], [2.0]], predictor)


if __name__ == "__main__":
    unittest.main()
