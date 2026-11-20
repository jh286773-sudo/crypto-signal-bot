import unittest
from unittest.mock import patch

from walk_forward import run_walk_forward, _closed_history, _validate, DURATION_MS


def bars(tf, n=800):
    step = DURATION_MS[tf]
    return [{"timestamp": i * step, "open": 101.0, "high": 103.0,
             "low": 99.0, "close": 101.0, "volume": 100.0}
            for i in range(n)]


def signal(side):
    # Deliberately no stop_loss or take_profits: those are not required by the
    # user's hold-until-SHORT strategy. ATR is used only for risk sizing.
    return {"signal": side, "timeframes": {"5m": {"indicators": {"atr": 1.0}}}}


class WalkForwardTests(unittest.TestCase):
    def setUp(self):
        self.data = {tf: bars(tf, 800) for tf in DURATION_MS}

    def test_htf_candle_must_be_closed_at_decision_time(self):
        fifteen = [(0, {"timestamp": 0}), (900000, {"timestamp": 900000})]
        self.assertEqual(len(_closed_history(fifteen, "15m", 900000)), 1)
        self.assertEqual(len(_closed_history(fifteen, "15m", 1800000)), 2)

    def test_requires_real_timestamps_and_contiguous_bars(self):
        data = {tf: bars(tf) for tf in DURATION_MS}
        del data["5m"][5]["timestamp"]
        with self.assertRaisesRegex(ValueError, "timestamp"):
            _validate(data)
        data = {tf: bars(tf) for tf in DURATION_MS}
        data["15m"][20]["timestamp"] += 1000
        with self.assertRaisesRegex(ValueError, "contiguous"):
            _validate(data)

    def test_strategy_receives_only_closed_htf_history(self):
        seen = []
        def fake(hist, **kwargs):
            seen.append({tf: rows[-1]["timestamp"] for tf, rows in hist.items()})
            return {"signal": "NO_SIGNAL"}
        with patch("walk_forward.analyze_market", side_effect=fake):
            run_walk_forward(self.data, fee_bps_per_side=0, slippage_bps_per_side=0)
        self.assertTrue(seen)
        for snap in seen:
            decision_close = snap["5m"] + DURATION_MS["5m"]
            self.assertLessEqual(snap["15m"] + DURATION_MS["15m"], decision_close)
            self.assertLessEqual(snap["30m"] + DURATION_MS["30m"], decision_close)

    def test_long_opens_without_stop_or_take_profit_fields_and_short_closes_it(self):
        def fake(hist, **kwargs):
            index = hist["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            if index == 359:
                return signal("LONG")
            if index == 362:
                return signal("SHORT")
            return {"signal": "NO_SIGNAL"}
        with patch("walk_forward.analyze_market", side_effect=fake):
            result = run_walk_forward(self.data, fee_bps_per_side=0,
                                      slippage_bps_per_side=0, initial_equity=10000)
        self.assertEqual(result["trade_count"], 1)
        trade = result["trades"][0]
        self.assertEqual(trade["side"], "LONG")
        self.assertEqual(trade["exit_reason"], "opposite_short_signal")
        self.assertTrue(trade["closed_by_short_signal"])
        self.assertIsNone(trade["stop_loss"])
        self.assertEqual(trade["take_profits"], [])
        self.assertEqual(result["side_stats"]["SHORT"]["trade_count"], 0)

    def test_short_while_flat_never_opens_a_short_trade(self):
        with patch("walk_forward.analyze_market", return_value=signal("SHORT")):
            result = run_walk_forward(self.data, fee_bps_per_side=0,
                                      slippage_bps_per_side=0)
        self.assertEqual(result["trade_count"], 0)
        self.assertEqual(result["side_stats"]["SHORT"]["trade_count"], 0)

    def test_long_is_held_through_long_and_wait_until_short(self):
        def fake(hist, **kwargs):
            index = hist["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            return signal("LONG") if index in (359, 360) else (
                signal("SHORT") if index == 362 else {"signal": "NO_SIGNAL"})
        with patch("walk_forward.analyze_market", side_effect=fake):
            result = run_walk_forward(self.data, fee_bps_per_side=0,
                                      slippage_bps_per_side=0)
        self.assertEqual(result["trade_count"], 1)
        self.assertEqual(result["trades"][0]["entry_time"], 360 * DURATION_MS["5m"])
        self.assertEqual(result["trades"][0]["exit_reason"], "opposite_short_signal")

    def test_two_net_losses_do_not_block_a_later_long_entry(self):
        def fake(hist, **kwargs):
            index = hist["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            if index in (359, 361, 651):
                return signal("LONG")
            if index in (360, 362):
                return signal("SHORT")
            return {"signal": "NO_SIGNAL"}
        with patch("walk_forward.analyze_market", side_effect=fake):
            result = run_walk_forward(self.data, fee_bps_per_side=25,
                                      slippage_bps_per_side=0, initial_equity=10000)
        self.assertEqual(result["trade_count"], 3)
        self.assertTrue(all(t["side"] == "LONG" for t in result["trades"]))
        self.assertNotIn("loss_guard", result)

    def test_distinct_fees_and_funding_are_charged_at_long_close(self):
        self.data["5m"][361]["funding_rate"] = 0.001
        self.data["5m"][361]["funding_settle_time"] = 362 * DURATION_MS["5m"]
        def fake(hist, **kwargs):
            index = hist["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            return signal("LONG") if index == 359 else (
                signal("SHORT") if index == 361 else {"signal": "NO_SIGNAL"})
        with patch("walk_forward.analyze_market", side_effect=fake):
            result = run_walk_forward(self.data, fee_bps_open=2, fee_bps_close=4,
                                      slippage_bps_per_side=0, initial_equity=10000)
        trade = result["trades"][0]
        self.assertEqual(trade["funding_mode"], "historical_settlement_events")
        self.assertGreater(trade["funding_cash"], 0)
        self.assertGreater(trade["fees_cash"], 0)

    def test_open_position_at_end_is_marked_to_market_not_discarded(self):
        def fake(hist, **kwargs):
            index = hist["5m"][-1]["timestamp"] // DURATION_MS["5m"]
            return signal("LONG") if index == 359 else {"signal": "NO_SIGNAL"}
        with patch("walk_forward.analyze_market", side_effect=fake):
            result = run_walk_forward(self.data, fee_bps_per_side=0,
                                      slippage_bps_per_side=0)
        self.assertEqual(result["trade_count"], 1)
        self.assertEqual(result["trades"][0]["exit_reason"], "end_of_data")


if __name__ == "__main__":
    unittest.main()
