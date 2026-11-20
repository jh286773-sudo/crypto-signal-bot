import unittest
from unittest.mock import patch
from backtest import run_backtest
from walk_forward import DURATION_MS


def candles(tf,n=400):
    step=DURATION_MS[tf]
    return [{"timestamp":i*step,"open":100.0,"high":103.0,"low":97.0,"close":101.0,"volume":100.0}
            for i in range(n)]


class BacktestTests(unittest.TestCase):
    def test_public_backtest_uses_timestamp_walk_forward_and_partial_exits(self):
        data={tf:candles(tf) for tf in DURATION_MS}
        for row in data["5m"][60:]:
            row["low"]=100.0; row["high"]=105.0
        with patch("walk_forward.analyze_market",return_value={
            "signal":"LONG","stop_loss":100.0,"take_profits":[102.0,103.0,104.0]
        }):
            result=run_backtest(data,holding_bars=2,fee_bps=0,slippage_bps=0)
        self.assertGreater(result["trade_count"],0)
        trade=result["trades"][0]
        self.assertIn("TP1",trade["targets_hit"])
        self.assertIn("TP2",trade["targets_hit"])
        self.assertIn("TP3",trade["targets_hit"])
        self.assertGreater(trade["position_quantity"],0)

    def test_rejects_missing_timestamps_instead_of_row_index_alignment(self):
        data={tf:candles(tf) for tf in DURATION_MS}
        for row in data["15m"]:
            row.pop("timestamp")
        with self.assertRaisesRegex(ValueError,"timestamp"):
            run_backtest(data,fee_bps=0,slippage_bps=0)

    def test_rejects_estimated_funding_without_historical_exchange_data(self):
        data={tf:candles(tf) for tf in DURATION_MS}
        with self.assertRaisesRegex(ValueError,"historical funding_rate"):
            run_backtest(data,fee_bps=0,slippage_bps=0,funding_bps_per_8h=8)


if __name__ == "__main__":
    unittest.main()
