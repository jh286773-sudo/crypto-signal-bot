import pathlib
import json
import math
import unittest


class CrossEngineContractTests(unittest.TestCase):
    def test_python_and_android_share_smc_and_entry_guards(self):
        root=pathlib.Path(__file__).parent
        py=(root/"signal_engine.py").read_text()
        kt=(root/"app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        # Contract-level parity guard: if either implementation changes a critical
        # threshold or SMC rule, CI requires this test to be reviewed and updated.
        for token in ("atr_ref*0.50", "retest_long", "retest_short",
                      "false_breakout", "equal_high_liquidity", "equal_low_liquidity"):
            self.assertIn(token,py)
        for token in ("atrRef*.50", "retestLong", "retestShort",
                      "falseBreakout", "equalHighs", "equalLows"):
            self.assertIn(token,kt)
        self.assertIn("entry_distance_atr",py)
        self.assertIn("expectancy_allows",py)
        self.assertIn("MIN_TRADES = 30", (root/"expectancy.py").read_text())
        policy=(root/"app/src/main/java/com/cryptoscalp/app/ExpectancyPolicy.kt").read_text()
        for token in ("MIN_TRADES=30","MIN_PROFIT_FACTOR=1.10","ONE_SIDED_Z=1.645"):
            self.assertIn(token,policy)
        self.assertIn("expectancyStats?.get(rawSignal)",kt)
        self.assertIn("entryDistanceAtr",kt)
        self.assertIn(">.50",kt)
        self.assertIn("0.50",py)


    def test_shared_golden_fixture_signal_matches_android_contract(self):
        root=pathlib.Path(__file__).parent
        fixture_path=root/"app/src/test/resources/strategy_parity_fixture.json"
        fixture=json.loads(fixture_path.read_text())
        durations={"5m":300000,"15m":900000,"30m":1800000}
        data={}
        for tf,count in fixture["bars"].items():
            rows=[]
            for i in range(count):
                close=fixture["base_price"]+i*0.02+math.sin(i*0.3)*0.03
                rows.append({"timestamp":fixture["start_ms"]+i*durations[tf],
                             "open":close-0.02,"high":close+0.15,"low":close-0.15,
                             "close":close,"volume":100+i})
            data[tf]=rows
        from signal_engine import analyze_market
        result=analyze_market(data)
        self.assertEqual(round(result["combined_score"]),fixture["expected_score"])
        self.assertEqual(result["signal"],fixture["expected_signal"])


if __name__ == "__main__":
    unittest.main()
