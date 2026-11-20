import unittest
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

    @patch("monitor.NTFY_TOPIC", "")
    def test_missing_topic_reports_notification_not_sent(self):
        self.assertFalse(monitor.notify("title", "message"))

    @patch("monitor.NTFY_TOPIC", "aurum-test-topic")
    @patch("monitor.requests.post")
    def test_successful_delivery_reports_notification_sent(self, post):
        response = Mock()
        response.raise_for_status.return_value = None
        post.return_value = response

        self.assertTrue(monitor.notify("title", "message", "high"))
        post.assert_called_once()
        response.raise_for_status.assert_called_once()


if __name__ == "__main__":
    unittest.main()
