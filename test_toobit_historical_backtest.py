import unittest
from unittest.mock import patch
from toobit_api import ToobitAPI


class ToobitHistoricalBacktestTests(unittest.TestCase):
    def test_kline_range_pages_without_duplicate_candles(self):
        api = ToobitAPI()
        first = [[i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
                 for i in range(1000)]
        second = [[i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
                  for i in range(1000, 1002)]
        with patch.object(api, "_get", side_effect=[first, second]) as get:
            rows = api.get_klines_range("BTC-SWAP-USDT", "5m", 0, 1002 * 300000)
        self.assertEqual(len(rows), 1002)
        self.assertEqual(rows[0]["timestamp"], 0)
        self.assertEqual(rows[-1]["timestamp"], 1001 * 300000)
        self.assertEqual(rows[0]["taker_buy_volume"], 4.0)
        self.assertEqual(get.call_count, 2)

    def test_kline_range_recovers_a_missing_multi_candle_leading_prefix(self):
        api = ToobitAPI()
        def row(i):
            return [i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
        later = [row(2), row(3)]
        # The bulk range omits the first two candles; exact-time retries recover
        # both real exchange candles rather than shifting the requested start.
        with patch.object(api, "_get", side_effect=[later, [row(0), row(1)]]):
            rows = api.get_klines_range("BTC-SWAP-USDT", "5m", 0, 4 * 300000)
        self.assertEqual([item["timestamp"] for item in rows], [0, 300000, 600000, 900000])

    def test_kline_range_allows_only_an_unavailable_leading_prefix_when_requested(self):
        api = ToobitAPI()
        def row(i):
            return [i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
        # The first two requested candles are not retrievable from Toobit. The
        # explicit backtest option starts at the first real candle and still
        # requires every later candle to be present and contiguous.
        with patch.object(api, "_get", side_effect=[[row(2), row(3)], []]):
            rows = api.get_klines_range(
                "BTC-SWAP-USDT", "5m", 0, 4 * 300000, allow_leading_gap=True
            )
        self.assertEqual([item["timestamp"] for item in rows], [600000, 900000])

    def test_allowing_leading_prefix_does_not_allow_internal_gaps(self):
        api = ToobitAPI()
        def row(i):
            return [i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
        with patch.object(api, "_get", side_effect=[[row(2), row(4)], [], [], [], []]):
            with self.assertRaisesRegex(RuntimeError, "omitted real 5m candle"):
                api.get_klines_range(
                    "BTC-SWAP-USDT", "5m", 0, 5 * 300000, allow_leading_gap=True
                )

    def test_kline_range_continues_after_a_short_nonfinal_page(self):
        api = ToobitAPI()
        def row(i):
            return [i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
        first = [row(i) for i in range(500)]
        second = [row(i) for i in range(500, 1002)]
        with patch.object(api, "_get", side_effect=[first, second]) as get:
            rows = api.get_klines_range("BTC-SWAP-USDT", "5m", 0, 1002 * 300000)
        self.assertEqual(len(rows), 1002)
        self.assertEqual(get.call_count, 2)

    def test_kline_range_retries_missing_real_exchange_candle(self):
        api = ToobitAPI()
        def row(i):
            return [i * 300000, "1", "2", "0.5", "1.5", "10", 0, "0", 1, "4"]
        with patch.object(api, "_get", side_effect=[[row(0), row(2)], [row(1)]]):
            rows = api.get_klines_range("BTC-SWAP-USDT", "5m", 0, 3 * 300000)
        self.assertEqual([item["timestamp"] for item in rows], [0, 300000, 600000])

    def test_crypto_universe_excludes_tradfi_contracts(self):
        api = ToobitAPI()
        info = {"coins": [{"coinId": "BTC"}, {"coinId": "ETH"}, {"coinId": "USDT"}],
                "contracts": [
                    {"symbol": "BTC-SWAP-USDT", "status": "TRADING", "quoteAsset": "USDT", "inverse": False, "underlying": "BTC"},
                    {"symbol": "NVDA-SWAP-USDT", "status": "TRADING", "quoteAsset": "USDT", "inverse": False, "underlying": "NVDA"},
                    {"symbol": "ETH-SWAP-USDT", "status": "TRADING", "quoteAsset": "USDT", "inverse": False, "underlying": "ETH"},
                ]}
        with patch.object(api, "_get", side_effect=[info, [{"s": "BTC-SWAP-USDT", "qv": "100"}, {"s": "NVDA-SWAP-USDT", "qv": "200"}, {"s": "ETH-SWAP-USDT", "qv": "50"}]]):
            symbols = api.get_futures_symbols(limit=100, preferred_bases=None)
        self.assertEqual(set(symbols), {"BTC-SWAP-USDT", "ETH-SWAP-USDT"})
    def test_live_klines_parse_taker_buy_volume_from_exchange_payload(self):
        api = ToobitAPI()
        row0 = [0, "1", "2", "0.5", "1.5", "10", 0, "15", 2, "6", "9"]
        row1 = [300000, "1", "2", "0.5", "1.5", "10", 0, "15", 2, "6", "9"]
        with patch.object(api, "server_time", return_value={"serverTime": 600000}):
            with patch.object(api, "_get", return_value=[row0, row1]):
                candles = api.get_klines("BTC-SWAP-USDT", "5m", limit=60)
        self.assertEqual(len(candles), 2)
        self.assertEqual(candles[0]["taker_buy_volume"], 6.0)

    def test_account_fee_rate_uses_signed_read_only_endpoint(self):
        api = ToobitAPI(api_key="test-key", api_secret="test-secret")
        with patch.object(api, "_signed_get", return_value={
            "openMakerFee": "0.00002", "openTakerFee": "0.0006",
            "closeMakerFee": "0.00002", "closeTakerFee": "0.0006"
        }) as signed:
            fees = api.get_account_commission_rate("BTC-SWAP-USDT")
        signed.assert_called_once_with("/api/v1/futures/commissionRate", {"symbol": "BTC-SWAP-USDT"})
        self.assertAlmostEqual(fees["openTakerFee"], 0.0006)

    def test_funding_history_is_parsed_and_sorted(self):
        api = ToobitAPI()
        payload = [
            {"settleTime": "1600", "settleRate": "0.001"},
            {"settleTime": "800", "settleRate": "-0.002"}
        ]
        with patch.object(api, "_get", return_value=payload):
            rates = api.get_historical_funding_rates("BTC-SWAP-USDT")
        self.assertEqual([x["settle_time"] for x in rates], [800, 1600])


    def test_historical_range_skips_funding_before_effective_leading_boundary(self):
        from walk_forward import DURATION_MS
        api = ToobitAPI()
        data = {}
        for tf, step in DURATION_MS.items():
            first = 600000 if tf == "5m" else 0
            data[tf] = [
                {"timestamp": first + i * step, "open_time": first + i * step,
                 "open": 1.0, "high": 1.1, "low": 0.9, "close": 1.0, "volume": 10.0}
                for i in range(60)
            ]
        with patch.object(api, "get_klines_range",
                          side_effect=lambda symbol, tf, start, end, **kwargs: data[tf]), \
             patch.object(api, "get_historical_funding_rates", return_value=[
                 {"settle_time": 300000, "funding_rate": 0.001},
                 {"settle_time": 900000, "funding_rate": 0.002},
             ]):
            result = api.get_historical_range(
                "BTC-SWAP-USDT", 0, 60 * DURATION_MS["30m"], allow_leading_gap=True
            )
        self.assertEqual(result["5m"][0]["funding_settle_time"], 900000)
        self.assertAlmostEqual(result["5m"][0]["funding_rate"], 0.002)
        self.assertNotIn("funding_rate", result["5m"][1])

    def test_historical_range_attaches_funding_to_candle_by_settlement_timestamp(self):
        from walk_forward import DURATION_MS
        api = ToobitAPI()
        data = {}
        for tf, step in DURATION_MS.items():
            data[tf] = [
                {"timestamp": i * step, "open_time": i * step, "open": 1.0,
                 "high": 1.1, "low": 0.9, "close": 1.0, "volume": 10.0}
                for i in range(60)
            ]
        with patch.object(api, "get_klines_range", side_effect=lambda symbol, tf, start, end: data[tf]), \
             patch.object(api, "get_historical_funding_rates", return_value=[
                 {"settle_time": 300000, "funding_rate": 0.001}
             ]):
            result = api.get_historical_range("BTC-SWAP-USDT", 0, 60 * DURATION_MS["30m"])
        self.assertEqual(result["5m"][0]["funding_settle_time"], 300000)
        self.assertAlmostEqual(result["5m"][0]["funding_rate"], 0.001)
        self.assertNotIn("funding_rate", result["5m"][1])


if __name__ == "__main__":
    unittest.main()
