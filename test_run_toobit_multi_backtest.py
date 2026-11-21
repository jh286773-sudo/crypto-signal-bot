import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_toobit_multi_backtest as runner


class FailedHistoryAPI:
    def server_time(self):
        return {"serverTime": 1_800_000_000_000}

    def get_futures_symbols(self, limit, preferred_bases):
        return ["BTC-SWAP-USDT"]

    def get_first_available_candle_open(self, symbol, interval, end_ms):
        return 1_799_000_000_000

    def get_historical_range(self, symbol, start_ms, end_ms, include_funding=True, allow_leading_gap=False):
        raise RuntimeError("historical candles unavailable")


class MultiSymbolBacktestReportTests(unittest.TestCase):
    def test_all_failed_symbols_still_produce_an_incomplete_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = str(Path(directory) / "report.json")
            with patch.object(runner, "ToobitAPI", FailedHistoryAPI):
                with patch.object(sys, "argv", ["runner", "--symbols", "BTC-SWAP-USDT", "--output", output]):
                    with self.assertRaisesRegex(RuntimeError, "Selected-symbol backtest is incomplete"):
                        runner.main()
            report = json.loads(Path(output).read_text(encoding="utf-8"))
            self.assertFalse(report["methodology"]["selected_symbol_data_complete"])
            self.assertEqual(report["methodology"]["failed_contract_count"], 1)
            self.assertEqual(report["candidate_failures"][0]["symbol"], "BTC-SWAP-USDT")
            self.assertEqual(report["pooled_summary"]["max_symbol_drawdown_pct"], 0.0)
            self.assertEqual(report["pooled_summary"]["net_return_pct_equal_capital_per_symbol"], 0.0)


if __name__ == "__main__":
    unittest.main()
