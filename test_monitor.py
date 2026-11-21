import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import monitor


class MonitorNotificationTests(unittest.TestCase):
    def test_alert_format_is_price_first_for_both_directions(self):
        self.assertEqual(monitor.format_signal_alert(2480, "SHORT"), "2480.00 ورود SHORT")
        self.assertEqual(monitor.format_signal_alert(2500, "LONG"), "2500.00 ورود LONG")
        self.assertEqual(monitor.format_signal_alert(0.0123456, "LONG"), "0.0123456 ورود LONG")

    def test_alert_formatter_rejects_wait_and_invalid_prices(self):
        with self.assertRaises(ValueError):
            monitor.format_signal_alert(100, "WAIT")
        with self.assertRaises(ValueError):
            monitor.format_signal_alert(float("nan"), "LONG")

    def test_python_monitor_uses_selected_timeframes_from_configuration(self):
        from toobit_api import ToobitAPI
        with patch.dict("os.environ", {"SELECTED_TIMEFRAMES": "1m,4h"}):
            self.assertEqual(ToobitAPI.configured_timeframes(), ("1m", "4h"))

    @patch("monitor.requests.post")
    def test_ntfy_receives_exact_compact_alert_body(self, post):
        response = Mock()
        response.raise_for_status.return_value = None
        post.return_value = response
        with patch.object(monitor, "NTFY_TOPIC", "aurum_test_topic"), \
             patch.object(monitor, "NTFY_SERVER", "https://ntfy.sh"), \
             patch.object(monitor, "NTFY_TOKEN", ""):
            self.assertTrue(monitor.notify("1621.44 ورود SHORT"))
        post.assert_called_once_with(
            "https://ntfy.sh/aurum_test_topic",
            data="1621.44 ورود SHORT".encode("utf-8"),
            headers={"Priority": "high"},
            timeout=20,
        )

    @patch("monitor.requests.post")
    def test_ntfy_supports_private_topic_authorization(self, post):
        post.return_value = Mock()
        with patch.object(monitor, "NTFY_TOPIC", "aurum_private"), \
             patch.object(monitor, "NTFY_SERVER", "https://ntfy.sh"), \
             patch.object(monitor, "NTFY_TOKEN", "secret-token"):
            self.assertTrue(monitor.notify("2500.00 ورود LONG"))
        self.assertEqual(post.call_args.kwargs["headers"]["Authorization"], "Bearer secret-token")

    @patch("monitor.requests.post")
    def test_missing_topic_does_not_claim_delivery(self, post):
        with patch.object(monitor, "NTFY_TOPIC", ""):
            self.assertFalse(monitor.notify("2500.00 ورود LONG"))
        post.assert_not_called()

    @patch("monitor.notify")
    @patch("monitor.analyze_market")
    @patch("monitor.ToobitAPI")
    def test_monitor_passes_selected_timeframes_and_deduplicates_repeated_signal(
        self, api_class, analyze, notify
    ):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ntfy-state.json"
            api = api_class.return_value
            api.configured_timeframes.return_value = ("1m", "4h")
            api.get_futures_symbols.return_value = ["BTC-SWAP-USDT"]
            api.get_multi_timeframe.return_value = {"1m": [], "4h": []}
            analyze.return_value = {
                "signal": "LONG", "entry": 2500.0, "entry_timeframe": "1m",
                "timeframes": {"1m": {"close": 2500.0}},
            }
            notify.return_value = True
            with patch.object(monitor, "NTFY_STATE_PATH", path):
                monitor.main()
                monitor.main()
            api.get_multi_timeframe.assert_called_with(
                "BTC-SWAP-USDT", selected_timeframes=("1m", "4h")
            )
            analyze.assert_called_with(
                {"1m": [], "4h": []}, selected_timeframes=("1m", "4h")
            )
            notify.assert_called_once_with("2500.00 ورود LONG")

    def test_signal_state_round_trips_and_replaces_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ntfy-state.json"
            with patch.object(monitor, "NTFY_STATE_PATH", path):
                monitor._save_signal_state({"BTC-SWAP-USDT": "LONG"})
                self.assertEqual(monitor._load_signal_state(), {"BTC-SWAP-USDT": "LONG"})
                self.assertFalse(path.with_suffix(".json.tmp").exists())


if __name__ == "__main__":
    unittest.main()
