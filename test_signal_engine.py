import unittest, math
from unittest.mock import patch
from signal_engine import analyze_timeframe, analyze_market, _indicator_bias, _adx_regime, _confirmed_pivots, _market_structure


def candles(n=220, trend=1.0):
    rows=[]
    p=100.0
    for i in range(n):
        p += trend*0.12 + math.sin(i/3)*0.03
        rows.append({"open":p-0.02,"high":p+0.15,"low":p-0.15,"close":p,"volume":100+i})
    return rows


def report(bias, close=100.0):
    return {"valid":True,"bias":bias,"close":close,
            "indicator_votes":{"EMA":1,"RSI":1,"MACD":1,"ADX_DMI":1,
                               "VWAP":1,"OBV_Trend":0,"Bollinger":1},
            "indicators":{"atr":1.0,"smc":{} }}


class EngineTests(unittest.TestCase):
    def test_optimized_macd_and_stoch_rsi_match_reference_calculation(self):
        from signal_engine import _macd, _stoch_rsi, _ema, _rsi
        values=[100.0+math.sin(i/4.0)*2.0+i*0.03 for i in range(120)]
        old_values=[_ema(values[:end],12)-_ema(values[:end],26) for end in range(26,len(values)+1)]
        old_line=_ema(values,12)-_ema(values,26)
        old_signal=_ema(old_values,9)
        actual=_macd(values)
        self.assertAlmostEqual(actual["line"],old_line,places=12)
        self.assertAlmostEqual(actual["signal"],old_signal,places=12)
        old_rsis=[_rsi(values[:end],14) for end in range(15,len(values)+1)]
        window=[value for value in old_rsis if value is not None][-14:]
        expected=0.0 if max(window)==min(window) else 100*(window[-1]-min(window))/(max(window)-min(window))
        self.assertAlmostEqual(_stoch_rsi(values),expected,places=12)

    def test_indicator_report_contains_technical_indicators_without_score_fields(self):
        result=analyze_timeframe(candles())
        self.assertTrue(result["valid"])
        for key in ("ema","rsi","macd","bollinger","adx_dmi","atr","vwap","obv","smc"):
            self.assertIn(key,result["indicators"])
        self.assertEqual(set(result["indicator_votes"]),
                         {"EMA","RSI","MACD","ADX_DMI","VWAP","OBV_Trend","Bollinger"})
        self.assertNotIn("score",result)
        self.assertNotIn("family_scores",result)

    def test_direct_indicator_rule_requires_core_alignment_and_no_supporting_opposition(self):
        self.assertEqual(_indicator_bias({"EMA":1,"RSI":1,"MACD":1,"ADX_DMI":1,
                                          "VWAP":1,"OBV_Trend":0,"Bollinger":1}),"bullish")
        self.assertEqual(_indicator_bias({"EMA":-1,"RSI":-1,"MACD":-1,"ADX_DMI":-1,
                                          "VWAP":-1,"OBV_Trend":0,"Bollinger":-1}),"bearish")
        self.assertEqual(_indicator_bias({"EMA":1,"RSI":1,"MACD":1,"ADX_DMI":1,
                                          "VWAP":-1,"OBV_Trend":0,"Bollinger":1}),"neutral")
        self.assertEqual(_indicator_bias({"EMA":1,"RSI":0,"MACD":1,"ADX_DMI":1}),"neutral")

    def test_one_selected_timeframe_generates_an_independent_signal(self):
        with patch("signal_engine.analyze_timeframe",return_value=report("bullish")):
            result=analyze_market({"1m":candles()})
        self.assertEqual(result["signal"],"LONG")
        self.assertEqual(result["entry_timeframe"],"1m")
        self.assertNotIn("combined_score",result)
        self.assertNotIn("timeframe_weights",result)

    def test_all_selected_timeframes_must_agree(self):
        with patch("signal_engine.analyze_timeframe",side_effect=[report("bullish"),report("bullish")]):
            aligned=analyze_market({"5m":candles(),"15m":candles()})
        self.assertEqual(aligned["signal"],"LONG")
        with patch("signal_engine.analyze_timeframe",side_effect=[report("bullish"),report("bearish")]):
            conflict=analyze_market({"5m":candles(),"15m":candles()})
        self.assertEqual(conflict["signal"],"NO_SIGNAL")

    def test_two_or_four_selected_timeframes_are_supported(self):
        with patch("signal_engine.analyze_timeframe",return_value=report("bearish")):
            two=analyze_market({"1m":candles(),"4h":candles()})
        self.assertEqual(two["signal"],"SHORT")
        data={tf:candles() for tf in ("1m","3m","15m","4h")}
        with patch("signal_engine.analyze_timeframe",return_value=report("bullish")):
            four=analyze_market(data)
        self.assertEqual(four["signal"],"LONG")

    def test_default_selection_uses_only_timeframes_present_in_input(self):
        with patch("signal_engine.analyze_timeframe",return_value=report("bullish")):
            result=analyze_market({"30m":candles()})
        self.assertEqual(result["signal"],"LONG")
        self.assertEqual(list(result["timeframes"]),["30m"])

    def test_missing_selected_timeframe_data_returns_no_signal(self):
        result=analyze_market({"1m":candles()},selected_timeframes=("1m","4h"))
        self.assertEqual(result["signal"],"NO_SIGNAL")
        self.assertIn("Missing selected timeframe",result["reason"])

    def test_timestamped_timeframes_must_share_a_closed_decision_boundary(self):
        durations={"5m":300000,"15m":900000}
        start=1800000000000
        decision=start+100*300000
        data={}
        for tf,duration in durations.items():
            count=220
            first=(decision//duration)*duration-duration-(count-1)*duration
            rows=candles(count)
            for i,row in enumerate(rows):
                row["open_time"]=first+i*duration
            data[tf]=rows
        with patch("signal_engine.analyze_timeframe",return_value=report("bullish")):
            self.assertEqual(analyze_market(data)["signal"],"LONG")
        data["15m"][-1]["open_time"]+=durations["15m"]
        result=analyze_market(data)
        self.assertEqual(result["signal"],"NO_SIGNAL")
        self.assertIn("Non-contiguous",result["reason"])

    def test_invalid_old_candle_is_rejected_before_recursive_indicators(self):
        rows=candles(220)
        rows[0]["open"]=float("nan")
        result=analyze_timeframe(rows)
        self.assertFalse(result["valid"])
        self.assertIn("invalid OHLCV",result["reason"])

    def test_adx_regime_and_confirmed_structure_helpers(self):
        self.assertEqual(_adx_regime({"adx":31,"plus_di":28,"minus_di":12})["state"],"trending")
        self.assertEqual(_adx_regime({"adx":14,"plus_di":18,"minus_di":17})["state"],"ranging")
        highs,lows=_confirmed_pivots([1,3,1,4,1],1,1)
        self.assertEqual(highs,[1,3])
        self.assertEqual(lows,[2])
        structure=_market_structure(candles(140))
        for key in ("structure_event","liquidity_sweep","order_blocks","retest_long","retest_short"):
            self.assertIn(key,structure)


if __name__=="__main__":
    unittest.main()
