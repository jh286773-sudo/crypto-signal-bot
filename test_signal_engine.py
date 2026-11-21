import unittest, math
from unittest.mock import patch
from signal_engine import analyze_timeframe, analyze_market, _indicator_bias, _adx_regime, _confirmed_pivots, _market_structure, _smc_confirms


def candles(n=220, trend=1.0):
    rows=[]
    p=100.0
    for i in range(n):
        p += trend*0.12 + math.sin(i/3)*0.03
        rows.append({"open":p-0.02,"high":p+0.15,"low":p-0.15,"close":p,"volume":100+i})
    return rows


def report(bias, close=100.0):
    event = "BOS_up" if bias == "bullish" else "BOS_down"
    return {"valid":True,"bias":bias,"close":close,
            "indicator_votes":{"EMA":1,"RSI":1,"MACD":1,"ADX_DMI":1,
                               "VWAP":1,"OBV_Trend":0,"Bollinger":1},
            "smc":{"false_breakout":False,"order_blocks":[],
                   "structure_event":event,"retest_long":False,"retest_short":False,
                   "liquidity_sweep":"none"},
            "indicators":{"atr":1.0}}


class EngineTests(unittest.TestCase):
    def test_smc_is_an_independent_directional_entry_filter(self):
        base = {"false_breakout": False, "order_blocks": [],
                "structure_event": "BOS_up", "retest_long": False,
                "retest_short": False, "liquidity_sweep": "none"}
        self.assertTrue(_smc_confirms(base, "LONG"))
        self.assertFalse(_smc_confirms(base, "SHORT"))
        false_break = dict(base, false_breakout=True)
        self.assertFalse(_smc_confirms(false_break, "LONG"))
        opposing_ob = dict(base, order_blocks=[{"type": "bearish"}])
        self.assertFalse(_smc_confirms(opposing_ob, "LONG"))
        sweep = dict(base, structure_event="none", liquidity_sweep="sell_side")
        self.assertTrue(_smc_confirms(sweep, "LONG"))
        retest = dict(base, structure_event="none", retest_short=True)
        self.assertFalse(_smc_confirms(retest, "LONG"))

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
        self.assertEqual(result["bias"],"bullish")  # RSI above 70 does not veto an aligned uptrend.
        for key in ("ema","rsi","macd","bollinger","adx_dmi","atr","vwap","obv"):
            self.assertIn(key,result["indicators"])
        self.assertEqual(set(result["indicator_votes"]),
                         {"EMA","RSI","MACD","ADX_DMI","VWAP","OBV_Trend","Bollinger"})
        self.assertNotIn("score",result)
        self.assertNotIn("family_scores",result)

    def test_relaxed_direct_rules_need_trend_dmi_and_one_momentum_confirmation(self):
        self.assertEqual(_indicator_bias({"EMA":1,"RSI":1,"MACD":0,"ADX_DMI":1,
                                          "VWAP":-1,"OBV_Trend":-1,"Bollinger":-1}),"bullish")
        self.assertEqual(_indicator_bias({"EMA":-1,"RSI":0,"MACD":-1,"ADX_DMI":-1}),"bearish")
        self.assertEqual(_indicator_bias({"EMA":1,"RSI":0,"MACD":0,"ADX_DMI":1}),"neutral")
        self.assertEqual(_indicator_bias({"EMA":1,"RSI":1,"MACD":-1,"ADX_DMI":1}),"bullish")

    def test_one_selected_timeframe_generates_an_independent_signal(self):
        with patch("signal_engine.analyze_timeframe",return_value=report("bullish")):
            result=analyze_market({"1m":candles()})
        self.assertEqual(result["signal"],"LONG")
        self.assertEqual(result["entry_timeframe"],"1m")
        self.assertNotIn("combined_score",result)
        self.assertNotIn("timeframe_weights",result)

    def test_smc_must_confirm_every_selected_timeframe_before_signal(self):
        bullish = report("bullish")
        bearish_structure = dict(bullish["smc"], structure_event="BOS_down")
        rejected = dict(bullish, smc=bearish_structure)
        with patch("signal_engine.analyze_timeframe", side_effect=[bullish, rejected]):
            result = analyze_market({"5m": candles(), "15m": candles()})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertIn("SMC structural confirmation", result["reason"])
        self.assertIn("smc_confirmation_required", result["rejection_reasons"])
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            accepted = analyze_market({"5m": candles()})
        self.assertEqual(accepted["signal"], "LONG")
        self.assertTrue(accepted["diagnostics"]["5m"]["smc_confirmed"])

    def test_exceptional_volatility_blocks_entries(self):
        volatile = dict(report("bullish"), high_volatility=True)
        with patch("signal_engine.analyze_timeframe",return_value=volatile):
            result = analyze_market({"5m": candles()})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertIn("Exceptional volatility", result["reason"])
        self.assertIn("exceptional_volatility", result["rejection_reasons"])

    def test_all_selected_timeframes_must_agree(self):
        with patch("signal_engine.analyze_timeframe",side_effect=[report("bullish"),report("bullish")]):
            aligned=analyze_market({"5m":candles(),"15m":candles()})
        self.assertEqual(aligned["signal"],"LONG")
        with patch("signal_engine.analyze_timeframe",side_effect=[report("bullish"),report("bearish")]):
            conflict=analyze_market({"5m":candles(),"15m":candles()})
        self.assertEqual(conflict["signal"],"NO_SIGNAL")
        self.assertIn("5m:",conflict["reason"])
        self.assertIn("diagnostics",conflict)

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

    def test_explicit_selection_ignores_unselected_timeframe_data(self):
        from unittest.mock import patch
        data = {tf: candles() for tf in ("1m", "3m", "5m", "15m", "4h")}
        with patch("signal_engine.analyze_timeframe", return_value=report("bullish")) as analyze:
            result = analyze_market(data, selected_timeframes=("3m", "5m"))
        self.assertEqual(result["signal"], "LONG")
        self.assertEqual(list(result["timeframes"]), ["3m", "5m"])
        self.assertEqual([call.args[1] for call in analyze.call_args_list], ["3m", "5m"])

    def test_missing_selected_timeframe_data_returns_no_signal(self):
        result=analyze_market({"1m":candles()},selected_timeframes=("1m","4h"))
        self.assertEqual(result["signal"],"NO_SIGNAL")
        self.assertIn("Missing selected timeframe",result["reason"])

    def test_timestamped_timeframes_use_their_own_latest_closed_candle(self):
        durations={"5m":300000,"15m":900000}
        now_ms=1_800_000_000_000
        data={}
        for tf,duration in durations.items():
            count=220
            latest_open=(now_ms//duration)*duration-duration
            first=latest_open-(count-1)*duration
            rows=candles(count)
            for i,row in enumerate(rows):
                row["open_time"]=first+i*duration
            data[tf]=rows
        seen={}
        def capture(rows,tf):
            seen[tf]=rows[-1]["open_time"]
            return report("bullish")
        with patch("signal_engine.time.time",return_value=now_ms/1000), patch("signal_engine.analyze_timeframe",side_effect=capture):
            result=analyze_market(data)
        self.assertEqual(result["signal"],"LONG")
        self.assertEqual(seen["5m"],now_ms-300000)
        self.assertEqual(seen["15m"],now_ms-900000)
        self.assertNotEqual(seen["5m"],seen["15m"])

        data["15m"][-1]["open_time"]+=durations["15m"]
        with patch("signal_engine.time.time",return_value=now_ms/1000):
            invalid=analyze_market(data)
        self.assertEqual(invalid["signal"],"NO_SIGNAL")
        self.assertIn("Non-contiguous",invalid["reason"])

    def test_partially_timestamped_series_is_rejected_instead_of_bypassing_candle_alignment(self):
        duration = 300_000
        start = 1_800_000_000_000
        rows = candles(220)
        for index, row in enumerate(rows):
            row["open_time"] = start + index * duration
        del rows[100]["open_time"]
        with patch("signal_engine.analyze_timeframe", return_value=report("bullish")) as analyze:
            result = analyze_market({"5m": rows})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertIn("Incomplete timestamps", result["reason"])
        analyze.assert_not_called()

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
