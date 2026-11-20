import unittest
from expectancy import expectancy_metrics, expectancy_allows


class ExpectancyPolicyTests(unittest.TestCase):
    def test_insufficient_sample_blocks(self):
        allowed, result = expectancy_allows({"LONG":[1.0]*29}, "LONG")
        self.assertFalse(allowed)
        self.assertEqual(result["reason"], "insufficient_closed_trades")

    def test_positive_gross_but_uncertain_sample_blocks(self):
        values = [1.0, -0.95] * 15
        result = expectancy_metrics(values)
        self.assertFalse(result["allowed"])

    def test_robust_positive_net_r_passes(self):
        values = [0.8, 1.0, 1.2] * 10
        result = expectancy_metrics(values)
        self.assertTrue(result["allowed"])
        self.assertGreater(result["lower_95_net_r"], 0)
        self.assertGreater(result["profit_factor"], 1.10)

    def test_negative_and_fee_eroded_history_blocks(self):
        self.assertFalse(expectancy_metrics([-0.1]*30)["allowed"])
        # Values are net R after fees/slippage; a gross win that becomes a net loss
        # is represented as a negative value and must not pass.
        self.assertFalse(expectancy_metrics([-0.02]*30)["allowed"])

    def test_long_short_histories_are_separate(self):
        stats = {"LONG":[0.8,1.0,1.2]*10, "SHORT":[-0.1]*30}
        self.assertTrue(expectancy_allows(stats,"LONG")[0])
        self.assertFalse(expectancy_allows(stats,"SHORT")[0])

    def test_non_finite_results_rejected(self):
        with self.assertRaises(ValueError):
            expectancy_metrics([0.2]*29+[float("nan")])


if __name__ == "__main__":
    unittest.main()
