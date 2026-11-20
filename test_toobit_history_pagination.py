import unittest
from unittest.mock import patch

from toobit_api import ToobitAPI


class ToobitHistoryPaginationTests(unittest.TestCase):
    def test_oldest_candle_discovery_pages_backward_until_history_start(self):
        api = ToobitAPI()
        step = ToobitAPI.INTERVALS["5m"]
        first_page = [[i * step, "1", "2", "0.5", "1.5", "10"] for i in range(1000, 2000)]
        second_page = [[i * step, "1", "2", "0.5", "1.5", "10"] for i in range(0, 1000)]
        calls = []

        def fake_get(_endpoint, params):
            calls.append(params.copy())
            return first_page if len(calls) == 1 else second_page

        with patch.object(api, "_get", side_effect=fake_get):
            oldest = api.get_first_available_candle_open(
                "BTC-SWAP-USDT", "5m", 2000 * step
            )

        self.assertEqual(oldest, 0)
        self.assertEqual(len(calls), 2)
        self.assertLess(calls[1]["endTime"], calls[0]["endTime"])

    def test_repeated_page_fails_instead_of_claiming_truncated_history_complete(self):
        api = ToobitAPI()
        step = ToobitAPI.INTERVALS["5m"]
        page = [[i * step, "1", "2", "0.5", "1.5", "10"] for i in range(1000, 2000)]
        with patch.object(api, "_get", return_value=page):
            with self.assertRaisesRegex(RuntimeError, "did not move backward"):
                api.get_first_available_candle_open(
                    "BTC-SWAP-USDT", "5m", 2000 * step
                )


if __name__ == "__main__":
    unittest.main()
