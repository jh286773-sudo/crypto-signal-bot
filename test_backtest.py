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


    def test_intrabar_stop_and_target_same_bar_resolves_to_stop(self):
        data={tf:flat_candles(80) for tf in ("5m","15m","30m")}
        data["5m"][59].update({"open":100.0,"high":100.5,"low":99.5,"close":100.0})
        data["5m"][60].update({"open":100.0,"high":100.5,"low":99.5,"close":100.0})
        data["5m"][61].update({"open":100.0,"high":104.0,"low":98.0,"close":102.0})
        with patch("backtest.analyze_market", return_value={
            "signal":"LONG","stop_loss":99.0,"take_profits":[101.0,103.0,105.0]
        }):
            result=run_backtest(data,holding_bars=2,fee_bps=0,slippage_bps=0)
        first=result["trades"][0]
        self.assertEqual(first["exit_reason"],"stop_ambiguous_bar")
        self.assertEqual(first["exit"],99.0)
        self.assertEqual(first["exit_index"],61)

    def test_rejects_non_chronological_candles(self):
        data={tf:flat_candles(70) for tf in ("5m","15m","30m")}
        for rows in data.values():
            for i,row in enumerate(rows):
                row["timestamp"]=i*300000
        data["5m"][10]["timestamp"]=data["5m"][9]["timestamp"]
        with self.assertRaisesRegex(ValueError,"strictly increasing"):
            run_backtest(data,holding_bars=1)

    def test_uses_historical_funding_rate_for_long(self):
        data={tf:flat_candles(64) for tf in ("5m","15m","30m")}
        data["5m"][60]["funding_rate"]=0.001
        data["5m"][61]["funding_rate"]=0.002
        with patch("backtest.analyze_market",return_value={"signal":"LONG"}):
            result=run_backtest(data,holding_bars=1,fee_bps=0,slippage_bps=0)
        self.assertEqual(result["trade_count"],1)
        self.assertEqual(result["trades"][0]["funding_source"],
                         "historical_candle_funding_rate")
        self.assertAlmostEqual(result["trades"][0]["funding_cost_bps"],30.0)
        self.assertEqual(result["funding_mode"],"historical_per_candle")

    def test_positive_funding_is_received_by_short(self):
        data={tf:flat_candles(64) for tf in ("5m","15m","30m")}
        data["5m"][60]["funding_rate"]=0.001
        data["5m"][61]["funding_rate"]=0.002
        with patch("backtest.analyze_market",return_value={"signal":"SHORT"}):
            result=run_backtest(data,holding_bars=1,fee_bps=0,slippage_bps=0)
        self.assertAlmostEqual(result["trades"][0]["funding_cost_bps"],-30.0)
        self.assertGreater(result["trades"][0]["net_return_pct"],0.0)


if __name__ == "__main__":
    unittest.main()
