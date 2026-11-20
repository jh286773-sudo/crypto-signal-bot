import unittest, math
from unittest.mock import patch
from signal_engine import analyze_timeframe, analyze_market, _adx_regime, _confirmed_pivots, _market_structure

def candles(n=100, trend=1.0):
    rows=[]; p=100.0
    for i in range(n):
        p += trend*0.12 + math.sin(i/3)*0.03
        rows.append({"open":p-0.02,"high":p+0.15,"low":p-0.15,"close":p,"volume":100+i})
    return rows

class EngineTests(unittest.TestCase):
    def test_optimized_macd_and_stoch_rsi_match_reference_calculation(self):
        from signal_engine import _macd, _stoch_rsi, _ema, _rsi
        values = [100.0 + math.sin(i/4.0)*2.0 + i*0.03 for i in range(120)]
        old_values = []
        for end in range(26, len(values)+1):
            old_values.append(_ema(values[:end],12)-_ema(values[:end],26))
        old_line = _ema(values,12)-_ema(values,26)
        old_signal = _ema(old_values,9)
        actual = _macd(values)
        self.assertAlmostEqual(actual["line"], old_line, places=12)
        self.assertAlmostEqual(actual["signal"], old_signal, places=12)
        old_rsis = [_rsi(values[:end],14) for end in range(15,len(values)+1)]
        old_rsis = [value for value in old_rsis if value is not None]
        window = old_rsis[-14:]
        expected = 0.0 if max(window)==min(window) else 100*(window[-1]-min(window))/(max(window)-min(window))
        self.assertAlmostEqual(_stoch_rsi(values), expected, places=12)

    def test_indicator_report(self):
        r=analyze_timeframe(candles())
        self.assertTrue(r["valid"])
        for key in ("ema","sma","rsi","macd","bollinger","stoch_rsi","adx_dmi","ichimoku","atr","vwap","obv","mfi","divergence","smc","taker_flow"):
            if key=="ema" or key=="sma": self.assertIn(key,r["indicators"])
            else: self.assertIn(key,r["indicators"])
    def test_requires_all_timeframes(self):
        r=analyze_market({"5m":candles(),"15m":candles()})
        self.assertEqual(r["signal"],"NO_SIGNAL")
    def test_signal_shape(self):
        data={tf:candles(120,1.0) for tf in ("5m","15m","30m")}
        r=analyze_market(data)
        self.assertIn(r["signal"],("LONG","SHORT","NO_SIGNAL"))
        self.assertIn("combined_score",r)
    def test_two_of_three_alignment_never_signals(self):
        bullish={"valid":True,"score":60.0,"bias":"bullish","family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, "indicators":{"atr":1.0},"close":100.0}
        bearish={"valid":True,"score":-60.0,"bias":"bearish","indicators":{"atr":1.0},"close":100.0}
        with patch("signal_engine.analyze_timeframe", side_effect=[bullish,bullish,bearish]):
            result=analyze_market({tf:[] for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"],"NO_SIGNAL")
    def test_all_three_aligned_can_signal_after_confirmed_retest(self):
        bullish={"valid":True,"score":60.0,"bias":"bullish","family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, "indicators":{"atr":1.0,"smc":{"retest_long":True,"structure_event":"BOS_up","entry_distance_atr":0.2,"previous_swing_low":98.0}},"ema21":99.5,"close":100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result=analyze_market({tf:[] for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"],"LONG")
    def test_anti_chase_filter_rejects_extended_entry_and_accepts_pullback(self):
        from signal_engine import _entry_not_chasing
        self.assertFalse(_entry_not_chasing(102.0, 100.0, 2.0))
        self.assertTrue(_entry_not_chasing(101.5, 100.0, 2.0))
        self.assertFalse(_entry_not_chasing(101.0, None, 2.0))
        self.assertFalse(_entry_not_chasing(101.0, 100.0, 0.0))

    def test_bad_input_safe(self):
        self.assertFalse(analyze_timeframe([{"close":1}]*70)["valid"])

    def test_invalid_old_candle_is_rejected_before_recursive_indicators(self):
        rows = candles(100)
        rows[0]["open"] = float("nan")
        result = analyze_timeframe(rows)
        self.assertFalse(result["valid"])
        self.assertIn("invalid OHLCV", result["reason"])

    def test_high_volatility_filter_blocks_signal(self):
        bullish={"valid":True,"score":60.0,"bias":"bullish","family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, "high_volatility":True,"indicators":{"atr":1.0},"close":100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result=analyze_market({tf:candles() for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"],"NO_SIGNAL")
        self.assertEqual(result["high_volatility_block"],["5m","15m","30m"])

    def test_volatility_filter_reports_baseline(self):
        r=analyze_timeframe(candles(),"5m")
        self.assertIn("volatility_filter",r)
        self.assertIn("baseline_atr_pct",r["volatility_filter"])

    def test_adx_regime_distinguishes_trend_range_and_transition(self):
        self.assertEqual(_adx_regime({"adx": 31, "plus_di": 28, "minus_di": 12})["state"], "trending")
        self.assertEqual(_adx_regime({"adx": 14, "plus_di": 18, "minus_di": 17})["state"], "ranging")
        self.assertEqual(_adx_regime({"adx": 21, "plus_di": 18, "minus_di": 17})["state"], "transition")

    def test_confirmed_pivots_exclude_unconfirmed_edges(self):
        highs, lows = _confirmed_pivots([1, 3, 1, 4, 1], 1, 1)
        self.assertEqual(highs, [1, 3])
        self.assertEqual(lows, [2])

    def test_smc_report_exposes_structure_and_liquidity_fields(self):
        data = candles(120)
        structure = _market_structure(data)
        self.assertIn("structure_event", structure)
        self.assertIn("liquidity_sweep", structure)
        self.assertIn("order_blocks", structure)
        self.assertIn("equal_high_liquidity", structure)

    def test_report_exposes_adx_market_regime(self):
        result = analyze_timeframe(candles(120), "15m")
        self.assertIn(result["market_regime"]["state"], ("trending", "ranging", "transition", "unknown"))

    def test_five_minute_trigger_must_confirm_higher_timeframe_bias(self):
        strong = {"valid": True, "score": 60.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0},  "indicators": {"atr": 1.0}, "close": 100.0}
        weak_trigger = {"valid": True, "score": 15.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0},  "indicators": {"atr": 1.0}, "close": 100.0}
        with patch("signal_engine.analyze_timeframe", side_effect=[weak_trigger, strong, strong]):
            result = analyze_market({tf: candles() for tf in ("5m", "15m", "30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")

    def test_higher_timeframes_need_stronger_conviction_than_basic_bias(self):
        strong = {"valid": True, "score": 60.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, 
                  "indicators": {"atr": 1.0, "smc": {"retest_long": True}}, "close": 100.0}
        moderate = {"valid": True, "score": 24.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, 
                    "indicators": {"atr": 1.0, "smc": {"retest_long": True}}, "close": 100.0}
        with patch("signal_engine.analyze_timeframe", side_effect=[strong, moderate, strong]):
            result = analyze_market({tf: candles() for tf in ("5m", "15m", "30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")

    def test_obv_uses_recent_direction_not_cumulative_sign(self):
        from signal_engine import _obv_trend
        rows = candles(20)
        for i, row in enumerate(rows):
            row["close"] = 100.0 + (i if i < 12 else 12 - (i - 12))
            row["volume"] = 100.0
        self.assertEqual(_obv_trend(rows), -1)

    def test_taker_flow_uses_exchange_buy_volume_when_available(self):
        from signal_engine import _taker_strength
        rows = candles(70)
        for row in rows:
            row["volume"] = 100.0
            row["taker_buy_volume"] = 60.0
        flow = _taker_strength(rows)
        self.assertTrue(flow["available"])
        self.assertEqual(flow["buy_percent"], 60.0)
        self.assertEqual(flow["sell_percent"], 40.0)

    def test_invalid_taker_buy_volume_rejects_entire_candle_history(self):
        rows = candles(80)
        rows[0]["taker_buy_volume"] = rows[0]["volume"] + 10.0
        result = analyze_timeframe(rows)
        self.assertFalse(result["valid"])
        self.assertIn("order-flow", result["reason"])

    def test_volume_impulse_requires_expansion_and_directional_close(self):
        from signal_engine import _volume_impulse
        rows = candles(30)
        for row in rows:
            row["volume"] = 100.0
        for row in rows[-5:]:
            row["volume"] = 200.0
        rows[-1].update({"open": 100.0, "low": 99.8, "high": 101.0,
                         "close": 100.9})
        self.assertEqual(_volume_impulse(rows, 0.5), 1)

    def test_divergence_requires_enough_closed_candles(self):
        from signal_engine import _divergence
        self.assertEqual(_divergence(candles(60)), "none")


    def test_directional_consensus_without_retest_is_blocked(self):
        bullish = {"valid": True, "score": 60.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, 
                   "indicators": {"atr": 1.0, "smc": {"structure_event": "BOS_up",
                       "retest_long": False}}, "close": 100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result = analyze_market({tf: candles() for tf in ("5m", "15m", "30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertEqual(result["entry_stage"], "CONFIRMATION")
        self.assertFalse(result["entry_context"]["retest_confirmed"])

    def test_missing_smc_retest_evidence_cannot_bypass_entry_gate(self):
        bullish = {"valid": True, "score": 60.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, 
                   "indicators": {"atr": 1.0, "smc": {}}, "close": 100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result = analyze_market({tf: candles() for tf in ("5m", "15m", "30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertEqual(result["entry_stage"], "PREPARATION")
        self.assertFalse(result["entry_context"]["retest_confirmed"])

    def test_adaptive_levels_use_structure_and_reward_risk_multiples(self):
        from signal_engine import _levels
        levels = _levels("LONG", 100.0, 1.0, {"previous_swing_low": 98.0})
        self.assertAlmostEqual(levels["stop_loss"], 97.85)
        self.assertEqual(levels["risk_reward"], [1.5, 2.5, 3.5])
        self.assertGreater(levels["take_profits"][1], levels["take_profits"][0])

    def test_market_structure_exposes_retest_and_false_breakout_state(self):
        rows = candles(120)
        structure = _market_structure(rows)
        for key in ("retest_long", "retest_short", "false_breakout", "breakout_quality"):
            self.assertIn(key, structure)

    def test_smc_reports_atr_distance_and_liquidity_pool_levels(self):
        structure = _market_structure(candles(140))
        self.assertIn("entry_distance_atr", structure)
        self.assertIn("equal_high_level", structure)
        self.assertIn("equal_low_level", structure)

    def test_failed_breakout_blocks_even_a_retest_flag(self):
        bullish = {"valid": True, "score": 60.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, 
                   "indicators": {"atr": 1.0, "smc": {"retest_long": True, "entry_distance_atr": 0.2,
                       "structure_event": "BOS_up", "false_breakout": True,
                       "entry_distance_atr": 0.2}}, "close": 100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result = analyze_market({tf: candles() for tf in ("5m", "15m", "30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertEqual(result["entry_stage"], "WAIT")
        self.assertIn("Failed breakout", result["reason"])

    def test_entry_guard_rejects_overextended_retest(self):
        bullish = {"valid": True, "score": 60.0, "bias": "bullish", "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,"volume":0.8,"structure":0.8,"orderflow":0.8,"volatility":0.0,"divergence":0.0}, 
                   "indicators": {"atr": 1.0, "smc": {"retest_long": True,
                       "structure_event": "BOS_up", "entry_distance_atr": 1.2,
                       "previous_swing_low": 98.0}}, "close": 100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result = analyze_market({tf: candles() for tf in ("5m", "15m", "30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertEqual(result["entry_stage"], "WAIT")
        self.assertIn("overextended", result["reason"])


    def test_individual_indicator_conflicts_allow_one_minor_but_block_core_or_two(self):
        from signal_engine import _no_major_indicator_conflict
        self.assertTrue(_no_major_indicator_conflict({"EMA":1,"RSI":1,"MACD":1,"VWAP":-1}, "LONG"))
        self.assertFalse(_no_major_indicator_conflict({"EMA":1,"RSI":-1,"MACD":1,"VWAP":1}, "LONG"))
        self.assertFalse(_no_major_indicator_conflict({"EMA":1,"RSI":1,"MACD":1,"VWAP":-1,"OBV_Trend":-1}, "LONG"))
        self.assertTrue(_no_major_indicator_conflict({"EMA":-1,"RSI":-1,"MACD":-1,"VWAP":1}, "SHORT"))

    def test_indicator_family_conflict_blocks_directional_signal(self):
        conflicted = {"valid": True, "score": 60.0, "bias": "bullish",
                      "family_scores": {"trend":-0.8,"momentum":0.7,"strength":0.6,
                                        "volume":0.7,"structure":0.6,"orderflow":0.8,
                                        "volatility":0.0,"divergence":0.0},
                      "indicators": {"atr":1.0,"smc":{"retest_long":True,"entry_distance_atr":0.2}},
                      "close":100.0}
        with patch("signal_engine.analyze_timeframe", return_value=conflicted):
            result = analyze_market({tf:candles() for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")

    def test_missing_retest_distance_blocks_signal(self):
        bullish = {"valid": True, "score": 60.0, "bias": "bullish",
                   "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,
                                     "volume":0.8,"structure":0.8,"orderflow":0.8},
                   "indicators": {"atr":1.0,"smc":{"retest_long":True,"structure_event":"BOS_up"}},
                   "close":100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result = analyze_market({tf:candles() for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")
        self.assertIn("missing or overextended", result["reason"])


    def test_liquidity_sweep_without_breakout_retest_is_not_an_entry(self):
        bullish = {"valid": True, "score": 60.0, "bias": "bullish",
                   "family_scores": {"trend":0.8,"momentum":0.8,"strength":0.8,
                                     "volume":0.8,"structure":0.8,"orderflow":0.8},
                   "indicators": {"atr":1.0,"smc":{"liquidity_sweep":"sell_side",
                       "retest_long":False,"structure_event":"none"}}, "close":100.0}
        with patch("signal_engine.analyze_timeframe", return_value=bullish):
            result = analyze_market({tf:candles() for tf in ("5m","15m","30m")})
        self.assertEqual(result["signal"], "NO_SIGNAL")


if __name__=="__main__": unittest.main()
