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
        # Expectancy-based signal gating has been removed from both engines.
        for obsolete in ("expectancy_allows", "expectancy_stats", "research_warmup", "net_expectancy_gate"):
            self.assertNotIn(obsolete,py)
        for obsolete in ("ExpectancyPolicy", "expectancyStats", "enforceExpectancy"):
            self.assertNotIn(obsolete,kt)
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
                aligned_start = (fixture["start_ms"] // durations[tf]) * durations[tf]
                rows.append({"timestamp":aligned_start+i*durations[tf],
                             "open":close-0.02,"high":close+0.15,"low":close-0.15,
                             "close":close,"volume":100+i})
            data[tf]=rows
        from signal_engine import analyze_market
        result=analyze_market(data)
        self.assertEqual(round(result["combined_score"]),fixture["expected_score"])
        self.assertEqual(result["signal"],fixture["expected_signal"])

    def test_python_android_use_simple_three_timeframe_bias_consensus(self):
        root=pathlib.Path(__file__).parent
        py=(root/"signal_engine.py").read_text()
        kt=(root/"app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        self.assertIn('len(long_tfs)==len(selected_tfs)',py)
        self.assertIn('len(short_tfs)==len(selected_tfs)',py)
        self.assertIn('reports.values.all{it.bias=="bullish"',kt)
        self.assertIn('reports.values.all{it.bias=="bearish"',kt)
        self.assertIn('fun evaluate(timeframes:Map<String,List<MarketCandle>>',kt)
        for obsolete in ("combined>=34", "combined<=-34", 'score"]>=26', 'score"]<=-26',
                         'score"]>=24', 'score"]<=-24', "score>=22", "score<=-22",
                         "bullish>=4", "bearish>=4", "directionalQuality"):
            self.assertNotIn(obsolete,py)
        for obsolete in ("combined>=34", "combined<=-34", "score>=26", "score<=-26",
                         "score>=24", "score<=-24", "score>=22", "score<=-22",
                         "up>=4", "down>=4", "directionalQuality"):
            self.assertNotIn(obsolete,kt)
        self.assertIn('float(value) > 0.0', py)
        self.assertIn('if(up>down)"bullish"', kt)


if __name__ == "__main__":
    unittest.main()
