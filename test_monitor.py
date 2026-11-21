import unittest

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

    def test_python_monitor_has_no_external_push_delivery_path(self):
        self.assertFalse(hasattr(monitor, "notify"))
        self.assertFalse(hasattr(monitor, "NTFY_TOPIC"))


if __name__ == "__main__":
    unittest.main()
