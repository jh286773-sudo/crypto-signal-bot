import unittest
from unittest.mock import patch
from backtest import run_backtest


def flat_candles(n=70):
    return [{"open":100.0+i,"high":102.0+i,"low":99.0+i,"close":101.0+i,"volume":100.0}
            for i in range(n)]


class BacktestTests(unittest.TestCase):
    def test_enters_on_next_candle_open_and_accounts_for_fees(self):
        data={tf:flat_candles() for tf in ("5m","15m","30m")}
        with patch("backtest.analyze_market", return_value={"signal":"LONG"}):
            result=run_backtest(data,holding_bars=2,fee_bps=0)
        self.assertGreater(result["trade_count"],0)
        first=result["trades"][0]
        self.assertEqual(first["entry_index"],first["signal_index"]+1)
        self.assertEqual(first["entry"],data["5m"][first["entry_index"]]["open"])
        self.assertEqual(first["exit_index"],first["entry_index"]+2)

    def test_rejects_unaligned_timeframes(self):
        data={"5m":flat_candles(70),"15m":flat_candles(69),"30m":flat_candles(70)}
        with self.assertRaises(ValueError):
            run_backtest(data)

    def test_backtest_accounts_for_slippage_and_funding_and_reports_drawdown(self):
        data={tf:flat_candles(80) for tf in ("5m","15m","30m")}
        with patch("backtest.analyze_market", return_value={"signal":"LONG"}):
            result=run_backtest(data,holding_bars=2,fee_bps=0,
                                slippage_bps=2,funding_bps_per_8h=8)
        self.assertGreater(result["round_trip_slippage_bps"],0)
        self.assertGreater(result["estimated_funding_bps"],0)
        self.assertIn("max_drawdown_pct",result)
        self.assertIn("expectancy_pct",result)

    def test_backtest_rejects_negative_execution_costs(self):
        data={tf:flat_candles(70) for tf in ("5m","15m","30m")}
        with self.assertRaises(ValueError):
            run_backtest(data,slippage_bps=-1)

    def test_rejects_negative_fees(self):
        data={tf:flat_candles() for tf in ("5m","15m","30m")}
        with self.assertRaises(ValueError):
            run_backtest(data,fee_bps=-1)


if __name__ == "__main__":
    unittest.main()
