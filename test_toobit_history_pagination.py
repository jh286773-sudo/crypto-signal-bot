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

    def test_range_fetch_splits_wide_history_into_bounded_pages(self):
        api = ToobitAPI()
        step = ToobitAPI.INTERVALS["5m"]
        start, end = 0, 2200 * step
        calls = []

        def fake_get(_endpoint, params):
            calls.append(params.copy())
            first = params["startTime"] // step
            last = params["endTime"] // step
            return [[i * step, "1", "2", "0.5", "1.5", "10"]
                    for i in range(first, min(last, first + 1000))]

        with patch.object(api, "_get", side_effect=fake_get):
            rows = api.get_klines_range("BTC-SWAP-USDT", "5m", start, end)

        self.assertEqual(len(rows), 2200)
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0]["endTime"] - calls[0]["startTime"], 1000 * step)
        self.assertEqual(calls[1]["startTime"], 1000 * step)
        self.assertEqual(calls[2]["startTime"], 2000 * step)


if __name__ == "__main__":
    unittest.main()
