import pathlib
import json
import math
import unittest


class CrossEngineContractTests(unittest.TestCase):
    def test_both_engines_use_direct_indicator_rules_without_weights_or_smc_gates(self):
        root=pathlib.Path(__file__).parent
        py=(root/"signal_engine.py").read_text()
        kt=(root/"app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        for token in ("_indicator_bias", '("EMA", "RSI", "MACD", "ADX_DMI")',
                      '("VWAP", "OBV_Trend", "Bollinger")'):
            self.assertIn(token,py)
        for token in ("fun indicatorBias", '"EMA","RSI","MACD","ADX_DMI"',
                      '"VWAP","OBV_Trend","Bollinger"'):
            self.assertIn(token,kt)
        py_signal=py[py.index("def analyze_market("):py.index("def generate_signal(")]
        kt_signal=kt[kt.index("fun evaluate(timeframes:"):kt.rfind("\\n}")]
        for obsolete in ("family_weights", "tfWeights", "familyWeights",
                         "combined_score", "timeframe_weights", "confidence_score",
                         "retest_long", "retest_short", "smc_false_breakout"):
            self.assertNotIn(obsolete,py_signal)
        for obsolete in ("tfWeights", "familyWeights", "combined", "retestLong",
                         "retestShort", "falseBreakout", "score"):
            self.assertNotIn(obsolete,kt_signal)

    def test_shared_golden_fixture_has_no_score_contract(self):
        root=pathlib.Path(__file__).parent
        fixture_path=root/"app/src/test/resources/strategy_parity_fixture.json"
        fixture=json.loads(fixture_path.read_text())
        durations={"5m":300000,"15m":900000,"30m":1800000}
        data={}
        for tf,count in fixture["bars"].items():
            rows=[]
            for i in range(count):
                close=fixture["base_price"]+i*0.02+math.sin(i*0.3)*0.03
                aligned_start=(fixture["start_ms"]//durations[tf])*durations[tf]
                rows.append({"timestamp":aligned_start+i*durations[tf],
                             "open":close-0.02,"high":close+0.15,"low":close-0.15,
                             "close":close,"volume":100+i})
            data[tf]=rows
        from signal_engine import analyze_market
        result=analyze_market(data)
        self.assertEqual(result["signal"],fixture["expected_signal"])
        self.assertNotIn("combined_score",result)
        self.assertNotIn("timeframe_weights",result)

    def test_any_selected_timeframe_count_uses_unanimous_direction(self):
        root=pathlib.Path(__file__).parent
        py=(root/"signal_engine.py").read_text()
        kt=(root/"app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        self.assertIn("selected_tfs = tuple(tf for tf in SUPPORTED_TFS if tf in timeframes)",py)
        self.assertIn('reports.values.all{it.bias=="bullish"',kt)
        self.assertIn('reports.values.all{it.bias=="bearish"',kt)
        self.assertIn("fun evaluate(timeframes:Map<String,List<MarketCandle>>",kt)
        self.assertNotIn("REQUIRED_TFS if selected_timeframes is None",py)


if __name__=="__main__":
    unittest.main()
