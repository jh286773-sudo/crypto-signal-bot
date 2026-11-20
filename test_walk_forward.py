import unittest
from unittest.mock import patch
from walk_forward import run_walk_forward, _closed_history, _validate, DURATION_MS


def bars(tf, n=100):
    step=DURATION_MS[tf]
    return [{"timestamp":i*step,"open":100.0,"high":103.0,"low":97.0,"close":101.0,"volume":100.0}
            for i in range(n)]


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

    def test_position_sizing_and_partial_targets_are_reported(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        for row in data["5m"][60:]:
            row["low"]=100.0; row["high"]=105.0
        signal={"signal":"LONG","stop_loss":100.0,"take_profits":[102.0,103.0,104.0]}
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


    def test_distinct_entry_exit_fees_and_funding_settlement_once(self):
        data={tf:bars(tf,400) for tf in DURATION_MS}
        for row in data["5m"][360:364]:
            row["low"]=100.0; row["high"]=100.5
        data["5m"][361]["funding_rate"]=0.001
        data["5m"][361]["funding_settle_time"]=362*DURATION_MS["5m"]
        with patch("walk_forward.analyze_market",return_value={
            "signal":"LONG","stop_loss":100.0,"take_profits":[102.0,103.0,104.0]
        }):
            result=run_walk_forward(data,fee_bps_open=2,fee_bps_close=4,
                                    slippage_bps_per_side=0,initial_equity=10000,
                                    max_holding_bars=2)
        trade=result["trades"][0]
        self.assertAlmostEqual(trade["funding_cash"],10.0,places=6)
        self.assertEqual(trade["funding_mode"],"historical_settlement_events")
        self.assertAlmostEqual(trade["fees_cash"],6.04,places=2)


if __name__ == "__main__":
    unittest.main()
