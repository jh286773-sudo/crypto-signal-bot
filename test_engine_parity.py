import pathlib
import json
import math
import unittest
from unittest.mock import patch


class CrossEngineContractTests(unittest.TestCase):
    def test_both_engines_use_direct_indicator_rules_and_independent_smc_confirmation(self):
        root=pathlib.Path(__file__).parent
        py=(root/"signal_engine.py").read_text()
        kt=(root/"app/src/main/java/com/cryptoscalp/app/TradingStrategy.kt").read_text()
        for token in ("def _indicator_bias", 'indicator_directions.get("EMA") == 1',
                      'indicator_directions.get("ADX_DMI") == 1',
                      'indicator_directions.get("RSI") == 1',
                      'indicator_directions.get("MACD") == 1'):
            self.assertIn(token,py)
        for token in ("fun indicatorBias", 'votes["EMA"]==1',
                      'votes["ADX_DMI"]==1', 'votes["RSI"]==1', 'votes["MACD"]==1'):
            self.assertIn(token,kt)
        py_signal=py[py.index("def analyze_market("):py.index("def generate_signal(")]
        kt_signal=kt[kt.index("fun evaluate(timeframes:"):kt.rfind("\n}")]
        for obsolete in ("family_weights", "tfWeights", "familyWeights",
                         "combined_score", "timeframe_weights", "confidence_score"):
            self.assertNotIn(obsolete,py_signal)
        for obsolete in ("tfWeights", "familyWeights", "combinedScore", "scoreThreshold"):
            self.assertNotIn(obsolete,kt_signal)
        self.assertIn("_smc_confirms(reports[tf].get(\"smc\", {}), side)",py_signal)
        self.assertIn('"smc_confirmation_required"',py_signal)
        self.assertIn("smcConfirms(report.structure,direction)",kt_signal)
        self.assertIn("تأیید مستقل SMC",kt_signal)

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
        # The golden fixture is intentionally historical and deterministic;
        # freeze the engine clock at its final candle close so the live stale-data
        # guard does not invalidate this offline cross-engine parity fixture.
        as_of_ms=max(rows[-1]["timestamp"]+durations[tf] for tf,rows in data.items())
        with patch("signal_engine.time.time",return_value=as_of_ms/1000):
            result=analyze_market(data)
        self.assertEqual(result["signal"],fixture["expected_signal"])
        if fixture["expected_signal"] == "NO_SIGNAL":
            self.assertIn("smc_confirmation_required",result.get("rejection_reasons",[]))
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
