import unittest
from unittest.mock import patch
from walk_forward import run_walk_forward, _closed_history, _validate, DURATION_MS


def bars(tf, n=100):
    step=DURATION_MS[tf]
    return [{"timestamp":i*step,"open":101.0,"high":103.0,"low":97.0,"close":101.0,"volume":100.0}
            for i in range(n)]


def make_signal(stop=100.0, targets=(102.0,103.0,104.0), side="LONG", atr=1.0):
    return {"signal":side, "stop_loss":stop, "take_profits":list(targets),
            "timeframes":{"5m":{"indicators":{"atr":atr}}}}


class WalkForwardTests(unittest.TestCase):
    def test_htf_candle_must_be_closed_at_decision_time(self):
        fifteen=[(0,{"timestamp":0}), (900000,{"timestamp":900000})]
        self.assertEqual(len(_closed_history(fifteen,"15m",900000)),1)
        self.assertEqual(len(_closed_history(fifteen,"15m",900001)),1)
        self.assertEqual(len(_closed_history(fifteen,"15m",1800000)),2)

    def test_requires_real_timestamps_and_contiguous_bars(self):
        data={tf:bars(tf) for tf in DURATION_MS}
        del data["5m"][5]["timestamp"]
        with self.assertRaisesRegex(ValueError,"timestamp"):
            _validate(data)
        data={tf:bars(tf) for tf in DURATION_MS}
        data["15m"][20]["timestamp"] += 1000
        with self.assertRaisesRegex(ValueError,"contiguous"):
            _validate(data)

    def test_only_closed_htf_history_is_passed_to_strategy(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        seen=[]
        def fake(hist):
            seen.append({tf:row[-1]["timestamp"] for tf,row in hist.items()})
            return {"signal":"NO_SIGNAL"}
        with patch("walk_forward.analyze_market",side_effect=fake):
            run_walk_forward(data,fee_bps_per_side=5,slippage_bps_per_side=1)
        for snap in seen:
            decision_close=snap["5m"]+DURATION_MS["5m"]
            self.assertLessEqual(snap["15m"]+DURATION_MS["15m"],decision_close)
            self.assertLessEqual(snap["30m"]+DURATION_MS["30m"],decision_close)

    def test_strategy_receives_only_live_equivalent_260_bar_windows(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        seen=[]
        def fake(hist):
            seen.append({tf:len(rows) for tf,rows in hist.items()})
            return {"signal":"NO_SIGNAL"}
        with patch("walk_forward.analyze_market",side_effect=fake):
            run_walk_forward(data,fee_bps_per_side=5,slippage_bps_per_side=1)
        self.assertTrue(seen)
        self.assertTrue(all(count<=260 for snap in seen for count in snap.values()))
        self.assertEqual(seen[0]["5m"],260)

    def test_position_sizing_and_partial_targets_are_reported(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        for row in data["5m"][60:]:
            row["low"]=102.1; row["high"]=105.0
        signal=make_signal(100.0,[102.0,103.0,104.0])
        with patch("walk_forward.analyze_market",return_value=signal):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0,
                                    initial_equity=10000,risk_fraction=.01,max_holding_bars=2)
        self.assertGreater(result["trade_count"],0)
        trade=result["trades"][0]
        self.assertAlmostEqual(trade["risk_cash"],100.0)
        self.assertAlmostEqual(trade["position_quantity"],100.0)
        self.assertIn("TP1",trade["targets_hit"])
        self.assertIn("TP2",trade["targets_hit"])
        self.assertIn("TP3",trade["targets_hit"])


    def test_tp1_then_same_bar_breakeven_touch_closes_remainder_conservatively(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        for row in data["5m"][60:]:
            row["low"]=100.5
            row["high"]=103.0
        signal=make_signal(100.0,[102.0,103.0,104.0])
        with patch("walk_forward.analyze_market",return_value=signal):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0,
                                    initial_equity=10000,risk_fraction=.01,max_holding_bars=2)
        trade=result["trades"][0]
        self.assertEqual(trade["targets_hit"],["TP1"])
        self.assertEqual(trade["exit_reason"],"stop_after_tp1")
        self.assertAlmostEqual(trade["exit"],trade["entry"])

    def test_position_size_accounts_for_round_trip_fees_and_slippage(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        signal=make_signal(100.0,[102.0,103.0,104.0])
        with patch("walk_forward.analyze_market",return_value=signal):
            free=run_walk_forward(data,fee_bps_open=0,fee_bps_close=0,
                                  slippage_bps_per_side=0,initial_equity=10000,
                                  risk_fraction=.01,max_holding_bars=1)
            costly=run_walk_forward(data,fee_bps_open=10,fee_bps_close=10,
                                    slippage_bps_per_side=5,initial_equity=10000,
                                    risk_fraction=.01,max_holding_bars=1)
        free_trade=free["trades"][0]
        costly_trade=costly["trades"][0]
        self.assertLess(costly_trade["position_quantity"],free_trade["position_quantity"])
        self.assertAlmostEqual(costly_trade["estimated_risk_cash_including_costs"],100.0,places=6)
        self.assertEqual(costly["position_sizing"],
                         "risk_budget_includes_estimated_round_trip_fees_and_slippage")

    def test_tp2_locks_tp1_for_remaining_position(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        # Entry is 100; targets are 101.5R, 102.5R, 103.5R for a 1.0 risk.
        data["5m"][360].update({"low":100.5,"high":103.0})
        data["5m"][361].update({"open":100.6,"low":100.5,"high":101.0,"close":100.8})
        signal=make_signal(100.0,[101.5,102.5,103.5])
        with patch("walk_forward.analyze_market",return_value=signal):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0,
                                    initial_equity=10000,risk_fraction=.01,max_holding_bars=2)
        trade=result["trades"][0]
        self.assertEqual(trade["targets_hit"],["TP1","TP2"])
        self.assertEqual(trade["exit_reason"],"stop_after_tp2")
        self.assertAlmostEqual(trade["exit"],101.5)

    def test_protected_stop_losses_count_toward_loss_cooldown(self):
        data={tf:bars(tf,800) for tf in DURATION_MS}
        for row in data["5m"][360:]:
            row["low"]=100.5
            row["high"]=102.0
        signal=make_signal(100.0,[101.5,102.5,103.5])
        with patch("walk_forward.analyze_market",return_value=signal):
            result=run_walk_forward(data,fee_bps_per_side=25,slippage_bps_per_side=0,
                                    initial_equity=10000,max_holding_bars=1)
        self.assertGreaterEqual(result["trade_count"],2)
        self.assertEqual(result["trades"][0]["exit_reason"],"stop_after_tp1")
        self.assertLess(result["trades"][0]["net_pnl"],0)
        self.assertGreaterEqual(result["loss_guard"]["cooldown_events"],1)

    def test_next_open_gap_is_skipped_instead_of_chasing_signal(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        data["5m"][360]["open"]=102.0
        signal=make_signal(100.0,[102.0,103.0,104.0])
        def one_signal(history):
            index=history["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            return signal if index==359 else {"signal":"NO_SIGNAL"}
        with patch("walk_forward.analyze_market",side_effect=one_signal):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0)
        self.assertEqual(result["trade_count"],0)
        self.assertEqual(result["entry_filter"]["entry_gap_skips"],1)

    def test_default_risk_is_half_percent_and_two_losses_trigger_cooldown(self):
        data={tf:bars(tf,800) for tf in DURATION_MS}
        signal=make_signal(99.0,[102.0,103.0,104.0])
        with patch("walk_forward.analyze_market",return_value=signal):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0,
                                    initial_equity=10000,max_holding_bars=1)
        self.assertEqual(result["risk_fraction"],0.005)
        self.assertAlmostEqual(result["trades"][0]["risk_cash"],50.0)
        self.assertEqual(result["loss_guard"]["loss_streak_limit"],2)
        self.assertEqual(result["loss_guard"]["cooldown_bars"],288)
        self.assertGreaterEqual(result["loss_guard"]["cooldown_events"],1)
        self.assertGreater(result["loss_guard"]["cooldown_skipped_bars"],200)
        self.assertLess(result["trade_count"],100)

    def test_full_stops_more_than_24_hours_apart_do_not_trigger_cooldown(self):
        data={tf:bars(tf,800) for tf in DURATION_MS}
        signal=make_signal(99.0,[102.0,103.0,104.0])
        def sparse_signals(history):
            index=history["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            return signal if index in (359,649) else {"signal":"NO_SIGNAL"}
        with patch("walk_forward.analyze_market",side_effect=sparse_signals):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0,
                                    initial_equity=10000,max_holding_bars=1)
        self.assertEqual(result["trade_count"],2)
        self.assertEqual(result["loss_guard"]["cooldown_events"],0)

    def test_walk_forward_reports_directional_quality_and_loss_streak(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        with patch("walk_forward.analyze_market",return_value={"signal":"NO_SIGNAL"}):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0)
        self.assertIn("LONG",result["side_stats"])
        self.assertIn("SHORT",result["side_stats"])
        self.assertEqual(result["max_consecutive_losses"],0)
        self.assertEqual(result["profit_factor"],0.0)

    def test_funding_is_not_silently_dropped_on_same_bar_exit(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        data["5m"][360]["low"]=98.0
        data["5m"][360]["high"]=101.0
        data["5m"][360]["funding_rate"]=0.001
        data["5m"][360]["funding_settle_time"]=360*DURATION_MS["5m"]+60_000
        with patch("walk_forward.analyze_market",return_value=make_signal(
            100.0,[102.0,103.0,104.0])):
            result=run_walk_forward(data,fee_bps_per_side=0,slippage_bps_per_side=0,
                                    initial_equity=10000,max_holding_bars=2)
        trade=result["trades"][0]
        self.assertEqual(trade["exit_reason"],"stop")
        self.assertAlmostEqual(
            trade["funding_cash"],trade["entry"]*trade["position_quantity"]*0.001,places=6
        )

    def test_distinct_entry_exit_fees_and_funding_settlement_once(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        for row in data["5m"][360:364]:
            row["low"]=100.5; row["high"]=101.0
        data["5m"][361]["funding_rate"]=0.001
        data["5m"][361]["funding_settle_time"]=362*DURATION_MS["5m"]
        with patch("walk_forward.analyze_market",return_value=make_signal(
            100.0,[102.0,103.0,104.0])):
            result=run_walk_forward(data,fee_bps_open=2,fee_bps_close=4,
                                    slippage_bps_per_side=0,initial_equity=10000,
                                    max_holding_bars=2)
        trade=result["trades"][0]
        self.assertAlmostEqual(
            trade["funding_cash"], trade["entry"] * trade["position_quantity"] * 0.001,
            places=6
        )
        self.assertEqual(trade["funding_mode"],"historical_settlement_events")
        expected_fees = (
            trade["entry"] * trade["position_quantity"] * 2 / 10000
            + trade["exit"] * trade["position_quantity"] * 4 / 10000
        )
        self.assertAlmostEqual(trade["fees_cash"], expected_fees, places=6)


if __name__ == "__main__":
    unittest.main()
