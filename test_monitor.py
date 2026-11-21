import unittest
from unittest.mock import patch

import monitor


class MonitorTests(unittest.TestCase):
    def test_python_monitor_uses_selected_timeframes_from_configuration(self):
        from toobit_api import ToobitAPI
        with patch.dict("os.environ", {"SELECTED_TIMEFRAMES": "1m,4h"}):
            self.assertEqual(ToobitAPI.configured_timeframes(), ("1m", "4h"))

    @patch("monitor.analyze_market")
    @patch("monitor.ToobitAPI")
    def test_monitor_scans_selected_timeframes_without_push_notifications(
        self, api_class, analyze
    ):
        api = api_class.return_value
        api.configured_timeframes.return_value = ("1m", "4h")
        api.get_futures_symbols.return_value = ["BTC-SWAP-USDT"]
        candles = {"1m": [], "4h": []}
        api.get_multi_timeframe.return_value = candles
        analyze.return_value = {
            "signal": "LONG", "entry": 2500.0, "entry_timeframe": "1m",
            "timeframes": {"1m": {"close": 2500.0}},
        }
        with patch("monitor.logging.info") as log_info:
            monitor.main()
        api.get_multi_timeframe.assert_called_once_with(
            "BTC-SWAP-USDT", selected_timeframes=("1m", "4h")
        )
        analyze.assert_called_once_with(candles, selected_timeframes=("1m", "4h"))
        self.assertTrue(any("no push notification configured" in str(c.args) for c in log_info.call_args_list))


if __name__ == "__main__":
    unittest.main()
