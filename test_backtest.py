import unittest
from unittest.mock import patch
from backtest import run_backtest
from walk_forward import DURATION_MS


def candles(tf,n=400):
    step=DURATION_MS[tf]
    return [{"timestamp":i*step,"open":101.0,"high":103.0,"low":97.0,"close":101.0,"volume":100.0}
            for i in range(n)]


class BacktestTests(unittest.TestCase):
    def test_public_backtest_uses_long_only_hold_until_short_model(self):
        data={tf:candles(tf) for tf in DURATION_MS}
        with patch("walk_forward.analyze_market",return_value={
            "signal":"LONG",
            "timeframes":{"5m":{"indicators":{"atr":1.0}}}
        }):
            result=run_backtest(data,holding_bars=2,fee_bps=0,slippage_bps=0)
        self.assertGreater(result["trade_count"],0)
        trade=result["trades"][0]
        self.assertEqual(trade["side"],"LONG")
        self.assertEqual(trade["exit_reason"],"end_of_data")
        self.assertIsNone(trade["stop_loss"])
        self.assertEqual(trade["take_profits"],[])
        self.assertGreater(trade["position_quantity"],0)

    def test_rejects_missing_timestamps_instead_of_row_index_alignment(self):
        data={tf:candles(tf) for tf in DURATION_MS}
        for row in data["15m"]:
            row.pop("timestamp")
        with self.assertRaisesRegex(ValueError,"timestamp"):
            run_backtest(data,fee_bps=0,slippage_bps=0)

    def test_rejects_estimated_funding_without_historical_exchange_data(self):
        data={tf:candles(tf) for tf in DURATION_MS}
        with self.assertRaisesRegex(ValueError,"funding settlement events"):
            run_backtest(data,fee_bps=0,slippage_bps=0,funding_bps_per_8h=8)


if __name__ == "__main__":
    unittest.main()
