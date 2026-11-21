import unittest
from toobit_api import ToobitAPI


class ToobitClosedCandleTests(unittest.TestCase):
    def test_forming_candle_is_excluded_and_close_boundary_is_included(self):
        rows=[{"open_time":0},{"open_time":300000},{"open_time":600000}]
        self.assertEqual(ToobitAPI.closed_candles(rows,"5m",600000),[rows[0],rows[1]])
        self.assertEqual(ToobitAPI.closed_candles(rows,"5m",900000),rows)

    def test_only_selected_intervals_are_fetched_and_keep_independent_latest_closes(self):
        from unittest.mock import patch
        api = ToobitAPI()
        one_hour = [{"open_time": 0}, {"open_time": 3_600_000}]
        four_hour = [{"open_time": 0}]
        # At 4h+30m, the 1h series has a newer closed candle than the 4h series.
        with patch.object(api, "server_time", return_value={"serverTime": 16_200_000}):
            with patch.object(api, "get_klines", side_effect=[one_hour, four_hour]) as get:
                result = api.get_multi_timeframe(
                    "BTC-SWAP-USDT", selected_timeframes=("1h", "4h")
                )
        self.assertEqual([call.args[1] for call in get.call_args_list], ["1h", "4h"])
        self.assertEqual([call.args[2] for call in get.call_args_list], [260, 260])
        self.assertTrue(all(call.kwargs["as_of_ms"] == 16_200_000 for call in get.call_args_list))
        self.assertEqual(result, {"1h": one_hour, "4h": four_hour})
        self.assertEqual(result["1h"][-1]["open_time"] + ToobitAPI.INTERVALS["1h"], 7_200_000)
        self.assertEqual(result["4h"][-1]["open_time"] + ToobitAPI.INTERVALS["4h"], 14_400_000)

    def test_configured_timeframes_accepts_any_supported_selection(self):
        from unittest.mock import patch
        with patch.dict("os.environ", {"SELECTED_TIMEFRAMES": "3m, 5m, 4h"}):
            self.assertEqual(ToobitAPI.configured_timeframes(), ("3m", "5m", "4h"))
        with patch.dict("os.environ", {"SELECTED_TIMEFRAMES": "30m"}):
            self.assertEqual(ToobitAPI.configured_timeframes(), ("30m",))
        with patch.dict("os.environ", {"SELECTED_TIMEFRAMES": "2m"}):
            with self.assertRaises(ValueError):
                ToobitAPI.configured_timeframes()


if __name__=="__main__":
    unittest.main()


class ToobitFullUniverseTests(unittest.TestCase):
    def test_exchange_info_contracts_missing_from_ticker_are_not_dropped(self):
        from unittest.mock import patch
        api = ToobitAPI()
        exchange_info = {"coins": [{"coinId": "BTC"}, {"coinId": "ETH"}], "contracts": [
            {"symbol": "BTC-SWAP-USDT", "status": "TRADING",
             "quoteAsset": "USDT", "inverse": False, "underlying": "BTC"},
            {"symbol": "ETH-SWAP-USDT", "status": "TRADING",
             "quoteAsset": "USDT", "inverse": False, "underlying": "ETH"},
            {"symbol": "BTC-USD-SWAP", "status": "TRADING",
             "quoteAsset": "USD", "inverse": True},
            {"symbol": "OLD-SWAP-USDT", "status": "CLOSED",
             "quoteAsset": "USDT", "inverse": False},
        ]}
        ticker = [{"s": "BTC-SWAP-USDT", "qv": "1000"}]
        with patch.object(api, "_get", side_effect=[exchange_info, ticker]):
            symbols = api.get_futures_symbols(limit=10000, preferred_bases=None)
        self.assertEqual(symbols, ["BTC-SWAP-USDT", "ETH-SWAP-USDT"])
