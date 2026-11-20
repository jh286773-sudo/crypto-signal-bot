import unittest
from unittest.mock import Mock, patch

import monitor


class MonitorNotificationTests(unittest.TestCase):
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
